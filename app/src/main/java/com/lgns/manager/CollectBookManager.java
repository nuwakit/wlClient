package com.lgns.manager;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.lgns.model.CollectPayerItem;
import com.lgns.model.PayerItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 归集管理数据层：collect_payer_entry（归集支付方）与 collect_address_entry（归集接收方）的唯一入口。
 *
 * <p>归集是转账的反向操作：多个支付方把「超出阈值的 fLGNS 余额」转给唯一的主接收方。
 * 因此这里没有比例/数额概念，只有两张简单的名单表 + 一个阈值配置。</p>
 *
 * <p>与 {@link AddressBookManager} 的两点关键差异：</p>
 * <ul>
 *   <li>归集支付方<b>不存密文</b>，只存 key_entry._id 引用（{@code key_id}）：
 *       私钥密文与密码由私钥管理独占一份，避免出现同一把私钥两套密码解不开的窘境；
 *       导入即快照编号/分组/地址，私钥后续改名不影响本页展示，只会体现在重新导入时；</li>
 *   <li>「主号」语义在接收方一侧（主接收方 = 唯一收款方），支付方一侧没有主号。</li>
 * </ul>
 *
 * <p>阈值（fLGNS 枚数）存 {@link AppPrefs}：它是全局配置而不是行数据，
 * 与批量转账方式等设置同库同风格。</p>
 */
public class CollectBookManager {

    // region 常量

    /** 默认归集阈值（fLGNS）：支付方余额超过该值的部分才归集 */
    public static final String DEFAULT_THRESHOLD = "100";
    /** 归集支付方表名 */
    private static final String T_PAYER = AppLogDbHelper.TABLE_COLLECT_PAYER;
    /** 归集接收方表名 */
    private static final String T_ADDRESS = AppLogDbHelper.TABLE_COLLECT_ADDRESS;
    /** SP 键：归集阈值（枚数十进制字符串） */
    private static final String KEY_THRESHOLD = "collect_threshold";
    /** 元数据键：当前主接收方 id */
    private static final String META_RECEIVER_MASTER = "collect_receiver_master_id";

    /** 应用上下文（只用于拿 SP，不持有 Activity） */
    private final Context ctx;
    /** 数据访问层：两张表的读写都走它 */
    private final AppLogDbHelper db;

    // endregion

    // region 构造

