package com.lgns.batch;

import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 批量充值手续费：给每个启用账号补手续费币，出资方是「私钥管理」的主号。
 *
 * <p>币种与链路：本链手续费是一枚 ERC20 DAI（{@code ArenaConfig.GAS_TOKEN_ADDR}，18 位小数），
 * 原生 value 转账会被节点按「余额不足」拒绝，所以入账走的是该代币的 {@code transfer}。</p>
 *
 * <p>充值口径：差额式。目标值是用户填的金额，实际只补「目标 − 当前余额」，余额已够就跳过；
 * 主号除了付出充值额，还要预留自己那几笔 transfer 的手续费，判断余额时必须一并计入。</p>
 *
 * <p>单位约定：界面与日志里的数量一律是 DAI；gas 只是计算手续费的计量单位，两者不能混写。</p>
 */
public final class BatchGasFundTask {
    // region 常量与数据结构

    /** 手续费币（DAI）的精度，18 位与链上 decimals() 一致 */
    public static final int DECIMALS = 18;
    /** 1 DAI 对应的最小单位（1e18 wei），所有界面金额与链上数值都经它换算 */
    public static final BigInteger WEI = BigInteger.TEN.pow(DECIMALS);

    /**
     * 列表行：一个目标账号的目标金额与当前余额。
     *
     * <p>金额字段有两套口径：{@link #amount} 是用户看到的 DAI（小数，可编辑），
     * {@link #balanceWei} 是链上拿回来的 wei；两者只在 {@link #targetWei()} / {@link #needWei()}
     * 里短暂相减，不要在别处直接比大小。</p>
     */
    public static class Row {
        /** 余额还没查回来（首屏先占位，不参与缺口合计） */
        public static final int STATE_QUERYING = 0;
        /** 余额已查到，可以算缺口、可以转账 */
        public static final int STATE_READY = 1;
        /** 余额查询失败（节点异常）：不能当成 0 余额继续充值，否则会多发 */
        public static final int STATE_FAILED = 2;

        /** 账号展示编号（优先自定义编号，没填回退到 3 位 displayNo） */
        public String keyNo = "";
        /** 收款地址，转账的实际目标 */
        public String address = "";
        /** 链上查到的当前余额（wei），只有 {@link #STATE_READY} 时才可信 */
        public BigInteger balanceWei = BigInteger.ZERO;
        /** 用户输入的目标余额（DAI 小数），默认值由页面按全局配置填 */
        public BigDecimal amount = BigDecimal.ZERO;
        /** 用户手动改过金额：改默认值时不再覆盖 */
        public boolean edited;
        /** 当前行状态，默认 {@link #STATE_QUERYING}；只有 READY 才允许参与缺口计算 */
        public int state = STATE_QUERYING;

        /** 用户填的目标值折算成 wei：界面是 DAI，链上要 wei，中间不能丢精度 */
        public BigInteger targetWei() {
            return amount == null ? BigInteger.ZERO : amount.multiply(new BigDecimal(WEI)).toBigInteger();
        }

        /** 实际要转的金额 = 目标 − 当前余额；已够则为 0（差额式，不是每次都补满额） */
        public BigInteger needWei() {
            if (state != STATE_READY) return BigInteger.ZERO;
            BigInteger diff = targetWei().subtract(balanceWei);
            return diff.signum() > 0 ? diff : BigInteger.ZERO;
        }
    }

    /** 余额查询回调：每查完一条回调一次，用于边查边刷新列表 */
    public interface BalanceCallback {
        /**
         * 单行余额回来。
         *
         * @param done  已处理条数（从 1 开始）
         * @param total 总条数，用于算进度
         * @param row   刚查完的那一行，状态已回填为 READY 或 FAILED
         */
        void onBalance(int done, int total, Row row);
    }

    /** 执行日志回调：文案已含 emoji 与序号，调用方只需追加到界面 */
    public interface LogCallback {
        /** @param message 已拼好的单行日志，可能来自任意工作线程 */
        void onLog(String message);
    }

