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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量归集任务：把「归集支付方」里启用账号的 fLGNS 超出阈值部分，逐个转给唯一的主接收方。
 *
 * <p>与批量转账方向相反（多对一）：转账是一个支付方按比例/数额分给多个接收方，
 * 归集是多个支付方各自把「余额 - 阈值」的全部剩余额转进同一个主接收方。</p>
 *
 * <p>私钥来源：归集支付方只存 key_id 引用，执行时按引用从私钥管理取密文，
 * 用私钥密码（{@code keyPwd}）解密——全程不落任何明文，也不复制密文。</p>
 *
 * <p>安全口径：每个支付方单独重查最新余额再算超出部分（页面展示的余额只是参考），
 * 单笔失败不重试只记账（重试可能重复上链）；DAI 不够付这一笔网络费的账号直接跳过并记失败，
 * 因为这种转账发出去也进不了块。</p>
 */
public final class BatchCollectTask {

    /** 一行归集支付方：私钥引用 + 展示信息 + 页面查询回来的余额快照 */
    public static class Row {
        /** 展示编号（优先自定义编号） */
        public String code = "";
        /** 引用的私钥 id（key_entry._id） */
        public long keyId;
        /** 出资地址（导入时快照） */
        public String address = "";
        /** 页面阶段查到的 fLGNS 余额（wei），仅作展示；执行时以链上最新值为准 */
        public BigInteger balanceWei = BigInteger.ZERO;
        /** 本次计划归集的超出部分（wei），由页面按阈值回填 */
        public BigInteger excessWei = BigInteger.ZERO;
    }

    /** 归集日志回调：文案已拼好，调用方只需追加到界面 */
    public interface LogCallback {
        /** @param message 单行日志，可能来自任意工作线程 */
        void onLog(String message);
    }

    /** 一次归集的结果快照：成功/失败笔数与实际归集合计 */
    public static class Result {
        /** 成功笔数 */
        public int success;
        /** 失败笔数（含私钥缺失、DAI 不足、上链失败；单笔失败不影响后续账号） */
        public int failed;
        /** 实际成功归集的合计（wei） */
        public BigInteger totalWei = BigInteger.ZERO;
    }

    private BatchCollectTask() { }

    /** 阈值（fLGNS 枚数十进制字符串）→ wei；非法或空按 0 处理 */
    public static BigInteger thresholdWei(String thresholdTokens) {
        try {
            BigDecimal v = new BigDecimal(
                    thresholdTokens == null || thresholdTokens.trim().isEmpty() ? "0" : thresholdTokens.trim());
            if (v.signum() < 0) return BigInteger.ZERO;
            return v.multiply(new BigDecimal(BatchTransferTask.WEI)).toBigInteger();
        } catch (Exception e) {
            return BigInteger.ZERO;
        }
    }

