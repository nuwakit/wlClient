package com.lgns.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 一笔链上代币转账（ERC-20 Transfer 事件），来自 Blockscout {@code /api/v2/addresses/{addr}/token-transfers}。
 *
 * <p>只承载展示所需字段，金额以 wei 原值字符串保存，格式化推迟到上屏时按代币小数位做，
 * 避免中途用 double 丢精度。方向（收/付）由列表页拿本行地址与 {@link #from} 比对得出，
 * 这里不预存，因为同一条记录在「付款方视角」和「收款方视角」方向相反。</p>
 */
public class TokenTransferItem {
    /** 发款方地址（0x…） */
    public String from;
    /** 收款方地址（0x…） */
    public String to;
    /** 代币符号，如 gasDAI / fLGNS */
    public String tokenSymbol = "";
    /** 代币合约地址 */
    public String tokenAddress = "";
    /** 代币小数位（字符串转 int，缺省 18） */
    public int tokenDecimals = 18;
    /** 转账金额 wei 原值（十进制字符串） */
    public String amountWei = "0";
    /** 所在区块号 */
    public long blockNumber;
    /** 交易时间（epoch 毫秒），解析失败为 0 */
    public long timestampMs;
    /** 交易哈希，详情页按它再查网络费 */
    public String txHash = "";

    /**
     * 命中的接收方自编号（接收方管理里的 label）；null = 该笔对手方不在接收方名单。
     * 由列表页回填，用于「优先展示 + 特殊标色 + 带编号」。
     */
    public String receiverCode;
    /** 接收方在「接收方管理」列表中的序号（越小越靠前）；未命中为 {@link Integer#MAX_VALUE} */
    public int receiverRank = Integer.MAX_VALUE;

    /** 是否与接收方名单关联 */
    public boolean associated() {
        return receiverCode != null;
    }

    /** 金额按代币小数位转可读字符串：保留到该代币本身的精度（如 fLGNS 18 位），再去尾随 0 */
    public String formattedAmount() {
        try {
            BigDecimal bd = new BigDecimal(amountWei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(tokenDecimals)), tokenDecimals, RoundingMode.DOWN);
            bd = bd.stripTrailingZeros();
            String s = bd.toPlainString();
            // stripTrailingZeros 会把 100 变成 1E+2，回退成普通写法
            if (s.contains("E") || s.contains("e")) s = bd.setScale(0, RoundingMode.DOWN).toPlainString();
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return amountWei;
        }
    }
}
