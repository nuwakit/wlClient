package com.lgns.batch;

import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.AddressItem;
import com.lgns.model.ArenaConfig;
import com.lgns.model.PayerItem;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量转账任务：把「支付方管理」主号的 fLGNS 余额转给「接收方管理」里启用的地址。
 *
 * <p>两套分配口径，由系统配置页的「批量转账」（{@code ArenaConfig.transferMode}）选定：</p>
 * <ul>
 *   <li><b>按收益比例</b>：把支付方余额当成 100%，按每行的百分比拆开（{@link #allocateWei}）；</li>
 *   <li><b>按收益数额</b>：每行固定拿用户填的 fLGNS 枚数（{@link #allocateByAmount}），
 *       不依赖余额，余额只用来判「累计数额能不能转得起」。</li>
 * </ul>
 *
 * <p>与批量充值同一套资产与链路：余额用 {@code balanceOf}、转账用 {@code transfer}，
 * 差别只在金额来源——充值是补齐到固定值，转账是把收益按两种口径之一分掉。</p>
 *
 * <p>比例与尾差：按比例算出的金额可能存在舍入误差，分配时以最后一次转账兜底剩余额度，
 * 避免分完之后还有零头留在主号上。按数额时不存在尾差概念，但多出来的余额仍留在支付方。</p>
 *
 * <p>前置条件：支付方 fLGNS 要够分（两种模式都是「合计 ≤ 余额」），同时 DAI 要够付这 N 笔
 * 转账的手续费，缺一即整体不执行。</p>
 */
public final class BatchTransferTask {
    // region 常量与数据结构

    /** 代币最小单位：fLGNS 与 DAI 都是 18 位小数，换算统一走这个常量 */
    public static final BigInteger WEI = BigInteger.TEN.pow(18);

    /** 一行接收方：编号 / 地址 / 收益比例 / 收益数额 / 本次转出的 wei */
    public static class Row {
        /** 展示编号（优先自定义编号，没填用地址序号） */
        public String code = "";
        /** 收款地址 */
        public String address = "";
        /** 收益比例，百分数字符串（如 "12.50" 表示 12.5%）；保留字符串是为了不丢用户输入的小数位 */
        public String ratio = "0.00";
        /** 收益数额，fLGNS 枚数的十进制字符串（如 "1000"）；按数额模式才是分配依据 */
        public String amount = "0.00";
        /** 本次实际转出的金额（wei），由 {@link #allocateWei} 或 {@link #allocateByAmount} 回填 */
        public BigInteger amountWei = BigInteger.ZERO;
    }

    /** 转账日志回调：文案已拼好，调用方只需追加到界面 */
    public interface LogCallback {
        /** @param message 单行日志，可能来自任意工作线程 */
        void onLog(String message);
    }

    /** 一次转账的结果快照：成功/失败笔数、合计转出、支付方地址与余额 */
    public static class Result {
        /** 成功笔数 */
        public int success;
        /** 失败笔数（单笔失败不重试，只记账） */
        public int failed;
        /** 本次计划转出的合计（wei），不是实际成功金额 */
        public BigInteger totalWei = BigInteger.ZERO;
        /** 支付方地址 */
        public String payerAddr = "";
        /** 支付方执行前的 fLGNS 余额（wei），用于余额不足提示的对比 */
        public BigInteger payerBalance = BigInteger.ZERO;
    }

    private BatchTransferTask() { }

    // endregion

    // region 构建与分配

    /**
     * 把「接收方管理」里启用的地址转成待转账行；比例与数额都只搬运不换算，
     * 具体走哪一套由 {@link #allocateWei}（比例）或 {@link #allocateByAmount}（数额）决定。
     */
    public static List<Row> buildRows(List<AddressItem> enabled) {
        List<Row> rows = new ArrayList<>();
        if (enabled == null) return rows;
        for (AddressItem it : enabled) {
            if (it.address == null || it.address.isEmpty()) continue;
            Row r = new Row();
            r.code = (it.customCode != null && !it.customCode.isEmpty()) ? it.customCode : it.displayNo();
            r.address = it.address;
            r.ratio = it.ratio;
            r.amount = it.amount;
            rows.add(r);
        }
        return rows;
    }

    /** 按比例拆分总额，每份向下取整到 wei；返回未分配部分（比例合计不足 100% 时留有余量） */
    public static BigInteger allocate(BigDecimal totalGas, List<Row> rows) {
        BigInteger totalWei = totalGas == null ? BigInteger.ZERO
                : totalGas.multiply(new BigDecimal(WEI)).toBigInteger();
        return allocateWei(totalWei, rows);
    }

    /** 同上，但直接接受 wei：可分配总额 = 支付方余额 - 手续费预留，已是 wei，不用来回换算 */
    public static BigInteger allocateWei(BigInteger totalWei, List<Row> rows) {
        if (totalWei == null) totalWei = BigInteger.ZERO;
        BigInteger used = BigInteger.ZERO;
        for (Row r : rows) {
            BigDecimal ratio;
            try {
                ratio = new BigDecimal(r.ratio == null ? "0" : r.ratio);
            } catch (Exception e) {
                ratio = BigDecimal.ZERO;
            }
            BigInteger part = new BigDecimal(totalWei)
                    .multiply(ratio)
                    .divide(new BigDecimal(100), 0, RoundingMode.DOWN)
                    .toBigInteger();
            r.amountWei = part.signum() > 0 ? part : BigInteger.ZERO;
            used = used.add(r.amountWei);
        }
        BigInteger left = totalWei.subtract(used);
        return left.signum() > 0 ? left : BigInteger.ZERO;
    }

    /**
     * 按数额分配：每行的收益数额（fLGNS 枚数）直接换成 wei，向下取整防超发。
     *
     * <p>与按比例不同，这里不读支付方余额，所以算不出「够不够」：
     * 返回值就是收款方累计应得总额，调用方拿它去跟余额比，超出就拦下。
     * 非法/空数额按 0 处理（不报错不丢行），因为 {@code AddressBookManager.normalizeAmount}
     * 已在入库前归一化过，走到这里只剩存量脏值。</p>
     *
     * @return 本次计划转出的合计（wei）
     */
    public static BigInteger allocateByAmount(List<Row> rows) {
        BigInteger used = BigInteger.ZERO;
        if (rows == null) return used;
        for (Row r : rows) {
            BigInteger part = BigInteger.ZERO;
            try {
                BigDecimal v = new BigDecimal(r.amount == null || r.amount.trim().isEmpty()
                        ? "0" : r.amount.trim());
                if (v.signum() > 0) {
                    part = v.multiply(new BigDecimal(WEI)).toBigInteger();
                }
            } catch (Exception e) {
                part = BigInteger.ZERO;
            }
            r.amountWei = part;
            used = used.add(part);
        }
        return used;
    }

    /** 本次转出全部需支付的网络费（原生币，gasPrice × ERC20 transfer 固定 gasLimit × 笔数） */
    public static BigInteger gasNeed(BigInteger gasPrice, int txCount) {
        if (gasPrice == null) gasPrice = BigInteger.ZERO;
        return gasPrice.multiply(ArenaConfig.GAS_LIMIT_ERC20_TRANSFER)
                .multiply(BigInteger.valueOf(Math.max(txCount, 1)));
    }

    // endregion

    // region 执行与展示

    /**
     * wei 直转小数字符串：截断后去掉末尾的 0（提示文案里不想出现 "1.500000"）。
     *
     * @param scale 先保留的最大小数位
     */
    public static String formatGas(BigInteger wei, int scale) {
        if (wei == null) return "0";
        return new BigDecimal(wei).divide(new BigDecimal(WEI), scale, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }

    /**
     * 执行转账。失败不重试、不中断：逐笔记账，最后一并汇报。
     * 转账前双重校验：fLGNS 余额要够本次合计（比例与数额两种模式都适用），
     * 原生币要够付全部网络费，任一不足就整体不动手，避免转到一半断供。
     */
    public static Result execute(PayerItem payer, List<Row> rows, char[] sessionKey,
                                 LogCallback cb) throws Exception {
        if (payer == null || payer.cipherText == null || payer.cipherText.isEmpty()) {
            throw new Exception("未设置支付方，请先在支付方管理里设置");
        }
        Result result = new Result();
        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
        String pk = mgr.decryptCipherText(payer.cipherText, sessionKey);
        ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        String fromAddr = Web3AndroidUtil.getAddress(keyPair);

        Web3j web3j = Web3AndroidUtil.dialAny();
        try {
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger nonce = web3j.ethGetTransactionCount(fromAddr,
                    DefaultBlockParameterName.PENDING).send().getTransactionCount();
            BigInteger balance = ArenaContractApi.callErc20Balance(web3j, fromAddr);
            BigInteger nativeBalance = ArenaContractApi.callGasTokenBalance(web3j, fromAddr);
            result.payerAddr = fromAddr;
            result.payerBalance = balance;

            BigInteger need = BigInteger.ZERO;
            int payCnt = 0;
            for (Row r : rows) {
                need = need.add(r.amountWei);
                if (r.amountWei.signum() > 0) payCnt++;
            }
            result.totalWei = need;
            BigInteger gasNeed = gasNeed(gasPrice, payCnt);
            if (balance.compareTo(need) < 0) {
                throw new Exception("支付方 fLGNS 不足：收款方累计需要 " + formatGas(need, 6)
                        + " fLGNS，现有 " + formatGas(balance, 6) + " fLGNS");
            }
            if (nativeBalance.compareTo(gasNeed) < 0) {
                throw new Exception("支付方 DAI 不足，付不起这 " + payCnt + " 笔转账的网络费：需要 " + formatGas(gasNeed, 6)
                        + " DAI，现有 " + formatGas(nativeBalance, 6) + " DAI");
            }

            for (Row r : rows) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    if (cb != null) cb.onLog("⚠ 已取消，剩余地址未转账");
                    break;
                }
                // 逐笔串行：同一支付方的 nonce 连续递增；失败不重试，避免同一笔重复上链
                if (r.amountWei.signum() <= 0) continue;
                try {
                    ArenaContractApi.doErc20Transfer(web3j, keyPair, r.address, r.amountWei, nonce, gasPrice);
                    nonce = nonce.add(BigInteger.ONE);
                    result.success++;
                    if (cb != null) {
                        cb.onLog("✅ " + r.code + " 转入 " + formatGas(r.amountWei, 6) + " fLGNS");
                    }
                } catch (Exception e) {
                    result.failed++;
                    if (cb != null) {
                        cb.onLog("❌ " + r.code + " 失败：" + Web3AndroidUtil.safeMessage(e));
                    }
                }
            }
        } finally {
            try { web3j.shutdown(); } catch (Exception ignored) { }
        }
        return result;
    }

    // endregion

}
