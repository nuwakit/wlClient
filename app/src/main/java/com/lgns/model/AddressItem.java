package com.lgns.model;

import java.util.Locale;

/**
 * 地址簿一行：地址 + 收益比例 + 收益数额 + 启用状态。
 *
 * <p>字段与 {@code address_entry} 表一一对应，由 {@code AddressBookManager} 读写；
 * 比例合计不超过 100% 的约束也由它统一校验，模型本身不做校验。</p>
 *
 * <p>比例与数额是两套互斥的分配口径（系统配置页的「批量转账」选一个）：
 * 按比例分支付方全部余额，按数额则每号固定拿这么多 fLGNS。两边可以同时填，
 * 不会因为切了模式就把另一边的值清掉。</p>
 */
public class AddressItem {
    /** 主键（自增），拖拽排序与删除都靠它定位 */
    public long id;
    /** 用户自定义编号；为空时列表回退显示三位数的 id */
    public String customCode = "";
    /** 所属分组名；为空或不存在的组在界面上归入「未分组」 */
    public String groupNo = "";
    /** 收款地址（0x 开头），批量转账的目标 */
    public String address = "";
    /** 收益比例，以字符串形式存储以避免浮点误差；与支付方比例共同决定分配 */
    public String ratio = "0.00";
    /** 收益数额（fLGNS 枚数），同样是字符串；按数额转账时它就是本号应得的全部金额 */
    public String amount = "0.00";
    /** 是否参与本轮分配：关闭后不落任何钱 */
    public boolean enabled = true;
    /** 展示顺序（升序）：只在保存拖拽结果时使用，运行时以内存列表顺序为准 */
    public int sortOrder;

    /** 默认编号：id 补零到三位，只用于展示，不落库 */
    public String displayNo() {
        return String.format(Locale.getDefault(), "%03d", id);
    }

    /** 展示名：优先用户自定义编号，没有就用三位数 id（转账日志也按这个口径显示） */
    public String label() {
        return (customCode == null || customCode.isEmpty()) ? displayNo() : customCode;
    }
}
