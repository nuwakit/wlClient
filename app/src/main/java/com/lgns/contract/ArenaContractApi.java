package com.lgns.contract;

import android.util.Log;

import com.lgns.model.ArenaConfig;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Arena 合约的最小化链上访问层：全部方法都是手写 ABI 编码/解码 + 手写交易流程，
 * 不依赖 web3j 的合约封装（ {@code FunctionReturnDecoder} 在 API 24 上会因
 * {@code BigInteger(byte[],int,int)} 缺失而 {@code NoSuchMethodError} 崩溃）。
 *
 * <p>三条主线：</p>
 * <ul>
 *   <li>只读查询：{@code eth_call} 拿 hex 回来自己按字（32 字节）切，失败一律降级不抛异常；</li>
 *   <li>交易写入：签名 → {@code PreBroadcastHook} 落盘 → 广播 → 等回执，回执失败抛
 *   {@link RevertedTxException} 供业务区分 revert 与超时；</li>
 *   <li>批量查询：{@code sendAsync} 真并发（受 OkHttp 默认 5 连接/host 限流），
 *   整批失败随时降级成逐个串行，宁可慢不可丢。</li>
 * </ul>
 *
 * <p>本链特别注意：Gas 币是一枚 ERC20（{@link ArenaConfig#GAS_TOKEN_ADDR}），
 * {@code eth_getBalance} 与它的 {@code balanceOf} 完全相等，但原生 value 转账会被节点以
 * “余额不足”拒掉，所以补 Gas 只能走 {@code transfer}，{@link #signRawTx} 固定 value=0 刚好适用。</p>
 */
public class ArenaContractApi {
    // region 常量与缓存

    /** Arena 合约地址（值统一收敛到 ArenaConfig，类内保留字段名以最小改动） */
    public static final String ARENA_ADDR = ArenaConfig.ARENA_ADDR;
    /** 链 ID：写进签名 v 值，设错节点会拒收 */
    public static final long CHAIN_ID = ArenaConfig.CHAIN_ID;
    /** 等回执的轮询间隔（毫秒） */
    public static final long TX_WAIT_POLL_MS = ArenaConfig.TX_WAIT_POLL_MS;
    /** 等回执的最大轮询次数：超时秒数 / 轮询间隔，换算一次避免每轮重算 */
    public static final int TX_WAIT_MAX_CNT =
            (int) (ArenaConfig.TX_TIMEOUT_SEC * 1000L / ArenaConfig.TX_WAIT_POLL_MS);

    /**
     * 合约里 {@code TOKEN()} 查询结果的进程缓存。
     *
     * <p>币地址在部署后就固定了，没必要每次转账都发一次 {@code eth_call}；
     * {@code volatile} 保证多线程读到的不是半初始化值，不追求严格单次读取。</p>
     */
    private static volatile String cachedTokenAddress;

    /** PlayerSummary 输出 ABI 原型(共用,避免重复构造) */
    private static final List<TypeReference<?>> PLAYER_SUMMARY_OUTPUTS = buildPlayerSummaryOutputs();

    // endregion

    // region 数据模型

    /**
     * {@code playerSummary(address)} 的返回解构（共 16 个字，下标即 ABI 顺序）。
     *
     * <p>金额单位都是 wei（18 位小数）；构造即全部给 0/false，
     * 解码时某个字越界就保留默认值而不是抛异常，避免一个字段异常拖垮整行展示。</p>
     */
    public static class PlayerSummary {
        /** [0] 钱包内的游戏代币（fLGNS）余额，即入场门票所在的钱包；<b>不是</b> DAI 手续费 */
        public BigInteger walletBalance = BigInteger.ZERO;
        /** [1] 钱包给 Arena 的授权额度 */
        public BigInteger allowance = BigInteger.ZERO;
        /** [2] 累计奖金池（可抵入场费） */
        public BigInteger prize = BigInteger.ZERO;
        /** [3] 当前比赛中的押金 */
        public BigInteger inMatchDeposit = BigInteger.ZERO;
        /** [4] 奖金 + 未结算押金（合约 {@code prize + inMatchDeposit}），<b>不是</b>入场次数；历史场次看 {@link #battles} */
        public BigInteger arenaTotal = BigInteger.ZERO;
        /** [5] 累计对局数 */
        public BigInteger battles = BigInteger.ZERO;
        /** [6] 累计冠军次数 */
        public BigInteger championships = BigInteger.ZERO;
        /** [7] 累计发放的甲子总数 */
        public BigInteger jiaziTotalGranted = BigInteger.ZERO;
        /** [8] 已释放（可领）的甲子 */
        public BigInteger jiaziReleased = BigInteger.ZERO;
        /** [9] 当前可领取的甲子（{@code claimJiazi} 的入参依据） */
        public BigInteger jiaziClaimable = BigInteger.ZERO;
        /** [10] 甲子是否因上限被截断（发放超限时的标记） */
        public boolean jiaziTruncated;
        /** [11] 今日已入场次数 */
        public BigInteger entriesToday = BigInteger.ZERO;
        /** [12] 今日剩余入场次数：为 0 说明已打满，不必再发交易 */
        public BigInteger entriesRemainingToday = BigInteger.ZERO;
        /** [13] 奖金能自动抵掉多少场入场费 */
        public BigInteger roundsFromPrize = BigInteger.ZERO;
        /** [14] 下一场入场费是否由奖金支付 */
        public boolean nextEntryFromPrize;
        /** [15] 是否已在当前轮入座：入座则不能重复入场 */
        public boolean seatedInCurrentRound;
    }

    // endregion

    // region 只读查询

    /**
     * 查询单个地址的完整玩家概览（主界面卡片与批量任务的主要数据源）。
     *
     * <p>只打 logcat 不落库：批量刷新时每个账号都会走到这里，写 DB 会迅速撑大日志库。</p>
     *
     * @throws Exception RPC 返回错误或返回数据长度不足 1024 个 hex（16 个字）
     */
    public static PlayerSummary callPlayerSummary(Web3j web3j, String playerAddr) throws Exception {
        String data = encodePlayerSummaryData(playerAddr);
        EthCall ethCall = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (ethCall.hasError()) {
            throw new Exception("playerSummary rpc error:" + ethCall.getError().getMessage());
        }
        String hexValue = ethCall.getValue();
        // 只输出 logcat 调试信息，不写数据库：批量刷新时每个账号都会走到这里，落库会迅速撑大 db
        Log.d("RPC_DEBUG", "playerSummary[" + playerAddr + "] 原始返回：" + hexValue);
        return decodePlayerSummary(hexValue);
    }

    /**
     * 批量查询 playerSummary。
     *
     * <p>用 {@code sendAsync()} + {@code CompletableFuture} 做真并发，按
     * {@code ArenaConfig.BATCH_CALL_SIZE} 切片；某一整批失败（超时/异常）就降级成逐个查，
     * 单个地址失败只丢那一条，不影响其他地址。</p>
     *
     * @return 地址 → 概览，按输入顺序；查不到的地址不会出现在结果里
     */
    public static Map<String, PlayerSummary> callPlayerSummaryBatch(
            Web3j web3j, Collection<String> playerAddrs) {
        Map<String, PlayerSummary> out = new LinkedHashMap<>();
        if (playerAddrs == null || playerAddrs.isEmpty()) return out;
        List<String> addrs = new ArrayList<>(playerAddrs);
        for (int start = 0; start < addrs.size(); start += ArenaConfig.BATCH_CALL_SIZE) {
            int end = Math.min(start + ArenaConfig.BATCH_CALL_SIZE, addrs.size());
            List<String> slice = addrs.subList(start, end);
            try {
                out.putAll(doOneBatchViaSendAsync(web3j, slice));
            } catch (Throwable t) {
                // 兜底:逐个查
                for (String a : slice) {
                    try { out.put(a, callPlayerSummary(web3j, a)); } catch (Exception ignore) {}
                }
            }
        }
        return out;
    }

    /**
     * 把一批地址用 {@code sendAsync} 一次发出去再统一等（降级前的主路径）。
     *
     * <p>超时给两倍 {@code RPC_CALL_TIMEOUT_SEC}：并发下单个请求的排队时间比串行长，
     * 按单请求超时算会把正常批次误判为失败，白白触发降级。</p>
     */
    private static Map<String, PlayerSummary> doOneBatchViaSendAsync(Web3j web3j, List<String> slice) throws Exception {
        List<CompletableFuture<EthCall>> futures = new ArrayList<>(slice.size());
        for (String addr : slice) {
            futures.add(web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, encodePlayerSummaryData(addr)),
                    DefaultBlockParameterName.LATEST).sendAsync());
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(ArenaConfig.RPC_CALL_TIMEOUT_SEC * 2L, TimeUnit.SECONDS);
        Map<String, PlayerSummary> out = new LinkedHashMap<>();
        for (int i = 0; i < futures.size(); i++) {
            EthCall ec = futures.get(i).get();
            if (ec.hasError()) continue;
            try { out.put(slice.get(i), decodePlayerSummary(ec.getValue())); } catch (Exception ignore) {}
        }
        return out;
    }

    /**
     * 手动解析返回值中第 wordIndex 个 uint256 字（32 字节 = 64 hex）
     * 不走 web3j TypeDecoder：其内部 new BigInteger(byte[],int,int) 三参构造器
     * 是 Java 9 API，低版本 Android 的 core-oj.jar 缺失 → NoSuchMethodError 崩溃
     */
    public static BigInteger decodeUint256At(String hexWith0x, int wordIndex) {
        if (hexWith0x == null) return BigInteger.ZERO;
        String hex = Numeric.cleanHexPrefix(hexWith0x);
        int from = wordIndex * 64;
        if (hex.length() < from + 64) return BigInteger.ZERO;
        try {
            return new BigInteger(hex.substring(from, from + 64), 16);
        } catch (NumberFormatException e) {
            return BigInteger.ZERO;
        }
    }

    /**
     * 查询地址已发放的甲子总数（{@code jiaziCount}）。
     *
     * @throws Exception RPC 返回错误；返回值异常时按 0 处理
     */
    public static BigInteger callJiaziCount(Web3j web3j, String owner) throws Exception {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(owner));
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Uint256.class));
        Function f = new Function("jiaziCount", inputs, outputs);
        String callData = FunctionEncoder.encode(f);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                DefaultBlockParameterName.LATEST
        ).send();
        if (res.hasError()) throw new Exception("jiaziCount error:" + res.getError().getMessage());
        String hexValue = res.getValue();
        Log.d("RPC_DEBUG", "jiaziCount[" + owner + "] 原始返回：" + hexValue);
        return decodeUint256At(hexValue, 0);
    }

    /** jiaziPage(address,uint256,uint256) 的 4 字节选择器（现场 keccak，与部署字节码 dispatcher 一致） */
    private static final String SEL_JIAZI_PAGE = "0x85adb0f7";

    /** ChampionMandate.mint(uint256[]) 选择器（官网 ABI 清单，链上 eth_call 干跑 from=持有人不 revert） */
    private static final String SEL_MANDATE_MINT = "0xf8e93ef9";
    /** ChampionMandate.used(address,uint256) 选择器：某笔俸禄 index 是否已铸过盟主令 */
    private static final String SEL_MANDATE_USED = "0xad04dc3f";

    /**
     * 手拼 jiaziPage 调用数据：selector + owner(32B) + from(32B) + count(32B)。
     * 与 encodePlayerHistoryLatestData 同一套路：不走 FunctionEncoder，直接用字节码验证过的 selector。
     */
    private static String encodeJiaziPageData(String owner, long from, long count) {
        String addr = Numeric.cleanHexPrefix(owner);
        String fromHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(from), 64);
        String countHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(count), 64);
        return SEL_JIAZI_PAGE
                + String.format("%64s", addr).replace(' ', '0')
                + fromHex + countHex;
    }

    /**
     * 盟主令两口径计数：{@code mintable}=可铸（可铸造）、{@code minted}=已铸（已铸造）。
     * <p>整体查失败时返回 null；两个字段成对出现，不会一个有一个无。</p>
     */
    public static class MintCounts {
        /** 可铸 = 领讫俸禄笔数 − 已铸（官网「可 Mint」） */
        public Integer mintable;
        /** 已铸 = ChampionMandate.balanceOf(地址)（官网「已 Mint」） */
        public Integer minted;
    }

    /**
     * 统计该地址盟主令两口径：<b>可铸</b> = 领讫俸禄笔数 − 已铸；<b>已铸</b> = {@code balanceOf}。
     *
     * <p>盟主令是独立的 ChampionMandate ERC721（{@link ArenaConfig#MANDATE_NFT_ADDR}，部署于
     * Anubis 6714，非 arena 主合约）。官网两口径：<b>已铸</b>={@code ChampionMandate.balanceOf(owner)}
     * （已铸造并持有的 NFT 数）；<b>可铸</b>=领讫俸禄笔数 − 已铸。领讫判据取 JiaziRow.claimedDays
     * （行内第 5 个字，偏移 +256 hex）满 {@link ArenaConfig#JIAZI_DAYS}(60)：该笔已全部领讫、可铸一枚。
     * 实测：0x66E4…领讫2/balanceOf1→可铸1/已铸1；0x2913…领讫5/balanceOf5→可铸0/已铸5；
     * 0xfca0…领讫7/balanceOf7→可铸0/已铸7，与官网(可 Mint/已 Mint)完全一致。</p>
     *
     * <p>领讫笔数用 {@code jiaziPage} 分页统计（每页最多 {@link ArenaConfig#MAX_PAGE}，按 total 续页）；
     * JiaziRow 是 6 个 uint256 定长结构（index,roundId,validDays,released,claimedDays,claimableAmount），
     * 每行 384 hex，手工按字切片绕开 web3j decoder 的 LowLevel 崩溃。全程只读、不发链上交易。</p>
     *
     * @return 可铸/已铸两个枚数；领讫或 balanceOf 任一 RPC 失败返回 null（调用方按“未查到”隐藏，不伪造 0）
     */
    public static MintCounts callMintCounts(Web3j web3j, String owner) {
        try {
            int full = 0;
            long from = 0;
            long total = -1;
            while (total < 0 || from < total) {
                EthCall res = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, ARENA_ADDR,
                                encodeJiaziPageData(owner, from, ArenaConfig.MAX_PAGE)),
                        DefaultBlockParameterName.LATEST).send();
                if (res.hasError()) return null;
                String hex = Numeric.cleanHexPrefix(res.getValue());
                // 头部 (JiaziRow[],uint256)：word0=数组字节偏移(0x40)，word1=俸禄总条数 total
                if (hex.length() < 128) break;
                long arrOffBytes = decodeUint256At(hex, 0).longValue();
                total = decodeUint256At(hex, 1).longValue();
                long lenStart = arrOffBytes * 2;
                if (hex.length() < lenStart + 64) break;
                long len = new BigInteger(hex.substring((int) lenStart, (int) (lenStart + 64)), 16).longValue();
                long rowsStart = lenStart + 64;
                for (long i = 0; i < len; i++) {
                    long base = rowsStart + i * 384;
                    // claimedDays 是行内第 5 个字，需读到 base+320 hex
                    if (base + 320 > hex.length()) break;
                    long claimed = new BigInteger(hex.substring((int) (base + 256), (int) (base + 320)), 16).longValue();
                    if (claimed >= ArenaConfig.JIAZI_DAYS) full++;
                }
                if (len == 0) break;
                from += ArenaConfig.MAX_PAGE;
            }
            // 已铸 = ChampionMandate.balanceOf；可铸 = 领讫笔数 − 已铸；balanceOf 失败随外层 catch 返回 null
            int minted = callMandateBalanceOf(web3j, owner);
            MintCounts mc = new MintCounts();
            mc.minted = minted;
            mc.mintable = Math.max(0, full - minted);
            return mc;
        } catch (Exception e) {
            Log.d("RPC_DEBUG", "callMintCounts[" + owner + "] 失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 查询该地址已铸造并持有的盟主令（ChampionMandate ERC721）数量 = 官网「已铸 / 已 Mint」。
     *
     * <p>balanceOf(address) 选择器 {@code 0x70a08231}（标准 ERC721，实测三地址 7/1/5 与官网一致）；
     * 打的是独立 NFT 合约 {@link ArenaConfig#MANDATE_NFT_ADDR}，非 arena 主合约。</p>
     *
     * @return 持有枚数；RPC 失败抛异常，由 {@link #callMintCounts} 归为 null
     */
    public static int callMandateBalanceOf(Web3j web3j, String owner) throws Exception {
        String addr = Numeric.cleanHexPrefix(owner);
        String callData = "0x70a08231" + String.format("%64s", addr).replace(' ', '0');
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ArenaConfig.MANDATE_NFT_ADDR, callData),
                DefaultBlockParameterName.LATEST).send();
        if (res.hasError()) throw new Exception("balanceOf error:" + res.getError().getMessage());
        return decodeUint256At(res.getValue(), 0).intValue();
    }

    /**
     * 查询该地址「可铸」的俸禄 index 列表：JiaziRow.claimedDays≥60 且 {@code used(地址,index)=false} 的那些。
     *
     * <p>{@link #callMintCounts} 的「可铸枚数」在这里变成精确的 index 清单——{@link #doMandateMint}
     * 的交易入参就是它们。index 是行内第 1 个字（base..+64）；已铸判断用 ChampionMandate 的
     * {@code used(address,uint256)}（selector {@code 0xad04dc3f}，取自官网 ABI 清单，实测
     * 0x66E4 两笔领讫行返回 1/0，与官网已铸1/可铸1 一致），逐个 eth_call 排除。</p>
     *
     * @return 可铸 index 列表（可能为空）；任一 RPC 失败返回 null（调用方不能当空列表发交易）
     */
    public static List<BigInteger> callMintableIndices(Web3j web3j, String owner) {
        try {
            List<BigInteger> fullIdx = new ArrayList<>();
            long from = 0;
            long total = -1;
            while (total < 0 || from < total) {
                EthCall res = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, ARENA_ADDR,
                                encodeJiaziPageData(owner, from, ArenaConfig.MAX_PAGE)),
                        DefaultBlockParameterName.LATEST).send();
                if (res.hasError()) return null;
                String hex = Numeric.cleanHexPrefix(res.getValue());
                if (hex.length() < 128) break;
                long arrOffBytes = decodeUint256At(hex, 0).longValue();
                total = decodeUint256At(hex, 1).longValue();
                long lenStart = arrOffBytes * 2;
                if (hex.length() < lenStart + 64) break;
                long len = new BigInteger(hex.substring((int) lenStart, (int) (lenStart + 64)), 16).longValue();
                long rowsStart = lenStart + 64;
                for (long i = 0; i < len; i++) {
                    long base = rowsStart + i * 384;
                    // 领讫判据与 callMintCounts 一致：claimedDays 行内第 5 个字满 60
                    if (base + 320 > hex.length()) break;
                    long claimed = new BigInteger(hex.substring((int) (base + 256), (int) (base + 320)), 16).longValue();
                    if (claimed >= ArenaConfig.JIAZI_DAYS) {
                        fullIdx.add(new BigInteger(hex.substring((int) base, (int) (base + 64)), 16));
                    }
                }
                if (len == 0) break;
                from += ArenaConfig.MAX_PAGE;
            }
            // 逐笔排除已铸：used(地址,index) 为 true 即该笔俸禄已换过 NFT
            List<BigInteger> out = new ArrayList<>();
            String addrPad = String.format("%64s", Numeric.cleanHexPrefix(owner)).replace(' ', '0');
            for (BigInteger idx : fullIdx) {
                String callData = SEL_MANDATE_USED + addrPad
                        + Numeric.toHexStringNoPrefixZeroPadded(idx, 64);
                EthCall res = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, ArenaConfig.MANDATE_NFT_ADDR, callData),
                        DefaultBlockParameterName.LATEST).send();
                if (res.hasError()) return null;
                if (decodeUint256At(res.getValue(), 0).signum() == 0) out.add(idx);
            }
            return out;
        } catch (Exception e) {
            Log.d("RPC_DEBUG", "callMintableIndices[" + owner + "] 失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 查询待领擂台奖金（{@code claimable} 公开 mapping 的 getter），单位 wei。
     *
     * <p>与甲子俸是两笔独立的钱：每输一场 +100.8，永不过期。
     * 拿它做领取前置判断，才不至于把从没拿过冠军的账号当成「没东西可领」。</p>
     *
     * @throws Exception RPC 返回错误；调用方应把查询失败当成「可能有」，不能当 0
     */
    public static BigInteger callClaimable(Web3j web3j, String owner) throws Exception {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(owner));
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Uint256.class));
        Function f = new Function("claimable", inputs, outputs);
        String callData = FunctionEncoder.encode(f);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                DefaultBlockParameterName.LATEST
        ).send();
        if (res.hasError()) throw new Exception("claimable error:" + res.getError().getMessage());
        return decodeUint256At(res.getValue(), 0);
    }

    /**
     * 查询今日实际可领的师徒分成（{@code lineagePending}），单位 wei。
     *
     * <p>合约只结算 `今天-1` 那一天，且按「今天已经打了几场」的比例解锁（10% 一场，10 场满额）。
     * 返回 0 时发交易必然 revert {@code NothingToClaimLineage}，所以调用前先查一次，
     * 既省 gas，也不会把一个正常结果记成失败。</p>
     *
     * @throws Exception RPC 返回错误
     */
    public static BigInteger callLineagePending(Web3j web3j, String owner) throws Exception {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(owner));
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Uint256.class));
        Function f = new Function("lineagePending", inputs, outputs);
        String callData = FunctionEncoder.encode(f);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                DefaultBlockParameterName.LATEST
        ).send();
        if (res.hasError()) throw new Exception("lineagePending error:" + res.getError().getMessage());
        return decodeUint256At(res.getValue(), 0);
    }

    /**
     * 查询今日对局数（{@code activityOf} 的返回元组取第 3 个字）。
     *
     * <p>约定：调用方拿到的 {@code null} 表示“没查到”，不是 0；
     * 展示时要区分成 {@code --}，否则会把网络失败误报成“今日未打”。</p>
     */
    public static Integer callActivityOfMatchesToday(Web3j web3j, String owner) {
        try {
            List<Type> inputs = new ArrayList<Type>();
            inputs.add(new Address(owner));
            List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            outputs.add(TypeReference.create(Uint256.class));
            Function f = new Function("activityOf", inputs, outputs);
            String callData = FunctionEncoder.encode(f);
            EthCall res = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, callData),
                    DefaultBlockParameterName.LATEST
            ).send();
            if (res.hasError()) return null;
            String hexValue = res.getValue();
            Log.d("RPC_DEBUG", "activityOf[" + owner + "] 原始返回：" + hexValue);
            // activityOf 返回 (fullDays, day, matchesToday, firstIncomplete)，matchesToday 为第 3 个字
            BigInteger val = decodeUint256At(hexValue, 2);
            return val.intValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * playerHistoryLatest(address,uint256) selector:0x2e1f91b9
     * selector 取自当前部署合约（0xff06…aAaA）字节码里的 dispatcher 常量；
     * 旧值 0x0810151a 在当前合约里不存在，eth_call 会直接落到 fallback 回 revert,
     * 导致今日冠军永远查不到（表现为整列“--”）
     * 修复:增加 hex 解析异常捕获,畸形数据直接返回 null,不向外抛 NumberFormatException
     */
    public static Integer callPlayerHistoryLatestWendToday(Web3j web3j, String owner) {
        try {
            EthCall res = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, ARENA_ADDR, encodePlayerHistoryLatestData(owner)),
                    DefaultBlockParameterName.LATEST
            ).send();
            if (res.hasError()) return null;
            Log.d("RPC_DEBUG", "playerHistoryLatest[" + owner + "] 原始返回长度: "
                    + Numeric.cleanHexPrefix(res.getValue()).length());
            return decodeWinCountToday(res.getValue());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 批量查询今日胜场：与 playerSummary 同样用 sendAsync 真并发（OkHttp 默认 5 连接/host 限流），
     * 单批最多 ArenaConfig.BATCH_CALL_SIZE，超过自动切片。
     * 返回 Map<地址, 今日胜场>；查询/解析失败的地址不会出现在 Map 里，由调用方标记为“未查到”
     */
    public static Map<String, Integer> callPlayerHistoryLatestWendTodayBatch(
            Web3j web3j, Collection<String> owners) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (web3j == null || owners == null || owners.isEmpty()) return out;
        List<String> addrs = new ArrayList<>(owners);
        for (int start = 0; start < addrs.size(); start += ArenaConfig.BATCH_CALL_SIZE) {
            int end = Math.min(start + ArenaConfig.BATCH_CALL_SIZE, addrs.size());
            List<String> slice = addrs.subList(start, end);
            List<CompletableFuture<EthCall>> futures = new ArrayList<>(slice.size());
            try {
                for (String addr : slice) {
                    futures.add(web3j.ethCall(
                            Transaction.createEthCallTransaction(null, ARENA_ADDR,
                                    encodePlayerHistoryLatestData(addr)),
                            DefaultBlockParameterName.LATEST).sendAsync());
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(ArenaConfig.RPC_CALL_TIMEOUT_SEC * 2L, TimeUnit.SECONDS);
            } catch (Exception e) {
                // 并发失败：降级为逐个查，与 playerSummary 批量保持一致的降级策略
                for (String addr : slice) {
                    Integer v = callPlayerHistoryLatestWendToday(web3j, addr);
                    if (v != null) out.put(addr, v);
                }
                continue;
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    EthCall ec = futures.get(i).get();
                    if (ec == null || ec.hasError()) continue;
                    Integer v = decodeWinCountToday(ec.getValue());
                    if (v != null) out.put(slice.get(i), v);
                } catch (Exception ignore) {}
            }
        }
        return out;
    }

    /** playerHistoryLatest(address,uint256) 的 4 字节选择器（与部署合约字节码一致） */
    private static final String SEL_PLAYER_HISTORY_LATEST = "0x2e1f91b9";
    
    /**
     * 手拼调用数据：selector + 地址（左补 0 到 32 字节）+ count。
     *
     * <p>count 固定传 100：合约按最近 N 条返回，取 100 足够覆盖一天的对局；
     * 传大了白白浪费带宽（每条记录 512 个 hex）。</p>
     */
    private static String encodePlayerHistoryLatestData(String owner) {
        String addrNo0x = Numeric.cleanHexPrefix(owner);
        String countHex = Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(100), 64);
        return SEL_PLAYER_HISTORY_LATEST
                + String.format("%64s", addrNo0x).replace(' ', '0')
                + countHex;
    }

    /**
     * 解析 playerHistoryLatest 返回值，统计“当天获胜”的条数；畸形数据返回 null
     */
    private static Integer decodeWinCountToday(String hexWith0x) {
        if (hexWith0x == null) return null;
        try {
            String hexRaw = Numeric.cleanHexPrefix(hexWith0x);
            if (hexRaw.length() < 64) return 0;

            int arrOffset;
            try { arrOffset = Integer.parseInt(hexRaw.substring(0, 64), 16) * 2; }
            catch (NumberFormatException e) { return null; }
            if (arrOffset + 64 > hexRaw.length()) return null;
            int arrLen;
            try { arrLen = Integer.parseInt(hexRaw.substring(arrOffset, arrOffset + 64), 16); }
            catch (NumberFormatException e) { return null; }

            int itemPtr = arrOffset + 64;
            int winCount = 0;
            long nowSec = System.currentTimeMillis() / 1000L;
            long todayDayId = (nowSec + ArenaConfig.DAY_OFFSET) / 86400L;

            for (int idx = 0; idx < arrLen; idx++) {
                int base = itemPtr + idx * 512;
                if (base + 512 > hexRaw.length()) break;
                String tsHex = hexRaw.substring(base + 64, base + 128);
                long ts;
                try { ts = new BigInteger(tsHex, 16).longValue(); }
                catch (NumberFormatException e) { continue; }
                long rowDayId = (ts + ArenaConfig.DAY_OFFSET) / 86400L;
                String wonHex = hexRaw.substring(base + 256, base + 320);
                boolean won = !"0000000000000000000000000000000000000000000000000000000000000000".equals(wonHex);
                if (won && rowDayId == todayDayId) winCount++;
            }
            return winCount;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读合约的 {@code TOKEN()} 得到币地址，结果进 {@link #cachedTokenAddress}。
     *
     * <p>手动取末尾 40 个 hex 当地址，不走 {@code FunctionReturnDecoder}（API 24 兼容问题）。
     * 只认成功结果：失败直接抛，不能缓存空值，否则一次 RPC 抖动会把币地址永久定死。</p>
     */
    private static String readTokenAddress(Web3j web3j) throws Exception {
        String cached = cachedTokenAddress;
        if (cached != null) return cached;
        List<Type> inputs = new ArrayList<Type>();
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        outputs.add(TypeReference.create(Address.class));
        Function f = new Function("TOKEN", inputs, outputs);
        String data = FunctionEncoder.encode(f);
        EthCall call = web3j.ethCall(
                Transaction.createEthCallTransaction(null, ARENA_ADDR, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (call.hasError()) throw new Exception("read TOKEN address fail:" + call.getError().getMessage());
        
        // 手动解析 hex 响应，绕过 web3j 的 FunctionReturnDecoder（API 24 有 BigInteger 兼容性问题）
        String hex = call.getValue();
        if (hex == null || hex.length() < 66) throw new Exception("TOKEN response too short");
        // Address 在返回值的最后 40 个 hex 字符（去掉 0x 前缀）
        String addr = "0x" + hex.substring(hex.length() - 40);
        cachedTokenAddress = addr;
        return addr;
    }

    /** 对外暴露币合约地址（首次调用会发一次 RPC，之后走缓存） */
    public static String getTokenAddress(Web3j web3j) throws Exception {
        return readTokenAddress(web3j);
    }

    // endregion

    // region 交易链路：签名 → 广播 → 等回执

    /** 广播前 hook:让业务方先把 txHash+rawTx 落 SP，以便崩溃后能原样重发 */
    public interface PreBroadcastHook {
        /**
         * 签名完成、尚未广播时回调（在调用线程同步执行，必须自己保证足够快）。
         *
         * @param txHash   本地算出的交易哈希，异常时可拿它去链上反查
         * @param rawTxHex 完整的签名交易体，恢复时直接重发这一串即可
         */
        void onSigned(String txHash, String rawTxHex);
    }

    /** 交易已上链但执行失败（revert）；与超时区分开，业务据此判定能否重试 */
    public static class RevertedTxException extends Exception {
        /** 已上链的失败交易哈希，供业务回查与写 outcome */
        public final String txHash;
        /** 失败交易实际消耗的 gas；等于发交易时的上限即 OOG，供业务区分「gas 没给够」与「没东西可领」；可为 null */
        public final BigInteger gasUsed;

        /** @param txHash 已上链的失败交易哈希，供业务回查与写 outcome */
        public RevertedTxException(String msg, String txHash) { this(msg, txHash, null); }

        /**
         * @param txHash 已上链的失败交易哈希，供业务回查与写 outcome
         * @param gasUsed 回执里的实际 gas 消耗，拿不到时传 null
         */
        public RevertedTxException(String msg, String txHash, BigInteger gasUsed) {
            super(msg); this.txHash = txHash; this.gasUsed = gasUsed;
        }
    }

    /** 签名结果：rawTx 用于广播，txHash 用于本地先落盘再发（不依赖 RPC 回包） */
    public static class SignedTx {
        /** 签名后的完整交易体（hex），直接可送 {@code eth_sendRawTransaction} */
        public final String rawTxHex;
        /** {@code keccak256(signedRLP)}，本地算出，与节点回包一致 */
        public final String txHash;
        /** 纯数据载体：两个字段都是最终广播值 */
        public SignedTx(String rawTxHex, String txHash) {
            this.rawTxHex = rawTxHex;
            this.txHash = txHash;
        }
    }

    /**
     * 只做签名,不广播。
     *
     * <p>txHash = keccak256(signedRLP)，本地算，不依赖 RPC；
     * value 固定 0（本链所有写入都不带原生币，带上去会被节点拒绝）。</p>
     *
     * @param gasLimit 由业务传（合约不允许估低），本方法不自行估算
     */
    public static SignedTx signRawTx(ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                     BigInteger gasLimit, String toAddr, String data) {
        Credentials credentials = Credentials.create(ecKeyPair);
        RawTransaction rawTx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, toAddr, BigInteger.ZERO, data);
        byte[] signedBytes = TransactionEncoder.signMessage(rawTx, CHAIN_ID, credentials);
        String hexSigned = Numeric.toHexString(signedBytes);
        byte[] hashBytes = Hash.sha3(signedBytes);
        String txHash = Numeric.toHexString(hashBytes);
        return new SignedTx(hexSigned, txHash);
    }

    /**
     * 广播一次；若节点返回 already known/imported，视为已在链上，吞掉异常继续等回执。
     *
     * @return RPC 返回的 txHash（可能与本地算的一致）；alreadyInFlight 时返回 null，
     * 调用方需回退到本地 txHash 继续等
     */
    public static String broadcastRaw(Web3j web3j, String hexSigned) throws Exception {
        EthSendTransaction sendResp = web3j.ethSendRawTransaction(hexSigned).send();
        if (sendResp.hasError()) {
            String err = sendResp.getError().getMessage();
            Exception wrap = new Exception(err == null ? "" : err);
            if (Web3AndroidUtil.isAlreadyInFlight(wrap)) {
                return null;
            }
            throw new Exception("sendRawTx error: " + err);
        }
        return sendResp.getTransactionHash();
    }

    /**
     * 轮询等回执：成功返回回执，status=0 抛 {@link RevertedTxException}，轮完仍未出块则抛超时。
     *
     * <p>三种结果必须分开：revert 不能重试（重发只会再 revert），超时可以重查，
     * 成功则进入后续业务状态更新。</p>
     */
    public static TransactionReceipt waitReceiptLocal(Web3j web3j, String txHash) throws Exception {
        for (int i = 0; i < TX_WAIT_MAX_CNT; i++) {
            EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResp.getTransactionReceipt().isPresent()) {
                TransactionReceipt rcpt = receiptResp.getTransactionReceipt().get();
                if (!rcpt.isStatusOK()) {
                    String revertReason = rcpt.getRevertReason() != null ? rcpt.getRevertReason() : "未知";
                    // OOG 时节点给不出 revert reason，只能靠 gasUsed 撞上限识别，随异常一并带出
                    throw new RevertedTxException("tx reverted, hash=" + txHash + " | reason=" + revertReason,
                            txHash, rcpt.getGasUsed());
                }
                return rcpt;
            }
            Thread.sleep(TX_WAIT_POLL_MS);
        }
        throw new Exception("wait receipt timeout, hash=" + txHash);
    }

    /**
     * 签名 → 落盘 hook → 广播 → 等回执，全链路一气完成。
     *
     * <p>hook 调在广播之前是关键顺序：先落盘才能保证“链上可能已存在、本地却没记录”的窗口为 0；
     * 广播返回 null（已在飞行中）时回退用本地算的 txHash 继续等，不重发。</p>
     */
    private static TransactionReceipt sendRawTxAndWaitReceipt(
            Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
            String toAddr, String data, PreBroadcastHook preBroadcastHook) throws Exception {
        SignedTx signed = signRawTx(ecKeyPair, nonce, gasPrice, gasLimit, toAddr, data);
        if (preBroadcastHook != null) {
            preBroadcastHook.onSigned(signed.txHash, signed.rawTxHex);
        }
        String rpcHash = broadcastRaw(web3j, signed.rawTxHex);
        String txHash = rpcHash != null ? rpcHash : signed.txHash;
        return waitReceiptLocal(web3j, txHash);
    }

    /** 不关心落盘时的便捷重载（内部委托到带 hook 的版本） */
    private static TransactionReceipt sendRawTxAndWaitReceipt(
            Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit,
            String toAddr, String data) throws Exception {
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, toAddr, data, null);
    }

    // endregion

    // region SetCode（EIP-7702）交易

    /**
     * 接受已预签名的 SetCode 交易（type=0x04）直接广播并等回执。
     *
     * <p>没有“签名”环节：调用方已经用 {@code SetCodeTx.sign} 签好，这里只做
     * 落盘 hook → 广播 → 等回执，与普通交易保持同一套失败分类。</p>
     */
    public static TransactionReceipt sendSetCodeRawAndWaitReceipt(
            Web3j web3j, String rawTxHex, String localTxHash,
            PreBroadcastHook preBroadcastHook) throws Exception {
        if (preBroadcastHook != null) {
            preBroadcastHook.onSigned(localTxHash, rawTxHex);
        }
        String rpcHash = broadcastRaw(web3j, rawTxHex);
        String txHash = rpcHash != null ? rpcHash : localTxHash;
        return waitReceiptLocal(web3j, txHash);
    }

    // endregion

    // region 业务写入函数

    /**
     * 授权 Arena 合约花掉自己的 ERC20（{@code transferFrom} 的前提）。
     *
     * <p>gasLimit 固定 10 万：approve 的消耗与额度无关，实测远低于此值，
     * 固定值比估算更稳（估算在某些 RPC 上会直接 revert）。</p>
     */
    public static TransactionReceipt doApproveArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger amount,
                                                    BigInteger nonce, BigInteger gasPrice,
                                                    PreBroadcastHook hook) throws Exception {
        String tokenContract = readTokenAddress(web3j);
        List<Type> inputs = Arrays.<Type>asList(new Address(ARENA_ADDR), new Uint256(amount));
        Function func = new Function("approve", inputs, new ArrayList<TypeReference<?>>());
        String txData = FunctionEncoder.encode(func);
        BigInteger gasLimit = BigInteger.valueOf(100000);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, tokenContract, txData, hook);
    }

    /** 授权 Arena 的便捷重载（不需要崩溃恢复） */
    public static TransactionReceipt doApproveArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger amount,
                                                    BigInteger nonce, BigInteger gasPrice) throws Exception {
        return doApproveArena(web3j, ecKeyPair, amount, nonce, gasPrice, null);
    }

    /** 入场（使用 {@link ArenaConfig#GAS_LIMIT_ENTER_ARENA} 默认 gas 上限） */
    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice, PreBroadcastHook hook) throws Exception {
        return doEnterArena(web3j, ecKeyPair, nonce, gasPrice, ArenaConfig.GAS_LIMIT_ENTER_ARENA, hook);
    }

    /**
     * 入场（{@code enterArena()}，无参）。
     *
     * <p>入场费可由奖金或钱包余额支付，具体走哪条由合约内部判断，客户端只管发交易。</p>
     */
    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice, BigInteger gasLimit,
                                                  PreBroadcastHook hook) throws Exception {
        Function func = new Function("enterArena", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, ARENA_ADDR, data, hook);
    }

    /** 入场的便捷重载（不需要崩溃恢复） */
    public static TransactionReceipt doEnterArena(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce,
                                                  BigInteger gasPrice) throws Exception {
        return doEnterArena(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    /**
     * 领取甲子（{@code claimJiazi(start,count)}）。
     *
     * <p>无返回值：结果只靠是否抛异常判断；start/count 通常来自
     * {@link PlayerSummary#jiaziClaimable} 的区间划分。</p>
     */
    public static void doClaimJiazi(Web3j web3j, ECKeyPair ecKeyPair, BigInteger start, BigInteger count,
                                    BigInteger nonce, BigInteger gasPrice, PreBroadcastHook hook) throws Exception {
        List<Type> inputs = Arrays.<Type>asList(new Uint256(start), new Uint256(count));
        Function func = new Function("claimJiazi", inputs, new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, ArenaConfig.GAS_LIMIT_CLAIM_JIAZI, ARENA_ADDR, data, hook);
    }

    /** 领取甲子的便捷重载（不需要崩溃恢复） */
    public static void doClaimJiazi(Web3j web3j, ECKeyPair ecKeyPair, BigInteger start, BigInteger count,
                                    BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimJiazi(web3j, ecKeyPair, start, count, nonce, gasPrice, null);
    }

    /** 领取血脉（{@code claimLineage()}，无参，gas 上限取配置常量） */
    public static void doClaimLineage(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                      PreBroadcastHook hook) throws Exception {
        Function func = new Function("claimLineage", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice,
                ArenaConfig.GAS_LIMIT_CLAIM_LINEAGE, ARENA_ADDR, data, hook);
    }

    /** 领取血脉的便捷重载（不需要崩溃恢复） */
    public static void doClaimLineage(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimLineage(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    /** 领取奖励（{@code claimRewards()}，无参） */
    public static void doClaimRewards(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice,
                                      PreBroadcastHook hook) throws Exception {
        Function func = new Function("claimRewards", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
        String data = FunctionEncoder.encode(func);
        sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice,
                ArenaConfig.GAS_LIMIT_CLAIM_REWARDS, ARENA_ADDR, data, hook);
    }

    /** 领取奖励的便捷重载（不需要崩溃恢复） */
    public static void doClaimRewards(Web3j web3j, ECKeyPair ecKeyPair, BigInteger nonce, BigInteger gasPrice) throws Exception {
        doClaimRewards(web3j, ecKeyPair, nonce, gasPrice, null);
    }

    /**
     * 铸造盟主令（ChampionMandate {@code mint(uint256[])}，打独立 NFT 合约而非 arena 主合约）。
     *
     * <p>入参是 {@link #callMintableIndices} 查回的可铸俸禄 index；单批上限
     * {@link ArenaConfig#MANDATE_MINT_BATCH}=24（对齐官网前端，合约硬上限 64，64 枚一笔
     * 约 12.25M gas 会超区块/超估算被拒）。calldata 手拼动态数组 ABI：
     * selector + offset(0x20) + length + 各元素，与类内其它手拼写法同一套路。</p>
     *
     * @throws Exception index 超限/空列表，或交易 revert/超时（见 {@link RevertedTxException}）
     */
    public static TransactionReceipt doMandateMint(Web3j web3j, ECKeyPair ecKeyPair,
                                                   List<BigInteger> indices, BigInteger nonce,
                                                   BigInteger gasPrice, PreBroadcastHook hook) throws Exception {
        if (indices == null || indices.isEmpty()) throw new Exception("mint: 无可铸 index");
        if (indices.size() > ArenaConfig.MANDATE_MINT_BATCH) {
            throw new Exception("mint: 单笔最多 " + ArenaConfig.MANDATE_MINT_BATCH + " 枚，当前 " + indices.size());
        }
        StringBuilder args = new StringBuilder(
                "0000000000000000000000000000000000000000000000000000000000000020");
        args.append(Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(indices.size()), 64));
        for (BigInteger idx : indices) {
            args.append(Numeric.toHexStringNoPrefixZeroPadded(idx, 64));
        }
        String data = SEL_MANDATE_MINT + args;
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice,
                ArenaConfig.GAS_LIMIT_MANDATE_MINT, ArenaConfig.MANDATE_NFT_ADDR, data, hook);
    }

    // endregion

    // region ERC20 转账与余额（主号充值用）

    /** 把合约默认 ERC20（{@code TOKEN()}）转给指定地址，gas 上限取配置常量 */
    public static TransactionReceipt doErc20Transfer(Web3j web3j, ECKeyPair ecKeyPair,
                                                      String toAddr, BigInteger amount,
                                                      BigInteger nonce, BigInteger gasPrice) throws Exception {
        return doTokenTransfer(web3j, readTokenAddress(web3j), ArenaConfig.GAS_LIMIT_ERC20_TRANSFER,
                ecKeyPair, toAddr, amount, nonce, gasPrice);
    }

    /**
     * 任意 ERC20 的 {@code transfer(address,uint256)}。
     *
     * <p>代币转账不传 value，金额全部走 calldata；也不挂落盘 hook（批量充值在任务层自己记录）。</p>
     */
    private static TransactionReceipt doTokenTransfer(Web3j web3j, String tokenContract, BigInteger gasLimit,
                                                       ECKeyPair ecKeyPair, String toAddr, BigInteger amount,
                                                       BigInteger nonce, BigInteger gasPrice) throws Exception {
        List<Type> inputs = Arrays.<Type>asList(new Address(toAddr), new Uint256(amount));
        Function func = new Function("transfer", inputs, new ArrayList<TypeReference<?>>());
        String txData = FunctionEncoder.encode(func);
        return sendRawTxAndWaitReceipt(web3j, ecKeyPair, nonce, gasPrice, gasLimit, tokenContract, txData);
    }

    /** 查合约默认 ERC20（{@code TOKEN()}）的余额 */
    public static BigInteger callErc20Balance(Web3j web3j, String owner) throws Exception {
        return callTokenBalance(web3j, readTokenAddress(web3j), owner);
    }

    /**
     * 任意 ERC20 的 {@code balanceOf(address)}。
     *
     * <p>取返回值末尾 64 个 hex 转 BigInteger：RPC 报了错就抛异常（余额查不到不能当 0），
     * 但返回体短了则回 0，避免个别节点返回畸形数据把整页拖垮。</p>
     */
    private static BigInteger callTokenBalance(Web3j web3j, String tokenContract, String owner) throws Exception {
        List<Type> inputs = new ArrayList<>();
        inputs.add(new Address(owner));
        List<TypeReference<?>> outputs = new ArrayList<>();
        outputs.add(TypeReference.create(Uint256.class));
        Function func = new Function("balanceOf", inputs, outputs);
        String data = FunctionEncoder.encode(func);
        EthCall res = web3j.ethCall(
                Transaction.createEthCallTransaction(null, tokenContract, data),
                DefaultBlockParameterName.LATEST
        ).send();
        if (res.hasError()) throw new Exception("balanceOf error:" + res.getError().getMessage());
        
        // 手动解析 Uint256 返回值（64 位 hex = 32 字节）
        String hex = res.getValue();
        if (hex == null || hex.length() < 64) return BigInteger.ZERO;
        String valueHex = hex.substring(hex.length() - 64);
        return new BigInteger(valueHex, 16);
    }

    // endregion

    // region 手续费（Gas）查询与转账
    //
    // 本链的 Gas 币是一枚 ERC20（ArenaConfig.GAS_TOKEN_ADDR），eth_getBalance 与它的
    // balanceOf 完全相等；原生 value 转账会被节点按“余额不足”拒绝（余额再高也一样），
    // 所以补 Gas 只能给它做 transfer。signRawTx 固定 value=0，正好适用。

    /** 查 Gas 币（ERC20）余额，批量补手续费前的余额判断依据 */
    public static BigInteger callGasTokenBalance(Web3j web3j, String owner) throws Exception {
        return callTokenBalance(web3j, ArenaConfig.GAS_TOKEN_ADDR, owner);
    }

    /** 用 ERC20 {@code transfer} 给目标地址补 Gas 币（不能走原生转账） */
    public static TransactionReceipt doGasTokenTransfer(Web3j web3j, ECKeyPair ecKeyPair,
                                                        String toAddr, BigInteger amount,
                                                        BigInteger nonce, BigInteger gasPrice) throws Exception {
        return doTokenTransfer(web3j, ArenaConfig.GAS_TOKEN_ADDR,
                ArenaConfig.GAS_LIMIT_GAS_TOKEN_TRANSFER, ecKeyPair, toAddr, amount, nonce, gasPrice);
    }

    /** 原生余额读数：本链与 GAS_TOKEN.balanceOf 恒等，仅用于展示/换算，不可用来发原生转账 */
    public static BigInteger callNativeBalance(Web3j web3j, String addr) throws Exception {
        return web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
    }

    // endregion

    // region 入场干跑预检（不上链）

    /** 18 位小数的代币数量转可读字符串（截断 6 位），提示文案用 */
    private static String fmtDai(BigInteger wei) {
        if (wei == null) return "0";
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 6, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /**
     * 入场前干跑检查，只读链上状态、不发交易。
     *
     * <p>检查顺序刻意从便宜到贵：先看本地就能判定的入座/打满，再比奖金与钱包余额能否凑出入场费，
     * 最后才去查 gasPrice 与 Gas 币余额（这两步要发 RPC）。</p>
     *
     * @param keyPair 当前未使用：本方法不做 {@code eth_estimateGas}，gas 直接取配置常量
     * @return {@code null} 表示可以入场；否则为不可入场的原因（可直接当提示文案）
     */
    public static String previewEnter(Web3j web3j, String addr, ECKeyPair keyPair) {
        try {
            PlayerSummary s = callPlayerSummary(web3j, addr);
            if (s.seatedInCurrentRound) return "已入座";
            if (s.entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) return "今日已打满12场";
            boolean payable = s.prize.compareTo(ArenaConfig.ENTRY_FEE) >= 0
                    || s.walletBalance.compareTo(ArenaConfig.ENTRY_FEE) >= 0;
            if (!payable) {
                double prizeD = new java.math.BigDecimal(s.prize)
                        .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
                return String.format("奖金 %.2f、钱包不足以 100", prizeD);
            }
            Function f = new Function("enterArena", new ArrayList<Type>(), new ArrayList<TypeReference<?>>());
            String data = FunctionEncoder.encode(f);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            
            // 使用 ArenaConfig 常量，与 BatchEnterTask 保持一致
            BigInteger gas = ArenaConfig.GAS_LIMIT_ENTER_ARENA;
            
            BigInteger need = gas.multiply(gasPrice);
            // 手续费币是 DAI：eth_getBalance 与它的 balanceOf 相等，但直接写出来是 wei 原值，不能给人看
            BigInteger gasBal = callGasTokenBalance(web3j, addr);
            if (gasBal.compareTo(need) < 0) {
                return "DAI 不足：需约 " + fmtDai(need) + " DAI，现有 " + fmtDai(gasBal)
                        + " DAI，请先到「批量充值手续费」补一点";
            }
            return null;
        } catch (Exception e) {
            return Web3AndroidUtil.safeMessage(e);
        }
    }

    // endregion

    // region playerSummary 编解码

    /**
     * 构造 playerSummary 的输出原型：前 10 个字全是 uint256，后面跟 1 个 bool + 3 个 uint256
     * + 2 个 bool。
     *
     * <p>必须与合约返回顺序完全一致：错一位会让后面的字段全部错位（金额变成布尔、布尔当金额），
     * 而且不会报错，只会展示成荒谬的值。</p>
     */
    private static List<TypeReference<?>> buildPlayerSummaryOutputs() {
        List<TypeReference<?>> outputs = new ArrayList<TypeReference<?>>();
        for (int i = 0; i < 10; i++) outputs.add(TypeReference.create(Uint256.class));
        outputs.add(TypeReference.create(Bool.class));          // 10 jiaziTruncated
        outputs.add(TypeReference.create(Uint256.class));       // 11 entriesToday
        outputs.add(TypeReference.create(Uint256.class));       // 12 entriesRemainingToday
        outputs.add(TypeReference.create(Uint256.class));       // 13 roundsFromPrize
        outputs.add(TypeReference.create(Bool.class));          // 14 nextEntryFromPrize
        outputs.add(TypeReference.create(Bool.class));          // 15 seatedInCurrentRound
        return outputs;
    }

    /** 拼 {@code playerSummary(address)} 的调用数据 */
    private static String encodePlayerSummaryData(String playerAddr) {
        List<Type> inputs = new ArrayList<Type>();
        inputs.add(new Address(playerAddr));
        Function func = new Function("playerSummary", inputs, PLAYER_SUMMARY_OUTPUTS);
        return FunctionEncoder.encode(func);
    }

    /**
     * 解析 playerSummary 返回的 16 个字（1024 个 hex）。
     *
     * <p>长度不足直接抛：sections 缺失时继续解会得到一堆 0，看起来就像“新号还没资产”，
     * 排查时极难发现是 RPC 返回了异常数据。</p>
     *
     * @throws RuntimeException 返回数据长度不足 1024 个 hex
     */
    private static PlayerSummary decodePlayerSummary(String hexValue) {
        PlayerSummary s = new PlayerSummary();
        String hex = Numeric.cleanHexPrefix(hexValue);
        Log.d("RPC_DEBUG", "decodePlayerSummary 输入 hex 长度: " + (hex != null ? hex.length() : "null"));

        if (hex == null || hex.length() < 1024) {
            Log.w("RPC_DEBUG", "decodePlayerSummary hex 长度不足 1024，实际: " + (hex != null ? hex.length() : "null"));
            throw new RuntimeException("decodePlayerSummary 返回数据长度不足：期望 1024，实际 " + (hex != null ? hex.length() : "null"));
        }

        s.walletBalance = safeHexUint(hex, 0);
        s.allowance = safeHexUint(hex, 1);
        s.prize = safeHexUint(hex, 2);
        s.inMatchDeposit = safeHexUint(hex, 3);
        s.arenaTotal = safeHexUint(hex, 4);
        s.battles = safeHexUint(hex, 5);
        s.championships = safeHexUint(hex, 6);
        s.jiaziTotalGranted = safeHexUint(hex, 7);
        s.jiaziReleased = safeHexUint(hex, 8);
        s.jiaziClaimable = safeHexUint(hex, 9);
        s.jiaziTruncated = safeHexBool(hex, 10);
        s.entriesToday = safeHexUint(hex, 11);
        s.entriesRemainingToday = safeHexUint(hex, 12);
        s.roundsFromPrize = safeHexUint(hex, 13);
        s.nextEntryFromPrize = safeHexBool(hex, 14);
        s.seatedInCurrentRound = safeHexBool(hex, 15);

        String result = "wallet=" + s.walletBalance + ", prize=" + s.prize + ", remaining=" + s.entriesRemainingToday;
        Log.d("RPC_DEBUG", "decodePlayerSummary 解析结果: " + result);
        return s;
    }

    /**
     * 取第 {@code fieldIndex} 个 32 字节字当 uint256；越界或格式异常一律回 0。
     *
     * <p>非抛异常的取舍：展示层宁可显示 0（配合日志里的 hex 可回溯），
     * 也不要因为一个字段把整行数据弄丢。</p>
     */
    private static BigInteger safeHexUint(String hex, int fieldIndex) {
        try {
            int offset = fieldIndex * 64;
            if (offset + 64 > hex.length()) return BigInteger.ZERO;
            return new BigInteger(hex.substring(offset, offset + 64), 16);
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /** 取第 {@code fieldIndex} 个字当 bool：非全 0 即 true，越界或异常回 false */
    private static boolean safeHexBool(String hex, int fieldIndex) {
        try {
            int offset = fieldIndex * 64;
            if (offset + 64 > hex.length()) return false;
            String word = hex.substring(offset, offset + 64);
            return !"0000000000000000000000000000000000000000000000000000000000000000".equals(word);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * web3j decode() 签名要求 List&lt;TypeReference&lt;Type&gt;&gt;,
     * 但 TypeReference.create() 返回 TypeReference&lt;?&gt;;
     * 运行时 ABI 解码保证类型正确,此处仅做编译期桥接.
     */
    @SuppressWarnings("unchecked")
    private static List<TypeReference<Type>> castOutputs(List<TypeReference<?>> outputs) {
        return (List<TypeReference<Type>>) (List<?>) outputs;
    }

    // endregion
}