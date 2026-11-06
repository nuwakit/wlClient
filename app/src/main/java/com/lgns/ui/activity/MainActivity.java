package com.lgns.ui.activity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;

import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.contract.ArenaContractApi;
import com.lgns.manager.AppPrefs;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.batch.BatchFundTask;
import com.lgns.batch.BatchEnterTask;
import com.lgns.batch.BatchClaimTask;
import com.lgns.batch.BatchMintTask;
import com.lgns.batch.BatchWorkflowTask;
import com.lgns.ui.adapter.AccountStatsAdapter;
import com.lgns.manager.KeyStoreManager;
import com.lgns.helper.MainDialogHelper;
import com.lgns.helper.MainMenuHelper;
import com.lgns.helper.MainDataLoader;
import com.lgns.R;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.web3j.protocol.Web3j;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 主界面：私钥、分组与统计的总入口，同时是各类批量任务的调度中心。
 *
 * <p>三项职责：</p>
 * <ul>
 *   <li>触发批量入场、批量领取、批量铸造与完整流程，并按任务运行状态回落按钮可用性
 *       （批量充值入口已收进齿轮菜单，由完整流程第 1 步与菜单单独触发）；</li>
 *   <li>聚合同步链上统计（收益、场次等），同步期间用进度条与同步栏反馈进度；</li>
 *   <li>作为 {@link TaskLogCallback} 接收各任务日志，按账号归档后追加到页面。</li>
 * </ul>
 *
 * <p>线程约定：任务在后台线程执行，回调可能来自任意线程，所有 UI 写入统一走主线程；
 * 页面销毁后回调仍可能到达，写入前必须先判断 Activity 是否还存活，不能假定调用时机。</p>
 *
 * <p>统计口径以链上同步结果为准，本地只保留上一次快照用于首屏兜底，两者在界面上用同步状态区分。</p>
 */
public class MainActivity extends BaseActivity implements TaskLogCallback, BatchFundTask.ButtonStateController {

    // region 视图与数据

    /** 入场模式：能打就打（每个账号各自入座，不强求同轮） */
    public static final int MODE_FLEXIBLE = 0;
    /** 入场模式：等齐再打（整批预演通过后一起提交） */
    public static final int MODE_TOGETHER = 1;
    /** 当前生效的入场模式，启动时从 SharedPreferences 读出，切模式后写回 */
    private int currentPlayMode;
    /** 当前会话的加解密密码（明文 char[]）：只内存持有，页面销毁时必须清零 */
    public char[] tempUserCryptoKey;
    /**
     * 当前实例的弱引用：私钥管理页改完加解密密码后靠它同步会话密码。
     *
     * <p>用 {@link java.lang.ref.WeakReference} 而不是强引用，避免静态字段把页面
     * 钉在内存里；取出来用前必须判 null，空引用就是“页面已销毁”的信号。</p>
     */
    public static java.lang.ref.WeakReference<MainActivity> sInstance;
    /** 真实账号清单（启用/禁用、含主号），批量任务以它为准 */
    public List<KeyListItem> keyListItemList = new ArrayList<>();
    // 调试专用：地址池展开出的展示行（仅主界面/锦标赛统计用；keyId 为负、无密文，不参与解密和转账）
    private volatile List<KeyListItem> debugKeyItems;

    /** 自动清除定时任务的唯一名字：重建调度时用同一个名字覆盖，避免多份并行 */
    private static final String WORK_AUTO_CLEAR = "work_auto_clear_task";
    /** 自动清除间隔的下限（分钟）：比这更短只会让清理线程频繁起停，没有实际意义 */
    private static final long MIN_ALLOW_MINUTE = 15;
    /** 应用设置存储（入场模式等轻量开关） */
    private SharedPreferences spAppSetting;

    // 控件引用：按钮区
    private ImageButton btnSettingGear;
    private TextView btnSecurityWarn;
    /** 标题栏副标题：加载/同步/就绪/失败/运行中，与按钮门控同源 */
    private TextView tvStatusSub;
    private LinearLayout btnBatchEnter, btnBatchClaim, btnBatchMint, btnFullWorkflow;
    /** 四个可见批量按钮的运行态视图：运行中要拿「正在跑的那个」当停止键用 */
    private final List<TaskButton> taskButtons = new ArrayList<>(4);
    /**
     * 充值/入场/领取/铸造/完整流程五个任务的运行态标记。
     *
     * <p>tbFund 不再对应任何主界面控件（充值按钮已移除，入口只剩齿轮菜单→确认页→
     * {@code onActivityResult} 回传）：它只作 {@code activeTaskButton} 的标记用，
     * 不进 {@code taskButtons} 列表，因此充值期间四个可见按钮全部置灰、无就地停止出口。</p>
     */
    private TaskButton tbFund, tbEnter, tbClaim, tbMint, tbWorkflow;
    /** 当前正在跑的那个按钮：运行中只有它可点，点一下 = 打完这一圈再停 */
    private volatile TaskButton activeTaskButton;
    /** 本次运行是否已经按过停止：按下后按钮切「停止中」并禁用，避免连点刷日志 */
    private volatile boolean stopRequested = false;
    // 控件引用：日志区
    private TextView tvLog;
    private ScrollView scrollView;
    private LinearLayout layoutLogHeader;
    private TextView tvLogToggle;

    /** 弹窗助手：批量任务确认、密码输入、安全提示 */
    private MainDialogHelper dialogHelper;
    /** 齿轮菜单：各管理页与配置页的入口 */
    private MainMenuHelper menuHelper;

    // 控件引用：账号统计列表与汇总行
    private RecyclerView rvAccountList;
    private AccountStatsAdapter accountStatsAdapter;

    private TextView tvSummaryEnter, tvSummaryTodayWin, tvSummaryTotalWin, tvSummaryPrize;
    private TextView tvSummaryEnterLabel;
    // 汇总行下方的同步细进度条：批量查询期间显示，查完隐藏
    private ProgressBar pbStatsSync;

    private volatile boolean isTaskRunning = false;
    /**
     * 本页是否真的持有跨页闸门（{@code AppGlobal.acquireTask()} 的返回值）。
     *
     * <p>闸门是 CAS 占锁，会失败：点击时的两层门与 {@code beginTask} 之间仍有极窄的窗口，
     * 详情页单号任务可能在这期间抢先占住。此时任务照跑（密码已验、用户已确认，回滚更糟），
     * 但收尾就<b>不能</b>释放——那把锁是别人的，一放就把详情页任务与其它入口同时放开。
     * 所以单独记一个归属位，保证「谁抢到谁释放」。</p>
     */
    private volatile boolean taskGateOwned = false;
    // 本地账号清单是否已就绪：未就绪时禁止发起批量操作，避免拿半成品清单去跑任务
    private volatile boolean accountDataReady = false;
    // 链上统计状态：主界面上的余额/入场/冠军全是占位 0 时不允许点按钮，
    // 否则用户会据此误判（以为都没充值/都没入场）而重复操作
    private static final int STATS_IDLE = 0;      // 未就绪：等待首轮链上回填
    private static final int STATS_READY = 1;     // 已回填：数据可读
    private static final int STATS_FAILED = 2;    // 首轮失败：保持禁用，汇总栏点按重试
    private volatile int statsPhase = STATS_IDLE;
    // 统计请求序号：onResume/任务结束/本地变更都可能并发触发，只让最新一次结果上屏
    private final java.util.concurrent.atomic.AtomicInteger statsSeq =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 本次 onResume 跳过统计刷新：从子页面返回且数据由子页面回填时用，避免重复拉链 */
    private boolean skipRefreshOnResume = false;
    /** 本次 onResume 只重读本地清单、不拉链上统计：私钥增删改后只需重建行结构 */
    private boolean refreshLocalOnlyOnResume = false;
    /** 本次 onResume 只按最新分组重建展示、不重新拉链上数据：分组名/顺序变更后（地址集合与链上值都没变） */
    private boolean refreshGroupOnlyOnResume = false;
    /** 本次 onResume 需重拉一次链上统计：从账号详情页返回时用（详情页可能已就地入场/领取，数值变了） */
    private boolean refreshStatsFromDetailOnResume = false;
    /** 页面在后台时被判空闲上锁：先压住弹窗，等 onResume 再弹（后台不创建 Dialog） */
    private volatile boolean pendingIdleUnlock = false;

    // endregion

    // region 生命周期

    /**
     * 启动流程：先搭列表与汇总 UI → 置灰按钮 → 自动清理/空闲锁 → 后台初始化密钥管理器
     * → 回到 UI 线程加载本地清单（不触发 RPC）。
     *
     * <p>初始化失败也要把按钮放开（{@code statsPhase} 直接置 READY）：
     * 此时没有可查账号，用户点按钮会得到「无启用密钥」的明确提示，比永远置灰好定位问题。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 先记下 App Context：checkAutoClearBoot() 里的元数据操作跑在独立线程上，
        // 可能早于下面 initKeyManager 那个线程，缺了它只会拿到「应用未初始化」
        AppGlobal.setAppContext(getApplicationContext());
        sInstance = new java.lang.ref.WeakReference<>(this);
        setContentView(R.layout.activity_main);

        rvAccountList = findViewById(R.id.rv_account_list);

        tvSummaryEnter = findViewById(R.id.tv_summary_enter);
        tvSummaryEnterLabel = findViewById(R.id.tv_summary_enter_label);
        pbStatsSync = findViewById(R.id.pb_stats_sync);
        tvSummaryTodayWin = findViewById(R.id.tv_summary_today_win);
        tvSummaryTotalWin = findViewById(R.id.tv_summary_total_win);
        tvSummaryPrize = findViewById(R.id.tv_summary_prize);

        rvAccountList.setLayoutManager(new LinearLayoutManager(this));
        accountStatsAdapter = new AccountStatsAdapter();
        rvAccountList.setAdapter(accountStatsAdapter);
        // 点账号行→该地址详情页（入场/领取明细+就地操作）；调试地址池行无私钥，标为不可操作
        accountStatsAdapter.setOnAccountClickListener(this::openAccountDetail);

        spAppSetting = AppPrefs.get(this);
        currentPlayMode = spAppSetting.getInt("play_mode", MODE_FLEXIBLE);

        dialogHelper = new MainDialogHelper(this);
        menuHelper = new MainMenuHelper(this, spAppSetting);

        bindViews();
        setupButtonEvents();
        // 本地清单尚未加载：先把四个操作按钮置灰，加载完成（或加载失败）后再放开
        applyButtonEnabledState();
        setupGearMenu();
        checkAutoClearBoot();
        setupIdleLockCallback();

        // 注册 AppGlobal 的日志回调
        AppGlobal.setLogCallback(this);
        // 自动过程提示（定时清理等）只上屏、不落库
        AppGlobal.setUiLogCallback((keyNo, type, msg) -> appendUiLog(keyNo, msg));

        new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    // 初始化成功不再上屏：本地私钥加载属于后台准备，成功时对用户无实际意义
                    // 初始化只加载本地私钥列表，不触发 RPC
                    loadKeyListLocalOnly(this::applyLocalDefaults);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendUiLog(null, "错误：密钥管理器初始化失败:" + e.getMessage());
                    // 初始化失败也要放开按钮：此时没有可查账号，交给点击时的“无启用密钥”提示
                    statsPhase = STATS_READY;
                    accountDataReady = true;
                    applyButtonEnabledState();
                });
            }
        }).start();
    }

    // endregion

    // region 视图与事件绑定

    /** 集中取控件引用：只做 findViewById，不涉及状态与监听 */
    private void bindViews() {
        btnSettingGear = findViewById(R.id.btn_setting_gear);
        btnSecurityWarn = findViewById(R.id.btn_security_warn);
        tvStatusSub = findViewById(R.id.tv_status_sub);
        btnBatchEnter = findViewById(R.id.btn_batch_enter);
        btnBatchClaim = findViewById(R.id.btn_batch_claim);
        btnBatchMint = findViewById(R.id.btn_batch_mint);
        btnFullWorkflow = findViewById(R.id.btn_full_workflow);
        bindTaskButtons();
        tvLog = findViewById(R.id.tv_log);
        scrollView = findViewById(R.id.scrollView);
        layoutLogHeader = findViewById(R.id.layout_log_header);
        tvLogToggle = findViewById(R.id.tv_log_toggle);
    }

