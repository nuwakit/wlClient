package com.lgns.model;

/**
 * 归集支付方：批量归集的出资账户（多对一里的「多」）。
 *
 * <p>继承 {@link PayerItem} 是为了直接复用支付方列表的适配器与分组/拖拽交互；
 * {@code cipherText} 恒为空串——归集支付方不复制密文，只通过 {@link #keyId}
 * 引用私钥管理里的记录，执行时用私钥密码经 KeyStoreManager 解密。</p>
 */
public class CollectPayerItem extends PayerItem {
    /** 引用的私钥 id（key_entry._id）；私钥被删后执行时按「私钥缺失」逐条报错 */
    public long keyId;

    public CollectPayerItem() {
        // 归集支付方没有自己的密文，保持空串让复用 PayerItem 的判空逻辑一目了然
        cipherText = "";
    }
}
