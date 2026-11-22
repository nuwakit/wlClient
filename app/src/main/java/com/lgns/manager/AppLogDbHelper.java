package com.lgns.manager;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.lgns.model.DailyStatusItem;
import com.lgns.model.FailureLogItem;
import com.lgns.task.TaskLogItem;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 全局唯一的 SQLite 数据访问层（单例，进程内一份连接）。
 *
 * <p>库里一共 7 张表，按职责分块：</p>
 * <ul>
 *   <li>{@code task_log}：任务过程日志（展示用，可整表清空）；</li>
 *   <li>{@code key_group}：分组名与排序（分组名 UNIQUE）；</li>
 *   <li>{@code daily_status}：某账号某日的计数快照；</li>
 *   <li>{@code failure_log}：失败记录，按时间倒序读；</li>
 *   <li>{@code key_entry}：私钥表（只存密文，主键不用自增，由 next_id 分配）；</li>
 *   <li>{@code key_meta}：键值元数据（next_id / master_account_id 等）；</li>
 *   <li>{@code address_entry / payer_entry}：地址簿与支付方（业务校验在各自 Manager）。</li>
 * </ul>
 *
 * <p>升级约定：{@link #onUpgrade} 里每个版本用 {@code if (oldVersion < N)} 逐段补，
 * 每段必须幂等（加列包 try/catch）。DDL 只写在 onCreate 与这里，不要在业务方法里裸写建表语句——
 * 否则 onCreate 与 onUpgrade 会逐渐漂移，旧版本升级后少列。</p>
 */
public class AppLogDbHelper extends SQLiteOpenHelper {

    // region 库与表、列名常量

    /** 库文件名 */
    private static final String DB_NAME = "app_log.db";
    /** 库版本：改表结构必须 +1，并在 onUpgrade 里补对应段落 */
    private static final int DB_VERSION = 12;
    /** 进程内单例 */
    private static AppLogDbHelper instance;

    /** 任务日志表 */
    public static final String TABLE_LOG = "task_log";
    /** 通用自增主键（多表共用同一列名） */
    public static final String COL_ID = "_id";
    /** 日志：账号编号（可为空：全局提示类日志没有账号） */
    public static final String COL_KEY_NO = "key_no";
    /** 日志：毫秒时间戳 */
    public static final String COL_TIME = "log_time";
    /** 日志：分类（ENTER / CLAIM / CONFIG 等，由调用方约定） */
    public static final String COL_TYPE = "log_type";
    /** 日志：正文 */
    public static final String COL_CONTENT = "log_content";

    /** 分组表 */
    public static final String TABLE_GROUP = "key_group";
    /** 分组：名称，建表时加了 UNIQUE，重名插入会直接失败 */
    public static final String COL_GROUP_NAME = "group_name";
    /** 分组：排序值（拖拽后写入） */
    public static final String COL_GROUP_SORT = "sort_order";
    /** 分组：创建时间 */
    public static final String COL_GROUP_CREATED = "created_at";

    /** 当日状态表 */
    public static final String TABLE_DAILY_STATUS = "daily_status";
    /** 当日状态：关联 key_entry.id */
    public static final String COL_DS_KEY_ID = "key_id";
    /** 当日状态：账号编号快照 */
    public static final String COL_DS_KEY_NO = "key_no";
    /** 当日状态：写入时的分组（查询时会被 key_entry 的最新值覆盖） */
    public static final String COL_DS_GROUP_NO = "group_no";
    /** 当日状态：日期 yyyy-MM-dd */
    public static final String COL_DS_DATE = "status_date";
    /** 累计完成次数（沿用早期签到口径） */
    public static final String COL_DS_TOTAL_QD = "total_qd";
    /** 当日完成次数 */
    public static final String COL_DS_TODAY_QD = "today_qd";
    /** 当日入场次数 */
    public static final String COL_DS_TODAY_ENTER = "today_enter";
    /** 累计获得量（代币最小单位） */
    public static final String COL_DS_TOKENS = "tokens";
    /** 当日是否出现过失败（0/1） */
    public static final String COL_DS_IS_FAILED = "is_failed";
    /** 最后更新时间：日志清理按它判旧，不是按日期列 */
    public static final String COL_DS_UPDATED = "updated_at";

    /** 失败日志表 */
    public static final String TABLE_FAILURE_LOG = "failure_log";
    /** 失败日志：关联 key_entry.id */
    public static final String COL_FL_KEY_ID = "key_id";
    /** 失败日志：账号编号快照 */
    public static final String COL_FL_KEY_NO = "key_no";
    /** 失败日志：写入时的分组 */
    public static final String COL_FL_GROUP_NO = "group_no";
    /** 失败日志：日期 yyyy-MM-dd */
    public static final String COL_FL_DATE = "fail_date";
    /** 失败日志：失败环节（ENTER / CLAIM 等） */
    public static final String COL_FL_TYPE = "fail_type";
    /** 失败日志：原因原文（直接展示给用户） */
    public static final String COL_FL_REASON = "fail_reason";
    /** 失败日志：毫秒时间戳，列表倒序依据 */
    public static final String COL_FL_TIME = "fail_time";

    /** 私钥表 */
    public static final String TABLE_KEY_ENTRY = "key_entry";
    /** 私钥表主键：不用自增，由 key_meta.next_id 分配（迁移时需保留原 id） */
    public static final String COL_KE_ID = "_id";
    /** 私钥：用户自定义编号 */
    public static final String COL_KE_CUSTOM_CODE = "custom_code";
    /** 私钥：所属分组名 */
    public static final String COL_KE_GROUP_NO = "group_no";
    /** 私钥：密文（PBKDF2 + AES-GCM 后的 Base64） */
    public static final String COL_KE_CIPHER_TEXT = "cipher_text";
    /** 私钥：地址（旧数据可能为空） */
    public static final String COL_KE_ADDRESS = "address";
    /** 私钥：是否启用 */
    public static final String COL_KE_ENABLED = "enabled";
    /** 私钥：排序值（列表与拖拽结果的唯一依据） */
    public static final String COL_KE_SORT_ORDER = "sort_order";
    /** 私钥：创建时间 */
    public static final String COL_KE_CREATED = "created_at";

    /** 键值元数据表（单行一键，CONFLICT_REPLACE 写入） */
    public static final String TABLE_KEY_META = "key_meta";
    /** 元数据：键名（主键） */
    public static final String COL_KM_KEY = "meta_key";
    /** 元数据：键值（统一存字符串） */
    public static final String COL_KM_VALUE = "meta_value";

    /**
     * 地址簿表：收款地址 + 收益比例 + 收益数额。
     *
     * <p>比例与数额是两套互斥的分配口径，由系统配置页的「批量转账」决定走哪一套：
     * 按比例分支付方全部余额，按数额则每号固定拿这么多 fLGNS。
     * 比例合计上限 100% 由 AddressBookManager 校验；数额没有固定上限（它要跟链上余额比），
     * 所以库层与 Manager 都不拦，实际「累计数额 ≤ 支付方余额」要到批量转账页查完余额才判。</p>
     */
    public static final String TABLE_ADDRESS = "address_entry";
    /** 地址簿：编号 */
    public static final String COL_AD_CUSTOM_CODE = "custom_code";
    /** 地址簿：分组名 */
    public static final String COL_AD_GROUP_NO = "group_no";
    /** 地址簿：收款地址（唯一业务键，NOT NULL） */
    public static final String COL_AD_ADDRESS = "address";
    /** 地址簿：比例，存字符串避免浮点误差 */
    public static final String COL_AD_RATIO = "ratio";
    /** 地址簿：收益数额（fLGNS 枚数的十进制字符串），同样存字符串避免浮点误差 */
    public static final String COL_AD_AMOUNT = "amount";
    /** 地址簿：是否参与分配 */
    public static final String COL_AD_ENABLED = "enabled";
    /** 地址簿：排序值 */
    public static final String COL_AD_SORT_ORDER = "sort_order";
    /** 地址簿：创建时间 */
    public static final String COL_AD_CREATED = "created";

    /**
     * 支付方表：只存加密私钥，明文不落库；列结构与密钥表(key_entry)保持一致，便于两边的读写代码互相对照。
     */
    public static final String TABLE_PAYER = "payer_entry";
    /** 支付方：编号 */
    public static final String COL_PAY_CUSTOM_CODE = "custom_code";
    /** 支付方：分组名 */
    public static final String COL_PAY_GROUP_NO = "group_no";
    /** 支付方：私钥密文（独立密码加密，与私钥表不同密码） */
    public static final String COL_PAY_CIPHER_TEXT = "cipher_text";
    /** 支付方：出资地址 */
    public static final String COL_PAY_ADDRESS = "address";
    /** 支付方：是否参与批量转账 */
    public static final String COL_PAY_ENABLED = "enabled";
    /** 支付方：排序值 */
    public static final String COL_PAY_SORT_ORDER = "sort_order";
    /** 支付方：创建时间（v9 由 created 改名而来） */
    public static final String COL_PAY_CREATED = "created_at";

    /**
     * 归集支付方表：批量归集的出资方来源。
     *
     * <p>不复制密文，只存 key_entry 的 _id（key_id）做引用：私钥密文与密码仍由私钥管理独占一份，
     * 避免同一把私钥出现两套密文两种密码。删除私钥后该引用行在归集页照常展示，
     * 执行时才发现私钥缺失并逐条报错，不会静默漏转。</p>
     */
    public static final String TABLE_COLLECT_PAYER = "collect_payer_entry";
    /** 归集支付方：引用的私钥 id（key_entry._id） */
    public static final String COL_CP_KEY_ID = "key_id";
    /** 归集支付方：编号 */
    public static final String COL_CP_CUSTOM_CODE = "custom_code";
    /** 归集支付方：分组名 */
    public static final String COL_CP_GROUP_NO = "group_no";
    /** 归集支付方：出资地址（导入时从私钥记录快照过来） */
    public static final String COL_CP_ADDRESS = "address";
    /** 归集支付方：是否参与批量归集 */
    public static final String COL_CP_ENABLED = "enabled";
    /** 归集支付方：排序值 */
    public static final String COL_CP_SORT_ORDER = "sort_order";
    /** 归集支付方：创建时间 */
    public static final String COL_CP_CREATED = "created_at";

    /**
     * 归集接收方表：批量归集的唯一收款方（主接收方）候选。
     * 列结构对齐 address_entry，但没有比例与数额——归集是「超出阈值全转」，不参与分配。
     */
    public static final String TABLE_COLLECT_ADDRESS = "collect_address_entry";
    /** 归集接收方：编号 */
    public static final String COL_CA_CUSTOM_CODE = "custom_code";
    /** 归集接收方：分组名 */
    public static final String COL_CA_GROUP_NO = "group_no";
    /** 归集接收方：收款地址（唯一业务键，NOT NULL） */
    public static final String COL_CA_ADDRESS = "address";
    /** 归集接收方：是否启用 */
    public static final String COL_CA_ENABLED = "enabled";
    /** 归集接收方：排序值 */
    public static final String COL_CA_SORT_ORDER = "sort_order";
    /** 归集接收方：创建时间 */
    public static final String COL_CA_CREATED = "created";

    /**
     * 门徒表：批量拜师的对象来源。
     *
     * <p>列结构与归集支付方表(collect_payer_entry)完全同构：同样只存 key_entry._id 引用
     * 不复制密文，拜师交易要用门徒自己的私钥签名，执行时现解密。</p>
     */
    public static final String TABLE_APPRENTICE = "apprentice_entry";
    /** 门徒：引用的私钥 id（key_entry._id） */
    public static final String COL_AP_KEY_ID = "key_id";
    /** 门徒：编号 */
    public static final String COL_AP_CUSTOM_CODE = "custom_code";
    /** 门徒：分组名 */
    public static final String COL_AP_GROUP_NO = "group_no";
    /** 门徒：徒弟地址（导入时从私钥记录快照过来） */
    public static final String COL_AP_ADDRESS = "address";
    /** 门徒：是否参与批量拜师 */
    public static final String COL_AP_ENABLED = "enabled";
    /** 门徒：排序值 */
    public static final String COL_AP_SORT_ORDER = "sort_order";
    /** 门徒：创建时间 */
    public static final String COL_AP_CREATED = "created_at";

    /**
     * 师傅表：批量拜师的目标师傅（主师傅）候选。
     * 列结构对齐归集接收方表(collect_address_entry)，师傅是收徒方、不需要自己的私钥引用。
     */
    public static final String TABLE_MASTER = "master_entry";
    /** 师傅：编号 */
    public static final String COL_MST_CUSTOM_CODE = "custom_code";
    /** 师傅：分组名 */
    public static final String COL_MST_GROUP_NO = "group_no";
    /** 师傅：收徒地址（唯一业务键，NOT NULL） */
    public static final String COL_MST_ADDRESS = "address";
    /** 师傅：是否启用 */
    public static final String COL_MST_ENABLED = "enabled";
    /** 师傅：排序值 */
    public static final String COL_MST_SORT_ORDER = "sort_order";
    /** 师傅：创建时间 */
    public static final String COL_MST_CREATED = "created";

    // endregion

    // region 单例与建表/升级

    /** 地址簿建表语句：onCreate 与 onUpgrade 共用，避免两处 DDL 漂移 */
    private static final String SQL_CREATE_ADDRESS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_ADDRESS + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_AD_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_AD_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_AD_ADDRESS + " TEXT NOT NULL," +
                    COL_AD_RATIO + " TEXT DEFAULT '0.00'," +
                    COL_AD_AMOUNT + " TEXT DEFAULT '0.00'," +
                    COL_AD_ENABLED + " INTEGER DEFAULT 1," +
                    COL_AD_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_AD_CREATED + " INTEGER);";

    /**
     * 支付方表 DDL：列结构与密钥表(key_entry)完全一致，建表/重建共用一份，避免两处漂移。
     *
     * @param table 表名（v9 重建时先建到临时表 payer_entry_old）
     */
    private static String payerDdl(String table) {
        return "CREATE TABLE IF NOT EXISTS " + table + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_PAY_CUSTOM_CODE + " TEXT DEFAULT ''," +
                COL_PAY_GROUP_NO + " TEXT DEFAULT ''," +
                COL_PAY_CIPHER_TEXT + " TEXT NOT NULL," +
                COL_PAY_ADDRESS + " TEXT," +
                COL_PAY_ENABLED + " INTEGER DEFAULT 1," +
                COL_PAY_SORT_ORDER + " INTEGER DEFAULT 0," +
                COL_PAY_CREATED + " INTEGER);";
    }

    /** 支付方建表语句（固定用于正式表名） */
    private static final String SQL_CREATE_PAYER = payerDdl(TABLE_PAYER);

    /** 归集支付方建表语句：onCreate 与 onUpgrade 共用，避免两处 DDL 漂移 */
    private static final String SQL_CREATE_COLLECT_PAYER =
            "CREATE TABLE IF NOT EXISTS " + TABLE_COLLECT_PAYER + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_CP_KEY_ID + " INTEGER NOT NULL," +
                    COL_CP_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_CP_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_CP_ADDRESS + " TEXT," +
                    COL_CP_ENABLED + " INTEGER DEFAULT 1," +
                    COL_CP_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_CP_CREATED + " INTEGER);";

    /** 归集接收方建表语句：同上，onCreate 与 onUpgrade 共用 */
    private static final String SQL_CREATE_COLLECT_ADDRESS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_COLLECT_ADDRESS + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_CA_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_CA_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_CA_ADDRESS + " TEXT NOT NULL," +
                    COL_CA_ENABLED + " INTEGER DEFAULT 1," +
                    COL_CA_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_CA_CREATED + " INTEGER);";

    /** 门徒建表语句：列结构同归集支付方，onCreate 与 onUpgrade 共用 */
    private static final String SQL_CREATE_APPRENTICE =
            "CREATE TABLE IF NOT EXISTS " + TABLE_APPRENTICE + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_AP_KEY_ID + " INTEGER NOT NULL," +
                    COL_AP_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_AP_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_AP_ADDRESS + " TEXT," +
                    COL_AP_ENABLED + " INTEGER DEFAULT 1," +
                    COL_AP_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_AP_CREATED + " INTEGER);";

    /** 师傅建表语句：列结构同归集接收方，同上共用 */
    private static final String SQL_CREATE_MASTER =
            "CREATE TABLE IF NOT EXISTS " + TABLE_MASTER + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_MST_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_MST_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_MST_ADDRESS + " TEXT NOT NULL," +
                    COL_MST_ENABLED + " INTEGER DEFAULT 1," +
                    COL_MST_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_MST_CREATED + " INTEGER);";

    /** 私有构造：开 WAL 提升读写并发（后台写日志时不阻塞列表查询） */
    private AppLogDbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    /**
     * 取单例。全进程共用一个连接，不要在业务层 new，否则会多份连接互相锁表。
     */
    public static synchronized AppLogDbHelper getInstance(Context ctx) {
        if (instance == null) {
            instance = new AppLogDbHelper(ctx);
        }
        return instance;
    }

    /**
     * 首次建库：所有表的 DDL 都集中在这里。
     *
     * <p>注意每个语句都带 {@code IF NOT EXISTS}：v1→v2 升级时会用「改名 + onCreate」的方式重建，
     * 那时其他表已经存在，不能重复建错。</p>
     */
    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE IF NOT EXISTS " + TABLE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_KEY_NO + " TEXT," +
                COL_TIME + " INTEGER NOT NULL," +
                COL_TYPE + " TEXT," +
                COL_CONTENT + " TEXT);";
        db.execSQL(sql);
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_GROUP_NAME + " TEXT UNIQUE NOT NULL," +
                COL_GROUP_SORT + " INTEGER DEFAULT 0," +
                COL_GROUP_CREATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_STATUS + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_DS_KEY_ID + " INTEGER NOT NULL," +
                COL_DS_KEY_NO + " TEXT," +
                COL_DS_GROUP_NO + " TEXT DEFAULT ''," +
                COL_DS_DATE + " TEXT NOT NULL," +
                COL_DS_TOTAL_QD + " INTEGER DEFAULT 0," +
                COL_DS_TODAY_QD + " INTEGER DEFAULT 0," +
                COL_DS_TODAY_ENTER + " INTEGER DEFAULT 0," +
                COL_DS_TOKENS + " INTEGER DEFAULT 0," +
                COL_DS_IS_FAILED + " INTEGER DEFAULT 0," +
                COL_DS_UPDATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAILURE_LOG + "(" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                COL_FL_KEY_ID + " INTEGER NOT NULL," +
                COL_FL_KEY_NO + " TEXT," +
                COL_FL_GROUP_NO + " TEXT DEFAULT ''," +
                COL_FL_DATE + " TEXT NOT NULL," +
                COL_FL_TYPE + " TEXT," +
                COL_FL_REASON + " TEXT," +
                COL_FL_TIME + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_ENTRY + "(" +
                COL_KE_ID + " INTEGER PRIMARY KEY," +
                COL_KE_CUSTOM_CODE + " TEXT DEFAULT ''," +
                COL_KE_GROUP_NO + " TEXT DEFAULT ''," +
                COL_KE_CIPHER_TEXT + " TEXT NOT NULL," +
                COL_KE_ADDRESS + " TEXT," +
                COL_KE_ENABLED + " INTEGER DEFAULT 1," +
                COL_KE_SORT_ORDER + " INTEGER DEFAULT 0," +
                COL_KE_CREATED + " INTEGER NOT NULL);");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_META + "(" +
                COL_KM_KEY + " TEXT PRIMARY KEY," +
                COL_KM_VALUE + " TEXT);");
        db.execSQL(SQL_CREATE_ADDRESS);
        db.execSQL(SQL_CREATE_PAYER);
        db.execSQL(SQL_CREATE_COLLECT_PAYER);
        db.execSQL(SQL_CREATE_COLLECT_ADDRESS);
        db.execSQL(SQL_CREATE_APPRENTICE);
        db.execSQL(SQL_CREATE_MASTER);
    }

    /**
     * 逐版本升级。每一段只在 {@code oldVersion < N} 时执行，且必须幂等：
     * 加列都包 try/catch（已存在就报 duplicate column，忽略即可）。
     *
     * <p>版本台账：2 重建日志表 / 3 新增分组表 / 4 新增当日状态与失败日志 /
     * 5 加 is_failed / 6 新增私钥表与元数据 / 7 给两张表加 group_no /
     * 8 新增地址簿与支付方 / 9 支付方表列名对齐 / 10 地址簿加 amount 收益数额 /
     * 11 新增归集支付方与归集接收方 / 12 新增门徒与师傅表。</p>
     */
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion >= 2) {
            String oldTableName = "task_log_old";
            db.execSQL("ALTER TABLE " + TABLE_LOG + " RENAME TO " + oldTableName);
            onCreate(db);
            String copySql = "INSERT INTO " + TABLE_LOG +
                    "("+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+") " +
                    "SELECT "+COL_ID+","+COL_KEY_NO+","+COL_TIME+","+COL_TYPE+","+COL_CONTENT+" FROM "+oldTableName;
            db.execSQL(copySql);
            db.execSQL("DROP TABLE IF EXISTS " + oldTableName);
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_GROUP + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_GROUP_NAME + " TEXT UNIQUE NOT NULL," +
                    COL_GROUP_SORT + " INTEGER DEFAULT 0," +
                    COL_GROUP_CREATED + " INTEGER NOT NULL);");
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DAILY_STATUS + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_DS_KEY_ID + " INTEGER NOT NULL," +
                    COL_DS_KEY_NO + " TEXT," +
                    COL_DS_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_DS_DATE + " TEXT NOT NULL," +
                    COL_DS_TOTAL_QD + " INTEGER DEFAULT 0," +
                    COL_DS_TODAY_QD + " INTEGER DEFAULT 0," +
                    COL_DS_TODAY_ENTER + " INTEGER DEFAULT 0," +
                    COL_DS_TOKENS + " INTEGER DEFAULT 0," +
                    COL_DS_IS_FAILED + " INTEGER DEFAULT 0," +
                    COL_DS_UPDATED + " INTEGER NOT NULL);");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FAILURE_LOG + "(" +
                    COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
                    COL_FL_KEY_ID + " INTEGER NOT NULL," +
                    COL_FL_KEY_NO + " TEXT," +
                    COL_FL_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_FL_DATE + " TEXT NOT NULL," +
                    COL_FL_TYPE + " TEXT," +
                    COL_FL_REASON + " TEXT," +
                    COL_FL_TIME + " INTEGER NOT NULL);");
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE " + TABLE_DAILY_STATUS + " ADD COLUMN " + COL_DS_IS_FAILED + " INTEGER DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_ENTRY + "(" +
                    COL_KE_ID + " INTEGER PRIMARY KEY," +
                    COL_KE_CUSTOM_CODE + " TEXT DEFAULT ''," +
                    COL_KE_GROUP_NO + " TEXT DEFAULT ''," +
                    COL_KE_CIPHER_TEXT + " TEXT NOT NULL," +
                    COL_KE_ADDRESS + " TEXT," +
                    COL_KE_ENABLED + " INTEGER DEFAULT 1," +
                    COL_KE_SORT_ORDER + " INTEGER DEFAULT 0," +
                    COL_KE_CREATED + " INTEGER NOT NULL);");
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEY_META + "(" +
                    COL_KM_KEY + " TEXT PRIMARY KEY," +
                    COL_KM_VALUE + " TEXT);");
        }
        if (oldVersion < 7) {
            try { db.execSQL("ALTER TABLE " + TABLE_DAILY_STATUS + " ADD COLUMN " + COL_DS_GROUP_NO + " TEXT DEFAULT ''"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE " + TABLE_FAILURE_LOG + " ADD COLUMN " + COL_FL_GROUP_NO + " TEXT DEFAULT ''"); } catch (Exception ignored) {}
        }
        if (oldVersion < 8) {
            // 地址簿 + 支付方（新增表，不动任何存量数据）
            db.execSQL(SQL_CREATE_ADDRESS);
            db.execSQL(SQL_CREATE_PAYER);
        }
        if (oldVersion < 9) {
            // 支付方表结构对齐密钥表：created -> created_at，并清掉旧列（重建表，数据原样搬迁）
            rebuildPayerTable(db);
        }
        if (oldVersion < 10) {
            // 地址簿加「收益数额」列：按数额转账模式的分配依据，存量行默认 0（等于不参与）
            try { db.execSQL("ALTER TABLE " + TABLE_ADDRESS + " ADD COLUMN " + COL_AD_AMOUNT + " TEXT DEFAULT '0.00'"); } catch (Exception ignored) {}
        }
        if (oldVersion < 11) {
            // 归集管理两张新表（只建表，不动任何存量数据；IF NOT EXISTS 保证幂等）
            db.execSQL(SQL_CREATE_COLLECT_PAYER);
            db.execSQL(SQL_CREATE_COLLECT_ADDRESS);
        }
        if (oldVersion < 12) {
            // 拜师管理两张新表（同上，只建表不动存量）
            db.execSQL(SQL_CREATE_APPRENTICE);
            db.execSQL(SQL_CREATE_MASTER);
        }
    }

    /**
     * v9：payer_entry 重建为与 key_entry 同构的列。
     * 整个重建在一个事务里，失败自动回滚，随后用“只补列”兜底，不会丢数据。
     */
    private void rebuildPayerTable(SQLiteDatabase db) {
        if (!hasColumn(db, TABLE_PAYER, "created")) {
            // 已经是新结构（v8 建表时用的就是 created_at），无需重建
            return;
        }
        db.beginTransaction();
        try {
            db.execSQL("DROP TABLE IF EXISTS payer_entry_old");
            db.execSQL(payerDdl("payer_entry_old"));
            db.execSQL("INSERT INTO payer_entry_old(" + COL_ID + "," + COL_PAY_CUSTOM_CODE + ","
                    + COL_PAY_GROUP_NO + "," + COL_PAY_CIPHER_TEXT + "," + COL_PAY_ADDRESS + ","
                    + COL_PAY_ENABLED + "," + COL_PAY_SORT_ORDER + "," + COL_PAY_CREATED + ")"
                    + " SELECT " + COL_ID + "," + COL_PAY_CUSTOM_CODE + "," + COL_PAY_GROUP_NO + ","
                    + COL_PAY_CIPHER_TEXT + "," + COL_PAY_ADDRESS + "," + COL_PAY_ENABLED + ","
                    + COL_PAY_SORT_ORDER + ",created FROM " + TABLE_PAYER);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_PAYER);
            db.execSQL("ALTER TABLE payer_entry_old RENAME TO " + TABLE_PAYER);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.w("AppLogDbHelper", "payer_entry 重建失败，退回补列方案: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
        // 兜底：重建没跑成也必须保证 created_at 存在，否则新增支付方会报 no such column
        try {
            db.execSQL("ALTER TABLE " + TABLE_PAYER + " ADD COLUMN " + COL_PAY_CREATED + " INTEGER");
        } catch (Exception ignored) {}
    }

    /**
     * 判断表里是否已有某列（用于判断迁移是否需要跑）。
     *
     * <p>拿不到的异常全吞：PRAGMA 在表不存在时也会报错，此时“没这列”的结论仍然成立。</p>
     */
    private boolean hasColumn(SQLiteDatabase db, String table, String column) {
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equals(c.getString(idx))) return true;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    // endregion

    // region 任务日志

    /**
     * 写入一条过程日志。
     *
     * <p>keyNo / logType 为空时不写该列（存 NULL），数据库 NULL 回到上层正好适配判空逻辑，
     * 不要在这里把空串写成 "null" 字符串。时间戳由调用方传入，便于补录时保留原始时间。</p>
     *
     * @return 新行 id，-1 表示插入失败（已记 error 日志）
     */
    public long insertLog(String keyNo, String logType, String content, long timeMs) {
        ContentValues cv = new ContentValues();
        if(keyNo != null){
            cv.put(COL_KEY_NO, keyNo);
        }
        cv.put(COL_TIME, timeMs); //使用外部传入的时间戳
        if(logType != null){
            cv.put(COL_TYPE, logType);
        }
        cv.put(COL_CONTENT, content);

        SQLiteDatabase db = getWritableDatabase();
        long rowId = db.insert(TABLE_LOG, null, cv);
        Log.d("insertLog_DB", "rowId=" + rowId
                + " | keyNo=" + keyNo
                + " | logType=" + logType
                + " | content=" + content);
        if(rowId == -1){
            Log.e("insertLog_DB", "⚠日志数据库插入失败！rowId = -1");
        }
        return rowId;
    }


    /** 清空任务日志表（只删日志，不动分组与密钥） */
    public int clearAllLog() {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete(TABLE_LOG, null, null);
    }

    /** 读全部日志，按时间倒序（日志页直接渲染） */
    public List<TaskLogItem> queryAllLogDesc() {
        List<TaskLogItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE_LOG, null, null, null, null, null, COL_TIME + " DESC");
        while (cursor.moveToNext()) {
            TaskLogItem item = new TaskLogItem();
            item.logId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID));
            //数据库NULL → Java对象得到null，正好适配上层判空逻辑
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_KEY_NO));
            item.timeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIME));
            item.logType = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE));
            item.content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT));
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // endregion

    // region 分组

    /**
     * 新增分组。
     *
     * <p>排序值直接用当前时间戳而不是行数：新分组自然排在最后，
     * 也不用先查一次总数。重名会因 UNIQUE 约束插入失败并返回 -1，调用方需判。</p>
     */
    public long insertGroup(String groupName) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_NAME, groupName);
        cv.put(COL_GROUP_SORT, System.currentTimeMillis());
        cv.put(COL_GROUP_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_GROUP, null, cv);
    }

    /** 删分组：只删分组表记录，不改正下账号的 group_no（界面上归为“未分组”） */
    public int deleteGroup(String groupName) {
        return getWritableDatabase().delete(TABLE_GROUP, COL_GROUP_NAME + "=?", new String[]{groupName});
    }

    /** 重命名分组：只改分组表。账号上的 group_no 由调用方额外同步（key_entry 那边一条 UPDATE） */
    public int renameGroup(String oldName, String newName) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_NAME, newName);
        return getWritableDatabase().update(TABLE_GROUP, cv, COL_GROUP_NAME + "=?", new String[]{oldName});
    }

    /** 读全部组名，按 sort_order 升序 */
    public List<String> queryAllGroups() {
        List<String> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_GROUP,
                new String[]{COL_GROUP_NAME}, null, null, null, null,
                COL_GROUP_SORT + " ASC");
        while (cursor.moveToNext()) {
            list.add(cursor.getString(cursor.getColumnIndexOrThrow(COL_GROUP_NAME)));
        }
        cursor.close();
        return list;
    }

    /** 分组是否存在（查一条就够，LIMIT 1） */
    public boolean groupExists(String groupName) {
        Cursor cursor = getReadableDatabase().query(TABLE_GROUP,
                new String[]{COL_ID}, COL_GROUP_NAME + "=?", new String[]{groupName},
                null, null, null, "1");
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    /** 写回分组排序（拖拽松手时逐条调用） */
    public int updateGroupSortOrder(String groupName, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_GROUP_SORT, sortOrder);
        return getWritableDatabase().update(TABLE_GROUP, cv, COL_GROUP_NAME + "=?", new String[]{groupName});
    }

    // endregion

    // region 当日状态

    /**
     * 写入/覆盖某账号某日的状态（按唯一约束 CONFLICT_REPLACE）。
     *
     * <p>注意 REPLACE 的语义是「删旧行再插新行」，主键会变；所以不要拿这里的返回 id 当业务标识，
     * 业务定位一律用 key_id + date。</p>
     */
    public long upsertDailyStatus(long keyId, String keyNo, String groupNo, String date, int totalQd, int todayQd, int todayEnter, long tokens, boolean isFailed) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DS_KEY_ID, keyId);
        cv.put(COL_DS_KEY_NO, keyNo);
        cv.put(COL_DS_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_DS_DATE, date);
        cv.put(COL_DS_TOTAL_QD, totalQd);
        cv.put(COL_DS_TODAY_QD, todayQd);
        cv.put(COL_DS_TODAY_ENTER, todayEnter);
        cv.put(COL_DS_TOKENS, tokens);
        cv.put(COL_DS_IS_FAILED, isFailed ? 1 : 0);
        cv.put(COL_DS_UPDATED, System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(TABLE_DAILY_STATUS, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * 查某一天的状态列表，按 key_id 升序。
     *
     * <p>分组取的是 {@code key_entry} 的当前值（LEFT JOIN）：账号换组后，
     * 历史记录也跟着显示新分组，这样统计页的分组汇总不会出现“旧组名”无效行。
     * JOIN 不到（账号已删）时返回空串，而不是 NULL。</p>
     */
    public List<DailyStatusItem> queryDailyStatus(String date) {
        List<DailyStatusItem> list = new ArrayList<>();
        // 关联 key_entry 表获取当前最新分组
        String sql = "SELECT ds.*, ke." + COL_KE_GROUP_NO + " as current_group " +
                "FROM " + TABLE_DAILY_STATUS + " ds " +
                "LEFT JOIN " + TABLE_KEY_ENTRY + " ke ON ds." + COL_DS_KEY_ID + " = ke." + COL_KE_ID + " " +
                "WHERE ds." + COL_DS_DATE + "=? " +
                "ORDER BY ds." + COL_DS_KEY_ID + " ASC";
        Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{date});
        int groupNoIdx = cursor.getColumnIndex("current_group");
        while (cursor.moveToNext()) {
            DailyStatusItem item = new DailyStatusItem();
            item.keyId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DS_KEY_ID));
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_DS_KEY_NO));
            item.groupNo = groupNoIdx >= 0 ? cursor.getString(groupNoIdx) : "";
            if (item.groupNo == null) item.groupNo = "";
            item.date = cursor.getString(cursor.getColumnIndexOrThrow(COL_DS_DATE));
            item.totalQd = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TOTAL_QD));
            item.todayQd = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TODAY_QD));
            item.todayEnter = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_TODAY_ENTER));
            item.tokens = cursor.getLong(cursor.getColumnIndexOrThrow(COL_DS_TOKENS));
            item.isFailed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_DS_IS_FAILED)) == 1;
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // endregion

    // region 失败日志

    /**
     * 记一条失败记录。时间取当前时刻；分组写写入时的快照（查询时会被 key_entry 的最新值覆盖）。
     */
    public long insertFailure(long keyId, String keyNo, String groupNo, String date, String failType, String reason) {
        ContentValues cv = new ContentValues();
        cv.put(COL_FL_KEY_ID, keyId);
        cv.put(COL_FL_KEY_NO, keyNo);
        cv.put(COL_FL_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_FL_DATE, date);
        cv.put(COL_FL_TYPE, failType);
        cv.put(COL_FL_REASON, reason);
        cv.put(COL_FL_TIME, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_FAILURE_LOG, null, cv);
    }

    /** 查某天的失败记录，按时间倒序；分组同样取 key_entry 的当前值（LEFT JOIN） */
    public List<FailureLogItem> queryFailureLog(String date) {
        List<FailureLogItem> list = new ArrayList<>();
        // 关联 key_entry 表获取当前最新分组
        String sql = "SELECT fl.*, ke." + COL_KE_GROUP_NO + " as current_group " +
                "FROM " + TABLE_FAILURE_LOG + " fl " +
                "LEFT JOIN " + TABLE_KEY_ENTRY + " ke ON fl." + COL_FL_KEY_ID + " = ke." + COL_KE_ID + " " +
                "WHERE fl." + COL_FL_DATE + "=? " +
                "ORDER BY fl." + COL_FL_TIME + " DESC";
        Cursor cursor = getReadableDatabase().rawQuery(sql, new String[]{date});
        int groupNoIdx = cursor.getColumnIndex("current_group");
        while (cursor.moveToNext()) {
            FailureLogItem item = new FailureLogItem();
            item.keyId = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FL_KEY_ID));
            item.keyNo = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_KEY_NO));
            item.groupNo = groupNoIdx >= 0 ? cursor.getString(groupNoIdx) : "";
            if (item.groupNo == null) item.groupNo = "";
            item.date = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_DATE));
            item.failType = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_TYPE));
            item.failReason = cursor.getString(cursor.getColumnIndexOrThrow(COL_FL_REASON));
            item.timeMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FL_TIME));
            list.add(item);
        }
        cursor.close();
        return list;
    }

    // endregion

    // region 日志清理与重建

    /** 清空任务日志表（保留表结构） */
    public int deleteAllLogs() {
        return getWritableDatabase().delete(TABLE_LOG, null, null);
    }

    /**
     * 重建日志类三张表（保留分组、密钥与元数据）。
     *
     * <p>先删数据再 DROP 再 onCreate：事务保证三步要么全成要么全不成；
     * 结束后 VACUUM 回收文件空间（单独失败只记日志，不影响数据正确性）。</p>
     */
    public void recreateTablesExceptGroups() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_LOG, null, null);
            db.delete(TABLE_DAILY_STATUS, null, null);
            db.delete(TABLE_FAILURE_LOG, null, null);
            
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_LOG);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_DAILY_STATUS);
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_FAILURE_LOG);
            
            onCreate(db);
            
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        // 重建后回收空间，避免 db 文件只增不减
        try {
            db.execSQL("VACUUM");
        } catch (Exception e) {
            Log.w("AppLogDbHelper", "VACUUM 失败: " + e.getMessage());
        }
    }

    // endregion

    // region 密钥表（key_entry）

    /**
     * 插入一条私钥记录。
     *
     * <p>id 由调用方传入（来自 next_id），不用自增：迁移时需要保留原 id，
     * 而自增主键一旦手动写值会把序列推乱。返回 -1 就是主键冲突或写失败，调用方必须判。</p>
     */
    public long insertKeyEntry(int id, String customCode, String groupNo, String cipherText, String address, boolean enabled, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_ID, id);
        cv.put(COL_KE_CUSTOM_CODE, customCode != null ? customCode : "");
        cv.put(COL_KE_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_KE_CIPHER_TEXT, cipherText);
        cv.put(COL_KE_ADDRESS, address);
        cv.put(COL_KE_ENABLED, enabled ? 1 : 0);
        cv.put(COL_KE_SORT_ORDER, sortOrder);
        cv.put(COL_KE_CREATED, System.currentTimeMillis());
        return getWritableDatabase().insert(TABLE_KEY_ENTRY, null, cv);
    }

    /** 按 id 删私钥（不动排序值，剩下的空洞不影响展示） */
    public int deleteKeyEntry(int id) {
        return getWritableDatabase().delete(TABLE_KEY_ENTRY, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 整体更新编号/分组/启用（会同时覆盖 enabled，适合整表单保存） */
    public int updateKeyEntry(int id, String customCode, String groupNo, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_CUSTOM_CODE, customCode != null ? customCode : "");
        cv.put(COL_KE_GROUP_NO, groupNo != null ? groupNo : "");
        cv.put(COL_KE_ENABLED, enabled ? 1 : 0);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    /**
     * 只改启用状态：开关走这里，绝不碰 custom_code / group_no / cipher_text。
     *
     * @return 受影响行数，调用方据此判断是否落库成功（0 = 该行已不存在）
     */
    public int updateKeyEnabled(int id, boolean enabled) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_ENABLED, enabled ? 1 : 0);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 只改编号与分组：编辑弹窗保存走这里，不再回写 enabled 这种容易竞态的字段 */
    public int updateKeyEntryMeta(int id, String customCode, String groupNo) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_CUSTOM_CODE, customCode != null ? customCode : "");
        cv.put(COL_KE_GROUP_NO, groupNo != null ? groupNo : "");
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 分组重命名：一条 UPDATE 改完同组全部记录，替代“整表读改写” */
    public int updateKeyGroupNo(String oldGroup, String newGroup) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_GROUP_NO, newGroup != null ? newGroup : "");
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv,
                COL_KE_GROUP_NO + "=?", new String[]{oldGroup == null ? "" : oldGroup});
    }

    /** 只改排序值（拖拽落盘时逐条调用） */
    public int updateKeyEntrySortOrder(int id, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KE_SORT_ORDER, sortOrder);
        return getWritableDatabase().update(TABLE_KEY_ENTRY, cv, COL_KE_ID + "=?", new String[]{String.valueOf(id)});
    }

    /** 清空私钥表（自动清除/重置用；不动分组与元数据，后者由调用方一并重置） */
    public int clearAllKeyEntries() {
        return getWritableDatabase().delete(TABLE_KEY_ENTRY, null, null);
    }

    /** 地址是否已存在于私钥表（导入时查重） */
    public boolean keyEntryAddressExists(String address) {
        if (address == null) return false;
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_ENTRY,
                new String[]{COL_KE_ID}, COL_KE_ADDRESS + "=?", new String[]{address},
                null, null, null, "1");
        boolean exists = cursor.getCount() > 0;
        cursor.close();
        return exists;
    }

    /** 取全部 id 与排序值（供新增时计算最大排序号） */
    public List<int[]> queryKeyEntryIdAndSort() {
        List<int[]> list = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_ENTRY,
                new String[]{COL_KE_ID, COL_KE_SORT_ORDER},
                null, null, null, null, COL_KE_SORT_ORDER + " ASC");
        while (cursor.moveToNext()) {
            list.add(new int[]{
                    (int) cursor.getLong(cursor.getColumnIndexOrThrow(COL_KE_ID)),
                    cursor.getInt(cursor.getColumnIndexOrThrow(COL_KE_SORT_ORDER))
            });
        }
        cursor.close();
        return list;
    }

    /** 私钥条数（用于判断是否首次使用/是否需要导入） */
    public int getKeyEntryCount() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE_KEY_ENTRY, null);
        int count = 0;
        if (cursor.moveToFirst()) count = cursor.getInt(0);
        cursor.close();
        return count;
    }

    // endregion

    // region 元数据（key_meta）

    /** 取元数据值，不存在时返回默认值 */
    public String getMetaValue(String key, String defaultValue) {
        Cursor cursor = getReadableDatabase().query(TABLE_KEY_META,
                new String[]{COL_KM_VALUE}, COL_KM_KEY + "=?", new String[]{key},
                null, null, null, "1");
        String val = defaultValue;
        if (cursor.moveToFirst()) val = cursor.getString(cursor.getColumnIndexOrThrow(COL_KM_VALUE));
        cursor.close();
        return val;
    }

    /** 写元数据（同键覆盖）：next_id / master_account_id 都走这里 */
    public void putMetaValue(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KM_KEY, key);
        cv.put(COL_KM_VALUE, value);
        getWritableDatabase().insertWithOnConflict(TABLE_KEY_META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // endregion

    // region 按保留期删旧数据

    /** 删旧任务日志（按日志时间），返回删除条数 */
    public int deleteLogsOlderThan(long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        String whereClause = COL_TIME + " < ?";
        String[] whereArgs = {String.valueOf(timestamp)};
        int deletedRows = db.delete(TABLE_LOG, whereClause, whereArgs);
        Log.d("AppLogDbHelper", "删除了 " + deletedRows + " 条旧日志 (时间 < " + new Date(timestamp) + ")");
        return deletedRows;
    }

    /** 删旧当日状态（按更新时间而非日期列），返回删除条数 */
    public int deleteDailyStatusOlderThan(long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        String whereClause = COL_DS_UPDATED + " < ?";
        String[] whereArgs = {String.valueOf(timestamp)};
        int deletedRows = db.delete(TABLE_DAILY_STATUS, whereClause, whereArgs);
        Log.d("AppLogDbHelper", "删除了 " + deletedRows + " 条旧状态数据 (时间 < " + new Date(timestamp) + ")");
        return deletedRows;
    }

    /** 删旧失败日志（按失败时间），返回删除条数 */
    public int deleteFailureLogsOlderThan(long timestamp) {
        SQLiteDatabase db = getWritableDatabase();
        String whereClause = COL_FL_TIME + " < ?";
        String[] whereArgs = {String.valueOf(timestamp)};
        int deletedRows = db.delete(TABLE_FAILURE_LOG, whereClause, whereArgs);
        Log.d("AppLogDbHelper", "删除了 " + deletedRows + " 条旧失败日志 (时间 < " + new Date(timestamp) + ")");
        return deletedRows;
    }

    /**
     * 清空所有日志类数据（保留表结构，也保留 key_entry / key_group）。
     *
     * <p>三张表的删除在同一个事务里，不会出现“日志清了一半”；返回总删除行数。</p>
     */
    public int clearAllLogData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int logCount = db.delete(TABLE_LOG, null, null);
            int statusCount = db.delete(TABLE_DAILY_STATUS, null, null);
            int failCount = db.delete(TABLE_FAILURE_LOG, null, null);
            db.setTransactionSuccessful();
            Log.d("AppLogDbHelper", "清空全部数据: 日志=" + logCount + ", 状态=" + statusCount + ", 失败=" + failCount);
            return logCount + statusCount + failCount;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 调试专用：强制 checkpoint，合并 WAL 数据到主 db 文件。
     *
     * <p>用 TRUNCATE 模式：合并后清空 WAL 文件，便于直接把 .db 拷出来排查。
     * 不关闭连接，App 继续正常使用。返回值三个字段分别是 busy / wal 页数 / 已 checkpoint 页数。</p>
     */
    public void forceCheckpointForDebug() {
        SQLiteDatabase db = getWritableDatabase();
        // 使用 TRUNCATE 模式：合并 WAL 到主 db 后清空 WAL 文件
        Cursor cursor = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
        if (cursor.moveToFirst()) {
            int busy = cursor.getInt(0);
            int log = cursor.getInt(1);
            int checkpointed = cursor.getInt(2);
            Log.d("AppLogDbHelper", "调试 checkpoint: busy=" + busy + ", log=" + log + ", checkpointed=" + checkpointed);
        }
        cursor.close();
    }

    // endregion
}