    /**
     * 执行归集：逐账号「解密 → 查最新余额 → 转超出部分」串行推进。
     *
     * <p>整个流程共用一条 Web3j 连接；每个支付方都是独立账户，nonce 各自现取，
     * 互不干扰。取消请求（{@code Web3AndroidUtil.isCancelRequested}）在每笔前检查，
     * 已上链的交易不回滚、未发的直接放弃。</p>
     *
     * @param toAddr 主接收方地址（唯一收款方）
     * @param rows   归集支付方行（只处理 excessWei > 0 的行）
     * @param keyPwd 私钥管理密码，用于解密引用到的私钥密文；调用方负责用后清零
     */
    public static Result execute(String toAddr, List<Row> rows, char[] keyPwd,
                                 LogCallback cb) throws Exception {
        if (toAddr == null || toAddr.isEmpty()) throw new Exception("未设置主接收方");
        Result result = new Result();
        if (rows == null || rows.isEmpty()) return result;

        // 一次性加载私钥表建索引：逐条查库会让 N 个账号多 N 次 IO
        KeyStoreManager mgr = AppGlobal.getKeyStoreManager();
        Map<Long, KeyStoreManager.KeyEntry> keyMap = new HashMap<>();
        for (KeyStoreManager.KeyEntry k : mgr.loadKeyEntryList()) {
            keyMap.put(k.id, k);
        }

        Web3j web3j = Web3AndroidUtil.dialAny();
        try {
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            BigInteger oneGas = ArenaConfig.GAS_LIMIT_ERC20_TRANSFER.multiply(gasPrice);

            for (Row r : rows) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    if (cb != null) cb.onLog("⚠ 已取消，剩余账号未归集");
                    break;
                }
                if (r.excessWei == null || r.excessWei.signum() <= 0) continue;

                // 私钥引用必须还能解出来：私钥被删时只记这一条的失败，不影响其它账号
                KeyStoreManager.KeyEntry entry = keyMap.get(r.keyId);
                if (entry == null || entry.cipherText == null || entry.cipherText.isEmpty()) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.code + " 失败：私钥缺失（可能已在私钥管理删除）");
                    continue;
                }
                String pk;
                ECKeyPair keyPair;
                String fromAddr;
                try {
                    pk = mgr.decryptCipherText(entry.cipherText, keyPwd);
                    keyPair = Web3AndroidUtil.pkToKeyPair(pk);
                    fromAddr = Web3AndroidUtil.getAddress(keyPair);
                } catch (Exception e) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.code + " 失败：解密出错 " + Web3AndroidUtil.safeMessage(e));
                    continue;
                }
                // 引用行里存的是导入时的快照地址：与实际解密出的地址对不上就不发，防止串号转错人
                if (r.address != null && !r.address.isEmpty()
                        && !r.address.equalsIgnoreCase(fromAddr)) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.code + " 失败：私钥地址与导入时不一致，已跳过");
                    continue;
                }

                try {
                    // 执行前重查最新余额：页面加载到现在可能又有进账/出账，超出部分以这一刻为准。
                    // 应保留额 = 页面快照余额 - 快照超出部分（即配置的阈值 wei，页面就是按这个口径展示的）
                    BigInteger balance = ArenaContractApi.callErc20Balance(web3j, fromAddr);
                    BigInteger keep = (r.balanceWei == null ? BigInteger.ZERO : r.balanceWei).subtract(r.excessWei);
                    if (keep.signum() < 0) keep = BigInteger.ZERO;
                    BigInteger amount = balance.subtract(keep);   // 最新余额再减一次，得到实际超出部分
                    if (amount.signum() <= 0) {
                        if (cb != null) cb.onLog("⏭ " + r.code + " 最新余额已不超阈值，跳过");
                        continue;
                    }
                    // DAI 只够不够这一笔网络费也要够，否则交易根本进不了块
                    BigInteger dai = ArenaContractApi.callGasTokenBalance(web3j, fromAddr);
                    if (dai.compareTo(oneGas) < 0) {
                        result.failed++;
                        if (cb != null) cb.onLog("❌ " + r.code + " 失败：DAI 不足付不起网络费（需 "
                                + BatchTransferTask.formatGas(oneGas, 6) + " DAI，现有 "
                                + BatchTransferTask.formatGas(dai, 6) + " DAI）");
                        continue;
                    }

                    BigInteger nonce = web3j.ethGetTransactionCount(fromAddr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    ArenaContractApi.doErc20Transfer(web3j, keyPair, toAddr, amount, nonce, gasPrice);
                    result.success++;
                    result.totalWei = result.totalWei.add(amount);
                    if (cb != null) {
                        cb.onLog("✅ " + r.code + " 归集 " + BatchTransferTask.formatGas(amount, 6)
                                + " fLGNS → 主接收方");
                    }
                } catch (Exception e) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.code + " 失败：" + Web3AndroidUtil.safeMessage(e));
                }
            }
        } finally {
            try { web3j.shutdown(); } catch (Exception ignored) { }
        }
        return result;
    }
}
