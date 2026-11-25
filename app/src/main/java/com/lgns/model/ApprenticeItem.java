package com.lgns.model;

/**
 * 门徒：批量拜师的对象（多个要拜出去的号）。
 *
 * <p>继承 {@link PayerItem} 是为了直接复用归集支付方列表的适配器与分组/拖拽交互；
 * {@code cipherText} 恒为空串——门徒不复制密文，只通过 {@link #keyId}
 * 引用私钥管理里的记录，执行拜师时用私钥密码经 KeyStoreManager 解密
 * （bind 交易必须由门徒自己的私钥签名）。</p>
 */
public class ApprenticeItem extends PayerItem {
    /** 引用的私钥 id（key_entry._id）；私钥被删后执行时按「私钥缺失」逐条报错 */
    public long keyId;

    public ApprenticeItem() {
        // 门徒没有自己的密文，保持空串让复用 PayerItem 的判空逻辑一目了然
        cipherText = "";
    }
}
