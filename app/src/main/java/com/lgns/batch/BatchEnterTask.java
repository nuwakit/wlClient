package com.lgns.batch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;
import com.google.gson.Gson;
import com.lgns.manager.AppGlobal;
import com.lgns.model.ArenaConfig;
import com.lgns.contract.ArenaContractApi;
import com.lgns.contract.BlockscoutApi;
import com.lgns.model.KeyListItem;
import com.lgns.manager.KeyStoreManager;
import com.lgns.task.TaskLogCallback;
import com.lgns.task.TaskAccountState;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Convert;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 批量入场任务：把选中账号按两种模式送进 Arena，并维护可续跑的账号级进度。
 *
 * <p>模式差异：{@code MODE_FLEXIBLE} 让每个账号尽快占到座位，{@code MODE_TOGETHER}
 * 尽量把一批账号压进同一轮，便于集中结算与对账。</p>
 *
 * <p>职责边界：只负责入场与必要的 approve；主号补币在上游 {@link BatchFundTask} 完成，
 * 手续费不足只做跳过与提示，本类不替账号转账。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>入场失败会记入 {@code enterFailedAddrSet}，供领取阶段提示该号需要人工关注；
 *       <b>不再用它跳过领取</b>——失败号反而更该把待领的钱收回来。</li>
 *   <li>一笔入场可能顺带结算多轮（最多 {@code MAX_AUTO_SETTLE}），gas 消耗因此波动很大，
 *       余额检查要用 gasLimit 上限而不是历史值。</li>
 *   <li>手续费币是 ERC20 DAI，原生 value 转账在该链不可用。</li>
 * </ul>
 */
public class BatchEnterTask {
    // region 常量与运行状态

    /** 入场模式：能打就打——每个账号各自入座，不等其它账号，速度优先 */
    public static final int MODE_FLEXIBLE = 0;

    /** 入场模式：等齐再打——先整批模拟校验，全部通过才真实提交，避免只进场一半 */
    public static final int MODE_TOGETHER = 1;

    /**
     * 单席门票 100 fLGNS，与合约 {@code ENTRY_FEE = 100e18} 对齐。
     * 支付顺序由合约决定：奖金余额够 100 时先扣奖金（不产生 ERC20 转账），不够才扣钱包。
     */
    public static final BigInteger ENTRY_FEE = ArenaConfig.ENTRY_FEE;

