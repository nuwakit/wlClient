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
 * 批量铸造任务：对选中的启用账号逐个铸造盟主令（ChampionMandate 独立 ERC721 合约）。
 *
 * <p>职责边界：只负责 {@code mint(uint256[])}，不参与充值/入场/领取；链上调用统一走
 * {@link ArenaContractApi}，本类只做串行调度、分批与失败归因，不涉及 UI。</p>
 *
 * <p>与批量领取不同，本任务<b>不需要进度落盘续跑</b>：每笔 mint 都独立上链，
 * 崩在任何位置都不会留下「半铸」状态——重跑时 {@link ArenaContractApi#callMintableIndices}
 * 会先按 {@code used(index)} 排除已铸的俸禄，天然幂等。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>单批 {@link ArenaConfig#MANDATE_MINT_BATCH}=24 枚（对齐官网前端；合约硬上限 64，
 *       64 枚一笔约 12.25M gas 会超区块/超估算被拒）；可铸超过一批的账号在本任务内连续拆批铸造。</li>
 *   <li>revert 不重试：mint 的 revert 意味着链上状态与本地查询已不一致（比如网页端刚铸过），
 *       重发只会再烧一次 gas，记日志后跳过该账号剩余批次。</li>
 *   <li>可铸查询失败（RPC 抖动）时不发交易：宁可整号跳过，也不基于「以为没铸过」的旧数据铸重。</li>
 * </ul>
 */
public class BatchMintTask {

    private static String msgOf(Throwable e) { return Web3AndroidUtil.safeMessage(e); }

    /**
     * 批量铸造入口。
     *
     * <p>单个账号的处理顺序：</p>
     * <ol>
     *   <li>解密私钥（失败记失败，继续下一个号）；</li>
     *   <li>{@code callMintableIndices} 查可铸俸禄 index：查询失败不发交易；空清单提示后跳过；</li>
     *   <li>按 {@link ArenaConfig#MANDATE_MINT_BATCH} 拆批，逐批发 {@code mint(uint256[])}，
     *       每笔都等到回执；本地 nonce 逐笔递增，中途异常时回链上重取一次再继续判断。</li>
     * </ol>
     *
     * <p>铸造前置：俸禄必须已领讫且满 {@link ArenaConfig#JIAZI_DAYS} 天才进可铸清单，
     * 所以「刚领完俸禄」不会立刻多出一枚可铸，完整流程里领取后紧跟本步骤是安全的。</p>
     */
    public static void batchMintAllResume(Context ctx, TaskLogCallback callback,
                                          List<KeyListItem> selectedList,
                                          char[] sessionCryptoKey) throws Exception {
        if (selectedList.isEmpty()) {
            callback.onLog("", "MINT", "⚠没有选中任何账号");
            return;
        }
        KeyStoreManager ksMgr;
        try {
            ksMgr = AppGlobal.getKeyStoreManager();
        } catch (IllegalStateException e) {
            callback.onLog("", "MINT", "❌密钥管理器未初始化,请等待初始化完成");
            return;
        }
        Web3AndroidUtil.resetCancel();
        AppGlobal.touch();

        int mintedAccountCount = 0, noneCount = 0, failCount = 0, totalMinted = 0;
        List<String> failNoList = new ArrayList<>();
        int total = selectedList.size();

        Web3j web3j = Web3AndroidUtil.dialAny();
        callback.onLog("", "MINT", "开始批量铸造 · 单批上限 " + ArenaConfig.MANDATE_MINT_BATCH
                + " 枚 · 节点=" + Web3AndroidUtil.currentRpcUrl()
                + " · chainId=" + ArenaConfig.CHAIN_ID);
        try {
            for (int i = 0; i < selectedList.size(); i++) {
                if (Web3AndroidUtil.isCancelRequested()) {
                    callback.onLog("", "MINT", "🛑 用户取消,已处理 " + i + "/" + total);
                    break;
                }
                AppGlobal.touch();
                KeyListItem item = selectedList.get(i);
                String keyNo = (item.customCode != null && !item.customCode.isEmpty())
                        ? item.customCode : item.displayNo;
                callback.onLog(keyNo, "MINT", "处理账号 " + (i + 1) + "/" + total);

                String plainPk;
                String fromAddr;
                ECKeyPair pair;
                try {
                    plainPk = ksMgr.decryptCipherText(item.cipherText, sessionCryptoKey);
                    pair = Web3AndroidUtil.pkToKeyPair(plainPk);
                    fromAddr = Web3AndroidUtil.getAddress(pair).toLowerCase();
                    plainPk = null;
                } catch (Exception ex) {
                    callback.onLog(keyNo, "MINT", "✖ 解密失败:" + msgOf(ex));
                    failCount++;
                    failNoList.add(keyNo);
                    continue;
                }

                try {
                    // ① 可铸清单：领讫满 60 天且 used()==false 的俸禄 index。
                    //    查询失败返回 null —— 宁可不发，也不能按猜测的清单铸重
                    List<BigInteger> indices = ArenaContractApi.callMintableIndices(web3j, fromAddr);
                    if (indices == null) {
                        callback.onLog(keyNo, "MINT", "❌ [" + shortAddr(fromAddr)
                                + "...] 可铸俸禄查询失败，未发交易，请稍后重试");
                        failCount++;
                        failNoList.add(keyNo);
                        continue;
                    }
                    if (indices.isEmpty()) {
                        callback.onLog(keyNo, "MINT", "ℹ [" + shortAddr(fromAddr)
                                + "...] 无可铸盟主令（领讫俸禄已全部铸造），跳过");
                        noneCount++;
                        continue;
                    }

                    // ② 逐批铸造：一笔最多 24 枚，多出的拆下一笔；nonce 本地递增，
                    //    与领取链路同一套约定（同一账号串行，不会自己撞 nonce）
                    BigInteger nonce = web3j.ethGetTransactionCount(fromAddr,
                            DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                    int minted = 0;
                    String lastTxHash = null;
                    boolean aborted = false;
                    for (int off = 0; off < indices.size(); off += ArenaConfig.MANDATE_MINT_BATCH) {
                        if (Web3AndroidUtil.isCancelRequested()) {
                            callback.onLog(keyNo, "MINT", "🛑 该账号已停在中途：剩余批次未发");
                            aborted = true;
                            break;
                        }
                        List<BigInteger> slice =
                                new ArrayList<>(indices.subList(off, Math.min(off + ArenaConfig.MANDATE_MINT_BATCH, indices.size())));
                        try {
                            TransactionReceipt rcpt = ArenaContractApi.doMandateMint(
                                    web3j, pair, slice, nonce, gasPrice, null);
                            minted += slice.size();
                            lastTxHash = rcpt.getTransactionHash();
                            nonce = nonce.add(BigInteger.ONE);
                        } catch (ArenaContractApi.RevertedTxException rv) {
                            // revert 不重试：状态与查询已不一致（多为网页端刚铸过），
                            // 剩余批次同样可能铸重，整号中止，重跑时清单会自己刷新
                            callback.onLog(keyNo, "MINT", "✖ [" + shortAddr(fromAddr)
                                    + "...] 铸造交易回滚(revert)，本号剩余批次不再提交：" + msgOf(rv));
                            aborted = true;
                            break;
                        } catch (IOException io) {
                            // 网络异常无法确认交易是否已上链：按领取链路惯例，回链上重取
                            // nonce 后中止本号（mint 非幂等场景下盲目重发就是铸重）
                            callback.onLog(keyNo, "MINT", "⚠ [" + shortAddr(fromAddr)
                                    + "...] 广播网络异常：" + msgOf(io)
                                    + "，本号剩余批次不再提交，重跑将自动续铸");
                            aborted = true;
                            break;
                        }
                    }
                    if (minted > 0) {
                        mintedAccountCount++;
                        totalMinted += minted;
                        callback.onLog(keyNo, "MINT", "✅ [" + shortAddr(fromAddr)
                                + "...] 盟主令铸造成功 " + minted + "/" + indices.size() + " 枚"
                                + (lastTxHash != null ? "，txHash=" + lastTxHash : "")
                                + (aborted ? "（剩余批次未铸，可重跑续铸）" : ""));
                    }
                    if (aborted || minted == 0) {
                        // 中途 revert/取消：本号未竟全功，记入待办编号便于重跑
                        failCount++;
                        failNoList.add(keyNo);
                    }
                } catch (Exception e) {
                    callback.onLog(keyNo, "MINT", "✖ 账号铸造整体失败:" + msgOf(e));
                    failCount++;
                    failNoList.add(keyNo);
                }
            }

            String summary = "批量铸造结束 · 铸成账号=" + mintedAccountCount
                    + "（共 " + totalMinted + " 枚）"
                    + " 无可铸=" + noneCount
                    + " 失败/未竟=" + failCount
                    + (failNoList.isEmpty() ? "" : " · 编号:" + String.join(",", failNoList));
            callback.onLog("", "MINT", summary);
        } finally {
            try { web3j.shutdown(); } catch (Exception ignore) {}
        }
    }

    /** 日志里的短地址：统一前 10 个字符，与批量领取的打印口径一致 */
    private static String shortAddr(String addr) {
        return addr.length() <= 10 ? addr : addr.substring(0, 10);
    }
}