    /**
     * 把四个批量按钮包装成运行态视图：记下各自的文字控件、原始文案与原始配色。
     *
     * <p>运行中需要把其中一个临时换成「停止」键，跑完必须原样还原。原始配色直接取自
     * {@code getBackgroundTintList()}（即 layout 里的 {@code backgroundTint}），
     * 不在代码里再写一份色表——以后调按钮配色只改 XML 就够。</p>
     *
     * <p>{@code roundBoundary} 标记这个按钮背后的任务有没有「圈」的概念：入场与完整流程有，
     * 所以停必须停在圈与圈之间（停在圈内会把押金锁死）；充值/领取是一串独立账号交易，
     * 当前账号跑完就能停。</p>
     */
    private void bindTaskButtons() {
        taskButtons.clear();
        // 充值已无主界面按钮：只建无视图标记，保持 onActivityResult 回传链路的 beginTask 语义
        tbFund = new TaskButton(null, null, R.string.title_batch_fund, null, false);
        tbEnter = new TaskButton(btnBatchEnter, findViewById(R.id.tv_btn_enter_label),
                R.string.common_btn_batch_enter, btnBatchEnter.getBackgroundTintList(), true);
        tbClaim = new TaskButton(btnBatchClaim, findViewById(R.id.tv_btn_claim_label),
                R.string.common_btn_batch_claim, btnBatchClaim.getBackgroundTintList(), false);
        tbMint = new TaskButton(btnBatchMint, findViewById(R.id.tv_btn_mint_label),
                R.string.common_btn_batch_mint, btnBatchMint.getBackgroundTintList(), false);
        tbWorkflow = new TaskButton(btnFullWorkflow, findViewById(R.id.tv_btn_workflow_label),
                R.string.common_btn_full_workflow, btnFullWorkflow.getBackgroundTintList(), true);
        taskButtons.add(tbEnter);
        taskButtons.add(tbClaim);
        taskButtons.add(tbMint);
        taskButtons.add(tbWorkflow);
    }

    /**
     * 绑定四个批量按钮与日志区折叠。
     *
     * <p>日志区默认展开（scrollView 可见），点标题行切换；
     * 汇总栏在统计失败时兼作重试入口，其它状态下点按不产生副作用。</p>
     */
    private void setupButtonEvents() {
        btnSecurityWarn.setOnClickListener(v -> dialogHelper.showSecurityWarningDialog());

        layoutLogHeader.setOnClickListener(v -> {
            boolean visible = scrollView.getVisibility() == View.VISIBLE;
            scrollView.setVisibility(visible ? View.GONE : View.VISIBLE);
            tvLogToggle.setText(visible
                    ? getString(R.string.arrow_collapsed)
                    : getString(R.string.arrow_expanded));
        });
        scrollView.setVisibility(View.VISIBLE);

        // 四个按钮的统一入口：空闲时各自开活；运行中点「正在跑的那个」= 打完这圈再停
        btnBatchEnter.setOnClickListener(v -> onTaskButtonClick(this::handleBatchEnterClick));
        btnBatchClaim.setOnClickListener(v -> onTaskButtonClick(this::handleBatchClaimClick));
        btnBatchMint.setOnClickListener(v -> onTaskButtonClick(this::handleBatchMintClick));
        btnFullWorkflow.setOnClickListener(v -> onTaskButtonClick(this::handleFullWorkflowClick));

        // 统计加载失败时，汇总栏兼作重试入口（其他状态下点按无副作用）
        tvSummaryEnter.setOnClickListener(v -> {
            if (statsPhase == STATS_FAILED) loadStatsOverview();
        });

        // 副标题兼作解锁入口：会话过期后不必杀进程重开，点一下就地弹密码框。
        // 只在「有账号 + 没跑任务 + 确实已锁定」时响应，其余状态点按无副作用。
        tvStatusSub.setOnClickListener(v -> {
            if (!accountDataReady || isTaskRunning || keyListItemList.isEmpty()) return;
            if (!AppGlobal.isSessionLocked()) return;
            showIdleUnlockDialog();
        });
    }

    // endregion

    // region 账号详情跳转

    /**
     * 打开某地址的详情页：把展示行已有的地址/名称/分组传给详情页，行上已回填的待领值不重复传。
     *
     * <p>不经 Intent 传密文/私钥（敏感）：详情页自己按地址从 {@code KeyStoreManager} 查回真实密钥。
     * 调试地址池行（展示源是固定地址池）没有对应私钥，传 {@code operable=false}，详情页只展示不可操作。</p>
     */
    private void openAccountDetail(AccountStatsAdapter.AccountStatsItem item) {
        if (item == null || item.address == null || item.address.isEmpty()) return;
        boolean debugMode = ArenaConfig.DEBUG_FIXED_ADDR_ENABLE
                && debugKeyItems != null && !debugKeyItems.isEmpty();
        Intent intent = new Intent(this, AccountDetailActivity.class);
        intent.putExtra(AccountDetailActivity.EXTRA_ADDRESS, item.address);
        intent.putExtra(AccountDetailActivity.EXTRA_NAME, item.name);
        intent.putExtra(AccountDetailActivity.EXTRA_GROUP, item.groupNo);
        intent.putExtra(AccountDetailActivity.EXTRA_OPERABLE, !debugMode);
        // 详情页可能就地入场/领取，返回时必须按链上真实状态重拉，待领小标签才会更新
        refreshStatsFromDetailOnResume = true;
        startActivity(intent);
    }

    // endregion

    // region 批量任务入口

