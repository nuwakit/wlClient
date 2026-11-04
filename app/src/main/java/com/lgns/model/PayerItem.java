package com.lgns.model;

import java.util.Locale;

/**
 * 支付方：批量转账的出资账户。
 *
 * <p>字段与 {@code payer_entry} 表一一对应。只持有密文（{@code cipherText}），
 * 明文私钥仅在执行期间临时解出，用完立刻清零，不落任何持久化介质。</p>
 *
 * <p>与私钥管理的加密密码相互独立：支付方用自己的密码加密，改密码也只重加密这张表。</p>
 */
public class PayerItem {
    /** 主键（自增），主号设置与删除都靠它定位 */
    public long id;
    /** 用户自定义编号；为空时列表回退显示三位数的 id */
    public String customCode = "";
    /** 所属分组名；为空或不存在的组在界面上归入「未分组」 */
    public String groupNo = "";
    /** 私钥密文：只能经 AddressBookManager 用支付方密码解密 */
    public String cipherText = "";
    /** 出资地址（由密文对应的私钥推导），用于展示与核对 */
    public String address = "";
    /** 是否可作为出资方参与转账 */
    public boolean enabled = true;
    /** 展示顺序（升序）：拖拽后落库，运行时以内存列表顺序为准 */
    public int sortOrder;

    /** 默认编号：id 补零到三位，只用于展示，不落库 */
    public String displayNo() {
        return String.format(Locale.getDefault(), "%03d", id);
    }

    /** 展示名：优先用户自定义编号，没有就用三位数 id */
    public String label() {
        return (customCode == null || customCode.isEmpty()) ? displayNo() : customCode;
    }
}
