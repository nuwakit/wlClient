package com.lgns.batch;

import android.content.Context;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.contract.ArenaContractApi;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 统收盟福任务：对选中的启用账号逐个领取江湖福泽（MandateRewards 独立合约）。
 *
 * <p>职责边界：只负责 {@code claim(uint256,uint256[])}，不参与充值/入场/领取/铸造；
 * 链上调用统一走 {@link ArenaContractApi}，本类只做串行调度、分批与失败归因，不涉及 UI。</p>
 *
 * <p>与 {@link BatchMintTask} 同一条纪律：<b>不需要进度落盘续跑</b>——每笔 claim 独立上链，
 * 崩在任何位置都不会留下「半领」状态；重跑时 {@link ArenaContractApi#callFuzeSummary}
 * 会重新按 {@code rewardOf} 的 status 排除已领的盟主令（status=已领 不再进待领清单），天然幂等。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>单批 {@link ArenaConfig#FUZE_CLAIM_BATCH}=8 枚（对齐官网前端 BATCH）；待领超过一批的
 *       账号在本任务内连续拆批领取。</li>
 *   <li>revert 不重试：claim 的 revert 意味着链上状态与本地查询已不一致（比如网页端刚领过），
 *       重发只会再烧一次 gas，记日志后跳过该账号剩余批次。</li>
 *   <li>待领查询失败（RPC 抖动）时不发交易：宁可整号跳过，也不基于旧清单重复提交。</li>
 * </ul>
 */
public class BatchFuzeTask {

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    /**
     * 统收盟福（批量领取福泽）入口。
     *
     * <p>单个账号的处理顺序：</p>
     * <ol>
     *   <li>解密私钥（失败记失败，继续下一个号）；</li>
     *   <li>{@code callFuzeSummary} 查待领福泽（期数 + 待领 tokenId 清单 + 合计）：
     *       查询失败不发交易；待领为空提示后跳过；</li>
     *   <li>按 {@link ArenaConfig#FUZE_CLAIM_BATCH} 拆批，逐批发 {@code claim(period,ids)}，
     *       每笔都等到回执；本地 nonce 逐笔递增，与铸造链路同一套约定。</li>
     * </ol>
     *
     * <p>完整流程里放在「批量铸造」之后是安全的：铸造出的新盟主令要等下一期开奖才产生福泽，
     * 本步骤只领当前期已待领的部分。</p>
     */
    public static void batchFuzeAllResume(Context ctx, TaskLogCallback callback,
                                          List<KeyListItem> selectedList,
                                          char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "FUZE", "⚠没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "FUZE", "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        int claimedAccountCount = 0, noneCount = 0, failCount = 0, totalClaimed = 0;
        BigInteger totalValueWei = BigInteger.ZERO;
        List<String> failNoList = new ArrayList<>();
        int total = selectedList.size();

        Web3j web3j = Web3AndroidUtil.dialAny();
        callback.onLog("", "FUZE", "开始统收盟福 · 单批上限 " + ArenaConfig.FUZE_CLAIM_BATCH
                + " 枚 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                + " · chainId=" + ArenaConfig.CHAIN_ID);
        try {
            for (int i = 0; i < selectedList.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "FUZE", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                        ? item.customCode : item.displayNo;
                callback.onLog(keyNo, "FUZE", "处理账号 " + (i + 1) + "/" + total);

                String plainPk;
                String fromAddr;
                ECKeyPair pair;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    pair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(pair).toLowerCase();
                    plainPk = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "FUZE", "✖ 解密失败:" + msgOf(ex));
                    failCount++;
                    failNoList.add(keyNo);
                    continue;
                }

                try {
                    // ① 待领清单：rewardOf status=待领 的 tokenId 及所在期数。
                    //    查询失败返回 null —— 宁可不发，也不能按猜测的清单重复提交
                    ArenaContractApi.FuzeSummary fs = ArenaContractApi.callFuzeSummary(web3j, fromAddr);
                    if (fs == null) {
                        callback.onLog(keyNo, "FUZE", "❌ [" + shortAddr(fromAddr)
                                + "...] 福泽查询失败，未发交易，请稍后重试");
                        failCount++;
                        failNoList.add(keyNo);
                        continue;
                    }
                    if (fs.pendingIds.isEmpty()) {
                        callback.onLog(keyNo, "FUZE", "ℹ [" + shortAddr(fromAddr)
                                + "...] 无待领福泽，跳过");
                        noneCount++;
                        continue;
                    }
                    callback.onLog(keyNo, "FUZE", "ℹ [" + shortAddr(fromAddr)
                            + "...] 第 " + fs.period + " 期待领 " + fs.pendingCount + " 枚 · "
                            + weiToFlat(fs.pendingValue) + " fLGNS");

                    // ② 逐批领取：一笔最多 8 枚，多出的拆下一笔；nonce 本地递增，
                    //    与铸造链路同一套约定（同一账号串行，不会自己撞 nonce）
                    BigInteger nonce = web3j.ethGetTransactionCount(fromAddr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                    int claimed = 0;
                    String lastTxHash = null;
                    boolean aborted = false;
                    for (int off = 0; off < fs.pendingIds.size(); off += ArenaConfig.FUZE_CLAIM_BATCH) {
                        if (Web3AndroidUtil.isCancelRequested()) {
                            callback.onLog(keyNo, "FUZE", "🛑 该账号已停在中途：剩余批次未发");
                            aborted = true;
                            break;
                        }
                        List<BigInteger> slice = new ArrayList<>(fs.pendingIds.subList(off,
                                Math.min(off + ArenaConfig.FUZE_CLAIM_BATCH, fs.pendingIds.size())));
                        try {
                            TransactionReceipt rcpt = ArenaContractApi.doFuzeClaim(
                                    web3j, pair, fs.period, slice, nonce, gasPrice, null);
                            claimed += slice.size();
                            lastTxHash = rcpt.getTransactionHash();
                            nonce = nonce.add(BigInteger.ONE);
                        } catch (ArenaContractApi.RevertedTxException rv) {
                            // revert 不重试：状态与查询已不一致（多为网页端刚领过），
                            // 剩余批次同样可能重领，整号中止，重跑时清单会自己刷新
                            callback.onLog(keyNo, "FUZE", "✖ [" + shortAddr(fromAddr)
                                    + "...] 领取福泽回滚(revert)，本号剩余批次不再提交：" + msgOf(rv));
                            aborted = true;
                            break;
                        } catch (IOException io) {
                            // 网络异常无法确认交易是否已上链：按惯例中止本号，
                            // 重跑时 rewardOf 会以链上实际状态排除已领的枚
                            callback.onLog(keyNo, "FUZE", "⚠ [" + shortAddr(fromAddr)
                                    + "...] 广播网络异常：" + msgOf(io)
                                    + "，本号剩余批次不再提交，重跑将自动续领");
                            aborted = true;
                            break;
                        }
                    }
                    if (claimed > 0) {
                        claimedAccountCount++;
                        totalClaimed += claimed;
                        // 合计金额按查询口径计；未领完的枚会在下次 rewardOf 里重新出现
                        if (!aborted) totalValueWei = totalValueWei.add(fs.pendingValue);
                        callback.onLog(keyNo, "FUZE", "✅ [" + shortAddr(fromAddr)
                                + "...] 福泽领取成功 " + claimed + "/" + fs.pendingCount + " 枚"
                                + (lastTxHash != null ? "，txHash=" + lastTxHash : "")
                                + (aborted ? "（剩余批次未领，可重跑续领）" : ""));
                    }
                    if (aborted || claimed == 0) {
                        // 中途 revert/取消：本号未竟全功，记入待办编号便于重跑
                        failCount++;
                        failNoList.add(keyNo);
                    }
                } catch (Exception e) {
                    callback.onLog(keyNo, "FUZE", "✖ 账号领取福泽整体失败:" + msgOf(e));
                    failCount++;
                    failNoList.add(keyNo);
                }
            }

            String summary = "统收盟福结束 · 领成账号=" + claimedAccountCount
                    + "（共 " + totalClaimed + " 枚 · " + weiToFlat(totalValueWei) + " fLGNS）"
                    + " 无待领=" + noneCount
                    + " 失败/未竟=" + failCount
                    + (failNoList.isEmpty() ? "" : " · 编号:" + String.join(",", failNoList));
            callback.onLog("", "FUZE", summary);
        } finally {
            try { web3j.shutdown(); } catch (Exception ignore) {}
        }
    }

    /** 日志里的短地址：统一前 10 个字符，与批量铸造的打印口径一致 */
    private static String shortAddr(String addr) {
        return addr.length() <= 10 ? addr : addr.substring(0, 10);
    }

    /** wei → fLGNS 展示串：4 位向下截断去尾零，与 BatchClaimTask 结果行同一口径 */
    private static String weiToFlat(BigInteger wei) {
        return new java.math.BigDecimal(wei)
                .divide(new java.math.BigDecimal(BigInteger.TEN.pow(18)), 4, java.math.RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString();
    }
}
