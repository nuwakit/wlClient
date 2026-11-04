package com.lgns.manager;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.lgns.model.AddressItem;
import com.lgns.model.PayerItem;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 地址簿(address_entry) 与 支付方(payer_entry) 的唯一入口。
 *
 * <p>两张表的 SQL 全部收在这里，AppLogDbHelper 只负责建表，避免两边签名漂移。</p>
 *
 * <p>两条业务铁则都在这一层校验，界面只负责展示错误文案：</p>
 * <ul>
 *   <li>地址唯一（忽略大小写）：同一地址不能重复录入；</li>
 *   <li>启用比例合计不超过 100%：新增/编辑/开启都要先算“除自己以外”的合计。</li>
 * </ul>
 *
 * <p>密码口径：支付方的密文与私钥表共用同一套加解密实现（{@link KeyStoreManager}），
 * 但密码是各自独立的一份，改支付方密码不会影响私钥。</p>
 */
public class AddressBookManager {

    // region 常量

    /** 默认补充 gas 的金额（代币枚数，字符串形式防止浮点误差） */
    public static final String DEFAULT_GAS_FUND = "5";
    /** 默认收益比例 */
    public static final String DEFAULT_RATIO = "0.00";
    /** 默认收益数额（fLGNS 枚数）：与比例一样存字符串，防止浮点误差 */
    public static final String DEFAULT_AMOUNT = "0.00";
    /** 比例上限：启用地址合计不能超过该值 */
    public static final BigDecimal RATIO_MAX = new BigDecimal("100");
    /**
     * 收益数额保留的小数位：与金额展示口径（{@code formatGas(wei, 6)}）一致。
     *
     * <p>为什么是 6 不是 18：代币最小单位是 10^18 wei，存 18 位小数没意义；
     * 超过 6 位的零头在界面上根本看不到，反倒容易把「转了 0.0000001」当成没转。</p>
     */
    public static final int AMOUNT_SCALE = 6;

    /** 地址簿表名（引用常量，避免手写字符串拼错） */
    private static final String T_ADDRESS = AppLogDbHelper.TABLE_ADDRESS;
    /** 支付方表名 */
    private static final String T_PAYER = AppLogDbHelper.TABLE_PAYER;
    /** SP 键：默认 gas 补充金额 */
    private static final String KEY_GAS_DEFAULT = "gas_fund_default";
    /** SP 键：默认收益比例 */
    private static final String KEY_RATIO_DEFAULT = "address_ratio_default";
    /** SP 键：默认收益数额 */
    private static final String KEY_AMOUNT_DEFAULT = "address_amount_default";
    /** 元数据键：当前主支付方 id */
    private static final String META_PAYER_MASTER = "payer_master_id";

    /** 应用上下文（只用于拿 SP，不持有 Activity） */
    private final Context ctx;
    /** 数据访问层：两张表的读写都走它 */
    private final AppLogDbHelper db;

    // endregion

    // region 构造与公共校验

