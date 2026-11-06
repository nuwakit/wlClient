package com.lgns.batch;

import android.content.Context;
import com.lgns.contract.ArenaContractApi;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;
import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整流程编排：把「充值 → 入场 → 领取 → 铸造」串成一次可中断、可续跑的批量作业。
 *
 * <p>本类只做步骤调度、等待、预检与取消检查，具体动作全部委托给
 * {@link BatchFundTask}、{@link BatchEnterTask}、{@link BatchClaimTask}、{@link BatchMintTask}，
 * 自身不直接发交易，也不读写进度存储。</p>
 *
 * <p>每一步都会检查取消标记：用户中途取消时只停止后续步骤，已完成的部分不回滚。</p>
 */
public class BatchWorkflowTask {
    /** 充值阶段结束后的额外等待：只补节点同步延迟，交易本身已在上一步等过回执 */
    private static final long WAIT_AFTER_FUND_MS = 5000L;

    /** 入场阶段结束后的额外等待：等轮次结算状态在轮换到的节点上可见 */
    private static final long WAIT_AFTER_ENTER_MS = 5000L;

    /** 可取消等待的检查粒度：按下取消后最迟这么久就能退出，不必等整段睡完 */
    private static final long WAIT_SLICE_MS = 250L;

    // region 工作流入口

