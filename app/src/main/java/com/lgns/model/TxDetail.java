package com.lgns.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 一笔交易的基础信息，来自 Blockscout {@code /api/v2/transactions/{hash}}。
 *
 * <p>列表接口的 token-transfers 只带转账事件本身，不含 gas；详情页的「网络费」「确认数」
 * 需要按 txHash 再查一次本对象。所有字段失败时留空/0，由页面按「查不到」展示占位，不伪造 0。</p>
 */
public class TxDetail {
    /** 交易哈希 */
    public String hash = "";
    /** 状态：ok / error，缺失为空 */
    public String status = "";
    /** 网络费 wei 原值（18 位小数），null = 没查到 */
    public String feeWei;
    /** 所在区块号 */
    public long blockNumber;
    /** 交易时间（epoch 毫秒） */
    public long timestampMs;
    /** 确认数 */
    public long confirmations;

    /** 网络费按 18 位小数转可读字符串：最多保留 10 位、去掉尾随 0 */
    public String formattedFee() {
        if (feeWei == null || feeWei.isEmpty()) return null;
        try {
            BigDecimal bd = new BigDecimal(feeWei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 10, RoundingMode.DOWN)
                    .stripTrailingZeros();
            String s = bd.toPlainString();
            if (s.contains("E") || s.contains("e")) s = bd.setScale(0, RoundingMode.DOWN).toPlainString();
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return feeWei;
        }
    }
}