    /**
     * ERC20 无限授权额度（2^256-1）。
     * 入场要合约从钱包划走 100 fLGNS，授权一次即长期有效，可以省掉每轮一次 approve 交易。
     */
    public static final BigInteger MAX_UINT256 = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);

    /** 账号进度状态码，取值与 {@link TaskAccountState} 一一对应：0 待执行 / 1 交易已广播 / 2 成功 / 3 失败 */
    public static final int STATUS_IDLE = 0;
    /** 交易已广播但未拿到回执：仅此状态会落 SharedPreferences，下次启动可续跑查回执 */
    public static final int STATUS_TX_SENT = 1;
    /** 本账号本轮已成功入场（不落盘，只是本次运行的统计口径） */
    public static final int STATUS_SUCCESS = 2;
    /** 本账号本轮失败（不落盘）：可重试失败进失败名单，后续领取阶段跳过 */
    public static final int STATUS_FAIL = 3;

    /**
     * 交易在节点池停留超过 60s 仍未打包就提示一次。
     * 参考值：正常的 transfer 数秒内出块，超过这个时长通常意味着节点拥塞或 gasPrice 偏低。
     */
    public static final long PENDING_WARN_THRESHOLD_MS = 60_000L;

    /** 本次批量里确定失败的地址，完整流程第 3 步领取前据此跳过，避免给失败账号白烧手续费 */
    public static final Set<String> enterFailedAddrSet = new HashSet<>();

    /**
     * 地址 → 最近一次 revert 的时间戳。
     * revert 属于确定性失败（条件不满足），同一状态下立刻重发只会再失败一次，冷却 10 分钟再试。
     */
    private static final java.util.Map<String, Long> revertedCooldownMap = new HashMap<>();
    private static final long REVERTED_COOLDOWN_MS = 10 * 60 * 1000L;

    /** 圈间等待封盘的轮询间隔：一轮大约几十秒封一次，5s 足够跟上又不打满节点 */
    private static final long SEAL_WAIT_POLL_MS = 5_000L;
    /** 圈间等待封盘的上限：一轮凑不齐就停下来，交给用户判断，不无限挂着 */
    private static final long SEAL_WAIT_TIMEOUT_MS = 6 * 60 * 1000L;

    /**
     * 缺座时「座位数连续多久没有增长」就判这一轮补不满，提前收尾。
     *
     * <p>6 分钟那个上限是留给「有人在补座」的；更常见的是停在 22/23 座再没人进来，
     * 那 6 分钟就是纯干等。座位数不再变化本身就是最直接的证据：缺的座没人填。</p>
     */
    private static final long SEAL_WAIT_NO_PROGRESS_MS = 2 * 60 * 1000L;

    /** 等待期间每隔这么久输出一条进度，避免界面长时间没有反馈 */
    private static final long SEAL_WAIT_REPORT_MS = 30_000L;

    /** {@link #waitRoundSealed} 结果：已封盘换轮，可以开下一圈 */
    private static final int WAIT_SEAL_OK = 1;
    /** {@link #waitRoundSealed} 结果：等满超时仍未封盘 */
    private static final int WAIT_SEAL_TIMEOUT = 0;
    /** {@link #waitRoundSealed} 结果：座位数长时间不再增长，判定这一轮补不满，提前收尾 */
    private static final int WAIT_SEAL_STALLED = -2;
    /** {@link #waitRoundSealed} 结果：用户按下停止，不再等（可能留下未封盘的轮次） */
    private static final int WAIT_SEAL_STOPPED = -1;

    /** 从执行日志里抠出 32 字节 txHash，用来判断这一笔是否真的发出了交易 */
    private static final Pattern TX_HASH_PATTERN = Pattern.compile("(0x[0-9a-fA-F]{64})");

    private static final Gson gson = new Gson();

    /** 账号级进度所在的 SharedPreferences 文件名，条目 key 为 {@code task_<地址小写>} */
    private static final String SP_TASK_PROGRESS = "task_progress";

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    // endregion

    // region 任务进度持久化

    private static SharedPreferences getTaskSp(Context ctx) {
        return ctx.getSharedPreferences(SP_TASK_PROGRESS, Context.MODE_PRIVATE);
    }

    /**
     * 落盘账号进度。只允许 IDLE / TX_SENT 进磁盘：
     * 成功与失败是本次运行的结果，没有续跑价值，写进去反而会让下次启动误判成「上一轮没跑完」。
     */
    private static void saveAccountState(Context ctx, String accountAddr, TaskAccountState state) {
        if (state.getStatus() == TaskAccountState.STATUS_SUCCESS || state.getStatus() == TaskAccountState.STATUS_FAIL) return;
        SharedPreferences sp = getTaskSp(ctx);
        String json = gson.toJson(state);
        sp.edit().putString("task_" + accountAddr.toLowerCase(), json).apply();
    }

    /**
     * 读取上次中断时留下的账号进度，并做两项清理：
     * chainId 与当前链不符（换链或升级前旧数据）直接丢弃；状态码越界的脏数据回落到 IDLE。
     */
    private static TaskAccountState getAccountState(Context ctx, String accountAddr) {
        SharedPreferences sp = getTaskSp(ctx);
        String json = sp.getString("task_" + accountAddr.toLowerCase(), null);
        if (json == null) return new TaskAccountState();
        TaskAccountState st = gson.fromJson(json, TaskAccountState.class);
        if (st == null) return new TaskAccountState();
        if (st.getChainId() != 0 && st.getChainId() != ArenaConfig.CHAIN_ID) {
            sp.edit().remove("task_" + accountAddr.toLowerCase()).apply();
            return new TaskAccountState();
        }
        if (st.getStatus() != TaskAccountState.STATUS_IDLE && st.getStatus() != TaskAccountState.STATUS_TX_SENT) {
            st = new TaskAccountState();
        }
        return st;
    }

    /**
     * 清空所有账号的入场进度（含 pending 的 txHash/rawTx）。
     *
     * <p>相当于放弃“可续跑”：清完之后再点批量入场会从头开始，
     * 已发出但未确认的交易不会再被回查（链上仍可能上链，需人工确认）。
     */
    public static void resetAllTaskProgress(Context context) {
        SharedPreferences sp = getTaskSp(context);
        sp.edit().clear().apply();
    }

    // endregion

    // region 批量入场入口

    /**
     * 批量入场入口（可续跑）。
     *
     * <p>过程分四段：</p>
     * <ol>
     *   <li>{@code MODE_TOGETHER} 先整批 {@code previewEnter} 模拟，任一账号被拒就整批不发；</li>
     *   <li>逐账号解密 → 命中 revert 冷却则跳过 → 交给 {@link #processEnterOneResume} 执行；</li>
     *   <li>单号遇到 IOException 时轮换 RPC 重试，其它异常只记失败不重试；</li>
     *   <li>结束后回读一遍 playerSummary 批量数据，让主界面直接按链上真实状态刷新统计。</li>
     * </ol>
     *
     * <p>串行执行 + 逐账号落盘：中途退出后再次点击会先按磁盘上的 TX_SENT 查回执，
     * 不会重新发一笔，因此重复点击不会造成重复入场。</p>
     */
    public static void batchEnterAllResume(Context ctx, TaskLogCallback callback, int playMode,
                                           List<KeyListItem> selectedList, char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "ENTER", "⚠没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "ENTER", "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();
        enterFailedAddrSet.clear();
        HashMap<String, BigInteger> accountLocalNonceMap = new HashMap<>();

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            callback.onLog("", "ENTER", "开始批量入场 · mode="
                    + (playMode == MODE_FLEXIBLE ? "能打就打" : "等齐再打")
                    + " · 节点=" + Web3AndroidUtil.currentRpcUrl()
                    + " · chainId=" + ArenaConfig.CHAIN_ID);

            // 等齐再打：整批先干跑一遍（eth_call 模拟），任一账号不满足条件就整批不发，
            // 避免只打进去一半、另一半因为余额/次数不够被拒
            if (playMode == MODE_TOGETHER) {
                callback.onLog("", "ENTER", "🔍 等齐再打模式:开始模拟预览");
                List<String> rejects = new ArrayList<>();
                for (KeyListItem item : selectedList) {
                    String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    String plainPk;
                    try {
                        plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    } catch (Exception ex) {
                        rejects.add(keyNo + ":解密失败");
                        continue;
                    }
                    ECKeyPair kp = Web3AndroidUtil.pkToKeyPair(plainPk);
                    String addr = Web3AndroidUtil.getAddress(kp).toLowerCase();
                    String reason = ArenaContractApi.previewEnter(web3j, addr, kp);
                    kp = null;
                    if (reason != null) rejects.add(keyNo + ":" + reason);
                    if (Web3AndroidUtil.isCancelRequested()) {
                        callback.onLog("", "ENTER", "🛑 预览阶段被用户取消");
                        return;
                    }
                }
                if (!rejects.isEmpty()) {
                    callback.onLog("", "ENTER", "✖ 预览失败 " + rejects.size() + " 个账号,整批拒发:"
                            + String.join(" / ", rejects));
                    return;
                }
                callback.onLog("", "ENTER", "✅ 预览全通过,开始真实提交");
            }

            // 逐账号串行：账号之间互不依赖，单号失败只进失败名单，不影响后面的账号
            int successCount = 0, skipCount = 0, failCount = 0;
            List<String> failNoList = new ArrayList<>();
            int total = selectedList.size();

            for (int i = 0; i < selectedList.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "ENTER", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                        ? item.customCode : item.displayNo;
                callback.onLog(keyNo, "ENTER", "处理账号 " + (i + 1) + "/" + total);

                String plainPk;
                String fromAddr = null;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    ECKeyPair tempPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(tempPair).toLowerCase();
                    tempPair = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "ENTER", "✖ 解密失败:" + msgOf(ex));
                    callback.onKeyFailure(keyNo, "解密失败", msgOf(ex));
                    failCount++;
                    failNoList.add(keyNo);
                    continue;
                }

                Long revertTs = revertedCooldownMap.get(fromAddr);
                if (revertTs != null && System.currentTimeMillis() - revertTs < REVERTED_COOLDOWN_MS) {
                    long remainMin = (REVERTED_COOLDOWN_MS - (System.currentTimeMillis() - revertTs)) / 60000;
                    callback.onLog(keyNo, "ENTER", " [" + fromAddr.substring(0, 10) + "...] reverted冷却中(" + remainMin + "min),跳过");
                    skipCount++;
                    continue;
                }

                // 只有连接类异常才换节点重试：revert / 业务失败换个节点结果一样，重试只会多烧一笔手续费
                int result = ENTER_FAIL;
                for (int rpcTry = 0; rpcTry < ArenaConfig.RPC_URLS.length; rpcTry++) {
                    try {
                        result = processEnterOneResume(ctx, web3j, plainPk, keyNo, callback, accountLocalNonceMap);
                        break;
                    } catch (java.io.IOException ioEx) {
                        callback.onLog(keyNo, "ENTER", "⚠ 网络异常(" + (rpcTry + 1) + "/"
                                + ArenaConfig.RPC_URLS.length + "):" + msgOf(ioEx) + ",轮换 RPC 重试");
                        Web3j next = Web3AndroidUtil.rotateWeb3j(web3j);
                        if (next == web3j) break;
                        web3j = next;
                    } catch (Exception e) {
                        callback.onLog(keyNo, "ENTER", "✖ 非 IOException 兜底:" + msgOf(e));
                        callback.onKeyFailure(keyNo, "执行异常", msgOf(e));
                        result = ENTER_FAIL;
                        break;
                    }
                }

                if (result == ENTER_SUCCESS) {
                    successCount++;
                } else if (result == ENTER_SKIP) {
                    skipCount++;
                } else {
                    failCount++;
                    failNoList.add(keyNo);
                    if (fromAddr != null) enterFailedAddrSet.add(fromAddr);
                    callback.onKeyFailure(keyNo, "入场失败", "RPC重试耗尽或执行失败");
                }
            }

            String failNosStr = String.join(",", failNoList);
            String enterSummary = "批量入场结束 · 成功=" + successCount + " 跳过=" + skipCount + " 失败=" + failCount
                    + (failNosStr.isEmpty() ? "" : " · 失败编号:" + failNosStr);
            callback.onLog("", "ENTER", enterSummary);

            // 收尾回读：把链上最新状态（座位、奖金、今日场次）一次性交给界面，避免界面拿旧数据
            java.util.Map<String, ArenaContractApi.PlayerSummary> summaries = null;
            try {
                java.util.List<String> addrList = new ArrayList<>();
                for (KeyListItem item : selectedList) {
                    if (item.address != null && !item.address.isEmpty()) addrList.add(item.address);
                }
                if (!addrList.isEmpty()) {
                    summaries = ArenaContractApi.callPlayerSummaryBatch(web3j, addrList);
                }
            } catch (Throwable t) {
                callback.onLog("", "ENTER", "⚠查询链上数据异常(不影响入场结果):" + t.getClass().getSimpleName());
            }
            callback.onBatchResult(summaries);
        } catch (java.io.IOException dialFail) {
            callback.onLog("", "ENTER", "❌ " + ArenaConfig.RPC_URLS.length + " 个节点全不可达:" + msgOf(dialFail));
            // 降级探测：用浏览器 API 判断「链活着、只是 RPC 网关挂」还是「链真停了」
            Long height = BlockscoutApi.fetchLatestBlockHeight();
            callback.onLog("", "ENTER", height != null
                    ? "Blockscout 备用探测：链高度=" + height + "（链正常，稍后重试即可）"
                    : "Blockscout 备用探测：浏览器也不可达，链可能停摆");
            throw dialFail;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 连打多圈入口：一次密码输完，按 {@code rounds} 圈连续入场。
     *
     * <p>为什么需要它：合约按「今天打了几场」线性解锁师徒分成（10%/场），只打一场只能领 10%，
     * 剩下 90% 到 D+2 就被 {@code _burnExpiredLineage} 烧掉。而同一轮不能重复入座
     * （{@code isSeated[rid][msg.sender]}），所以第 2 场必须等上一轮凑满 24 座封盘。</p>
     *
     * <p>圈数被夹在 {@code [1, MAX_ENTRIES_PER_DAY]}（合约硬上限 12，越过就 revert
     * {@code DailyLimitReached}）。但 12 不等于「都该打」：甲子释放、师徒系数、解锁比例
     * 三项都在第 10 场封顶（{@code _recordMatch} 从第 11 场起就 {@code return false}）。
     * 第 11、12 场并不是白打——每场仍是一次独立的 1/24 抽奖，师承加成也继续逐席计提
     * （那里的 {@code mine} 只被 12 封顶），所以 12 场比 10 场多约 +10.67 fLGNS 名义期望，
     * 只多付 2 笔手续费。推荐 10 场真正的理由是<b>余量</b>：打满 12 场当天额度清零，
     * 任一号失败就再无重试机会，而缺一个座这一轮就封不了盘。完整口径见
     * {@link ArenaConfig#MAX_ENTRIES_PER_DAY}，本处不再复述，避免两份账跑偏。</p>
     */
    public static void batchEnterAllRoundsResume(Context ctx, TaskLogCallback callback, int playMode,
                                                 int rounds, List<KeyListItem> selectedList,
                                                 char[] sessionCryptoKey) throws Exception {
        batchEnterAllRoundsResume(ctx, callback, playMode, rounds, selectedList, sessionCryptoKey, false);
    }

    /**
     * 同上，多一个「预检是否已经做过」的开关。
     *
     * <p>{@code preflightDone=false}（单点「批量入场」）：先跑一遍预检，把圈数夹到全批最差的号
     * 真能打的圈数；预检说 0 圈就干脆不发交易。以前单点完全没预检，DAI 见底、今日已打满
     * 都要等每个号依次跑完才从日志里看到，而这期间还在圈间白白等封盘。</p>
     *
     * <p>{@code preflightDone=true}（完整流程）：充值之后已经预检并夹过圈数了，再查一遍只是
     * 白花一轮 RPC、再打一条重复的「预检通过」。</p>
     */
    public static void batchEnterAllRoundsResume(Context ctx, TaskLogCallback callback, int playMode,
                                                 int rounds, List<KeyListItem> selectedList,
                                                 char[] sessionCryptoKey, boolean preflightDone) throws Exception {
        int total = Math.max(1, Math.min(rounds, ArenaConfig.MAX_ENTRIES_PER_DAY));
        if (!preflightDone) {
            total = runPreflight(callback, selectedList, total);
            if (total <= 0) {
                // 一个号都不该进去：把上一轮的失败名单清掉，否则领取阶段会拿着旧名单提示「本次入场失败」
                enterFailedAddrSet.clear();
                return;
            }
        }
        if (total <= 1) {
            // 只打 1 圈时没有「圈边界」可停：开始前就收到请求就整段跳过，
            // 开始后才收到就让它跑完——反正只有一圈，强行中断反而会留下半轮
            if (Web3AndroidUtil.isStopAfterRoundRequested()) {
                callback.onLog("", "ENTER", "⏸ 已按要求停止：停止请求在入场开始前收到，本次不入场");
                return;
            }
            batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
            if (Web3AndroidUtil.isStopAfterRoundRequested()) {
                callback.onLog("", "ENTER", "⏸ 已按要求停止：本次只有 1 圈，已经跑完（没有半轮残留）");
            }
            return;
        }
        callback.onLog("", "ENTER", "连打模式 · 共 " + total + " 圈 · 在场 " + selectedList.size()
                + " 个号（一轮需 " + ArenaConfig.SEATS_PER_ROUND + " 座）");
        for (int r = 1; r <= total; r++) {
            if (Web3AndroidUtil.isCancelRequested()) {
                callback.onLog("", "ENTER", "🛑 用户取消，已打 " + (r - 1) + "/" + total + " 圈");
                return;
            }
            // 「打完这圈就停」只在这里生效：上一圈已封盘、座位已清空，停下不会留下半轮。
            // 半轮里的押金在合约里锁着（没有超时退款），所以不能停在圈内。
            if (Web3AndroidUtil.isStopAfterRoundRequested()) {
                callback.onLog("", "ENTER", "⏸ 已按要求停止：打完 " + (r - 1) + " 圈，剩余 "
                        + (total - r + 1) + " 圈未打。已打的都停在封盘之后，没有半轮锁仓");
                logResumeHint(callback, total - r + 1);
                return;
            }
            callback.onLog("", "ENTER", "===== 第 " + r + "/" + total + " 圈 =====");
            // 单场足额逐圈补币：前置补币只在全部圈之前跑一次，补到 100 的号
            // 夺冠后（拿锁仓甲子、无 claimable）下一圈钱包见底，不逐圈补足就会缺座停摆
            if (r > 1 && ArenaConfig.autoReplenishEnable
                    && ArenaConfig.fundMode == ArenaConfig.FUND_MODE_SINGLE) {
                topUpSingleMode(callback, selectedList, sessionCryptoKey, r);
            }
            batchEnterAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);

            if (r == total) break;
            // 同一轮不能重复入座：没封盘就开下一圈，只会整批撞 AlreadySeated
            int seal = waitRoundSealed(callback, selectedList, r);
            if (seal == WAIT_SEAL_STOPPED) {
                // 这一圈没等到封盘就退出：不是「打完 r 圈」，要如实说缺座与押金的后果
                callback.onLog("", "ENTER", "⏸ 已按要求停止：第 " + r + " 圈未封盘就退出，剩余 "
                        + (total - r) + " 圈未打。这一轮的押金要等凑满 "
                        + ArenaConfig.SEATS_PER_ROUND + " 座才会结算");
                logResumeHint(callback, total - r);
                return;
            }
            if (seal == WAIT_SEAL_STALLED) {
                // 座位数已经不再增长了：缺的座我们自己补不了，外部也没人来。
                // 和等满 6 分钟的后果一样（后续圈取消、这一轮押金继续锁着），只是不再干耗时间
                callback.onLog("", "ENTER", "⏹ 第 " + r + " 圈补不满，后续 " + (total - r)
                        + " 圈已取消。这一轮的押金要等凑满 " + ArenaConfig.SEATS_PER_ROUND
                        + " 座才会结算；补好缺座的号再点一次「批量入场」即可续打");
                return;
            }
            if (seal == WAIT_SEAL_TIMEOUT) {
                callback.onLog("", "ENTER", "⏹ 第 " + r + " 圈后未能封盘，后续"
                        + (total - r) + " 圈已取消（先把这一轮凑满，再点一次入场即可续打）");
                return;
            }
        }
        callback.onLog("", "ENTER", "✅ 连打结束 · 共 " + total + " 圈");
    }

    /**
     * 单点「批量入场」的开打前预检：把圈数夹到「全批最差那个号」真能打的圈数。
     *
     * <p>完整流程在充值之后会做同一件事，单点过去没有：DAI 见底、今日已打满 12 场、
     * 门票不够，都要等每个号依次跑完才从日志里看到，而且前两种还会在前面白花时间。
     * 判据直接复用 {@link BatchWorkflowTask#preflight}，不另写一套—两份账早晚会对不上。</p>
     *
     * @return 实际应打的圈数；0 表示这一批不该开打（原因已写进日志）
     */
    private static int runPreflight(TaskLogCallback callback, List<KeyListItem> selectedList, int wantRounds) {
        BatchWorkflowTask.Preflight pf = BatchWorkflowTask.preflight(callback, selectedList, wantRounds);
        if (pf.fatal) {
            callback.onLog("", "ENTER", "⛔ 预检未通过，本次不入场（原因见上方）");
            return 0;
        }
        if (pf.rounds <= 0) {
            callback.onLog("", "ENTER", "⏹ 预检：本轮不入场（原因见上方）。补好 DAI 或等明天再点；"
                    + "先点「批量领取」也能把分成与奖金领回来");
            return 0;
        }
        if (pf.rounds < wantRounds) {
            callback.onLog("", "ENTER", "ℹ 圈数已按预检夹到 " + pf.rounds + " 圈（配置 " + wantRounds + " 圈）");
        }
        return pf.rounds;
    }

    /** 停止后给一句可操作的续打指引：不用改配置，圈数会被夹到今日剩余场次 */
    private static void logResumeHint(TaskLogCallback callback, int remainRounds) {
        if (remainRounds <= 0) return;
        callback.onLog("", "ENTER", "💡 续打：再点一次「批量入场」即可 —— 配置不用改，"
                + "今日剩余场次不够时会自动夹到 " + remainRounds + " 圈以内");
    }

    /**
     * 单场足额逐圈补币：把钱包不足 100 fLGNS 的号由主号补到正好 100，再开这一圈。
     *
     * <p>为什么需要它：前置补币（{@link BatchFundTask#executeAutoFund}）只在全部圈之前跑一次，
     * 单场足额的目标只有 100；第 1 圈后输家拿到 claimable 够下一场，但冠军拿的是锁仓甲子、
     * 钱包归零，不逐圈补足就会命中「奖金、钱包都不足 100」被跳过 → 这一轮缺座永不封盘。
     * 多场足额没有这个问题（一次性按最坏情况预留），所以不在这里重复补。</p>
     *
     * <p>静默跳过式补币：全部够则不发交易也不刷日志（连打时每圈都经过这里）；
     * 只有真补了币才等 5 秒再入场，与前置补币同一口径，避免入场预检读到旧余额。
     * 单号失败只记日志不中断整批，与 {@code executeAutoFund} 同一套容错。</p>
     */
    private static void topUpSingleMode(TaskLogCallback callback, List<KeyListItem> selectedList,
                                        char[] sessionCryptoKey, int roundNo) {
        long masterId;
        KeyStoreManager.KeyEntry masterEntry;
        try {
            KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
            masterId = mgr.getMasterAccountId();
            if (masterId <= 0) return;
            masterEntry = null;
            for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                if (e.id == masterId) { masterEntry = e; break; }
            }
            if (masterEntry == null || masterEntry.cipherText == null) return;
        } catch (Exception e) {
            callback.onLog("", "FUND", "⚠ 逐圈补币初始化失败（本圈照常入场）：" + msgOf(e));
            return;
        }

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            // 筛出钱包不足本场门票的号（同 executeAutoFund 口径：主号与调试地址不进名单）
            List<String> addrs = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<BigInteger> diffs = new ArrayList<>();
            for (KeyListItem item : selectedList) {
                if (item.keyId == masterId) continue;
                if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                        && ArenaConfig.isDebugAddr(item.address)) continue;
                if (item.address == null || item.address.equalsIgnoreCase(masterEntry.address)) continue;
                BigInteger bal = ArenaContractApi.callErc20Balance(web3j, item.address);
                if (bal.compareTo(ENTRY_FEE) < 0) {
                    addrs.add(item.address);
                    diffs.add(ENTRY_FEE.subtract(bal));
                    labels.add((item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo);
                }
            }
            if (addrs.isEmpty()) return;

            callback.onLog("", "FUND", "🔄 单场足额逐圈补币：第 " + roundNo + " 圈前 " + addrs.size()
                    + " 个号门票不足 100 fLGNS，由主号补齐");
            String masterPk = AppGlobal.getKeyStoreManager()
                    .decryptCipherText(masterEntry.cipherText, sessionCryptoKey);
            ECKeyPair masterKeyPair = Web3AndroidUtil.pkToKeyPair(masterPk);
            String masterAddr = Web3AndroidUtil.getAddress(masterKeyPair);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger nonce = web3j.ethGetTransactionCount(masterAddr,
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();

            int topped = 0;
            for (int i = 0; i < addrs.size(); i++) {
                if (Web3AndroidUtil.isStopRequested()) {
                    callback.onLog("", "FUND", "⏸ 逐圈补币中断：剩余 " + (addrs.size() - i)
                            + " 个号未补（已发出的交易不回滚）");
                    break;
                }
                try {
                    ArenaContractApi.doErc20Transfer(web3j, masterKeyPair, addrs.get(i),
                            diffs.get(i), nonce, gasPrice);
                    nonce = nonce.add(BigInteger.ONE);
                    topped++;
                    callback.onLog("", "FUND", labels.get(i) + " 补入 "
                            + new java.math.BigDecimal(diffs.get(i)).divide(
                                    new java.math.BigDecimal(BigInteger.TEN.pow(18)), 2,
                                    java.math.RoundingMode.HALF_UP)
                            + " fLGNS");
                } catch (Exception e) {
                    callback.onLog("", "FUND", "错误：" + labels.get(i) + " 补币失败: " + msgOf(e));
                }
            }
            if (topped > 0) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException ignore) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            callback.onLog("", "FUND", "错误：逐圈补币异常（本圈照常入场）：" + msgOf(e));
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 等「所有账号都离开当前轮」，即上一轮已凑满 24 座并封盘。
     *
     * <p>判据是 {@code playerSummary.seatedInCurrentRound}：只要还有一个号坐在当轮，
     * 继续入场就会撞 {@code AlreadySeated}。封盘时 {@code currentRoundId} 已经 +1，
     * 旧座位的 {@code isSeated} 也随之清掉，所以「全部为 false」就等于「已经换轮了」。</p>
     *
     * <p>一轮要 24 座，因此只有「在场账号数 >= 24」时才会立即通过。缺座时不能只管等：
     * 这个等待期间只有外部玩家会往里坐，所以<b>座位数连续 {@code SEAL_WAIT_NO_PROGRESS_MS}
     * 不再增长</b>就等于「这一轮补不满」，提前返回 {@link #WAIT_SEAL_STALLED}，
     * 不再陪着干等满 {@code SEAL_WAIT_TIMEOUT_MS}。座位数还在涨就继续等，最长仍是 6 分钟。</p>
     *
     * @return {@link #WAIT_SEAL_OK} 已封盘可以开下一圈；{@link #WAIT_SEAL_STALLED}
     *         缺座且长时间无新增入座；{@link #WAIT_SEAL_TIMEOUT} 等满超时或查询失败；
     *         {@link #WAIT_SEAL_STOPPED} 用户要求停止
     */
    private static int waitRoundSealed(TaskLogCallback callback, List<KeyListItem> selectedList, int roundNo) {
        List<String> addrs = new ArrayList<>();
        for (KeyListItem item : selectedList) {
            if (item.address != null && !item.address.isEmpty()) addrs.add(item.address);
        }
        if (addrs.isEmpty()) return WAIT_SEAL_OK;

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            long start = System.currentTimeMillis();
            long deadline = start + SEAL_WAIT_TIMEOUT_MS;
            // 首次轮询就报一次：缺座时让用户 5 秒内看到实情，而不是干等 30 秒才出第一行日志
            long nextReport = start;
            // 上一轮读到的入座数，仅用于停止时把账说清楚；-1 表示还没查到
            int lastSeated = -1;
            // 座位数最近一次变多的时刻。这个等待期间只有外部玩家会往里坐，
            // 所以它连续不动够久，就是「这一轮没人补座」的证据
            long lastProgressAt = start;
            while (System.currentTimeMillis() < deadline) {
                if (Web3AndroidUtil.isCancelRequested()) return WAIT_SEAL_TIMEOUT;
                // 停止检查放在查询之前：RPC 一直拿不到数时也要能停下来，不能被查询卡住
                if (Web3AndroidUtil.isStopAfterRoundRequested()) {
                    if (lastSeated <= 0) {
                        callback.onLog("", "ENTER", "⏸ 已按要求停止等待封盘");
                    } else {
                        int lack = Math.max(0, ArenaConfig.SEATS_PER_ROUND - lastSeated);
                        callback.onLog("", "ENTER", "⏸ 已按要求停止等待封盘：当前轮已入座 "
                                + lastSeated + " 座，还差 " + lack + " 座 —— 这轮的押金要等凑满 "
                                + ArenaConfig.SEATS_PER_ROUND + " 座才会结算");
                    }
                    return WAIT_SEAL_STOPPED;
                }
                java.util.Map<String, ArenaContractApi.PlayerSummary> summaries =
                        ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);
                // 查询不全就不能当「已封盘」：宁可多等一轮，也不要带着旧状态开下一圈
                if (summaries == null || summaries.size() < addrs.size()) {
                    Thread.sleep(SEAL_WAIT_POLL_MS);
                    continue;
                }
                // 先数实际入座数：日志必须报「已入座 N 座」，不能报账号总数——
                // 有号因余额不足跳过时，总数仍是 24，但座位只有 23，用户会看不懂“还差 0 座为何封不了盘”
                int seatedCount = 0;
                for (ArenaContractApi.PlayerSummary s : summaries.values()) {
                    if (s != null && s.seatedInCurrentRound) seatedCount++;
                }
                long now = System.currentTimeMillis();
                if (seatedCount > lastSeated) lastProgressAt = now;
                lastSeated = seatedCount;
                if (seatedCount == 0) {
                    callback.onLog("", "ENTER", "✅ 第 " + roundNo + " 圈已封盘，继续下一圈");
                    return WAIT_SEAL_OK;
                }
                // 座位数不再增长 = 缺的座没人来补，再等下去只是干耗时间
                if (now - lastProgressAt >= SEAL_WAIT_NO_PROGRESS_MS) {
                    int lack = Math.max(0, ArenaConfig.SEATS_PER_ROUND - seatedCount);
                    callback.onLog("", "ENTER", "⏹ 第 " + roundNo + " 圈 " + (SEAL_WAIT_NO_PROGRESS_MS / 1000L)
                            + "s 没有新增入座：停在 " + seatedCount + " 座 / 需 "
                            + ArenaConfig.SEATS_PER_ROUND + " 座（还差 " + lack
                            + " 座），判定外部玩家不会补这一轮，不再等待");
                    return WAIT_SEAL_STALLED;
                }
                if (now >= nextReport) {
                    long remainSec = (deadline - now) / 1000;
                    int lack = Math.max(0, ArenaConfig.SEATS_PER_ROUND - seatedCount);
                    callback.onLog("", "ENTER", "⏳ 第 " + roundNo + " 圈尚未封盘：已入座 " + seatedCount
                            + " 座 / 需 " + ArenaConfig.SEATS_PER_ROUND + " 座，还差 " + lack
                            + " 座；最多再等 " + remainSec + "s");
                    // 缺座且不会再有人补：提前说清后果，别让用户干等满 6 分钟
                    if (lack > 0) {
                        callback.onLog("", "ENTER", "💡 缺的座位只能靠外部玩家补。若一直等不到，"
                                + "本轮不封盘、后续圈会取消（多半是某个冠军号钱包已空，下一圈买不了票）");
                    }
                    nextReport = now + SEAL_WAIT_REPORT_MS;
                }
                Thread.sleep(SEAL_WAIT_POLL_MS);
            }
            return WAIT_SEAL_TIMEOUT;
        } catch (Exception e) {
            callback.onLog("", "ENTER", "⚠️等待封盘时查询失败:" + msgOf(e));
            return WAIT_SEAL_TIMEOUT;
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    // endregion

    // region 单号入场

    /**
     * 单号结果：可重试的失败，计入失败名单。
     *
     * <p>三个值依次为：0=失败 / 1=跳过（含已在座、交易在途）/ 2=真正成功（拿到 txHash）。</p>
     */
    private static final int ENTER_FAIL = 0;
    /** 单号结果：本轮不需要再发交易（已在座 / 交易还在路上 / 条件不满足），不算失败 */
    private static final int ENTER_SKIP = 1;
    /** 单号结果：本轮真的发出了一笔入场交易 */
    private static final int ENTER_SUCCESS = 2;

    /**
     * 单号入场（可续跑）。
     *
     * <p>先处理上一次留下的尾巴，再决定要不要发新交易：</p>
     * <ol>
     *   <li>磁盘记录是 TX_SENT：查回执——成功则结束；reverted 则清状态重执行；
     *       长时间无回执则用 PENDING nonce 判断是否已被消费（nonce-consumed）；</li>
     *   <li>链上 {@code seatedInCurrentRound} 已为真 → 跳过，不重复买座；</li>
     *   <li>取 nonce（优先用本批缓存）后进入 {@link #enterOneAccountOriginal} 真实入场；</li>
     *   <li>从日志里抠 txHash 判定是否真发出，没发出就清状态，交给下次重试。</li>
     * </ol>
     */
    private static int processEnterOneResume(Context ctx, Web3j web3j, String privateKey, String keyNo,
                                                 TaskLogCallback logCallback, HashMap<String, BigInteger> accountLocalNonceMap)
            throws java.io.IOException {
        ECKeyPair keyPair = null;
        String fromAddr = null;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(privateKey);
            fromAddr = Web3AndroidUtil.getAddress(keyPair).toLowerCase();
            TaskAccountState diskState = getAccountState(ctx, fromAddr);
            if (diskState.getStatus() == TaskAccountState.STATUS_TX_SENT && diskState.getTxHash() != null) {
                long elapsed = System.currentTimeMillis() - diskState.getTimestamp();
                logCallback.onLog(keyNo, "ENTER", "[" + fromAddr.substring(0, 10) + "...] 查询历史入场交易回执 txHash:" + diskState.getTxHash());
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(diskState.getTxHash()).send();
                Optional<TransactionReceipt> optReceipt = receiptResp.getTransactionReceipt();
                TransactionReceipt receipt = optReceipt.orElse(null);

                if (receipt != null) {
                    boolean txSuccess = "0x1".equalsIgnoreCase(receipt.getStatus());
                    if (txSuccess) {
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        logCallback.onLog(keyNo, "ENTER", "✔ [" + fromAddr.substring(0, 10) + "...] 历史入场交易链上确认成功");
                        return ENTER_SKIP;
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "✖ [" + fromAddr.substring(0, 10) + "...] 历史入场交易reverted,重置状态准备重执行");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                    }
                } else if (elapsed > ArenaConfig.PENDING_STALE_SEC * 1000L) {
                    BigInteger currentNonce = web3j.ethGetTransactionCount(fromAddr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    BigInteger recorded = accountLocalNonceMap.get(fromAddr);
                    boolean consumed = (recorded == null) || currentNonce.compareTo(recorded) > 0;
                    if (consumed) {
                        logCallback.onLog(keyNo, "ENTER", "⚠ [" + fromAddr.substring(0, 10) + "...] 交易 "
                                + diskState.getTxHash().substring(0, 10) + "... 超 5min 未回执且 nonce="
                                + currentNonce + ",判 nonce-consumed,清状态");
                        saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                        accountLocalNonceMap.put(fromAddr, currentNonce);
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...] 超 5min 仍在节点池,保持 TX_SENT");
                    }
                    return ENTER_SKIP;
                } else {
                    if (elapsed > PENDING_WARN_THRESHOLD_MS) {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场pending已超过60s,交易仍在节点池中,请观察链上状态,本轮跳过");
                    } else {
                        logCallback.onLog(keyNo, "ENTER", "⏳ [" + fromAddr.substring(0, 10) + "...]入场交易在节点Pending池中,等待打包,本轮跳过");
                    }
                    return ENTER_SKIP;
                }
            }

            ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, fromAddr);
            if (summary.seatedInCurrentRound) {
                logCallback.onLog(keyNo, "ENTER", "➖ [" + fromAddr.substring(0, 10) + "...] 合约查询已经入座,跳过入场");
                return ENTER_SKIP;
            }

            BigInteger nonce;
            if (accountLocalNonceMap.containsKey(fromAddr)) {
                nonce = accountLocalNonceMap.get(fromAddr);
            } else {
                nonce = web3j.ethGetTransactionCount(fromAddr, DefaultBlockParameterName.PENDING).send().getTransactionCount();
                accountLocalNonceMap.put(fromAddr, nonce);
            }

            Pair<String, BigInteger> ret = enterOneAccountOriginal(ctx, fromAddr, web3j, keyPair, nonce);
            String logLine = ret.first;
            BigInteger nextNonce = ret.second;
            logCallback.onLog(keyNo, "ENTER", logLine);

            String txHash = null;
            Matcher matcher = TX_HASH_PATTERN.matcher(logLine);
            if (matcher.find()) {
                txHash = matcher.group(1);
            }

            if (txHash != null && !txHash.isEmpty()) {
                accountLocalNonceMap.put(fromAddr, nextNonce);
                return ENTER_SUCCESS;
            } else {
                saveAccountState(ctx, fromAddr, new TaskAccountState(TaskAccountState.STATUS_IDLE, null));
                return ENTER_SKIP;
            }
        } catch (java.io.IOException ioEx) {
            throw ioEx;
        } catch (Throwable t) {
            t.printStackTrace();
            String msg = msgOf(t);
            if (msg != null && msg.contains("reverted")) {
                revertedCooldownMap.put(fromAddr, System.currentTimeMillis());
            }
            logCallback.onLog(keyNo, "ENTER", " 【入场致命异常】:" + t.getClass().getSimpleName() + " | " + msg);
            return ENTER_FAIL;
        } finally {
            keyPair = null;
        }
    }

    /**
     * 真实入场：先做本地可用性判断，再按「奖金 / 钱包」两条支付路径发交易。
     *
     * <p>顺序不能调：</p>
     * <ol>
     *   <li>用固定 gasLimit 估网络费，原生币（本链就是 DAI）不够直接跳过；</li>
     *   <li>读 playerSummary：今日剩余场次为 0 直接跳过（合约 MAX_ENTRIES_PER_DAY = 12）；</li>
     *   <li>奖金 ≥ 100 走奖金抵扣，不动钱包也不用 approve；否则查钱包与 allowance，
     *       不足 100 跳过，够但授权不足时先 approve 无限额度；</li>
     *   <li>发 enterArena 并等回执，日志带 block / nonce / gasUsed 方便对账。</li>
     * </ol>
     */
    private static Pair<String, BigInteger> enterOneAccountOriginal(Context ctx, String fromAddrLower,
                                                                    Web3j web3j, ECKeyPair keyPair, BigInteger nonce) throws Exception {
        String addr = Web3AndroidUtil.getAddress(keyPair);
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        String enterData = org.web3j.abi.FunctionEncoder.encode(
                new org.web3j.abi.datatypes.Function("enterArena",
                        new ArrayList<>(), new ArrayList<>()));

        // 使用 ArenaConfig 常量，避免 eth_estimateGas 返回值格式错误。
        // 入场可能顺带结算前面轮次（MAX_AUTO_SETTLE = 1），gas 波动很大，只能用上限估算
        BigInteger estGas = ArenaConfig.GAS_LIMIT_ENTER_ARENA;

        BigInteger nativeBalance = web3j.ethGetBalance(addr, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger needNative = estGas.multiply(gasPrice);
        if (nativeBalance.compareTo(needNative) < 0) {
            BigInteger diff = needNative.subtract(nativeBalance);
            double diffEth = Convert.fromWei(diff.toString(), Convert.Unit.ETHER).doubleValue();
            return new Pair<>(String.format("%s... 跳过:DAI 不足，差约 %.4f DAI", addr.substring(0, 10), diffEth), nonce);
        }
        ArenaContractApi.PlayerSummary summary = ArenaContractApi.callPlayerSummary(web3j, addr);
        BigInteger walletBalance = summary.walletBalance;
        BigInteger allowance = summary.allowance;
        BigInteger prize = summary.prize;
        BigInteger entriesRemainingToday = summary.entriesRemainingToday;

        // 检查今日是否还能入场
        if (entriesRemainingToday.compareTo(BigInteger.ZERO) == 0) {
            return new Pair<>(String.format("%s... 跳过:今日已打满12场", addr.substring(0, 10)), nonce);
        }

        // 支付优先级与合约一致：奖金余额够就先扣奖金，既不用 approve 也不受钱包余额影响
        String payDesc;
        boolean needApprove = false;
        if (prize.compareTo(ENTRY_FEE) >= 0) {
            payDesc = "参与游戏 (奖金抵扣)";
        } else {
            if (walletBalance.compareTo(ENTRY_FEE) >= 0) {
                payDesc = "参与游戏 (钱包扣币)";
                if (allowance.compareTo(ENTRY_FEE) < 0) {
                    needApprove = true;
                }
            } else {
                double prizeD = Convert.fromWei(prize.toString(), Convert.Unit.ETHER).doubleValue();
                double walletD = Convert.fromWei(walletBalance.toString(), Convert.Unit.ETHER).doubleValue();
                return new Pair<>(String.format("%s... 跳过:奖金 %.2f、钱包 %.2f 都不足 100", addr.substring(0, 10), prizeD, walletD), nonce);
            }
        }
        BigInteger currentNonce = nonce;
        if (needApprove) {
            try {
                ArenaContractApi.doApproveArena(web3j, keyPair, MAX_UINT256, currentNonce, gasPrice,
                        (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                                new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
            } catch (Exception approveEx) {
                throw new Exception("approve 失败:" + Web3AndroidUtil.safeMessage(approveEx), approveEx);
            }
            currentNonce = currentNonce.add(BigInteger.ONE);
        }
        try {
            TransactionReceipt enterReceipt = ArenaContractApi.doEnterArena(web3j, keyPair, currentNonce, gasPrice, estGas,
                    (txHash, rawTxHex) -> saveAccountState(ctx, fromAddrLower,
                            new TaskAccountState(TaskAccountState.STATUS_TX_SENT, txHash, rawTxHex)));
            String txHash = enterReceipt.getTransactionHash();
            String blockNumber = enterReceipt.getBlockNumber() != null ? enterReceipt.getBlockNumber().toString() : "未知";
            String gasUsed = enterReceipt.getGasUsed() != null ? enterReceipt.getGasUsed().toString() : "未知";
            String logText = String.format("%s... 成功 %s | txHash=%s | block=%s | nonce=%s | gasUsed=%s | gasLimit=%s",
                addr.substring(0, 10), payDesc, txHash, blockNumber, currentNonce.toString(), gasUsed, estGas.toString());
            return new Pair<>(logText, currentNonce.add(BigInteger.ONE));
        } catch (Exception enterEx) {
            String errMsg = Web3AndroidUtil.safeMessage(enterEx);
            if (errMsg.contains("reverted")) {
                throw new Exception("enterArena reverted:" + errMsg, enterEx);
            }
            throw new Exception("enterArena 失败:" + errMsg + " | nonce=" + currentNonce, enterEx);
        }
    }

    // endregion

}