    /** 执行结果汇总：成功/失败笔数与主号的余额、应付额 */
    public static class ExecResult {
        /** 成功笔数 */
        public int success;
        /** 失败笔数（单笔失败不影响其它账号） */
        public int failed;
        /** 出资方（主号）地址，日志与对账用 */
        public String masterAddr = "";
        /** 主号执行前的 DAI 余额（wei），用于“不够”提示里的对比 */
        public BigInteger masterBalance = BigInteger.ZERO;
        /** 本轮实际需要付出的 DAI（不含主号自己的转账手续费） */
        public BigInteger masterNeed = BigInteger.ZERO;
    }

    private BatchGasFundTask() { }

    // endregion

    // region 采集账号

    /**
     * 采集目标账号：启用 + 非主号 + 地址有效 + 非调试地址。
     *
     * <p>读库在主线程之外调用，方法内不做任何链上交互；主号由
     * {@link KeyStoreManager#getMasterAccountId()} 指定，主号自己不参与补 Gas。</p>
     */
    public static List<Row> buildRows() throws Exception {
        // 只读库拼行，不解密：会话过期也要能进页看清单
        KeyStoreManager mgr = AppGlobal.getMetaManager();
        long masterId = mgr.getMasterAccountId();
        List<KeyStoreManager.KeyEntry> all = mgr.loadKeyEntryList();
        List<Row> rows = new ArrayList<>();
        for (KeyStoreManager.KeyEntry e : all) {
            if (e.id == masterId) continue;
            if (!e.enabled) continue;
            if (e.address == null || e.address.isEmpty()) continue;
            if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && ArenaConfig.isDebugAddr(e.address)) continue;
            Row r = new Row();
            r.keyNo = (e.customCode != null && !e.customCode.isEmpty())
                    ? e.customCode : String.format(Locale.getDefault(), "%03d", e.id);
            r.address = e.address;
            rows.add(r);
        }
        return rows;
    }

    // endregion

    // region 金额格式化

    /**
     * wei 直转小数字符串（截断不舍入，避免多显示额度）。
     *
     * @param scale 保留小数位
     */
    public static String formatWei(BigInteger wei, int scale) {
        if (wei == null) return "0";
        return new BigDecimal(wei).divide(new BigDecimal(WEI), scale, java.math.RoundingMode.DOWN).toPlainString();
    }

    /**
     * 界面展示用：保持 4 位小数的观感，但当金额小到 4 位显示不出来时放宽到 8 位，
     * 否则 0.00001 DAI 这样的合法输入会被显示成 0.0000，用户会以为没在充。
     */
    public static String formatAmount(BigInteger wei) {
        String s = formatWei(wei, 4);
        if (wei != null && wei.signum() > 0 && !hasNonZero(s)) s = formatWei(wei, 8);
        return s;
    }

    /** 判断格式化后的字符串里是否还存在非 0 数字位（小数点和 0 不算） */
    private static boolean hasNonZero(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '1' && c <= '9') return true;
        }
        return false;
    }

    // endregion

    // region 余额查询

    /** 逐行查 DAI 余额，查一条回一条，长列表边查边看 */
    public static void queryBalances(List<Row> rows, BalanceCallback cb) {
        Web3j web3j = null;
        try {
            web3j = Web3AndroidUtil.dialAny();
            int total = rows.size();
            int done = 0;
            for (Row r : rows) {
                if (Web3AndroidUtil.isCancelRequested()) break;
                try {
                    r.balanceWei = ArenaContractApi.callGasTokenBalance(web3j, r.address);
                    r.state = Row.STATE_READY;
                } catch (Exception e) {
                    r.state = Row.STATE_FAILED;
                    r.balanceWei = BigInteger.ZERO;
                }
                done++;
                if (cb != null) cb.onBalance(done, total, r);
            }
        } catch (Exception ignored) {
            // 节点连不上：行状态已经在上面逐条落成 FAILED，交给页面提示
        } finally {
            if (web3j != null) {
                try { web3j.shutdown(); } catch (Exception ignored) { }
            }
        }
    }

    // endregion

    // region 执行充值

    /**
     * 执行：主号签名逐笔转 GAS_TOKEN（手续费币）。
     *
     * <p>四个步骤缺一不可：</p>
     * <ol>
     *   <li>重查每个账号余额，算出真实缺口（页面可能停留很久）；</li>
     *   <li>把主号自己这几十笔 transfer 的网络费一并计入（手续费币也是 DAI，同样从主号扣）；</li>
     *   <li>主号不够就整体不动手，避免转一半断供；</li>
     *   <li>逐笔发送，单笔失败只记失败数，已发出的不回滚。</li>
     * </ol>
     */
    public static ExecResult execute(List<Row> rows, char[] sessionKey, LogCallback cb) throws Exception {
        ExecResult result = new ExecResult();
        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
        long masterId = mgr.getMasterAccountId();
        if (masterId <= 0) throw new Exception("未设置主号，请先在私钥管理中设置主号");

        KeyStoreManager.KeyEntry master = null;
        for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
            if (e.id == masterId) { master = e; break; }
        }
        if (master == null || master.cipherText == null) throw new Exception("主号信息异常，请检查私钥管理");

        String pk = mgr.decryptCipherText(master.cipherText, sessionKey);
        ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        String fromAddr = Web3AndroidUtil.getAddress(keyPair);
        result.masterAddr = fromAddr;

        Web3j web3j = Web3AndroidUtil.dialAny();
        try {
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger nonce = web3j.ethGetTransactionCount(fromAddr,
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();
            result.masterBalance = ArenaContractApi.callGasTokenBalance(web3j, fromAddr);

            // 先算一遍真实缺口（含手续费预留），不够就整体不动手，避免转到一半断供
            List<BigInteger> needs = new ArrayList<>(rows.size());
            BigInteger needTotal = BigInteger.ZERO;
            int payCnt = 0;
            for (Row r : rows) {
                BigInteger bal;
                try {
                    bal = ArenaContractApi.callGasTokenBalance(web3j, r.address);
                } catch (Exception e) {
                    // 单行查不到余额：记 0 缺口并标记失败，不能因为一个坏节点让整批都不转
                    needs.add(BigInteger.ZERO);
                    r.state = Row.STATE_FAILED;
                    if (cb != null) cb.onLog("⚠ " + r.keyNo + " 余额查询失败，已跳过");
                    continue;
                }
                BigInteger diff = r.targetWei().subtract(bal);
                if (diff.signum() < 0) diff = BigInteger.ZERO;
                needs.add(diff);
                needTotal = needTotal.add(diff);
                if (diff.signum() > 0) payCnt++;
            }
            result.masterNeed = needTotal;
            BigInteger feeReserve = gasPrice.multiply(ArenaConfig.GAS_LIMIT_GAS_TOKEN_TRANSFER)
                    .multiply(BigInteger.valueOf(Math.max(payCnt, 1)));
            if (needTotal.signum() > 0
                    && result.masterBalance.compareTo(needTotal.add(feeReserve)) < 0) {
                throw new Exception("主号 DAI 不足：本次共需 "
                        + formatWei(needTotal.add(feeReserve), 6) + " DAI（其中 "
                        + formatWei(feeReserve, 6) + " DAI 留给 " + payCnt + " 笔转账的网络费），主号现有 "
                        + formatWei(result.masterBalance, 6) + " DAI");
            }

            for (int i = 0; i < rows.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    if (cb != null) cb.onLog("⚠ 已取消，剩余账号未充值");
                    break;
                }
                Row r = rows.get(i);
                BigInteger diff = needs.get(i);
                if (diff.signum() <= 0) {
                    if (cb != null) cb.onLog("➖ " + r.keyNo + " DAI 已够，跳过");
                    continue;
                }
                try {
                    ArenaContractApi.doGasTokenTransfer(web3j, keyPair, r.address, diff, nonce, gasPrice);
                    nonce = nonce.add(BigInteger.ONE);
                    r.balanceWei = r.balanceWei.add(diff);
                    result.success++;
                    if (cb != null) cb.onLog("✅ " + r.keyNo + " 转入 " + formatWei(diff, 6) + " DAI");
                } catch (Exception e) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.keyNo + " 失败：" + Web3AndroidUtil.safeMessage(e));
                }
            }
        } finally {
            try { web3j.shutdown(); } catch (Exception ignored) { }
        }
        return result;
    }

    // endregion

}