    /**
     * 批量铸造：与领取同一套外壳（互斥 → 就绪 → 启用账号 → 密码 → 后台执行），
     * 具体铸哪几枚、按什么批次铸由 {@link BatchMintTask} 决定。
     *
     * <p>铸造打的是独立 ChampionMandate NFT 合约，可铸清单只含「领讫且满 60 天」的俸禄，
     * 刚领完不会立刻多出一枚，所以点之前不必先跑领取。</p>
     */
    private void handleBatchMintClick() {
        // 两层门「本地先判、全局后判」：先看本页 isTaskRunning（快，且能提示“本页正在跑”），
        // 再靠短路求值判跨页闸门（详情页单号任务可能还在跑，本页看不到它，不判就会并发签名）
        if (isTaskRunning || AppGlobal.isTaskBusy()) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量铸造】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            beginTask(tbMint);
            new Thread(() -> {
                try {
                    BatchMintTask.batchMintAllResume(getApplicationContext(), this, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "MINT", "错误：批量铸造外层异常：" + e.getMessage()));
                    Log.e("BATCH_MINT", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(this::endTask);
                }
            }).start();
        });
    }

    /**
     * 批量入场：前置检查后弹解密密码，密码通过才锁按钮并开后台线程。
     *
     * <p>圈数取自 {@link ArenaConfig#enterRounds}，但<b>在任务启动时快照一次</b>再传下去：
     * 任务一跑起来齿轮就置灰了（见 {@link #applyButtonEnabledState()}），但仍要保证
     * 「点下去那一刻看到的配置」和「实际跑的圈数」严格一致，不能让中途改配置污染正在跑的这批。
     * 1 圈 = 每号打一场就停（出厂默认）；10 圈 = 一次点完当日满勤，想拿满师徒分成自己改到 10；
     * 最多 12 圈（合约硬上限）。
     * 连打时任务会在两圈之间等上一轮封盘——
     * 同一轮不能重复入座，而一轮要 24 座才封，账号数不够时任务会等到超时并提示。</p>
     *
     * <p>密码数组用完必须 {@code Arrays.fill} 清零；无论任务成功失败，
     * finally 都要把按钮放开，否则一次异常就会把界面永久锁死。</p>
     */
    private void handleBatchEnterClick() {
        // 两层门「本地先判、全局后判」：先看本页 isTaskRunning（快，且能提示“本页正在跑”），
        // 再靠短路求值判跨页闸门（详情页单号任务可能还在跑，本页看不到它，不判就会并发签名）
        if (isTaskRunning || AppGlobal.isTaskBusy()) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量入场】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            // 圈数在启动前读一次：之后配置页已被锁，但这个值也不再回读，避免“显示 12、实际打 1”
            final int rounds = ArenaConfig.enterRounds;
            beginTask(tbEnter);
            new Thread(() -> {
                try {
                    BatchEnterTask.batchEnterAllRoundsResume(getApplicationContext(), this, currentPlayMode,
                            rounds, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "ENTER", "批量入场外层异常：" + e.getMessage()));
                    Log.e("BATCH_ENTER", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(this::endTask);
                }
            }).start();
        });
    }

    /**
     * 批量领取：与入场同一套外壳（互斥 → 就绪 → 启用账号 → 密码 → 后台执行），
     * 具体领什么、按什么顺序领由 {@link BatchClaimTask} 决定。
     */
    private void handleBatchClaimClick() {
        // 两层门「本地先判、全局后判」：先看本页 isTaskRunning（快，且能提示“本页正在跑”），
        // 再靠短路求值判跨页闸门（详情页单号任务可能还在跑，本页看不到它，不判就会并发签名）
        if (isTaskRunning || AppGlobal.isTaskBusy()) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【批量领取】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            beginTask(tbClaim);
            new Thread(() -> {
                try {
                    BatchClaimTask.batchClaimAllResume(getApplicationContext(), this, currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "CLAIM", "错误：批量领取外层异常：" + e.getMessage()));
                    Log.e("BATCH_CLAIM", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(this::endTask);
                }
            }).start();
        });
    }

    /**
     * 完整工作流：充 fLGNS 门票 → 入场（按 {@link ArenaConfig#enterRounds} 圈）→ 领奖 → 铸造盟主令，一次跑完。
     *
     * <p>第 1 步的充值目标与圈数联动（{@link ArenaConfig#fundTargetFor(int)}）：补多少只看圈数，
     * 默认 1 圈时每号补到 100，12 圈时补到 500——冠军拿的是锁仓甲子、不会形成 claimable，
     * 只补一场门票的号第二圈就买不起票，缺一个座就让这一轮凑不满 24 座、后续圈全部取消。</p>
     *
     * <p>第 1 步<b>不含 DAI 手续费</b>：手续费不够的交易会直接失败，
     * 所以 DAI 必须先到「批量充值手续费」页面补足，再点这个流程。</p>
     *
     * <p>传入的 {@code 2} 是甲子领取的每号最大重试次数（甲子一页 64 条，序号多的号需要多笔），
     * 不是圈数——圈数在按下的那一刻快照一次，之后充值目标、预检、入场三处都用这个值。</p>
     *
     * <p>内含等待确认的固定延时，耗时比单独任务长，所以同样在后台线程执行、
     * 全程锁住四个批量按钮。</p>
     */
    private void handleFullWorkflowClick() {
        // 两层门「本地先判、全局后判」：先看本页 isTaskRunning（快，且能提示“本页正在跑”），
        // 再靠短路求值判跨页闸门（详情页单号任务可能还在跑，本页看不到它，不判就会并发签名）
        if (isTaskRunning || AppGlobal.isTaskBusy()) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ensureAccountDataReady()) return;
        List<KeyListItem> enabledList = getEnabledKeyItems();
        if (enabledList.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.no_enabled_key, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("UI_CLICK", "点击【完整工作流】按钮");
        // 密码弹窗阶段不锁按钮：取消或输错都要保持可点，密码校验通过后才进入任务锁定
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            // 圈数在启动前读一次，三个步骤（充值目标/预检/入场）共用，中途改配置影响不到这批
            final int rounds = ArenaConfig.enterRounds;
            beginTask(tbWorkflow);
            new Thread(() -> {
                try {
                    BatchWorkflowTask.fullArenaWorkflowResume(getApplicationContext(), this, 2, rounds,
                            currentPlayMode, enabledList, pwdCharArray);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "", "完整工作流外层异常：" + e.getMessage()));
                    Log.e("FULL_WORKFLOW", "异常", e);
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(this::endTask);
                }
            }).start();
        });
    }

    /** 任务开始/结束时统一改按钮可用性；真正的判定条件在 {@link #applyButtonEnabledState()} */
    private void setTaskButtonsEnabled(boolean enabled) {
        isTaskRunning = !enabled;
        applyButtonEnabledState();
    }

    /**
     * 任务开始：锁住四个按钮，只把「正在跑的那个」留成停止键。
     *
     * <p>先清两个请求标记：上一次的停止/取消标记若被带进来，任务一启动就会自行退出。</p>
     *
     * <p>齿轮菜单同时收起来：任务开始时还开着的菜单必须关掉，否则里面那些写操作项
     * 会停在「任务启动前」的可用态上（置灰是在菜单弹出时算的）。</p>
     */
    private void beginTask(TaskButton active) {
        // 占用跨页闸门：与详情页单号任务互斥（点击时已判过，这里兜住并发窗口）。
        // 必须记住是否抢到：收尾只释放自己抢到的锁
        taskGateOwned = AppGlobal.acquireTask();
        if (!taskGateOwned) {
            appendUiLog(null, "⚠ 跨页闸门正被另一入口占用，本次任务未取得互斥，收尾不会释放该锁");
        }
        activeTaskButton = active;
        stopRequested = false;
        Web3AndroidUtil.resetStop();
        Web3AndroidUtil.resetCancel();
        menuHelper.dismissPopup();
        setTaskButtonsEnabled(false);
    }

    /**
     * 任务收尾：清掉停止态，按钮回各自的文案与配色。
     *
     * <p>两个标记都要清：{@code beginTask} 里也会清，但它只在「开新任务」时执行；
     * 上一次「立即取消」的标记若残留，下一次任务（无论走哪个入口）会一开头就自行退出。
     * 收尾时线程已经结束，清掉不会漏响应。</p>
     */
    private void endTask() {
        activeTaskButton = null;
        stopRequested = false;
        Web3AndroidUtil.resetStop();
        Web3AndroidUtil.resetCancel();
        setTaskButtonsEnabled(true);
        // 任务收尾必须放闸门，否则详情页之后所有操作都会被拒绝；只放自己抢到的那把（见 taskGateOwned）
        if (taskGateOwned) {
            taskGateOwned = false;
            AppGlobal.releaseTask();
        }
    }

    /**
     * 密码框显示期间，主界面一律不吃触摸。
     *
     * <p>弹窗自己的手势走的是它自己的窗口，根本不会经过这里；能走到这里的只有“穿透”下来的那一批：
     * 弹窗窗口首帧还没注册好触摸区域时，系统会把第二击又派发回底下的 Activity
     * （logcat 里因此能连出两条「点击【xx】按钮」，并建出第二个 EditText）。
     * 在这一层直接吞掉，比去每个点击入口重复判一遍更彻底：四个按钮、齿轮菜单、汇总栏、安全提醒一并覆盖。</p>
     *
     * <p>看的是“有没有任何模态弹窗在显示”（{@code isModalDialogShowing}），不是只看密码框：
     * 安全提醒、日志这类弹窗同样存在首帧未就绪的空档。</p>
     *
     * <p>整串吞是安全的：对应的 DOWN 已经吞掉了，后续 MOVE/UP 到达时也没有控件能把它拼成一次点击。</p>
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (dialogHelper != null && dialogHelper.isModalDialogShowing()) return true;
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 四个批量按钮的唯一入口：空闲时开任务，运行中则转成「停止」请求。
     *
     * <p>运行中四个按钮里只有正在跑的那个还是 {@code enabled}（见
     * {@link #applyButtonEnabledState()}），所以「停止」分支实际只会被它触发。</p>
     *
     * <p>第三道门是弹窗：弹窗刚弹出来的头一两百毫秒里，它的窗口首帧还没走完 layout，
     * 那一段里点在底下的第二击会直接落到这里（logcat 里能连续看到两条「点击【xx】按钮」）。
     * 不挡就会重建一个密码框，所以有弹窗盖着时把这一击直接丢掉——用户想接着用，
     * 就把已经弹出来的那个填完，不用重新点按钮。</p>
     */
    private void onTaskButtonClick(Runnable startTask) {
        if (isTaskRunning) {
            requestStop();
            return;
        }
        if (dialogHelper.isModalDialogShowing()) return;
        startTask.run();
    }

    /**
     * 「就停在这里」：按任务性质分两种停法。
     *
     * <p>入场与完整流程带「圈」——置 {@link Web3AndroidUtil#requestStopAfterRound()} 标记，
     * 正在入场时当前这一圈会跑完、凑满 {@code SEATS_PER_ROUND} 座封盘后才退出。原因是合约没有
     * 「轮次超时关闭」：{@code enterArena} 只在座满时结算，停在半轮里，已入座那些号的
     * 100 fLGNS 会一直挂在 {@code inMatchDeposit} 上，既不结算也退不回。</p>
     *
     * <p>同一个标记在<b>完整流程的第 1 步（充值）</b>里是「立刻停」：那时还没人入座，
     * 不存在半轮锁仓的风险，{@link BatchFundTask#executeAutoFund} 按 {@code isStopRequested()}
     * 在当前这笔转账结束后就跳出，随后跳过入场直接领取。</p>
     *
     * <p>充值/领取是一串互不依赖的账号交易，没有押金风险，走立即取消（同样是当前账号
     * 处理完就跳出，不会留下半笔交易）。</p>
     */
    private void requestStop() {
        if (!isTaskRunning || stopRequested || activeTaskButton == null) return;
        stopRequested = true;
        if (activeTaskButton.roundBoundary) {
            Web3AndroidUtil.requestStopAfterRound();
            appendUiLog(null, "⏸ 已请求停止：还在充值就当前这笔转完即停（随后跳过入场）；已开始入场则打完这一圈封盘再停");
        } else {
            Web3AndroidUtil.requestCancel();
            appendUiLog(null, "⏸ 已请求停止：当前账号处理完就停，已上链的交易不回滚");
        }
        applyButtonEnabledState();
    }

    /**
     * 按钮可用 = 本地清单已就绪 && 首轮链上统计已回填 && 当前没有任务在跑。
     * 关键在第二项：统计没回来之前，界面上的余额/入场/冠军全是占位 0，
     * 与真实的 0 无法区分，此时放行等于让用户基于错数据去点批量充值/入场。
     *
     * <p>运行中是唯一例外：正在跑的那个按钮要保持可点（红底「停止」），
     * 它是用户在长任务里唯一的出口；已经按过停止就一并置灰，避免连点。</p>
     *
     * <p>齿轮不在这里管：任务跑着时齿轮仍然可点，但菜单里的写操作项由
     * {@code MainMenuHelper} 逐项置灰，只留「看日志」「锦标赛统计」两个只读入口 ——
     * 连打十来圈的途中查日志是刚需，不该逼用户先停任务。</p>
     */
    private void applyButtonEnabledState() {
        runOnUiThread(() -> {
            boolean ready = accountDataReady && statsPhase == STATS_READY;
            boolean idle = ready && !isTaskRunning;
            for (TaskButton b : taskButtons) {
                boolean stopKey = isTaskRunning && b == activeTaskButton;
                boolean clickable = idle || (stopKey && !stopRequested);
                b.layout.setEnabled(clickable);
                b.layout.setAlpha(clickable ? 1.0f : 0.5f);
                if (stopKey) {
                    b.label.setText(stopRequested
                            ? R.string.common_btn_stopping : R.string.common_btn_stop_after_round);
                    b.layout.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(getColor(R.color.btn_stop)));
                } else {
                    b.label.setText(b.labelRes);
                    b.layout.setBackgroundTintList(b.tint);
                }
            }
            refreshStatusSubtitle();
        });
    }

    /**
     * 批量按钮的运行态视图：布局、文字控件、原始文案资源、原始背景色，
     * 以及「这个按钮背后的任务有没有圈边界」。
     */
    private static final class TaskButton {
        final LinearLayout layout;
        final TextView label;
        final int labelRes;
        final android.content.res.ColorStateList tint;
        /** true = 任务按圈跑，必须停在圈边界；false = 可直接取消 */
        final boolean roundBoundary;

        TaskButton(LinearLayout layout, TextView label, int labelRes,
                   android.content.res.ColorStateList tint, boolean roundBoundary) {
            this.layout = layout;
            this.label = label;
            this.labelRes = labelRes;
            this.tint = tint;
            this.roundBoundary = roundBoundary;
        }
    }

    /**
     * 刷新标题栏副标题。
     *
     * <p>六种状态与按钮门控同源（{@code accountDataReady} / {@code statsPhase} /
     * {@code isTaskRunning} / 会话是否锁定），所以主要在 {@link #applyButtonEnabledState()}
     * 里调用：任何一个状态变化都会走到那里，标题栏文案因此不会和「按钮能不能点」打架。</p>
     *
     * <p>判定顺序有讲究——任务运行中优先于统计态：任务跑起来时按钮被锁，
     * 此时提示「运行中」比提示「已就绪」更贴近用户看到的现象。会话锁定排在「无账号」之后：
     * 列表为空时本来就没有可解密的东西，提示解锁只会误导。</p>
     *
     * <p>「已锁定」这一支同时换琥珀色：这是唯一需要用户就地动手的状态，
     * 光靠一段小字很容易被当成普通提示略过。</p>
     */
    private void refreshStatusSubtitle() {
        if (tvStatusSub == null) return;
        // 有账号、没跑任务、会话为空 → 需要用户输密码；三者缺一都不该提示解锁
        boolean locked = !isTaskRunning && !keyListItemList.isEmpty() && AppGlobal.isSessionLocked();
        String text;
        if (!accountDataReady) {
            text = getString(R.string.main_status_loading);
        } else if (isTaskRunning) {
            text = getString(R.string.main_status_running);
        } else if (keyListItemList.isEmpty()) {
            text = getString(R.string.main_status_empty);
        } else if (locked) {
            text = getString(R.string.main_status_locked);
        } else if (statsPhase == STATS_FAILED) {
            text = getString(R.string.main_status_failed);
        } else if (statsPhase == STATS_IDLE) {
            text = getString(R.string.main_status_syncing);
        } else {
            text = getString(R.string.main_status_ready,
                    getEnabledKeyItems().size(), keyListItemList.size());
        }
        tvStatusSub.setText(text);
        tvStatusSub.setTextColor(getColor(locked ? R.color.status_locked : R.color.status_normal));
    }

    // 清单/统计未就绪时点击（按钮已置灰，此处兼顾无障碍/代码路径）：明确提示，不拿半成品数据跑任务
    private boolean ensureAccountDataReady() {
        if (!accountDataReady) {
            android.widget.Toast.makeText(this, R.string.account_loading_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        if (statsPhase == STATS_FAILED) {
            android.widget.Toast.makeText(this, R.string.stats_load_failed_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        if (statsPhase != STATS_READY) {
            android.widget.Toast.makeText(this, R.string.account_loading_hint,
                    android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    // endregion

    // region 溢出菜单（⋮）与自动清理

    /** 溢出菜单（⋮）：点开右上角折叠菜单（设置、统计、各种管理页的入口都在这） */
    private void setupGearMenu() {
        btnSettingGear.setOnClickListener(v -> menuHelper.showCollapseMenu(v));
    }

    /**
     * 启动时的自动清理检查：私钥清除与日志清除各管各的开关与时间戳。
     *
     * <p>机制是「懒执行」：没有常驻定时器，靠启动时比对上次执行时间戳来判断是否到期，
     * 所以 App 没打开期间不会清，下次打开时补齐一次。</p>
     *
     * <p>日志清除只删早于保留时长的旧记录（不是清空），且删到 0 条时不写日志，
     * 避免每次启动都往日志区刷一行无用记录。</p>
     */
    private void checkAutoClearBoot() {
        ArenaConfig.loadConfig(this);
        SharedPreferences spTs = AppPrefs.get(this);
        long now = System.currentTimeMillis();

        // 检查私钥自动清除
        if (ArenaConfig.autoClearEnable) {
            long lastClearTs = spTs.getLong("last_clear_timestamp", 0);
            long clearIntervalMs = getClearIntervalMs(ArenaConfig.autoClearIntervalValue, ArenaConfig.autoClearIntervalUnitIndex);

            if (now - lastClearTs >= clearIntervalMs) {
                new Thread(() -> {
                    try {
                        // 清空私钥是纯数据库操作，不需要密码；走会话门的话，
                        // 冷启动时 initKeyManager 还在另一个线程里跑，这里必然抛异常，自动清除等于失效
                        KeyStoreManager mgr = AppGlobal.getMetaManager();
                        mgr.clearAllKeys();
                        spTs.edit().putLong("last_clear_timestamp", now).apply();
                        runOnUiThread(() -> appendUiLog(null, "警告：检测到自动清除计时到期，打开 APP 自动清空全部私钥！"));
                    } catch (Exception ignored) {
                    }
                }).start();
            }
        }

        // 检查日志自动清理（独立开关：只删除早于保留时长的旧记录）
        if (ArenaConfig.logClearEnable) {
            long lastLogClearTs = spTs.getLong("last_log_clear_timestamp", 0);
            long logClearIntervalMs = getClearIntervalMs(ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);

            if (now - lastLogClearTs >= logClearIntervalMs) {
                new Thread(() -> {
                    try {
                        long cutoff = now - logClearIntervalMs;
                        AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
                        int deletedCount = dbHelper.deleteLogsOlderThan(cutoff)
                                + dbHelper.deleteDailyStatusOlderThan(cutoff)
                                + dbHelper.deleteFailureLogsOlderThan(cutoff);
                        spTs.edit().putLong("last_log_clear_timestamp", now).apply();
                        // 没删到东西就不刷屏：启动即校验，每次都写一行会把日志区冲满
                        if (deletedCount > 0) {
                            runOnUiThread(() -> appendUiLog(null, "日志清理到期，已清理 " + deletedCount + " 条旧记录"));
                        }
                    } catch (Exception ignored) {
                    }
                }).start();
            }

            // 启动定时清理日志调度器（App 运行期间按间隔删除旧记录）
            long logClearMinutes = AppGlobal.getIntervalInMinutes(
                    ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
            AppGlobal.startLogClearScheduler(logClearMinutes);
        } else {
            // 开关关闭：停掉运行中的日志清理线程
            AppGlobal.stopLogClearScheduler();
        }
    }

    /** 单位索引转毫秒（0 分钟 / 1 小时 / 2 天）；索引异常时按小时处理 */
    private long getClearIntervalMs(int value, int unitIndex) {
        switch (unitIndex) {
            case 0:
                return java.util.concurrent.TimeUnit.MINUTES.toMillis(value);
            case 1:
                return java.util.concurrent.TimeUnit.HOURS.toMillis(value);
            case 2:
                return java.util.concurrent.TimeUnit.DAYS.toMillis(value);
            default:
                return java.util.concurrent.TimeUnit.HOURS.toMillis(value);
        }
    }

    /**
     * 会话空闲超时回调：提示并弹解密弹窗重新解锁。
     *
     * <p>三处刻意的写法：</p>
     * <ul>
     *   <li>{@code onIdleLock} 是 {@link AppGlobal} 上的静态字段，直接捕获 this 会让页面被静态引用钉在内存里；
     *   这里只持有弱引用，页面已回收就静默丢弃；</li>
     *   <li>回调触发时页面很可能在后台（App 挂着但人不在），此时不立刻 show：
     *   在不可见的窗口上创建 Dialog 既白做也容易撞 {@code BadTokenException}，
     *   所以先记 pending，等 onResume 再弹；</li>
     *   <li>弹之前再判一次 isFinishing/isDestroyed，兜住「回调到达时页面正好在销毁」。</li>
     * </ul>
     */
    private void setupIdleLockCallback() {
        final java.lang.ref.WeakReference<MainActivity> ref = new java.lang.ref.WeakReference<>(this);
        AppGlobal.setOnIdleLock(() -> {
            MainActivity act = ref.get();
            if (act != null) act.onIdleLocked();
        });
    }

    /** 空闲上锁回调的落地：不保证在主线程，也不保证页面在前台 */
    private void onIdleLocked() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            appendUiLog(null, "会话已过期 · 点顶部状态或任一操作按钮输入密码即可，不用重启");
            // 副标题立刻切成琥珀色「已锁定」：这是用户能看见的就地解锁入口
            refreshStatusSubtitle();
            if (!isForeground()) {
                // 人不在屏幕前：只记标记，回前台再弹，避免在后台创建 Dialog
                pendingIdleUnlock = true;
                return;
            }
            showIdleUnlockDialog();
        });
    }

    /** 弹解密框恢复会话：密码通过后重建密钥管理器、刷新清单并重算按钮门控 */
    private void showIdleUnlockDialog() {
        pendingIdleUnlock = false;
        if (isFinishing() || isDestroyed()) return;
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> new Thread(() -> {
            try {
                AppGlobal.initKeyManager(getApplicationContext());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    appendUiLog(null, "密码验证成功，会话已恢复");
                    refreshCipherKeyList();
                    // 副标题与按钮门控同源，会话变化后重算一次
                    applyButtonEnabledState();
                });
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("", "", "错误：密码验证失败：" + e.getMessage()));
            } finally {
                Arrays.fill(pwdCharArray, (char) 0);
            }
        }).start());
    }

    // endregion

    // region 对外数据接口

    /** 取本地清单中已启用的账号（批量任务的输入） */
    public List<KeyListItem> getEnabledKeyItems() {
        List<KeyListItem> enabled = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.enabled) enabled.add(item);
        }
        return enabled;
    }

    /** 取勾选中的账号；调试模式固定地址池优先，保证统计与界面地址一致 */
    public List<KeyListItem> getSelectedKeyItem() {
        // 调试模式：锦标赛统计按地址池展示行出数，与主界面看到的地址一致
        List<KeyListItem> debug = debugKeyItems;
        if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && debug != null && !debug.isEmpty()) {
            return new ArrayList<>(debug);
        }
        List<KeyListItem> res = new ArrayList<>();
        for (KeyListItem item : keyListItemList) {
            if (item.isChecked) res.add(item);
        }
        return res;
    }

    /** 读分组名列表（逗号串存储，空项忽略）；与分组表数据库是两份数据，供旧逻辑兼容 */
    public List<String> loadGroupList() {
        String raw = spAppSetting.getString("group_list", "");
        List<String> list = new ArrayList<>();
        if (!raw.isEmpty()) {
            for (String s : raw.split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) list.add(t);
            }
        }
        return list;
    }

    /** 写分组名列表：拼成逗号串存入本地 SP */
    public void saveGroupList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        spAppSetting.edit().putString("group_list", sb.toString()).apply();
    }

    /** 刷新私钥清单（含地址等耗时工作），具体逻辑在 MainDataLoader */
    public void refreshCipherKeyList() {
        MainDataLoader.refreshCipherKeyList(this, this, keyListItemList);
    }

    // endregion

    // region 日志与运行状态

    /**
     * 任务日志回调：先上屏，再在子线程落库。
     *
     * <p>只有用户主动触发的操作（批量任务、密钥增删等）才走这里；
     * 启动初始化、定时清理这类后台过程日志走 {@link #appendUiLog}，不写库，
     * 否则数据库会被过程日志迅速撞满。</p>
     *
     * @param keyNo 密钥编号，空串/空白视为无归属
     * @param type  日志类型，空串/空白视为无类型
     */
    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final long timeMs = System.currentTimeMillis();
        appendLogToUi(safeKeyNo, msg, timeMs);
        // 只有用户主动触发的操作（批量任务、密钥增删等）才落库
        new Thread(() -> AppLogDbHelper.getInstance(this).insertLog(safeKeyNo, safeType, msg, timeMs)).start();
    }

    /**
     * 只上屏不落库的日志：用于启动初始化、定时清理、自动刷新这类非用户点击触发的过程信息。
     *
     * @param keyNo 密钥编号，空串/空白视为无归属
     */
    public void appendUiLog(String keyNo, String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        appendLogToUi(safeKeyNo, msg, System.currentTimeMillis());
    }

    private void appendLogToUi(final String safeKeyNo, final String msg, final long timeMs) {
        runOnUiThread(() -> {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy‑MM‑dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) sb.append("[").append(safeKeyNo).append("]");
            sb.append(msg);
            tvLog.setText(tvLog.getText() + sb.toString() + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    /**
     * 写一条带类型、会落库的运行日志（等价于 {@link #onLog}）。
     *
     * <p>保留这个别名是因为任务层统一用 {@code appendLog} 这个叫法，两边不要各写一套。</p>
     */
    public void appendLog(String keyNo, String type, String msg) {
        onLog(keyNo, type, msg);
    }

    /** 密码输入回调：pwd 为明文 char[]，回调方用完后必须自行清零 */
    public interface PasswordCallback {
        /** @param pwd 用户输入的明文密码，调用方负责 Arrays.fill 清零 */
        void onInputPwd(char[] pwd);
    }

    /** 本地清单构好后的回调：items 为后台线程提前拼好的展示行（链上数值此时全是占位） */
    public interface StatsReadyCallback {
        /** @param items 可直接丢给适配器渲染的列表，已含分组头 */
        void onReady(List<AccountStatsAdapter.DisplayItem> items);
    }

    /** 弹窗助手：批量任务、密码输入、安全提示都由它统一弹出 */
    public MainDialogHelper getDialogHelper() {
        return dialogHelper;
    }

    /**
     * 是否有批量任务正在跑：任务层靠它拦住重复启动，齿轮菜单也靠它决定
     * 要不要把写操作项置灰（见 {@code MainMenuHelper#applyTaskRunningLock}）。
     */
    @Override
    public boolean isRunning() {
        return isTaskRunning;
    }

    /**
     * 标记任务开始/结束：只切按钮可用性（{@code setTaskButtonsEnabled}），
     * 真正的运行标记由任务层自己维护，避免两边状态不同步。
     */
    @Override
    public void setRunning(boolean running) {
        setTaskButtonsEnabled(!running);
    }

    // endregion

    // region 页面回调与结果回填

    /** 退出时收起菜单、关掉残留弹窗并清空会话密码，同时注销单例引用与静态回调 */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        menuHelper.dismissPopup();
        // 弹窗不主动 dismiss 会留下 WindowLeaked 警告，且销毁后状态不好回收
        if (dialogHelper != null) dialogHelper.dismissModalDialogs();
        if (tempUserCryptoKey != null) {
            Arrays.fill(tempUserCryptoKey, (char) 0);
            tempUserCryptoKey = null;
        }
        // 静态回调不能留着指向本页面：页面销毁后空闲线程仍会调，轻则泄漏、重则撞 BadTokenException
        AppGlobal.setOnIdleLock(null);
        pendingIdleUnlock = false;
        if (sInstance != null && sInstance.get() == this) sInstance = null;
    }

    /**
     * 回到前台：每次都跑一次自动清理检查，再按「本次从哪个页返回」决定刷新的粒度——
     * 系统设置页返回跳过刷新，私钥类页面返回只重读本地（不拉链），其余情况不刷新。
     */
    @Override
    protected void onResume() {
        super.onResume();
        checkAutoClearBoot();

        if (refreshStatsFromDetailOnResume) {
            // 从账号详情返回：详情页可能已就地入场/领取，按链上真实状态重拉，待领小标签随之更新
            refreshStatsFromDetailOnResume = false;
            loadStatsOverview();
            Log.d("MainActivity", "onResume: reload stats from account detail");
        } else if (skipRefreshOnResume) {
            skipRefreshOnResume = false;
            Log.d("MainActivity", "onResume: skip refresh from sys config");
        } else if (refreshLocalOnlyOnResume) {
            refreshLocalOnlyOnResume = false;
            loadKeyListLocalOnly(this::applyLocalDefaults);
            Log.d("MainActivity", "onResume: refresh local only from key manage");
        } else if (refreshGroupOnlyOnResume) {
            refreshGroupOnlyOnResume = false;
            // 分组/编号等本地变更只改标签，沿用已有链上值不再发 RPC；
            // 但首屏链上数据还没回来（STATS_IDLE）时必须正常重载，否则数字会永久停在占位
            if (statsPhase == STATS_IDLE) {
                loadKeyListLocalOnly(this::applyLocalDefaults);
                Log.d("MainActivity", "onResume: stats not ready, full reload");
            } else {
                loadKeyListLocalOnly(items -> applyLocalDefaults(items, false));
                Log.d("MainActivity", "onResume: regroup local only from group manage (no chain)");
            }
        } else {
            // 其他情况（如从日志查看返回），不刷新
            Log.d("MainActivity", "onResume: no refresh");
        }

        // 离开期间被判空闲上锁的：回到前台再补弹，后台那会儿不该创建 Dialog
        if (pendingIdleUnlock) showIdleUnlockDialog();
        // 子页面可能已就地解锁（改密、批量充值页验密）：副标题要从「已锁定」切回来
        else refreshStatusSubtitle();
    }

    /**
     * 标记：下一次 onResume 跳过统计刷新（子页已经回填好数据时用）。
     *
     * <p>三个刷新标志互斥：只保留最后一次设置。否则从菜单进页时预置了
     * {@code refreshLocalOnlyOnResume}，子页 {@code onPause} 再选“不拉网络”也盖不住旧标志。</p>
     */
    public void setSkipRefreshOnResume() {
        skipRefreshOnResume = true;
        refreshLocalOnlyOnResume = false;
        refreshGroupOnlyOnResume = false;
    }

    /** 标记：下一次 onResume 只重读本地清单并重拉链上统计（私钥增删/启用开关/批量交易后） */
    public void setRefreshLocalOnlyOnResume() {
        refreshLocalOnlyOnResume = true;
        skipRefreshOnResume = false;
        refreshGroupOnlyOnResume = false;
    }

    /** 标记：下一次 onResume 只重新分组、不拉链上统计（分组名/编号/排序/主号等纯本地变更） */
    public void setRefreshGroupOnlyOnResume() {
        refreshGroupOnlyOnResume = true;
        skipRefreshOnResume = false;
        refreshLocalOnlyOnResume = false;
    }

    /** 批量任务结束回调：无论成败都按链上真实状态重拉一次统计 */
    @Override
    public void onBatchResult(java.util.Map<String, ArenaContractApi.PlayerSummary> summaries) {
        // 批量任务结束后直接用链上最新数据刷新账号列表
        loadStatsOverview();
    }

    /**
     * 单账号失败留档：按编号反查分组名，写一条失败记录供失败日志页展示。
     *
     * <p>纯记录行为，不弹提示也不改列表状态（失败提醒由任务自身写运行日志完成），
     * 写库失败直接忽略，不能因为归档出问题影响主流程。</p>
     */
    @Override
    public void onKeyFailure(String keyNo, String failType, String reason) {
        new Thread(() -> {
            try {
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                // 查找对应的 groupNo
                String groupNo = "";
                for (KeyListItem item : keyListItemList) {
                    String no = (item.customCode != null && !item.customCode.isEmpty())
                            ? item.customCode : item.displayNo;
                    if (no.equals(keyNo)) {
                        groupNo = item.groupNo != null ? item.groupNo : "";
                        break;
                    }
                }
                // 失败留档（日志中亦有记录），不再驱动任何 UI
                AppLogDbHelper.getInstance(this).insertFailure(0, keyNo, groupNo, today, failType, reason);
            } catch (Exception ignored) {}
        }).start();
    }

    // endregion

    // region 统计与同步

    // 列表里的有效地址数（调试模式=地址池行数）：今日入场分母 = 地址数 × 12
    private static int countAddresses(List<KeyListItem> items) {
        int n = 0;
        for (KeyListItem it : items) {
            if (it != null && it.address != null && !it.address.isEmpty()) n++;
        }
        return n;
    }

    // 私钥管理变更/冷启动回调：数据库最新结构立即上屏；
    // 未变更地址继承已加载的链上数值（避免归零闪烁），新增地址零值兜底；汇总栏按合并值重算；随后链上异步回填
    private void applyLocalDefaults(List<AccountStatsAdapter.DisplayItem> items) {
        applyLocalDefaults(items, true);
    }

    private void applyLocalDefaults(List<AccountStatsAdapter.DisplayItem> items, boolean repullChain) {
        Map<String, AccountStatsAdapter.AccountStatsItem> oldMap = accountStatsAdapter.snapshotAccountItems();
        int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0, knownToday = 0;
        BigInteger sumEarnings = BigInteger.ZERO;
        for (AccountStatsAdapter.DisplayItem d : items) {
            AccountStatsAdapter.AccountStatsItem item = d.account;
            if (item == null) continue;
            AccountStatsAdapter.AccountStatsItem old = oldMap.get(item.address);
            if (old != null) {
                item.balance = old.balance;
                item.entriesToday = old.entriesToday;
                item.entriesRemaining = old.entriesRemaining;
                item.todayChampion = old.todayChampion;
                item.totalChampion = old.totalChampion;
                item.prize = old.prize;
                item.totalEarnings = old.totalEarnings;
                item.jiaziClaimable = old.jiaziClaimable;
                item.arenaClaimable = old.arenaClaimable;
                item.lineagePending = old.lineagePending;
                item.mintableCount = old.mintableCount;
                item.mintedCount = old.mintedCount;
                item.pending = old.pending;
                item.unknown = old.unknown;
            } else {
                // 从未查过的地址：零值只是占位，标成“同步中”（不是查不到），别当成真实的 0
                item.pending = true;
            }
            sumEnter += item.entriesToday;
            // 未就绪/未查到的行不参与今日冠军合计：它们带着占位值或 -1，累加会把总计算错
            if (!item.pending && !item.unknown && item.todayChampion >= 0) {
                sumTodayWin += item.todayChampion;
                knownToday++;
            }
            sumTotalWin += item.totalChampion;
            if (item.totalEarnings != null) sumEarnings = sumEarnings.add(item.totalEarnings);
        }
        accountStatsAdapter.setItems(items);
        int addrCount = countAddresses(getDisplayKeyItems());
        // 标签里直接写出地址数：分母是“地址数 × 12”，不写出来很容易被误读
        tvSummaryEnterLabel.setText(getString(R.string.summary_enter_label_fmt, addrCount));
        // 分组变更不重拉链上：行值已全部继承自旧数据，直接按就绪态展示，不走占位符（失败态保留，不当成已加载）
        if (!repullChain && statsPhase != STATS_FAILED) statsPhase = STATS_READY;
        if (statsPhase == STATS_READY) {
            tvSummaryEnter.setText(sumEnter + "/" + (addrCount * 12));
            tvSummaryTodayWin.setText((addrCount > 0 && knownToday == 0)
                    ? getString(R.string.common_placeholder) : String.valueOf(sumTodayWin));
            tvSummaryTotalWin.setText(String.valueOf(sumTotalWin));
            tvSummaryPrize.setText(formatToken(sumEarnings));
        } else if (statsPhase == STATS_FAILED) {
            showStatsUnavailable();
        } else {
            // 首轮链上统计还没回来：数值位一律占位（进度由汇总行下方的细进度条表达），
            // 别把占位 0 当成真实合计
            tvSummaryEnter.setText(R.string.common_placeholder);
            tvSummaryTodayWin.setText(R.string.common_placeholder);
            tvSummaryTotalWin.setText(R.string.common_placeholder);
            tvSummaryPrize.setText(R.string.common_placeholder);
        }
        if (repullChain) {
            loadStatsOverview();
        } else {
            // 分组变更无需重拉：沿用继承来的链上值，落到就绪态并撤掉同步条、重算按钮可用性
            showSyncBar(false);
            applyButtonEnabledState();
        }
    }

    // 统计不可用：汇总栏改成提示语兼重试入口，避免用户把占位 0 当真实数据
    private void showStatsUnavailable() {
        showSyncBar(false);
        tvSummaryEnter.setText(R.string.stats_load_failed_retry);
        tvSummaryTodayWin.setText(R.string.common_placeholder);
        tvSummaryTotalWin.setText(R.string.common_placeholder);
        tvSummaryPrize.setText(R.string.common_placeholder);
    }

    // 同步细进度条开关：整列表的同步信号只在这里表达，不在行上重复；
    // 显示时把进度归零，避免上一次刷新的比例残留
    private void showSyncBar(boolean show) {
        if (pbStatsSync != null) {
            if (show) pbStatsSync.setProgress(0);
            pbStatsSync.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 同步进度条按比例推进：进度按「三波查询」计，第一波主体数据、第二波今日冠军、
     * 第三波待领额度，各占一份。用确定进度而不是一直转的不确定条，地址多时用户能看到数字在涨。
     */
    private void updateSyncProgress(int doneUnits, int totalUnits) {
        if (pbStatsSync == null || totalUnits <= 0) return;
        pbStatsSync.setProgress((int) Math.min(100L, doneUnits * 100L / totalUnits));
    }

    /** 把屏上已有的账号统计快照给其他页（锦标赛统计用来继承已加载的冠军次数，避免重复请求链上） */
    public Map<String, AccountStatsAdapter.AccountStatsItem> getAccountStatsSnapshot() {
        return accountStatsAdapter.snapshotAccountItems();
    }

    /**
     * 只加载本地私钥清单，不触发任何 RPC：读库 → 去重 → 按自定义排序 → 拼 KeyListItem
     * → 同一线程内直接构建占位统计列表，最后一次 runOnUiThread 上屏。
     *
     * <p>这样做的目的：冷启动先出结构与零值，毫秒级可交互；链上数值由 {@code loadStatsOverview}
     * 异步回填。清单只在主线程整体替换，避免 UI 线程读到后台清空一半的集合。</p>
     *
     * <p>清单换新后会把 {@code statsSeq} 自增作废在途的旧统计，并把 {@code statsPhase} 重置回 IDLE，
     * 按钮重新进入「等统计回填」状态。调试模式下展示源换成固定地址池，但真实清单不动。</p>
     */
    private void loadKeyListLocalOnly(StatsReadyCallback onLoaded) {
        new Thread(() -> {
            List<AccountStatsAdapter.DisplayItem> placeholderItems = new ArrayList<>();
            List<KeyListItem> newItems = new ArrayList<>();
            boolean ok = true;
            try {
                // 元数据实例：读清单不解密，所以会话过期也照常出数据（走会话门会整页变空）
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                List<KeyStoreManager.KeyEntry> originEntryList = mgr.loadKeyEntryList();

                List<KeyStoreManager.KeyEntry> unique = new ArrayList<>();
                for (KeyStoreManager.KeyEntry e : originEntryList) {
                    boolean dup = false;
                    for (KeyStoreManager.KeyEntry u : unique) {
                        if (u.id == e.id) { dup = true; break; }
                    }
                    if (!dup) unique.add(e);
                }

                List<Long> customSortIds = mgr.getCustomSortIdList();
                List<KeyStoreManager.KeyEntry> sorted = new ArrayList<>();
                for (Long sortId : customSortIds) {
                    for (KeyStoreManager.KeyEntry e : unique) {
                        if (e.id == sortId) { sorted.add(e); break; }
                    }
                }
                for (KeyStoreManager.KeyEntry e : unique) {
                    boolean exist = false;
                    for (KeyStoreManager.KeyEntry s : sorted) {
                        if (s.id == e.id) { exist = true; break; }
                    }
                    if (!exist) sorted.add(e);
                }

                for (int i = 0; i < sorted.size(); i++) {
                    KeyStoreManager.KeyEntry entry = sorted.get(i);
                    KeyListItem item = new KeyListItem();
                    item.keyId = entry.id;
                    item.seqNo = i + 1;
                    item.displayNo = String.format(Locale.getDefault(), "%03d", entry.id);
                    item.cipherText = entry.cipherText;
                    item.address = entry.address;
                    item.isUsable = true;
                    item.isChecked = true;
                    item.customCode = entry.customCode;
                    item.groupNo = entry.groupNo;
                    item.enabled = entry.enabled;
                    newItems.add(item);
                }

                // 调试专用：主界面展示行改为地址池全部地址（按分组平分，与私钥数量无关）；
                // 只影响展示与统计，不动真实清单，批量任务仍按真实私钥执行
                debugKeyItems = MainDataLoader.buildDebugKeyItems(this);
                // 清单还没交给主线程，展示源只能从本地变量取（getDisplayKeyItems 读的是旧字段）
                List<KeyListItem> displaySource = new ArrayList<>();
                if (debugKeyItems != null && !debugKeyItems.isEmpty()) {
                    displaySource.addAll(debugKeyItems);
                } else {
                    displaySource.addAll(newItems);
                }

                // 同一线程内立即构建占位展示列表（数据库分组 + 零值默认），避免额外线程跳转
                List<AccountStatsAdapter.AccountStatsItem> statItems = new ArrayList<>();
                for (KeyListItem keyItem : displaySource) {
                    if (!keyItem.enabled) continue;
                    String addr = keyItem.address;
                    if (addr == null || addr.isEmpty()) continue;
                    String name = (keyItem.customCode != null && !keyItem.customCode.isEmpty())
                            ? keyItem.customCode : keyItem.displayNo;
                    AccountStatsAdapter.AccountStatsItem statItem = new AccountStatsAdapter.AccountStatsItem(
                            name, addr, BigInteger.ZERO, 0, 12, 0, 0, BigInteger.ZERO, BigInteger.ZERO);
                    statItem.groupNo = keyItem.groupNo;
                    statItems.add(statItem);
                }
                placeholderItems = groupStatsItems(statItems);
            } catch (Exception e) {
                ok = false;
                Log.e("MainActivity", "loadKeyListLocalOnly error", e);
            }
            final List<AccountStatsAdapter.DisplayItem> fItems = placeholderItems;
            final List<KeyListItem> fNewItems = newItems;
            final boolean fOk = ok;
            runOnUiThread(() -> {
                if (!fOk) {
                    // 读库失败时不动界面：旧实现照样 clear 再装空列表，
                    // 用户看到的就是「库里明明有私钥，界面一个不剩」+ 点按钮报「没有启用密钥」
                    appendUiLog(null, "错误：读取本地密钥清单失败，界面保留上次数据");
                    // 失败也算「本地已就绪」：否则按钮会永久置灰，用户连重试入口都没有
                    accountDataReady = true;
                    applyButtonEnabledState();
                    return;
                }
                // 清单在主线程整体替换：后台线程 clear/add 会让 UI 线程读到半成品（并发修改集合）
                keyListItemList.clear();
                keyListItemList.addAll(fNewItems);
                // 清单换新：在途的旧统计作废，按钮重新进入“等统计回填”状态
                statsSeq.incrementAndGet();
                statsPhase = STATS_IDLE;
                // 本地清单已就绪（含“空清单/读失败”）；链上统计另由 statsPhase 单独门控
                accountDataReady = true;
                applyButtonEnabledState();
                if (onLoaded != null) onLoaded.onReady(fItems);
            });
        }).start();
    }

    /**
     * 按分组把统计行摊平成「分组头 + 账号行」。
     *
     * <p>分组顺序跟分组表走，空组不占位；groupNo 为空或指向已删分组的行统一归入「未分组」。
     * 会查数据库取分组名，只能在后台线程调用。</p>
     */
    private List<AccountStatsAdapter.DisplayItem> groupStatsItems(List<AccountStatsAdapter.AccountStatsItem> statItems) {
        List<String> groupNames = AppLogDbHelper.getInstance(this).queryAllGroups();
        Map<String, List<AccountStatsAdapter.AccountStatsItem>> groupMap = new java.util.LinkedHashMap<>();
        for (String g : groupNames) groupMap.put(g, new ArrayList<>());
        List<AccountStatsAdapter.AccountStatsItem> ungrouped = new ArrayList<>();
        for (AccountStatsAdapter.AccountStatsItem item : statItems) {
            String group = (item.groupNo != null && !item.groupNo.isEmpty()) ? item.groupNo : null;
            if (group != null && groupMap.containsKey(group)) {
                groupMap.get(group).add(item);
            } else {
                ungrouped.add(item);
            }
        }
        List<AccountStatsAdapter.DisplayItem> displayItems = new ArrayList<>();
        for (Map.Entry<String, List<AccountStatsAdapter.AccountStatsItem>> me : groupMap.entrySet()) {
            if (!me.getValue().isEmpty()) {
                displayItems.add(AccountStatsAdapter.DisplayItem.header(me.getKey()));
                for (AccountStatsAdapter.AccountStatsItem ai : me.getValue()) {
                    displayItems.add(AccountStatsAdapter.DisplayItem.account(me.getKey(), ai));
                }
            }
        }
        if (!ungrouped.isEmpty()) {
            String ungroupedName = getString(R.string.common_ungrouped);
            displayItems.add(AccountStatsAdapter.DisplayItem.header(ungroupedName));
            for (AccountStatsAdapter.AccountStatsItem ai : ungrouped) {
                displayItems.add(AccountStatsAdapter.DisplayItem.account(ungroupedName, ai));
            }
        }
        return displayItems;
    }

    /**
     * 链上统计回填（两波分片查询）。
     *
     * <p>第一波：余额/今日入场/冠军/收益，按 {@code STATS_CHUNK_SIZE} 分批，
     * 每片回来立即回填并刷新汇总，首片到达就放开按钮，地址多时不用等到底；
     * 第二波：今日冠军（每地址一次 eth_call，最重），只刷该列、不影响按钮可用性。</p>
     *
     * <p>并发保护：每次进入自增 {@code statsSeq}，回调里比对序号，过期请求直接丢，
     * 避免旧请求把新数据盖回去。全部地址都查不到时降级为 {@code STATS_FAILED}，
     * 汇总栏变成重试入口。</p>
     */
    private void loadStatsOverview() {
        final int seq = statsSeq.incrementAndGet();
        // 立刻切回“同步中”：一箭双雕——
        // ① 汇总栏在失败态下兼作重试入口，双击时第二击看到的不再是 FAILED，不会并发拉两轮 RPC；
        // ② 副标题会显示“链上数据同步中…”，与真实正在发生的事一致
        statsPhase = STATS_IDLE;
        final List<KeyListItem> enabledList = getDisplayKeyItems();
        // 整列表同步的唯一信号：顶部细进度条（行上不写任何进度）；
        // onBatchResult 等回调不保证在主线程，这里统一回到 UI 线程操作 View
        runOnUiThread(() -> showSyncBar(true));
        if (enabledList.isEmpty()) {
            // 没有可查账号：不存在“数据没查完”，直接放行到点击时的“无启用密钥”提示
            statsPhase = STATS_READY;
            runOnUiThread(() -> showSyncBar(false));
            applyButtonEnabledState();
            return;
        }

        // 行结构已由 applyLocalDefaults 建好（pending 行），这里只做数值回填：
        // 分批到达就分批刷新，不再等全部地址查完再整表替换
        final List<AccountStatsAdapter.AccountStatsItem> rows = accountStatsAdapter.accountItems();
        // 从未加载过的行先把今日冠军置为未知，免得二波回填前被当成真实的 0 计入汇总
        for (AccountStatsAdapter.AccountStatsItem it : rows) {
            if (it.pending) it.todayChampion = -1;
        }

        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();

                List<String> addrs = new ArrayList<>();
                for (KeyListItem item : enabledList) {
                    if (item.address != null && !item.address.isEmpty()) {
                        addrs.add(item.address);
                    }
                }
                final int total = addrs.size();

                // 同一地址可能对应多行（重复导入），回填时逐行都要写
                Map<String, List<AccountStatsAdapter.AccountStatsItem>> byAddr =
                        new java.util.HashMap<>();
                for (AccountStatsAdapter.AccountStatsItem it : rows) {
                    if (it.address == null || it.address.isEmpty()) continue;
                    List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(it.address);
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        byAddr.put(it.address, bucket);
                    }
                    bucket.add(it);
                }

                int done = 0, unknownTotal = 0;

                // 没有可查地址（行没有地址）：等同于无需同步，必须显式置 READY，
                // 否则门控会一直停在 IDLE，按钮永远不可点
                if (total == 0) {
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        statsPhase = STATS_READY;
                        showSyncBar(false);
                        applyButtonEnabledState();
                        refreshSummary(rows, 0);
                    });
                    return;
                }

                // 第一波：余额/入场/冠军/收益。分片回填，首片回来就放开按钮，
                // 地址多时用户不必盯着“同步中”等到底
                for (int start = 0; start < total; start += ArenaConfig.STATS_CHUNK_SIZE) {
                    int end = Math.min(start + ArenaConfig.STATS_CHUNK_SIZE, total);
                    final List<String> slice = new ArrayList<>(addrs.subList(start, end));
                    final Map<String, ArenaContractApi.PlayerSummary> part =
                            ArenaContractApi.callPlayerSummaryBatch(web3j, slice);
                    done += slice.size();
                    for (String addr : slice) {
                        if (!part.containsKey(addr)) unknownTotal++;
                    }

                    final int fDone = done;
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;   // 已被更新的请求取代
                        applySummaryChunk(byAddr, slice, part);
                        // 首片到达就放开按钮：后面的分片继续在后台补齐
                        if (statsPhase != STATS_READY) {
                            statsPhase = STATS_READY;
                            applyButtonEnabledState();
                        }
                        accountStatsAdapter.notifyStatsChanged();
                        refreshSummary(rows, total);
                        updateSyncProgress(fDone, total * 3);
                    });
                }

                // 一个地址都没拿到：等同于“查不到”，降级为失败态，不再跑第二波
                if (total > 0 && unknownTotal == total) {
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        statsPhase = STATS_FAILED;
                        showStatsUnavailable();
                        applyButtonEnabledState();
                        appendUiLog(null, "链上未返回任何账号数据，请点击汇总栏重试");
                    });
                    return;
                }

                // 第二波：今日冠军（每地址一次 eth_call，最重），只刷新该列，不影响按钮
                int doneToday = 0, todayMiss = 0;
                for (int start = 0; start < total; start += ArenaConfig.STATS_CHUNK_SIZE) {
                    int end = Math.min(start + ArenaConfig.STATS_CHUNK_SIZE, total);
                    final List<String> slice = new ArrayList<>(addrs.subList(start, end));
                    final Map<String, Integer> winMap =
                            ArenaContractApi.callPlayerHistoryLatestWendTodayBatch(web3j, slice);
                    for (String a : slice) {
                        if (winMap.get(a) == null) todayMiss++;
                    }
                    doneToday += slice.size();
                    final int fDoneToday = doneToday;
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        for (String addr : slice) {
                            List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(addr);
                            if (bucket == null) continue;
                            Integer win = winMap.get(addr);
                            for (AccountStatsAdapter.AccountStatsItem it : bucket) {
                                it.todayChampion = win != null ? win : -1;
                            }
                        }
                        accountStatsAdapter.notifyStatsChanged();
                        refreshSummary(rows, total);
                        updateSyncProgress(total + fDoneToday, total * 3);
                    });
                }

                // 第三波：擂台可领 claimable() + 师徒可领 lineagePending()，逐地址一次 eth_call。
                // 这两个 mapping 没有批量 getter，只能单号查；失败只置 null 让该行标签隐藏，
                // 不伪造 0，也不影响其它列——它们只是列表上的待领小标签，不进汇总。
                int doneClaim = 0, claimMiss = 0;
                for (int start = 0; start < total; start += ArenaConfig.STATS_CHUNK_SIZE) {
                    int end = Math.min(start + ArenaConfig.STATS_CHUNK_SIZE, total);
                    final List<String> slice = new ArrayList<>(addrs.subList(start, end));
                    final Map<String, BigInteger> claimMap = new java.util.HashMap<>();
                    final Map<String, BigInteger> lineageMap = new java.util.HashMap<>();
                    final Map<String, ArenaContractApi.MintCounts> mintMap = new java.util.HashMap<>();
                    for (String addr : slice) {
                        BigInteger c = null, lpc = null;
                        ArenaContractApi.MintCounts mint = null;
                        try { c = ArenaContractApi.callClaimable(web3j, addr); } catch (Exception ignore) {}
                        try { lpc = ArenaContractApi.callLineagePending(web3j, addr); } catch (Exception ignore) {}
                        // 盟主令两口径：逐地址 jiaziPage 领讫笔数 − ChampionMandate.balanceOf 已铸 = 可铸，与领取后刷新口径一致
                        try { mint = ArenaContractApi.callMintCounts(web3j, addr); } catch (Exception ignore) {}
                        if (c == null && lpc == null) claimMiss++;
                        claimMap.put(addr, c);
                        lineageMap.put(addr, lpc);
                        mintMap.put(addr, mint);
                    }
                    doneClaim += slice.size();
                    final int fDoneClaim = doneClaim;
                    runOnUiThread(() -> {
                        if (seq != statsSeq.get()) return;
                        for (String addr : slice) {
                            List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(addr);
                            if (bucket == null) continue;
                            BigInteger c = claimMap.get(addr);
                            BigInteger lpc = lineageMap.get(addr);
                            ArenaContractApi.MintCounts mint = mintMap.get(addr);
                            for (AccountStatsAdapter.AccountStatsItem it : bucket) {
                                it.arenaClaimable = c;
                                it.lineagePending = lpc;
                                it.mintableCount = mint != null ? mint.mintable : null;
                                it.mintedCount = mint != null ? mint.minted : null;
                            }
                        }
                        accountStatsAdapter.notifyStatsChanged();
                        updateSyncProgress(2 * total + fDoneClaim, total * 3);
                    });
                }

                final int fUnknown = unknownTotal, fTodayMiss = todayMiss, fClaimMiss = claimMiss;
                runOnUiThread(() -> {
                    if (seq != statsSeq.get()) return;
                    refreshSummary(rows, total);
                    // 三波都结束：进度条退场，界面回到静止态
                    showSyncBar(false);
                    if (fUnknown > 0) {
                        appendUiLog(null, "有 " + fUnknown + " 个账号链上数据未返回，已标记为未查到");
                    }
                    if (fTodayMiss > 0) {
                        appendUiLog(null, "有 " + fTodayMiss + " 个账号今日冠军未查到，该列不计入合计");
                    }
                    if (fClaimMiss > 0) {
                        appendUiLog(null, "有 " + fClaimMiss + " 个账号待领额度未查到，对应小标签暂不显示");
                    }
                });

            } catch (Exception e) {
                Log.e("StatsOverview", "加载失败", e);
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    if (seq != statsSeq.get()) return;
                    showSyncBar(false);
                    appendUiLog(null, "加载统计失败：" + msg);
                    // 已有成功结果时（手动/任务后刷新失败）保留旧数据，不打断操作；
                    // 首轮就失败则保持禁用，汇总栏给出重试入口
                    if (statsPhase != STATS_READY) {
                        statsPhase = STATS_FAILED;
                        showStatsUnavailable();
                    }
                    applyButtonEnabledState();
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    /**
     * 把一片 playerSummary 写回对应行（主线程调用）。
     *
     * <p>同一地址可能对应多行（重复导入），按地址建桶后逐行都写；
     * 本片没返回的地址标 {@code unknown} 并保留行，不清掉已有展示位置。</p>
     */
    private void applySummaryChunk(
            Map<String, List<AccountStatsAdapter.AccountStatsItem>> byAddr,
            List<String> slice,
            Map<String, ArenaContractApi.PlayerSummary> part) {
        for (String addr : slice) {
            List<AccountStatsAdapter.AccountStatsItem> bucket = byAddr.get(addr);
            if (bucket == null) continue;
            ArenaContractApi.PlayerSummary s = part.get(addr);
            for (AccountStatsAdapter.AccountStatsItem it : bucket) {
                it.pending = false;
                if (s == null) {
                    // 本片没返回这个地址：标未查到，保留行，不清掉之前的展示位置
                    it.unknown = true;
                    it.todayChampion = -1;
                } else {
                    it.unknown = false;
                    it.balance = s.walletBalance;
                    it.entriesToday = s.entriesToday.intValue();
                    it.entriesRemaining = s.entriesRemainingToday.intValue();
                    it.totalChampion = s.championships.intValue();
                    it.prize = s.prize;
                    it.totalEarnings = calcNetEarnings(s);
                    // 甲子可提随 playerSummary 免费带回，第一波即有值；擂台/师徒靠第三波逐地址查
                    it.jiaziClaimable = s.jiaziClaimable;
                }
            }
        }
    }

    /**
     * 按当前已回填的行重算汇总栏（同步进度由顶部细进度条表达，这里只出数字）。
     *
     * <p>口径：pending/unknown 行整体排除；今日冠军为 -1 的行只不计该列。
     * 今日入场分母固定为「账号数 × 12」（合约每地址每日上限）。</p>
     */
    private void refreshSummary(List<AccountStatsAdapter.AccountStatsItem> rows, int total) {
        int sumEnter = 0, sumTodayWin = 0, sumTotalWin = 0, knownToday = 0;
        BigInteger sumEarnings = BigInteger.ZERO;
        for (AccountStatsAdapter.AccountStatsItem it : rows) {
            if (it.pending || it.unknown) continue;
            sumEnter += it.entriesToday;
            // todayChampion 为 -1 表示该行今日冠军还没查到：不计入合计，也不能显示成 0
            if (it.todayChampion >= 0) {
                sumTodayWin += it.todayChampion;
                knownToday++;
            }
            sumTotalWin += it.totalChampion;
            if (it.totalEarnings != null) sumEarnings = sumEarnings.add(it.totalEarnings);
        }
        tvSummaryEnterLabel.setText(getString(R.string.summary_enter_label_fmt, total));
        // 分母 = 账号数 × 每日上限（合约 MAX_ENTRIES_PER_DAY），不写死 12：
        // 与连打场数的默认值同源，日后合约调整日上限时这里不会先跑偏
        tvSummaryEnter.setText(sumEnter + "/" + (total * ArenaConfig.MAX_ENTRIES_PER_DAY));
        // 一个今日冠军都没拿到时给占位：写 0 会被误读成“今天都没赢”
        tvSummaryTodayWin.setText((total > 0 && knownToday == 0)
                ? getString(R.string.common_placeholder) : String.valueOf(sumTodayWin));
        tvSummaryTotalWin.setText(String.valueOf(sumTotalWin));
        tvSummaryPrize.setText(formatToken(sumEarnings));
    }

    /**
     * 净收益口径（与合约一致）：每场返还 100 本金 + 0.8 安慰奖，夺冠另得 200 甲子俸（60 天释放），
     * 成本是每场 100 门票。甲子俸按全额计入、不做时间折现；算出来为负按 0 显示。
     */
    private static BigInteger calcNetEarnings(ArenaContractApi.PlayerSummary s) {
        BigInteger battles = s.battles != null ? s.battles : BigInteger.ZERO;
        BigInteger championships = s.championships != null ? s.championships : BigInteger.ZERO;
        BigInteger loserPayout = new BigDecimal("100.8").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger jiaziGrant = new BigDecimal("200").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger entryFee = new BigDecimal("100").multiply(BigDecimal.TEN.pow(18)).toBigInteger();
        BigInteger net = battles.multiply(loserPayout)
                .add(championships.multiply(jiaziGrant))
                .subtract(battles.multiply(entryFee));
        return net.signum() < 0 ? BigInteger.ZERO : net;
    }

    /** 金额展示：wei 转 18 位小数并保留 2 位（四舍五入） */
    private String formatToken(BigInteger wei) {
        if (wei == null) return "0.00";
        BigDecimal bd = new BigDecimal(wei)
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
        return bd.toPlainString();
    }

    /**
     * 批量充值确认页的回传：只有用户在确认页点了确定（{@code RESULT_OK}）才走一次密码验证，
     * 验密通过后锁按钮、后台执行充值，finally 释放密码数组与按钮。
     *
     * <p>两个细节：</p>
     * <ul>
     *   <li><b>不管确认还是取消</b>都要通知弹窗助手「确认页已关」，否则单飞标记一直为真，
     *       用户之后再也拉不开充值页（双击防护的复位点就在这里）；</li>
     *   <li>走 {@link #beginTask}/{@link #endTask} 而不是裸的 {@code setTaskButtonsEnabled}：
     *       这条入口也要有红键可停，也要在收尾时把两个停止标记清干净，
     *       不能四个按钮里只有它一套语义。</li>
     * </ul>
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_BATCH_FUND) return;

        // 确认页已经不在栈里：先复位，不然下次点「批量充值」会被单飞标记拦住
        dialogHelper.markFundPreviewClosed();
        if (resultCode != RESULT_OK) return;

        // 防御：确认页是独立 Activity，站在它上面时主界面仍可能被排队的一下点击启动别的任务
        // （比如“点完充值立刻又点到入场”），回来时就不能再开第二条；详情页单号任务同样要靠跨页闸门拦
        if (isTaskRunning || AppGlobal.isTaskBusy()) {
            android.widget.Toast.makeText(this, R.string.task_running, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // 用户确认充值：密码校验通过后才锁定按钮，取消弹窗不影响按钮。
        // 圈数在启动前快照：充值目标跟着它走，不能在转账过程中再回读配置
        dialogHelper.showDecryptPasswordDialog(pwdCharArray -> {
            final int rounds = ArenaConfig.enterRounds;
            beginTask(tbFund);
            new Thread(() -> {
                try {
                    BatchFundTask.executeAutoFund(getEnabledKeyItems(), pwdCharArray, this, rounds);
                } catch (Exception e) {
                    runOnUiThread(() -> appendLog("", "FUND",
                            getString(R.string.fund_query_fail, Web3AndroidUtil.safeMessage(e))));
                } finally {
                    Arrays.fill(pwdCharArray, (char) 0);
                    runOnUiThread(this::endTask);
                }
            }).start();
        });
    }

    private static final int REQUEST_BATCH_FUND = 1001;

    // 调试专用探针（领取链路调试完毕，整体注释停用；往后需要时整段取消注释）
    // 固定地址复现领取流程的只读查询链，每步打日志定位崩溃点；全程只读，不涉及私钥与签名

    /** 主界面展示/统计口径：调试开关打开时用地址池展开行（与私钥数量无关），否则用真实启用私钥 */
    private List<KeyListItem> getDisplayKeyItems() {
        List<KeyListItem> debug = debugKeyItems;
        if (ArenaConfig.DEBUG_FIXED_ADDR_ENABLE && debug != null && !debug.isEmpty()) return debug;
        return getEnabledKeyItems();
    }

    /** 全量账号列表（含禁用/主号），供充值预览等只观察的场景使用 */
    public List<KeyListItem> getAllKeyItems() {
        return keyListItemList;
    }
    // endregion

}
