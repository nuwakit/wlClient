package com.lgns.manager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.lgns.model.ApprenticeItem;
import com.lgns.model.PayerItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拜师管理数据层：apprentice_entry（门徒）与 master_entry（师傅）的唯一入口。
 *
 * <p>拜师与归集同构（多对一）：多个门徒各自发一笔 {@code bind(master)} 交易拜入
 * 唯一的主师傅门下。因此这里没有比例/数额/阈值概念，只有两张简单的名单表。</p>
 *
 * <p>与 {@link CollectBookManager} 的两点差异：</p>
 * <ul>
 *   <li>门徒侧与归集支付方侧完全同构：只存 key_entry._id 引用不复制密文，
 *       因为 bind 交易必须由门徒自己的私钥签名；</li>
 *   <li>师傅侧是纯地址名单（收徒方不需要在本机有私钥），且主号语义在师傅一侧
 *       （主师傅 = 批量拜师的目标），无阈值配置。</li>
 * </ul>
 */
public class ApprenticeBookManager {

    // region 常量

    /** 门徒表名 */
    private static final String T_APPRENTICE = AppLogDbHelper.TABLE_APPRENTICE;
    /** 师傅表名 */
    private static final String T_MASTER = AppLogDbHelper.TABLE_MASTER;
    /** 元数据键：当前主师傅 id */
    private static final String META_MASTER_ID = "apprentice_master_id";

    /** 数据访问层：两张表的读写都走它 */
    private final AppLogDbHelper db;

    // endregion

    // region 构造

    /** 构造：只持有 ApplicationContext + 单例 DB 句柄，传 Activity 会泄漏 */
    public ApprenticeBookManager(Context ctx) {
        this.db = AppLogDbHelper.getInstance(ctx);
    }

    /** 数据库 NULL 统一转成空串，避免上层到处判 null */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // endregion

    // region 门徒