    /** 构造：只持有 ApplicationContext + 单例 DB 句柄，传 Activity 会泄漏 */
    public CollectBookManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = AppLogDbHelper.getInstance(ctx);
    }

    /** 数据库 NULL 统一转成空串，避免上层到处判 null */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // endregion

    // region 归集支付方

    /** 读全部归集支付方，按 sort_order 升序 */
    public List<CollectPayerItem> loadPayerList() {
        List<CollectPayerItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,key_id,custom_code,group_no,address,enabled,sort_order FROM " + T_PAYER
                        + " ORDER BY sort_order ASC, _id ASC", null);
        try {
            while (c.moveToNext()) {
                list.add(readPayer(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 游标 → 模型（列顺序需与 SELECT 一致） */
    private CollectPayerItem readPayer(Cursor c) {
        CollectPayerItem it = new CollectPayerItem();
        it.id = c.getLong(0);
        it.keyId = c.getLong(1);
        it.customCode = nz(c.getString(2));
        it.groupNo = nz(c.getString(3));
        it.address = nz(c.getString(4));
        it.enabled = c.getInt(5) == 1;
        it.sortOrder = c.getInt(6);
        return it;
    }

    /** 该私钥是否已导入过归集支付方 */
    public boolean collectPayerKeyExists(long keyId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_PAYER + " WHERE key_id=?",
                new String[]{String.valueOf(keyId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 下一个归集支付方排序值 = 当前最大值 + 1 */
    private int nextPayerSortOrder() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT IFNULL(MAX(sort_order),-1)+1 FROM " + T_PAYER, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 从私钥管理导入归集支付方：按 key_id 去重，已导入的私钥自动跳过。
     *
     * <p>只快照编号/分组/地址三个展示字段，不复制密文——执行归集时再按 key_id
     * 现取私钥记录解密，保证密码始终只有私钥管理那一份。</p>
     *
     * @return 实际导入条数（0 表示全部已导入或传空）
     */
    public int importFromKeys(List<KeyStoreManager.KeyEntry> keys) {
        if (keys == null || keys.isEmpty()) return 0;
        SQLiteDatabase w = db.getWritableDatabase();
        int imported = 0;
        w.beginTransaction();
        try {
            for (KeyStoreManager.KeyEntry k : keys) {
                if (k == null || k.id <= 0) continue;
                if (collectPayerKeyExists(k.id)) continue;   // 同一把私钥只进一次
                ContentValues v = new ContentValues();
                v.put("key_id", k.id);
                v.put("custom_code", nz(k.customCode).trim());
                v.put("group_no", nz(k.groupNo));
                v.put("address", k.address == null ? "" : k.address.toLowerCase(Locale.ROOT));
                v.put("enabled", 1);
                v.put("sort_order", nextPayerSortOrder());
                v.put("created_at", System.currentTimeMillis());
                if (w.insert(T_PAYER, null, v) > 0) imported++;
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        return imported;
    }

    /** 只改编号与分组，不动引用与启用状态 */
    public void updatePayerMeta(long id, String code, String groupNo) {
        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        db.getWritableDatabase().update(T_PAYER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 只改启用状态：批量归集只会用到启用行 */
    public void setPayerEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_PAYER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 删归集支付方：只断引用，绝不动私钥管理里的原记录 */
    public void deletePayer(long id) {
        db.getWritableDatabase().delete(T_PAYER, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 拖拽后整体写回排序：事务内按下标逐条 update */
    public void savePayerSortOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort_order", i);
                w.update(T_PAYER, v, "_id=?", new String[]{String.valueOf(ids.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    // endregion

    // region 归集接收方

    /** 读全部归集接收方，按 sort_order 升序；模型复用 {@link PayerItem}（cipherText 恒空） */
    public List<PayerItem> loadAddressList() {
        List<PayerItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,enabled,sort_order FROM " + T_ADDRESS
                        + " ORDER BY sort_order ASC, _id ASC", null);
        try {
            while (c.moveToNext()) {
                list.add(readAddress(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 游标 → 模型（列顺序需与 SELECT 一致） */
    private PayerItem readAddress(Cursor c) {
        PayerItem it = new PayerItem();
        it.id = c.getLong(0);
        it.customCode = nz(c.getString(1));
        it.groupNo = nz(c.getString(2));
        it.cipherText = "";   // 归集接收方只收钱，永远没有密文
        it.address = nz(c.getString(3));
        it.enabled = c.getInt(4) == 1;
        it.sortOrder = c.getInt(5);
        return it;
    }

    /** 按 id 查归集接收方；不存在返回 null */
    public PayerItem findAddress(long id) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,enabled,sort_order FROM " + T_ADDRESS
                        + " WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readAddress(c) : null;
        } finally {
            c.close();
        }
    }

    /** 接收方地址是否重复（忽略大小写，excludeId 用于编辑时排除自身） */
    public boolean collectAddressExists(String address, long excludeId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_ADDRESS + " WHERE LOWER(address)=? AND _id<>?",
                new String[]{nz(address).toLowerCase(Locale.ROOT), String.valueOf(excludeId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 下一个归集接收方排序值 = 当前最大值 + 1 */
    private int nextAddressSortOrder() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT IFNULL(MAX(sort_order),-1)+1 FROM " + T_ADDRESS, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 新增归集接收方：地址严格校验 + 全局去重；第一条自动成为主接收方 */
    public PayerItem addAddress(String code, String groupNo, String rawAddress) throws Exception {
        String addr = nz(rawAddress).trim();
        if (!AddressBookManager.isValidAddress(addr)) throw new Exception("地址格式错误：需 0x 开头的 40 位十六进制");
        addr = addr.toLowerCase(Locale.ROOT);
        if (collectAddressExists(addr, -1)) throw new Exception("该地址已存在接收方列表");

        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        v.put("enabled", 1);
        v.put("sort_order", nextAddressSortOrder());
        v.put("created", System.currentTimeMillis());
        long id = db.getWritableDatabase().insert(T_ADDRESS, null, v);
        if (id <= 0) throw new Exception("保存失败，请重试");

        if (getReceiverMasterId() < 0) setReceiverMasterId(id);

        PayerItem item = new PayerItem();
        item.id = id;
        item.customCode = nz(code).trim();
        item.groupNo = nz(groupNo);
        item.cipherText = "";
        item.address = addr;
        item.enabled = true;
        return item;
    }

    /** 编辑接收方：改编号/分组/地址（地址仍查重），不动启用状态 */
    public void updateAddress(long id, String code, String groupNo, String rawAddress) throws Exception {
        String addr = nz(rawAddress).trim();
        if (!AddressBookManager.isValidAddress(addr)) throw new Exception("地址格式错误：需 0x 开头的 40 位十六进制");
        addr = addr.toLowerCase(Locale.ROOT);
        if (collectAddressExists(addr, id)) throw new Exception("该地址已存在接收方列表");
        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        db.getWritableDatabase().update(T_ADDRESS, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 只改启用状态 */
    public void setAddressEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_ADDRESS, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 删接收方；若删的正好是主接收方，同时把主号重置为 -1（不自动顺移给下一个） */
    public void deleteAddress(long id) {
        db.getWritableDatabase().delete(T_ADDRESS, "_id=?", new String[]{String.valueOf(id)});
        if (getReceiverMasterId() == id) setReceiverMasterId(-1);
    }

    /** 拖拽后整体写回排序 */
    public void saveAddressSortOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort_order", i);
                w.update(T_ADDRESS, v, "_id=?", new String[]{String.valueOf(ids.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** 取主接收方 id；解析失败或未设置返回 -1 */
    public long getReceiverMasterId() {
        String raw = db.getMetaValue(META_RECEIVER_MASTER, "-1");
        try {
            return Long.parseLong(raw == null ? "-1" : raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 指定主接收方 id（传 -1 取消） */
    public void setReceiverMasterId(long id) {
        db.putMetaValue(META_RECEIVER_MASTER, String.valueOf(id));
    }

    /** 主接收方（唯一收款方）：优先取被指定的那条，找不到就回落到第一个启用行 */
    public PayerItem getReceiverMaster() {
        long id = getReceiverMasterId();
        if (id > 0) {
            PayerItem p = findAddress(id);
            if (p != null) return p;
        }
        List<PayerItem> all = loadAddressList();
        for (PayerItem p : all) {
            if (p.enabled) return p;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    // endregion

    // region 归集阈值（AppPrefs）

    /** 取归集阈值（fLGNS 枚数字符串）；空/非法回落到 {@link #DEFAULT_THRESHOLD} */
    public String getThreshold() {
        SharedPreferences sp = AppPrefs.get(ctx);
        String v = sp.getString(KEY_THRESHOLD, DEFAULT_THRESHOLD);
        return normalizeThreshold(v);
    }

    /** 存归集阈值：入库前先归一化，界面拿 {@link #normalizeThreshold} 回显真实值 */
    public void setThreshold(String raw) {
        AppPrefs.get(ctx).edit()
                .putString(KEY_THRESHOLD, normalizeThreshold(raw))
                .apply();
    }

    /**
     * 阈值归一化：空/非法/负数/零一律回默认 100；最多保留 6 位小数（与金额展示口径一致），
     * 容忍千分位逗号与 fLGNS 后缀（与 {@code normalizeAmount} 同风格）
     */
    public static String normalizeThreshold(String raw) {
        if (raw == null) return DEFAULT_THRESHOLD;
        String s = raw.trim().replace(",", "").replace("%", "");
        if (s.toUpperCase(Locale.ROOT).endsWith("FLGNS")) {
            s = s.substring(0, s.length() - 5).trim();
        }
        if (s.isEmpty()) return DEFAULT_THRESHOLD;
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.signum() <= 0) return DEFAULT_THRESHOLD;
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return DEFAULT_THRESHOLD;
        }
    }

    // endregion
}