    /**
     * 完整流程：充值 → 等待入账 → 入场（按 {@link ArenaConfig#enterRounds} 圈）→ 等待结算 → 领取 → 铸造盟主令。
     *
     * <p>两处等待等的是「节点间可见性」，不是交易本身：{@link BatchFundTask} 与
     * {@link BatchEnterTask} 对每笔交易都会等到回执才返回，回到这里时交易已经上链。
     * 留几秒只是给轮换到的其它 RPC 节点同步区块——不然子号可能读到旧余额被误判“不足”跳过入场，
     * 或者领到还没结算完的甲子俸。等待切成 {@value #WAIT_SLICE_MS}ms 小段，期间可随时响应取消。</p>
     *
     * <p>圈数由调用方快照传入（出厂默认值 {@link ArenaConfig#DEFAULT_ENTER_ROUNDS} 是 1 圈）：
     * 充值目标、预检圈数、实际入场圈数三者用同一个值，任务中途改配置也影响不到已在跑的这批。
     * 合约按「今天打了几场」线性解锁师徒分成，只打一场只能领 10%，至少要打到
     * {@code FULL_ACTIVITY_MATCHES}（10）才能全额解锁；所以想拿满<b>解锁比例</b>的用户
     * 必须自己把配置改到 10。11/12 圈虽在允许范围内，甲子推进与解锁比例都不再变化，
     * 但每场仍是一次独立抽奖、师承加成也继续逐席计提 —— 该说的是「结构性收益已封顶」，
     * 不是「多打没收益」，口径以 {@link ArenaConfig#MAX_ENTRIES_PER_DAY} 为准。</p>
     *
     * <p>连打多圈时，最后一轮的结算由领取阶段的<b>第一笔</b>交易顺带触发
     * （{@code _drainPending(MAX_AUTO_SETTLE)}，每次只推一轮）；前几轮已经在
     * 下一圈的第一笔入场里结算完毕。</p>
     *
     * <p>{@code claimMaxRetry} 透传给领取阶段：甲子俸一页 64 个，序号多的账号需要多笔。</p>
     *
     * @param rounds 连打圈数快照，由调用方在任务启动时读一次 {@link ArenaConfig#enterRounds} 传入
     */
    public static void fullArenaWorkflowResume(Context ctx, TaskLogCallback callback, int claimMaxRetry,
                                               int rounds, int playMode, List<KeyListItem> selectedList,
                                               char[] sessionCryptoKey) throws Exception {
        // 圈数只夹一次，后面三步（充值目标 / 预检 / 入场）全用这一个值，不再回读配置
        final int wantRounds = Math.max(1, Math.min(rounds, ArenaConfig.MAX_ENTRIES_PER_DAY));
        callback.onLog("", "FUND", "完整流程 · 第1步：批量充值");
        BatchFundTask.executeAutoFund(selectedList, sessionCryptoKey, callback, wantRounds);

        // 充值阶段按「停止」也要真的停：这一段没有座位与押金，停就是停。
        // 但入场不能拿着一份「一部分号有钱、一部分没钱」的名单硬打——那样这一轮必然凑不满
        // 24 座，已入座的号的押金还要等到封盘才能结算；所以跳过入场，直接进领取
        // （甲子俸与师徒分成不领就会进烧毁队列，那一段不管用户怎么停都要跑）。
        if (Web3AndroidUtil.isStopRequested()) {
            callback.onLog("", "", "⏸ 已按要求停止：充值阶段中断，跳过入场（领取/铸造照常执行）");
            BatchEnterTask.enterFailedAddrSet.clear();
            callback.onLog("", "CLAIM", "完整流程 · 第3步：批量领取");
            BatchClaimTask.batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
            callback.onLog("", "MINT", "完整流程 · 第4步：批量铸造");
            BatchMintTask.batchMintAllResume(ctx, callback, selectedList, sessionCryptoKey);
            callback.onLog("", "", "完整流程结束");
            return;
        }

        callback.onLog("", "FUND", "等待充值交易确认...");
        if (!sleepUnlessCancelled(WAIT_AFTER_FUND_MS)) {
            callback.onLog("", "", "用户取消，停止流程");
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        // 充值已经落地，这时读到的余额才是真实值，预检放这里最准
        Preflight pf = preflight(callback, selectedList, wantRounds);
        if (pf.fatal) {
            callback.onLog("", "", "完整流程中止：预检未通过（原因见上方 ⚠ 行）");
            BatchEnterTask.enterFailedAddrSet.clear();
            return;
        }

        if (pf.rounds > 0) {
            callback.onLog("", "ENTER", "完整流程 · 第2步：批量入场（" + pf.rounds + " 圈）");
            // 已经在这里预检过并把圈数夹好了：告诉入场任务别再查一遍（白花一轮 RPC、
            // 还会在日志里重复打一条「预检通过」）
            BatchEnterTask.batchEnterAllRoundsResume(ctx, callback, playMode, pf.rounds,
                    selectedList, sessionCryptoKey, true);

            // 按了「停止」只作用于入场：已打的圈都停在封盘之后，接着领奖反而不能省
            // （甲子俸与分成不领就会进烧毁队列）
            if (Web3AndroidUtil.isStopAfterRoundRequested()) {
                callback.onLog("", "ENTER", "已按要求停在圈边界：剩余圈数未打，领取阶段照常执行");
            }

            if (Web3AndroidUtil.isCancelRequested()) {
                callback.onLog("", "", "用户取消，跳过领取阶段");
                // 入场失败的地址只在本次流程内有意义，退出前清掉，避免下次流程误跳
                BatchEnterTask.enterFailedAddrSet.clear();
                return;
            }

            callback.onLog("", "ENTER", "等待入场交易确认及游戏状态更新...");
            if (!sleepUnlessCancelled(WAIT_AFTER_ENTER_MS)) {
                callback.onLog("", "", "用户取消，跳过领取阶段");
                BatchEnterTask.enterFailedAddrSet.clear();
                return;
            }
        } else {
            // 圈数为 0：要么今日已打满，要么 DAI 不够再打一场，具体原因预检那行已经写了。
            // 领取仍然要做——昨天的甲子俸与师徒分成不领就会过期。
            callback.onLog("", "ENTER", "跳过入场（原因见上方预检），直接进入领取");
        }

        callback.onLog("", "CLAIM", "完整流程 · 第3步：批量领取");
        BatchClaimTask.batchClaimAllResume(ctx, callback, playMode, selectedList, sessionCryptoKey);
        BatchEnterTask.enterFailedAddrSet.clear();

        // 铸造紧跟领取：两者打的是不同合约（arena 主合约 vs ChampionMandate NFT），
        // 不需等待结算——可铸清单只含领讫且满 60 天的俸禄，本轮新领的不进清单，
        // callMintableIndices 会自己筛；用户在领取阶段按过停止，这里首个取消检查就自然跳出
        callback.onLog("", "MINT", "完整流程 · 第4步：批量铸造");
        BatchMintTask.batchMintAllResume(ctx, callback, selectedList, sessionCryptoKey);

        callback.onLog("", "", "完整流程结束");
    }

    // endregion

    // region 预检

    /**
     * 预检结果：只回答「这轮能不能打、能打几圈」，不改任何状态、不发任何交易。
     *
     * <p>包级可见：单点「批量入场」也要用同一套判据，见
     * {@link BatchEnterTask#batchEnterAllRoundsResume}。</p>
     */
    static final class Preflight {
        /** 实际可打圈数；0 表示今天打不了（已打满或 DAI 不足），应跳过入场只做领取 */
        int rounds;
        /** true 表示连地址都读不出来，整条流程没有继续的意义 */
        boolean fatal;
    }

    /**
     * 入场前预检：先算清「今天还能打几场」再决定圈子数，避开空跑与半途崩溃。
     *
     * <p>为什么必须放在充值之后：{@link BatchFundTask} 刚把门票补到目标值，
     * 补完再读余额才是真实值；提到充值之前会读到旧余额，把「钱其实已经到位」的号误判成不够。</p>
     *
     * <p>三项检查的取舍：</p>
     * <ol>
     *   <li><b>今日剩余场次</b>——合约 {@code matchesToday >= MAX_ENTRIES_PER_DAY} 直接 revert
     *       {@code DailyLimitReached}。以前不做这个判断，今天已打 2 场再点 12 圈，
     *       会空跑到第 11 圈才被本地拦下，刷出 24×10 条「跳过」日志。</li>
     *   <li><b>每号 DAI 够打几圈</b>——这是唯一会让整轮凑不满 {@link ArenaConfig#SEATS_PER_ROUND}
     *       座的致命前置：只要有一个号发不出交易，那一轮就永远封不了盘，后续所有圈在
     *       {@code SEAL_WAIT_TIMEOUT_MS} 后集体取消。而充值只补 fLGNS，<b>完全不碰 DAI</b>，
     *       所以必须单独查一次。</li>
     *   <li><b>账号数是不是 24 的整数倍</b>——只提示不拦。缺的座只能等外部玩家，
     *       这是开打前能把它说出口的唯一机会。</li>
     * </ol>
     *
     * <p>查不到就当够用：预检是加分项，一次 RPC 抖动不该把整条流程拦下，
     * 真正的判定留给入场阶段。取的都是全批<b>最差</b>那个号——木桶效应，一个号掉队全轮作废。</p>
     */
    static Preflight preflight(TaskLogCallback callback, List<KeyListItem> list, int wantRounds) {
        Preflight pf = new Preflight();
        int want = Math.max(1, Math.min(wantRounds, ArenaConfig.MAX_ENTRIES_PER_DAY));
        pf.rounds = want;

        List<String> addrs = new ArrayList<String>();
        Map<String, String> addrNo = new HashMap<String, String>();
        for (KeyListItem item : list) {
            if (item.address == null || item.address.isEmpty()) continue;
            addrs.add(item.address);
            addrNo.put(item.address, (item.customCode != null && !item.customCode.isEmpty())
                    ? item.customCode : item.displayNo);
        }
        if (addrs.isEmpty()) {
            pf.fatal = true;
            callback.onLog("", "ENTER", "⛔ 预检未通过：选中账号都没有地址，无法判断余额与场次");
            return pf;
        }

        // ① 座位数：缺座不会报错，只会静默等到封盘超时，所以要提前把预期说清楚。
        // 只提示不拦：账号数是用户自己导入的私钥，客户端没有理由替他做数量校验。
        // 用「ℹ」而不是「⚠」也是这个意思——凑不满不是错，是这一轮要等外部玩家补座。
        int seats = ArenaConfig.SEATS_PER_ROUND;
        int filled = addrs.size() / seats;
        int remainder = addrs.size() % seats;
        if (remainder != 0) {
            callback.onLog("", "ENTER", "ℹ 预检：" + addrs.size() + " 个号，一轮要 " + seats
                    + " 座才封盘 —— " + (filled > 0 ? "前 " + filled + " 轮能自行凑满；" : "")
                    + "剩下 " + remainder + " 座要等外部玩家补齐，补不上就停在那一轮（已打的圈不受影响）");
        }

        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();

            // ② 今日剩余场次：一次批量查询拿全部账号，比逐号 activityOf 省一半 RPC
            Map<String, ArenaContractApi.PlayerSummary> summaries =
                    ArenaContractApi.callPlayerSummaryBatch(web3j, addrs);
            int minRounds = want;
            String matchBlocker = null;
            int seated = 0;
            for (String addr : addrs) {
                ArenaContractApi.PlayerSummary s = summaries.get(addr);
                if (s == null) continue;   // 查不到就不参与判定，交给入场阶段自己判
                if (s.seatedInCurrentRound) seated++;
                int left = s.entriesRemainingToday.intValue();
                if (left < minRounds) {
                    minRounds = Math.max(0, left);
                    matchBlocker = addrNo.get(addr) + " 今日已打 " + s.entriesToday + " 场";
                }
                if (minRounds <= 0) break;
            }
            if (seated > 0) {
                // 同一轮不能重复入座：这些号要等当前轮凑满 24 座、封盘结算后才能再进
                callback.onLog("", "ENTER", "⚠ 预检：" + seated + " 个号仍在当前轮里，"
                        + "要等该轮凑满 " + seats + " 座封盘后才能打下一场");
            }

            // ③ 每号 DAI 够打几圈：同样取全批最差
            String gasBlocker = null;
            if (minRounds > 0) {
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                BigInteger perRound = ArenaConfig.ENTER_GAS_ESTIMATE.multiply(gasPrice);
                for (String addr : addrs) {
                    if (Web3AndroidUtil.isCancelRequested()) break;
                    BigInteger dai;
                    try {
                        dai = ArenaContractApi.callGasTokenBalance(web3j, addr);
                    } catch (Exception ignore) {
                        continue;   // 单号查不到按够用处理，不因一次抖动拦整批
                    }
                    // 领取阶段也要花 DAI，先预留出去；否则「打得动却领不了」同样白跑
                    BigInteger usable = dai.subtract(ArenaConfig.CLAIM_GAS_RESERVE);
                    BigInteger afford = usable.signum() <= 0
                            ? BigInteger.ZERO : usable.divide(perRound);
                    if (afford.compareTo(BigInteger.valueOf(minRounds)) < 0) {
                        // 只在「比现有圈数更少」时才取值，intValue 不会溢出
                        minRounds = afford.intValue();
                        gasBlocker = addrNo.get(addr) + " DAI 仅 " + fmtDai(dai)
                                + "，够打 " + minRounds + " 圈";
                    }
                    if (minRounds <= 0) break;
                }
            }

            pf.rounds = minRounds;
            if (minRounds <= 0) {
                callback.onLog("", "ENTER", "⏹ 预检：本轮不入场 —— "
                        + joinReason(matchBlocker, gasBlocker));
            } else if (minRounds < want) {
                callback.onLog("", "ENTER", "⚠ 预检：配置 " + want + " 圈，本次只打 " + minRounds
                        + " 圈（" + joinReason(matchBlocker, gasBlocker) + "）");
            } else {
                callback.onLog("", "ENTER", "✅ 预检通过：" + addrs.size() + " 个号都能打满 " + want
                        + " 圈（DAI 与今日场次均够）");
            }
        } catch (Exception e) {
            // 拿不到链上数据不等于不能打：按配置圈数继续，判定留给入场阶段
            pf.rounds = want;
            callback.onLog("", "ENTER", "⚠ 预检查询失败，按配置 " + want + " 圈继续："
                    + Web3AndroidUtil.safeMessage(e));
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignore) {}
            }
        }
        return pf;
    }

    /** 把「场次」与「DAI」两条拦截原因拼成一句话，都为空时给兜底文案 */
    private static String joinReason(String matches, String gas) {
        if (matches == null) return gas == null ? "见上方明细" : gas;
        return gas == null ? matches : matches + "；" + gas;
    }

    /** 18 位小数的金额转可读 DAI（截断 4 位）：日志里直接打 wei 用户看不出量级 */
    private static String fmtDai(BigInteger wei) {
        if (wei == null) return "0";
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 4, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    // endregion

    // region 可取消等待

    /**
     * 分段休眠，任一段之间检查取消标记。
     *
     * <p>直接用 {@link Thread#sleep(long)} 的话，用户在这段时间按下取消要等整段睡完才生效；
     * 切成小段后取消最多延迟一个 {@link #WAIT_SLICE_MS}。中途被打断（InterruptedException）
     * 按取消处理，异常继续上抛给调用方收尾。</p>
     *
     * @return {@code true} 表示睡满且期间未取消；{@code false} 表示期间收到取消请求
     */
    private static boolean sleepUnlessCancelled(long totalMs) throws InterruptedException {
        long waited = 0L;
        while (waited < totalMs) {
            if (Web3AndroidUtil.isCancelRequested()) return false;
            long step = Math.min(WAIT_SLICE_MS, totalMs - waited);
            Thread.sleep(step);
            waited += step;
        }
        return !Web3AndroidUtil.isCancelRequested();
    }

    // endregion

}