    /**
     * 构造：只持有 ApplicationContext + 单例 DB 句柄。
     *
     * <p>传 Activity 进来会把这个页面的生命周期拉长到管理器存活期间，造成内存泄漏。</p>
     */
    public AddressBookManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.db = AppLogDbHelper.getInstance(ctx);
    }

    /** 数据库 NULL 统一转成空串，避免上层到处判 null */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 地址合法性：严格 0x + 40 位十六进制（不接受大小写混合以外的写法） */
    public static boolean isValidAddress(String addr) {
        return addr != null && addr.matches("^0x[0-9a-fA-F]{40}$");
    }

    /**
     * 统一成两位小数的字符串；空/非法 -> 0.00，负数 -> 0.00，超 100 -> 100.00。
     *
     * <p>之所以全程用 BigDecimal + 字符串：比例直接影响分钱，float/double 的 0.1 误差
     * 会在多地址叠加后放大。输入允许带 \% 符号（用户习惯直接贴百分比）。</p>
     */
    public static String normalizeRatio(String raw) {
        if (raw == null) return DEFAULT_RATIO;
        String s = raw.trim().replace("%", "");
        if (s.isEmpty()) return DEFAULT_RATIO;
        try {
            BigDecimal v = new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
            if (v.signum() < 0) return DEFAULT_RATIO;
            if (v.compareTo(RATIO_MAX) > 0) {
                return RATIO_MAX.setScale(2, RoundingMode.HALF_UP).toPlainString();
            }
            return v.toPlainString();
        } catch (Exception e) {
            return DEFAULT_RATIO;
        }
    }

    /**
     * 数额归一化：空/非法/负数 → {@link #DEFAULT_AMOUNT}，最多保留 {@link #AMOUNT_SCALE} 位小数，末尾的 0 去掉。
     *
     * <p>容忍两种常见粘贴写法：千分位逗号与带单位后缀（"1,000 fLGNS"），因为数额比
     * 百分比更容易从账本里直接复制。与比例一样全程 BigDecimal：数额直接就是转账金额，
     * double 的误差会真金白银地转错。</p>
     *
     * <p>零一律回成 "0.00" 而不走 {@code stripTrailingZeros()}：BigDecimal 对零去尾数在
     * 不同版本上行为不一致（历史上有返回 "0E-6" 的情况），不如显式回默认值。</p>
     */
    public static String normalizeAmount(String raw) {
        if (raw == null) return DEFAULT_AMOUNT;
        String s = raw.trim().replace(",", "").replace("%", "");
        if (s.toUpperCase(Locale.ROOT).endsWith("FLGNS")) {
            s = s.substring(0, s.length() - 5).trim();
        }
        if (s.isEmpty()) return DEFAULT_AMOUNT;
        try {
            BigDecimal v = new BigDecimal(s).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
            if (v.signum() <= 0) return DEFAULT_AMOUNT;
            return v.stripTrailingZeros().toPlainString();
        } catch (Exception e) {
            return DEFAULT_AMOUNT;
        }
    }

    // endregion

    // region 地址簿

    /** 读全部地址，按 sort_order 升序（同一排序值再用 id 保证稳定） */
    public List<AddressItem> loadAddressList() {
        List<AddressItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,ratio,amount,enabled,sort_order FROM " + T_ADDRESS
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

    /** 只取启用行：批量转账只给这些地址分钱 */
    public List<AddressItem> loadEnabledAddressList() {
        List<AddressItem> list = new ArrayList<>();
        for (AddressItem it : loadAddressList()) {
            if (it.enabled) list.add(it);
        }
        return list;
    }

    /** 按 id 查单条；不存在返回 null */
    public AddressItem findAddress(long id) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,address,ratio,amount,enabled,sort_order FROM " + T_ADDRESS
                        + " WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readAddress(c) : null;
        } finally {
            c.close();
        }
    }

    /** 游标 → 模型（列顺序必须与上面 SELECT 完全一致）；比例与数额在这里就做归一化，库里的脏值不会流出 */
    private AddressItem readAddress(Cursor c) {
        AddressItem it = new AddressItem();
        it.id = c.getLong(0);
        it.customCode = nz(c.getString(1));
        it.groupNo = nz(c.getString(2));
        it.address = nz(c.getString(3));
        it.ratio = normalizeRatio(c.getString(4));
        it.amount = normalizeAmount(c.getString(5));
        it.enabled = c.getInt(6) == 1;
        it.sortOrder = c.getInt(7);
        return it;
    }

    /**
     * 地址是否已存在（忽略大小写）；excludeId 用于编辑时排除自身。
     */
    public boolean addressExists(String address, long excludeId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_ADDRESS + " WHERE LOWER(address)=? AND _id<>?",
                new String[]{nz(address).toLowerCase(Locale.ROOT), String.valueOf(excludeId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 已启用地址的比例合计；excludeId > 0 时排除该行（编辑/开启时用） */
    public BigDecimal enabledRatioSum(long excludeId) {
        BigDecimal sum = BigDecimal.ZERO;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT ratio FROM " + T_ADDRESS + " WHERE enabled=1 AND _id<>?",
                new String[]{String.valueOf(excludeId)});
        try {
            while (c.moveToNext()) {
                sum = sum.add(new BigDecimal(normalizeRatio(c.getString(0))));
            }
        } finally {
            c.close();
        }
        return sum;
    }

    /**
     * 已启用地址的数额合计（fLGNS 枚数）；excludeId > 0 时排除该行。
     *
     * <p>只用来做界面汇总，不当作硬约束：真正的上限是支付方的链上余额，
     * 那是个动态值，只能在批量转账页查完余额后拦（见 {@code BatchTransferActivity}）。</p>
     */
    public BigDecimal enabledAmountSum(long excludeId) {
        BigDecimal sum = BigDecimal.ZERO;
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT amount FROM " + T_ADDRESS + " WHERE enabled=1 AND _id<>?",
                new String[]{String.valueOf(excludeId)});
        try {
            while (c.moveToNext()) {
                sum = sum.add(new BigDecimal(normalizeAmount(c.getString(0))));
            }
        } finally {
            c.close();
        }
        return sum;
    }

    /** 下一个排序值 = 当前最大值 + 1（删过记录后也不会与老行撞号） */
    private int nextAddressSortOrder() {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT IFNULL(MAX(sort_order),-1)+1 FROM " + T_ADDRESS, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /**
     * 新增地址：格式校验 → 去重 → 比例/数额归一化 → 比例合计不超 100% → 落库（默认启用，排在最后）。
     *
     * <p>数额不参与入库校验：它的上限是支付方的链上余额，写入时拿不到也不该拿。
     * 就算这里填大了，批量转账页也会在查完余额后拦下，不会把钱转超。</p>
     *
     * @throws Exception 校验失败或写库失败，消息可直接弹给用户
     */
    public AddressItem addAddress(String code, String groupNo, String address,
                                  String ratio, String amount) throws Exception {
        String addr = nz(address).trim().toLowerCase(Locale.ROOT);
        if (!isValidAddress(addr)) throw new Exception("地址格式不正确，应为 0x 开头的 42 位十六进制");
        if (addressExists(addr, -1)) throw new Exception("该地址已在列表中");

        String r = normalizeRatio(ratio);
        BigDecimal after = enabledRatioSum(-1).add(new BigDecimal(r));
        if (after.compareTo(RATIO_MAX) > 0) {
            throw new Exception("启用地址比例合计将超过 100%，当前已分配 "
                    + enabledRatioSum(-1).stripTrailingZeros().toPlainString() + "%");
        }

        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        v.put("ratio", r);
        v.put("amount", normalizeAmount(amount));
        v.put("enabled", 1);
        v.put("sort_order", nextAddressSortOrder());
        v.put("created", System.currentTimeMillis());
        long id = db.getWritableDatabase().insert(T_ADDRESS, null, v);
        if (id <= 0) throw new Exception("保存失败，请重试");

        AddressItem item = new AddressItem();
        item.id = id;
        item.customCode = nz(code).trim();
        item.groupNo = nz(groupNo);
        item.address = addr;
        item.ratio = r;
        item.amount = normalizeAmount(amount);
        item.enabled = true;
        return item;
    }

    /**
     * 编辑地址：同样做格式/去重校验；仅当这条保持启用时才校验比例合计
     * （关闭状态下比例不占额度，不必拦）。数额同样不受此约束。
     */
    public void updateAddress(long id, String code, String groupNo, String address,
                              String ratio, String amount, boolean enabled) throws Exception {
        String addr = nz(address).trim().toLowerCase(Locale.ROOT);
        if (!isValidAddress(addr)) throw new Exception("地址格式不正确，应为 0x 开头的 42 位十六进制");
        if (addressExists(addr, id)) throw new Exception("该地址已在列表中");

        String r = normalizeRatio(ratio);
        if (enabled) {
            BigDecimal others = enabledRatioSum(id);
            if (others.add(new BigDecimal(r)).compareTo(RATIO_MAX) > 0) {
                throw new Exception("启用地址比例合计将超过 100%，其余地址已分配 "
                        + others.stripTrailingZeros().toPlainString() + "%");
            }
        }

        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("address", addr);
        v.put("ratio", r);
        v.put("amount", normalizeAmount(amount));
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_ADDRESS, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /**
     * 开关启用状态。
     *
     * <p>开启前要校验合计：把这条的比例加上其他启用地址后不能超 100%，
     * 否则直接报错让用户先调其他人的比例（不做自动减少）。关闭则无约束。</p>
     */
    public void setAddressEnabled(long id, boolean enabled) throws Exception {
        if (enabled) {
            AddressItem it = findAddress(id);
            if (it == null) throw new Exception("地址不存在");
            BigDecimal others = enabledRatioSum(id);
            if (others.add(new BigDecimal(it.ratio)).compareTo(RATIO_MAX) > 0) {
                throw new Exception("开启后比例合计将超过 100%，请先调小其他地址的比例");
            }
        }
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_ADDRESS, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 删地址，返回受影响行数 */
    public int deleteAddress(long id) {
        return db.getWritableDatabase().delete(T_ADDRESS, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 拖拽后整组写回排序：事务内按下标逐条 update，下标即顺序 */
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

    // endregion

    // region 支付方

    /** 读全部支付方，按 sort_order 升序 */
    public List<PayerItem> loadPayerList() {
        List<PayerItem> list = new ArrayList<>();
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,cipher_text,address,enabled,sort_order FROM " + T_PAYER
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

    /** 按 id 查支付方；不存在返回 null */
    public PayerItem findPayer(long id) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT _id,custom_code,group_no,cipher_text,address,enabled,sort_order FROM " + T_PAYER
                        + " WHERE _id=?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readPayer(c) : null;
        } finally {
            c.close();
        }
    }

    /** 游标 → 模型（列顺序需与 SELECT 一致）；密文原样带出，解密交给 {@link #decryptPayerPk} */
    private PayerItem readPayer(Cursor c) {
        PayerItem it = new PayerItem();
        it.id = c.getLong(0);
        it.customCode = nz(c.getString(1));
        it.groupNo = nz(c.getString(2));
        it.cipherText = nz(c.getString(3));
        it.address = nz(c.getString(4));
        it.enabled = c.getInt(5) == 1;
        it.sortOrder = c.getInt(6);
        return it;
    }

    /** 支付方地址是否重复（忽略大小写，excludeId 用于编辑时排除自身） */
    public boolean payerAddressExists(String address, long excludeId) {
        Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT COUNT(1) FROM " + T_PAYER + " WHERE LOWER(address)=? AND _id<>?",
                new String[]{nz(address).toLowerCase(Locale.ROOT), String.valueOf(excludeId)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    /** 下一个支付方排序值 = 当前最大值 + 1 */
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
     * 新增支付方：由私钥推导地址后只存密文，明文不落库。第一个支付方自动成为出资方。
     *
     * <p>私钥只校验“是十六进制”，不强制 64 位：私钥本质是大整数，前导零可省略，
     * 强制长度反而会让合法私钥被拒。地址由私钥推导，不接受手填。
     * 加密走当前会话的 KeyStoreManager（同一套 AES-GCM 实现，密钥是支付方密码）。</p>
     */
    public PayerItem addPayer(String rawPk, char[] cryptoKey, String code, String groupNo) throws Exception {
        String pk = nz(rawPk).trim();
        if (pk.startsWith("0x") || pk.startsWith("0X")) pk = pk.substring(2);
        // 与私钥管理一致：只要求是十六进制，不强制 64 位（私钥本质是大整数，短写同样可用）
        if (!pk.matches("^[0-9a-fA-F]+$")) throw new Exception("私钥格式错误：只能包含 0-9 和 a-f 字符（可带 0x 前缀）");

        ECKeyPair keyPair;
        try {
            keyPair = Web3AndroidUtil.pkToKeyPair(pk);
        } catch (Exception e) {
            throw new Exception("私钥无效，无法推导地址");
        }
        String addr = Web3AndroidUtil.getAddress(keyPair).toLowerCase(Locale.ROOT);
        if (!isValidAddress(addr)) throw new Exception("私钥对应的地址无效");
        if (payerAddressExists(addr, -1)) throw new Exception("该私钥对应的地址已存在支付方列表");

        // 密码由调用方传入，与会话无关：走会话门会让会话过期后添加支付方必然失败
        String cipher = AppGlobal.getMetaManager().encrypt(pk, cryptoKey);
        if (cipher == null || cipher.isEmpty()) throw new Exception("私钥加密失败");

        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        v.put("cipher_text", cipher);
        v.put("address", addr);
        v.put("enabled", 1);
        v.put("sort_order", nextPayerSortOrder());
        v.put(AppLogDbHelper.COL_PAY_CREATED, System.currentTimeMillis());
        long id = db.getWritableDatabase().insert(T_PAYER, null, v);
        if (id <= 0) throw new Exception("保存失败，请重试");

        if (getPayerMasterId() < 0) setPayerMasterId(id);

        PayerItem item = new PayerItem();
        item.id = id;
        item.customCode = nz(code).trim();
        item.groupNo = nz(groupNo);
        item.cipherText = cipher;
        item.address = addr;
        item.enabled = true;
        return item;
    }

    /** 只改编号与分组，不动密文、地址与启用状态 */
    public void updatePayerMeta(long id, String code, String groupNo) {
        ContentValues v = new ContentValues();
        v.put("custom_code", nz(code).trim());
        v.put("group_no", nz(groupNo));
        db.getWritableDatabase().update(T_PAYER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** 只改启用状态：批量转账只会用到启用行 */
    public void setPayerEnabled(long id, boolean enabled) {
        ContentValues v = new ContentValues();
        v.put("enabled", enabled ? 1 : 0);
        db.getWritableDatabase().update(T_PAYER, v, "_id=?", new String[]{String.valueOf(id)});
    }

    /**
     * 支付方专用的改密：支付方密文与私钥管理各自一套密码，互不影响。
     *
     * <p>只有全部密文都能用旧密码解开才落库，任一条失败就整体中止，不会出现“改一半”
     * （否则一部分密文只认旧密码，一部分只认新密码，用户两边都解不开）。
     * 写回时逐条校验受影响行数，不是 1 就抛错回滚。</p>
     *
     * @return 重加密的支付方条数
     */
    public int changePayerCryptoKey(char[] oldKey, char[] newKey) throws Exception {
        List<PayerItem> list = loadPayerList();
        if (list.isEmpty()) throw new Exception("暂无可修改的支付方");
        List<String> plains = new ArrayList<>(list.size());
        KeyStoreManager mgr = AppGlobal.getMetaManager();
        for (int i = 0; i < list.size(); i++) {
            PayerItem p = list.get(i);
            if (p.cipherText == null || p.cipherText.isEmpty()) {
                throw new Exception("第 " + (i + 1) + " 条支付方密文缺失，无法改密");
            }
            try {
                plains.add(mgr.decryptCipherText(p.cipherText, oldKey));
            } catch (Exception e) {
                throw new Exception("旧密码不正确（第 " + (i + 1) + " 条解密失败），未做任何修改");
            }
        }
        List<String> ciphers = new ArrayList<>(list.size());
        try {
            for (int i = 0; i < list.size(); i++) {
                ciphers.add(mgr.encrypt(plains.get(i), newKey));
            }
        } finally {
            for (int i = 0; i < plains.size(); i++) plains.set(i, null);
        }

        SQLiteDatabase w = db.getWritableDatabase();
        w.beginTransaction();
        try {
            for (int i = 0; i < list.size(); i++) {
                ContentValues v = new ContentValues();
                v.put(AppLogDbHelper.COL_PAY_CIPHER_TEXT, ciphers.get(i));
                int row = w.update(T_PAYER, v, "_id=?", new String[]{String.valueOf(list.get(i).id)});
                if (row != 1) throw new Exception("第 " + (i + 1) + " 条支付方密文写入失败，已回滚");
            }
            w.setTransactionSuccessful();
        } finally {
            w.endTransaction();
        }
        return list.size();
    }

    /** 删支付方；若删的正好是主支付方，同时把主号重置为 -1（不自动顺移给下一个） */
    public void deletePayer(long id) {
        db.getWritableDatabase().delete(T_PAYER, "_id=?", new String[]{String.valueOf(id)});
        if (getPayerMasterId() == id) setPayerMasterId(-1);
    }

    /** 拖拽后整组写回排序：事务内按下标逐条 update */
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

    /** 取主支付方 id；解析失败或未设置返回 -1 */
    public long getPayerMasterId() {
        String raw = db.getMetaValue(META_PAYER_MASTER, "-1");
        try {
            return Long.parseLong(raw == null ? "-1" : raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 指定主支付方 id（传 -1 取消；实际取值时还会回落到第一个启用行） */
    public void setPayerMasterId(long id) {
        db.putMetaValue(META_PAYER_MASTER, String.valueOf(id));
    }

    /** 出资方：优先取被指定的那条，找不到就回落到第一个启用的支付方 */
    public PayerItem getPayerMaster() {
        long id = getPayerMasterId();
        if (id > 0) {
            PayerItem p = findPayer(id);
            if (p != null) return p;
        }
        List<PayerItem> all = loadPayerList();
        for (PayerItem p : all) {
            if (p.enabled) return p;
        }
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * 解出支付方明文私钥。
     *
     * <p>调用方负责在使用后尽快丢弃返回值（字符串无法主动清零，只能缩短存活时间）；
     * 支付方未设置或密文缺失时直接抛异常，不返回 null。</p>
     */
    public String decryptPayerPk(PayerItem payer, char[] cryptoKey) throws Exception {
        if (payer == null || payer.cipherText == null || payer.cipherText.isEmpty()) {
            throw new Exception("未设置支付方");
        }
        return AppGlobal.getMetaManager().decryptCipherText(payer.cipherText, cryptoKey);
    }

    // endregion

    // region 默认值（存 AppPrefs，新建弹窗的预填值）

    /** 取默认 gas 补充金额；空值回退到 {@link #DEFAULT_GAS_FUND} */
    public String getGasFundDefault() {
        SharedPreferences sp = AppPrefs.get(ctx);
        String v = sp.getString(KEY_GAS_DEFAULT, DEFAULT_GAS_FUND);
        return (v == null || v.trim().isEmpty()) ? DEFAULT_GAS_FUND : v.trim();
    }

    /** 存默认 gas 补充金额（空值存默认值，不存空串） */
    public void setGasFundDefault(String value) {
        String v = (value == null || value.trim().isEmpty()) ? DEFAULT_GAS_FUND : value.trim();
        AppPrefs.get(ctx).edit().putString(KEY_GAS_DEFAULT, v).apply();
    }

    /** 取默认收益比例；读出时也过一遍归一化，旧脏值不会带到界面 */
    public String getRatioDefault() {
        return normalizeRatio(AppPrefs.get(ctx).getString(KEY_RATIO_DEFAULT, DEFAULT_RATIO));
    }

    /** 存默认收益比例（写入前先归一化） */
    public void setRatioDefault(String value) {
        AppPrefs.get(ctx).edit().putString(KEY_RATIO_DEFAULT, normalizeRatio(value)).apply();
    }

    /** 取默认收益数额；读出时再过一遍归一化，旧脏值不会带到界面 */
    public String getAmountDefault() {
        return normalizeAmount(AppPrefs.get(ctx).getString(KEY_AMOUNT_DEFAULT, DEFAULT_AMOUNT));
    }

    /** 存默认收益数额（写入前先归一化） */
    public void setAmountDefault(String value) {
        AppPrefs.get(ctx).edit().putString(KEY_AMOUNT_DEFAULT, normalizeAmount(value)).apply();
    }

    // endregion
}