    /** 读全部门徒，按 sort_order 升序 */
    public List<ApprenticeItem> loadApprenticeList() {
        List<ApprenticeItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,key_id,custom_code,group_no,address,enabled,sort_order FROM " + T_APPRENTICE
                        + " ORDER BY sort_order ASC, _id ASC", null);
        try {
            while (c.moveToNext()) {
                list.add(readApprentice(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 游标 → 模型（列顺序需与 SELECT 一致） */
    private ApprenticeItem readApprentice(Cursor c) {
        ApprenticeItem it = new ApprenticeItem();
        it.id = c.getLong(0);
        it.keyId = c.getLong(1);
        it.customCode = nz(c.getString(2));
        it.groupNo = nz(c.getString(3));
        it.address = nz(c.getString(4));
        it.enabled = c.getInt(5) == 1;
        it.sortOrder = c.getInt(6);
        return it;
    }

    /** 该私钥是否已导入过门徒 */
    public boolean apprenticeKeyExists(long keyId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_APPRENTICE + " WHERE key_id=?",
                new String[]{String.valueOf(keyId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 下一个门徒排序值 = 当前最大值 + 1 */
    private int nextApprenticeSortOrder() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT IFNULL(MAX(sort_order),-1)+1 FROM " + T_APPRENTICE, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 从私钥管理导入门徒：按 key_id 去重，已导入的私钥自动跳过。
     *
     * <p>只快照编号/分组/地址三个展示字段，不复制密文——执行拜师时再按 key_id
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
                if (apprenticeKeyExists(k.id)) continue;   // 同一把私钥只进一次
                ContentValues v = new ContentValues();
                v.put("key_id", k.id);
                v.put("custom_code", nz(k.customCode).trim());
                v.put("group_no", nz(k.groupNo));
                v.put("address", k.address == null ? "" : k.address.toLowerCase(Locale.ROOT));
                v.put("enabled", 1);
                v.put("sort_order", nextApprenticeSortOrder());
                v.put("created_at", System.currentTimeMillis());
                if (w.insert(T_APPRENTICE, null, v) > 0) imported++;
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        return imported;
    }

    /** 只改编号与分组，不动引用与启用状态 */
    public void updateApprenticeMeta(long id, String code, String groupNo) {
        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        db.getWritableDatabase().update(T_APPRENTICE, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 只改启用状态：批量拜师只会用到启用行 */
    public void setApprenticeEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_APPRENTICE, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 删门徒：只断引用，绝不动私钥管理里的原记录 */
    public void deleteApprentice(long id) {
        db.getWritableDatabase().delete(T_APPRENTICE, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 拖拽后整体写回排序：事务内按下标逐条 update */
    public void saveApprenticeSortOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort_order", i);
                w.update(T_APPRENTICE, v, "_id=?", new String[]{String.valueOf(ids.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    // endregion

    // region 师傅

    /** 读全部师傅，按 sort_order 升序；模型复用 {@link PayerItem}（cipherText 恒空） */
    public List<PayerItem> loadMasterList() {
        List<PayerItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,enabled,sort_order FROM " + T_MASTER
                        + " ORDER BY sort_order ASC, _id ASC", null);
        try {
            while (c.moveToNext()) {
                list.add(readMaster(c));
            }
        } finally {
            c.close();
        }
        return list;
    }

    /** 游标 → 模型（列顺序需与 SELECT 一致） */
    private PayerItem readMaster(Cursor c) {
        PayerItem it = new PayerItem();
        it.id = c.getLong(0);
        it.customCode = nz(c.getString(1));
        it.groupNo = nz(c.getString(2));
        it.cipherText = "";   // 师傅只收徒，永远没有密文
        it.address = nz(c.getString(3));
        it.enabled = c.getInt(4) == 1;
        it.sortOrder = c.getInt(5);
        return it;
    }

    /** 按 id 查师傅；不存在返回 null */
    public PayerItem findMaster(long id) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,enabled,sort_order FROM " + T_MASTER
                        + " WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readMaster(c) : null;
        } finally {
            c.close();
        }
    }

    /** 师傅地址是否重复（忽略大小写，excludeId 用于编辑时排除自身） */
    public boolean masterExists(String address, long excludeId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_MASTER + " WHERE LOWER(address)=? AND _id<>?",
                new String[]{nz(address).toLowerCase(Locale.ROOT), String.valueOf(excludeId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 下一个师傅排序值 = 当前最大值 + 1 */
    private int nextMasterSortOrder() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT IFNULL(MAX(sort_order),-1)+1 FROM " + T_MASTER, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** 新增师傅：地址严格校验 + 全局去重；第一条自动成为主师傅 */
    public PayerItem addMaster(String code, String groupNo, String rawAddress) throws Exception {
        String addr = nz(rawAddress).trim();
        if (!AddressBookManager.isValidAddress(addr)) throw new Exception("地址格式错误：需 0x 开头的 40 位十六进制");
        addr = addr.toLowerCase(Locale.ROOT);
        if (masterExists(addr, -1)) throw new Exception("该地址已存在师傅列表");

        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        v.put("enabled", 1);
        v.put("sort_order", nextMasterSortOrder());
        v.put("created", System.currentTimeMillis());
        long id = db.getWritableDatabase().insert(T_MASTER, null, v);
        if (id <= 0) throw new Exception("保存失败，请重试");

        if (getMasterMasterId() < 0) setMasterMasterId(id);

        PayerItem item = new PayerItem();
        item.id = id;
        item.customCode = nz(code).trim();
        item.groupNo = nz(groupNo);
        item.cipherText = "";
        item.address = addr;
        item.enabled = true;
        return item;
    }

    /** 编辑师傅：改编号/分组/地址（地址仍查重），不动启用状态 */
    public void updateMaster(long id, String code, String groupNo, String rawAddress) throws Exception {
        String addr = nz(rawAddress).trim();
        if (!AddressBookManager.isValidAddress(addr)) throw new Exception("地址格式错误：需 0x 开头的 40 位十六进制");
        addr = addr.toLowerCase(Locale.ROOT);
        if (masterExists(addr, id)) throw new Exception("该地址已存在师傅列表");
        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        db.getWritableDatabase().update(T_MASTER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 只改启用状态 */
    public void setMasterEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_MASTER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 删师傅；若删的正好是主师傅，同时把主号重置为 -1（不自动顺移给下一个） */
    public void deleteMaster(long id) {
        db.getWritableDatabase().delete(T_MASTER, "_id=?", new String[]{String.valueOf(id)});
        if (getMasterMasterId() == id) setMasterMasterId(-1);
    }

    /** 拖拽后整体写回排序 */
    public void saveMasterSortOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort_order", i);
                w.update(T_MASTER, v, "_id=?", new String[]{String.valueOf(ids.get(i))});
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
    }

    /** 取主师傅 id；解析失败或未设置返回 -1 */
    public long getMasterMasterId() {
        String raw = db.getMetaValue(META_MASTER_ID, "-1");
        try {
            return Long.parseLong(raw == null ? "-1" : raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 指定主师傅 id（传 -1 取消） */
    public void setMasterMasterId(long id) {
        db.putMetaValue(META_MASTER_ID, String.valueOf(id));
    }

    /** 主师傅（批量拜师的目标）：优先取被指定的那条，找不到就回落到第一个启用行 */
    public PayerItem getMasterMaster() {
        long id = getMasterMasterId();
        if (id > 0) {
            PayerItem p = findMaster(id);
            if (p != null) return p;
        }
        List<PayerItem> all = loadMasterList();
        for (PayerItem p : all) {
            if (p.enabled) return p;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    // endregion
}
