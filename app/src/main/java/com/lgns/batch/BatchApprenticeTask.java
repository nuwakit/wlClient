package com.lgns.batch;

import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量拜师任务：把「门徒管理」里启用账号逐个发 {@code bind(master)} 交易拜入主师傅门下。
 *
 * <p>与批量归集同构（多对一），但交易打到师徒登记簿（FunLineageRegistry）而不是代币合约，
 * 且由门徒自己的私钥签名——每个门徒都是一笔独立交易，nonce 各自现取。</p>
 *
 * <p><b>一次性绑定是本合同最大的坑</b>：bind 成功后改绑必然 revert。所以每笔执行前
 * 必须现查一次 {@code masterOf}（页面快照只能参考，不能当依据）：
 * 已拜目标师傅 → 跳过（不算失败）；已拜他人 → 记失败（发了也必 revert，白烧 gas）；
 * 未拜师 → 才真正发 bind。查询失败同样记失败且不猜测状态——把「查不到」当「未拜师」
 * 发出去，可能撞上一次注定 revert 的浪费交易。</p>
 *
 * <p>私钥来源：门徒只存 key_id 引用，执行时按引用从私钥管理取密文，
 * 用私钥密码（{@code keyPwd}）解密——全程不落任何明文，也不复制密文。</p>
 */
public final class BatchApprenticeTask {

    /** 一行门徒：私钥引用 + 展示信息 + 页面查询回来的拜师状态快照 */
    public static class Row {
        /** 展示编号（优先自定义编号） */
        public String code = "";
        /** 引用的私钥 id（key_entry._id） */
        public long keyId;
        /** 门徒地址（导入时快照） */
        public String address = "";
        /** 页面阶段查到的当前师傅地址（""=未拜师，null=没查到）；执行时以链上最新值为准 */
        public String masterOfAddr;
    }

    /** 拜师日志回调：文案已拼好，调用方只需追加到界面 */
    public interface LogCallback {
        /** @param message 单行日志，可能来自任意工作线程 */
        void onLog(String message);
    }

    /** 一次批量拜师的结果快照：成功/跳过/失败笔数 */
    public static class Result {
        /** 成功笔数（本批实际发出并成功的 bind） */
        public int success;
        /** 跳过笔数（已拜入目标师傅门下，属正常状态，不算失败） */
        public int skipped;
        /** 失败笔数（含私钥缺失、已拜他人、DAI 不足、查询失败、上链失败；单笔失败不影响后续账号） */
        public int failed;
    }

    private BatchApprenticeTask() { }

    /**
     * 执行批量拜师：逐账号「解密 → 现查 masterOf → 未拜师才发 bind」串行推进。
     *
     * <p>取消请求（{@code Web3AndroidUtil.isCancelRequested}）在每笔前检查，
     * 已上链的交易不回滚、未发的直接放弃。</p>
     *
     * @param toMasterAddr 主师傅地址（拜师目标）
     * @param rows         门徒行（全部启用行都要走一遍现查，不能按页面快照预先剔掉）
     * @param keyPwd       私钥管理密码，用于解密引用到的私钥密文；调用方负责用后清零
     */
    public static Result execute(String toMasterAddr, List<Row> rows, char[] keyPwd,
                                 LogCallback cb) throws Exception {
        if (toMasterAddr == null || toMasterAddr.isEmpty()) throw new Exception("未设置主师傅");
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
            BigInteger oneGas = ArenaConfig.GAS_LIMIT_LINEAGE_BIND.multiply(gasPrice);

            for (Row r : rows) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    if (cb != null) cb.onLog("⚠ 已取消，剩余门徒未拜师");
                    break;
                }

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
                // 引用行里存的是导入时的快照地址：与实际解密出的地址对不上就不发，防止串号拜错人
                if (r.address != null && !r.address.isEmpty()
                        && !r.address.equalsIgnoreCase(fromAddr)) {
                    result.failed++;
                    if (cb != null) cb.onLog("❌ " + r.code + " 失败：私钥地址与导入时不一致，已跳过");
                    continue;
                }

                try {
                    // 现查最新拜师状态：一次性绑定，状态决定这一笔是该跳过、该失败还是该发
                    String master = ArenaContractApi.callMasterOf(web3j, fromAddr);
                    if (master.equalsIgnoreCase(toMasterAddr)) {
                        result.skipped++;
                        if (cb != null) cb.onLog("⏭ " + r.code + " 已拜入本师门下，跳过");
                        continue;
                    }
                    if (!master.isEmpty()) {
                        result.failed++;
                        if (cb != null) cb.onLog("✗ " + r.code + " 已拜他人为师（一次性绑定不可更改）");
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
                    ArenaContractApi.doLineageBind(web3j, keyPair, toMasterAddr, nonce, gasPrice);
                    result.success++;
                    if (cb != null) cb.onLog("✅ " + r.code + " 拜师成功 → 主师傅");
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
