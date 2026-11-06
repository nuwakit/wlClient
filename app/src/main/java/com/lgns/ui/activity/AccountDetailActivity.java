package com.lgns.ui.activity;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.lgns.R;
import com.lgns.batch.BatchClaimTask;
import com.lgns.batch.BatchEnterTask;
import com.lgns.contract.ArenaContractApi;
import com.lgns.helper.CryptoKeyPrompt;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.AppPrefs;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.task.TaskLogCallback;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 账号详情页：从主界面点某行进入，展示该地址的入场/领取两类链上明细，并可就地做单号「入场」「领取」「铸造盟主令」。
 *
 * <p>取数与主界面同源（playerSummary + claimable + lineagePending），只是单地址、逐字段展开；
 * 操作直接复用批量任务（传单元素列表），日志与主界面一致——先上屏本页，再在子线程 {@code insertLog} 落库。</p>
 *
 * <p>不经 Intent 传密文/私钥（敏感）：需要解密时按地址从 {@code KeyStoreManager} 查回真实密钥，
 * 密码走 {@link CryptoKeyPrompt} 就地验证。调试地址池行无私钥，只能看不能操作。</p>
 */
public class AccountDetailActivity extends BaseActivity implements TaskLogCallback {

    public static final String EXTRA_ADDRESS = "extra_address";
    public static final String EXTRA_NAME = "extra_name";
    public static final String EXTRA_GROUP = "extra_group";
    public static final String EXTRA_OPERABLE = "extra_operable";

    /**
     * 单号入场固定 1 场（「就地补一场」入口）。
     *
     * <p>刻意不跟随主界面的 {@code ArenaConfig.enterRounds}：连打要按圈等封盘，是批量任务的能力；
     * 这个入口只承担「这个号今天差一场，就地补掉」。该含义会在任务启动日志里明说，
     * 避免和主界面「12 场满勤」混淆。</p>
     */
    private static final int SINGLE_ENTER_ROUNDS = 1;

    private String address;
    private String displayName;
    private String groupName;
    /** 该行是否可操作：调试地址池行为 false，只展示不入场/领取 */
    private boolean operable = true;

    private TextView tvName, tvGroup, tvAddr;
    private ProgressBar pbLoading;
    private View btnEnter, btnClaim, btnMint, btnRefresh;
    private TextView tvLog, tvLogToggle;
    private View logHeader;
    private ScrollView scrollLog;

    // 入场明细行的数值控件（代码里回填）
    private TextView valBalance, valAllowance, valEntriesToday, valEntriesRemaining,
            valSeated, valInMatchDeposit, valNextFeeSource;
    // 领取明细行的数值控件
    private TextView valBattles, valChampionships, valJiaziClaimable, valJiaziReleased,
            valJiaziGranted, valArenaClaimable, valLineagePending, valPrize, valMintableCount;

    /** 本页单号任务运行标记：跑任务时禁用三个按钮，避免重复提交抢同一 nonce */
    private volatile boolean taskRunning = false;
    /** 页面是否已销毁：任务线程收尾时不再碰控件 */
    private volatile boolean destroyed = false;
    /** 刷新是否在途：连点只认第一次，避免重复建 RPC 连接 */
    private volatile boolean loading = false;
    /** 在途加载作废序号：快速刷新/返回时旧线程不再回填 */
    private final java.util.concurrent.atomic.AtomicInteger loadSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_detail);

        address = getIntent().getStringExtra(EXTRA_ADDRESS);
        displayName = getIntent().getStringExtra(EXTRA_NAME);
        groupName = getIntent().getStringExtra(EXTRA_GROUP);
        operable = getIntent().getBooleanExtra(EXTRA_OPERABLE, true);

        tvName = findViewById(R.id.tv_d_name);
        tvGroup = findViewById(R.id.tv_d_group);
        tvAddr = findViewById(R.id.tv_d_addr);
        pbLoading = findViewById(R.id.pb_detail_loading);
        btnEnter = findViewById(R.id.btn_d_enter);
        btnClaim = findViewById(R.id.btn_d_claim);
        btnMint = findViewById(R.id.btn_d_mint);
        btnRefresh = findViewById(R.id.btn_d_refresh);
        tvLog = findViewById(R.id.tv_detail_log);
        scrollLog = findViewById(R.id.scroll_detail_log);
        logHeader = findViewById(R.id.ll_detail_log_header);
        tvLogToggle = findViewById(R.id.tv_detail_log_toggle);

        // 账号头数据回填：上一版合并时这三行赋值丢了，页面只剩布局占位 "--"
        tvName.setText(displayName != null && !displayName.isEmpty()
                ? displayName : getString(R.string.common_placeholder));
        tvGroup.setText(groupName != null && !groupName.isEmpty()
                ? groupName : getString(R.string.common_ungrouped));
        tvAddr.setText(shortAddress(address));

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        buildRows();

        if (!operable) {
            // 调试地址池提示不再占卡片控件，内容计入底部运行日志（仅上屏不落库）；
            // 没有私钥就先把入场/领取置灰，避免每次点了才被拒
            appendLogToUi(null, getString(R.string.detail_debug_no_key), System.currentTimeMillis());
            setActionEnabled(false);
        }

        btnRefresh.setOnClickListener(v -> handleRefreshClick());
        btnEnter.setOnClickListener(v -> handleEnterClick());
        btnClaim.setOnClickListener(v -> handleClaimClick());
        btnMint.setOnClickListener(v -> handleMintClick());

        // 日志折叠：点标题行切换，交互与主界面 layout_log_header 一致；默认收起由布局 visibility=gone 决定
        logHeader.setOnClickListener(v -> {
            boolean visible = scrollLog.getVisibility() == View.VISIBLE;
            scrollLog.setVisibility(visible ? View.GONE : View.VISIBLE);
            tvLogToggle.setText(visible
                    ? getString(R.string.arrow_collapsed)
                    : getString(R.string.arrow_expanded));
        });

        if (address == null || address.isEmpty()) {
            showError(getString(R.string.detail_key_missing));
            return;
        }
        loadData();
    }

    // region 明细行构建

    /**
     * 建两节明细：入场 + 领取。
     *
     * <p>结构对齐大厂金融 App 的详情页：<b>顶部 KPI 区（大数字 + 小标签）承载核心指标，
     * 下面用分组小标题 + 表格行承载其余明细</b>。原来 7~8 行等权平铺，核心指标（今日进度、
     * 三项可领）只靠字号与颜色区分，扫读时仍要在十几行里找；KPI 化之后一眼就能看到
     * 「这个号今天还差几场」「有没有钱可领」，明细行退成背景信息。</p>
     *
     * <p>分隔线由调用方按位置决定：KPI 区下方一条 + 组内行与行之间；分组标题后面不加，
     * 否则标题与首行之间会悬着一条线。</p>
     */
    private void buildRows() {
        LinearLayout enter = findViewById(R.id.container_enter);
        LinearLayout claim = findViewById(R.id.container_claim);

        // ── 入场：今日进度 KPI + 当前轮入座状态胶囊
        LinearLayout enterKpi = addKpiRow(enter);
        valEntriesToday = addKpi(enterKpi, R.string.detail_label_entries_today,
                getString(R.string.detail_unit_match), getColor(R.color.primary), 24f);
        valEntriesRemaining = addKpi(enterKpi, R.string.detail_label_entries_remaining,
                getString(R.string.detail_unit_match), getColor(R.color.primary), 24f);
        valSeated = addStatusChip(enterKpi);

        addDivider(enter);

        addGroupLabel(enter, R.string.detail_group_fund);
        valBalance = addRow(enter, R.string.detail_label_wallet_balance, false);
        valAllowance = addRow(enter, R.string.detail_label_allowance, true);
        valInMatchDeposit = addRow(enter, R.string.detail_label_in_match_deposit, true);

        addGroupLabel(enter, R.string.detail_group_fee);
        valNextFeeSource = addRow(enter, R.string.detail_label_next_fee_source, false);

        // ── 领取：三项可领 KPI（绿色只留给「真的有钱可领」，见 setClaimKpi）
        LinearLayout claimKpi = addKpiRow(claim);
        final int claimGreen = getColor(R.color.claim_green);
        final String flgns = getString(R.string.detail_unit_flgns);
        // 金额块比进度块小一号：三栏平分时“12345.00 + fLGNS”在窄屏上会溢出
        valJiaziClaimable = addKpi(claimKpi, R.string.detail_label_jiazi_claimable, flgns, claimGreen, 20f);
        valArenaClaimable = addKpi(claimKpi, R.string.detail_label_arena_claimable, flgns, claimGreen, 20f);
        valLineagePending = addKpi(claimKpi, R.string.detail_label_lineage_pending, flgns, claimGreen, 20f);

        // 盟主令两口径（可铸/已铸）紧跟三项可领胶囊：铸造与领取同属俸禄链路，挂在底部
        // 甲子分组里会被当成普通统计行，用户找不到入口；dividerAbove 顺带承担原 KPI 下分隔线
        valMintableCount = addRow(claim, R.string.detail_label_mintable_count, true);

        addDivider(claim);

        addGroupLabel(claim, R.string.detail_group_stats);
        valBattles = addRow(claim, R.string.detail_label_battles, false);
        valChampionships = addRow(claim, R.string.detail_label_championships, true);
        valPrize = addRow(claim, R.string.detail_label_prize, true);

        addGroupLabel(claim, R.string.detail_group_jiazi);
        valJiaziReleased = addRow(claim, R.string.detail_label_jiazi_released, false);
        valJiaziGranted = addRow(claim, R.string.detail_label_jiazi_granted, true);

        // 初始全部占位，避免链上回来前把空白当成 0
        TextView[] all = {valBalance, valAllowance, valEntriesToday, valEntriesRemaining,
                valSeated, valInMatchDeposit, valNextFeeSource, valBattles, valChampionships,
                valJiaziClaimable, valJiaziReleased, valJiaziGranted, valArenaClaimable,
                valLineagePending, valPrize, valMintableCount};
        for (TextView tv : all) tv.setText(R.string.common_placeholder);
    }

    /**
     * 新建一行 KPI 容器（横向）。
     *
     * <p>顶部 12dp / 底部 10dp 内边距，与下方分组标题的 14dp 上间距错开，
     * 让「数字区」与「表格区」在留白上有节奏差，而不是一整块均匀行距。</p>
     */
    private LinearLayout addKpiRow(LinearLayout container) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(12), 0, dp(10));
        container.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /**
     * 追加一个 KPI 指标块：大数字 + 可选单位后缀 + 下方小标签，返回数字控件引用。
     *
     * <p>单位单独用一个 11sp 的 TextView，不拼进字符串：数字要被反复回填，拼进去每次
     * {@code setText} 都得重拼，还容易出现「12.50fLGNS」贴在一起；分开后数字控件只收纯数值，
     * 单位跟着基线排版。</p>
     *
     * @param color      数字颜色：金额块用领取绿，进度块用主色
     * @param textSizeSp 数字字号：进度块 24（只有 1~2 位），金额块 20（三栏平分还要留单位位）
     */
    private TextView addKpi(LinearLayout row, int labelRes, String unit, int color, float textSizeSp) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);

        LinearLayout valueLine = new LinearLayout(this);
        valueLine.setOrientation(LinearLayout.HORIZONTAL);
        // 底对齐：单位后缀贴着数字基线，不居中悬浮
        valueLine.setGravity(Gravity.BOTTOM);

        TextView tvValue = new TextView(this);
        tvValue.setTextSize(textSizeSp);
        tvValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvValue.setTextColor(color);
        tvValue.setIncludeFontPadding(false);
        // 金额不换行：一旦折成两行，下方标签与同行另两栏会错位
        tvValue.setSingleLine(true);
        // 等宽数字：金额逐次刷新时位数不跳
        tvValue.setFontFeatureSettings("tnum");
        valueLine.addView(tvValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        if (unit != null && !unit.isEmpty()) {
            TextView tvUnit = new TextView(this);
            tvUnit.setText(unit);
            tvUnit.setTextSize(11);
            tvUnit.setTextColor(getColor(R.color.text_hint));
            LinearLayout.LayoutParams lpUnit = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lpUnit.setMarginStart(dp(3));
            lpUnit.bottomMargin = dp(2);
            valueLine.addView(tvUnit, lpUnit);
        }

        block.addView(valueLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(labelRes);
        tvLabel.setTextSize(11);
        tvLabel.setTextColor(getColor(R.color.text_hint));
        LinearLayout.LayoutParams lpLabel = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpLabel.topMargin = dp(5);
        block.addView(tvLabel, lpLabel);

        row.addView(block, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return tvValue;
    }

    /**
     * 追加状态胶囊（当前轮是否入座），返回文字控件。
     *
     * <p>入座与否是二值状态，做成胶囊比「标签：已入座」更省一行，也更接近大厂的 status chip；
     * 底色在 {@code fillDetails} 里按值切换（已入座绿 / 未入座灰）。</p>
     */
    private TextView addStatusChip(LinearLayout row) {
        TextView tv = new TextView(this);
        tv.setTextSize(10);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.white));
        tv.setBackgroundResource(R.drawable.bg_tag_gray);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        lp.setMarginStart(dp(6));
        lp.topMargin = dp(4);
        row.addView(tv, lp);
        return tv;
    }

    /** 分组小标题：淡灰、小号、加字距；只做分区不强调，强调交给 KPI 数字 */
    private void addGroupLabel(LinearLayout container, int titleRes) {
        TextView tv = new TextView(this);
        tv.setText(titleRes);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(getColor(R.color.text_hint));
        tv.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(2);
        container.addView(tv, lp);
    }

    /** 行间细分隔线：取物理像素级的最细，避免 1dp 在低密度屏上显得过重 */
    private void addDivider(LinearLayout container) {
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        container.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
    }

    /**
     * 追加一行「标签—数值」（表格行），返回右侧数值控件。
     *
     * @param dividerAbove true 时在该行上方补一条分隔线。为什么由调用方决定：
     *                     分组标题后的第一行不需要线（标题本身已形成分隔），
     *                     若按「容器非空就加」，标题与首行之间会悬一条多余的线
     */
    private TextView addRow(LinearLayout container, int labelRes, boolean dividerAbove) {
        if (dividerAbove) addDivider(container);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(labelRes);
        tvLabel.setTextSize(13);
        tvLabel.setTextColor(getColor(R.color.text_secondary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvValue = new TextView(this);
        tvValue.setTextSize(14);
        tvValue.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvValue.setTextColor(getColor(R.color.text_primary));
        tvValue.setGravity(Gravity.END);
        // 等宽数字：多行金额右对齐时位数不跳，扫读更稳（大厂详情页标配）
        tvValue.setFontFeatureSettings("tnum");
        row.addView(tvValue, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(row);
        return tvValue;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // endregion

    // region 链上取数

    /** 后台拉链上明细并回填；失败给可重试提示，不伪造 0 */
    private void loadData() {
        final int seq = loadSeq.incrementAndGet();
        loading = true;
        runOnUiThread(() -> {
            if (destroyed) return;
            pbLoading.setVisibility(View.VISIBLE);
        });
        new Thread(() -> {
            Web3j web3j = null;
            try {
                web3j = Web3AndroidUtil.dialAny();
                final ArenaContractApi.PlayerSummary s =
                        ArenaContractApi.callPlayerSummary(web3j, address);
                BigInteger c = null, lpc = null;
                ArenaContractApi.MintCounts mint = null;
                try { c = ArenaContractApi.callClaimable(web3j, address); } catch (Exception ignore) {}
                try { lpc = ArenaContractApi.callLineagePending(web3j, address); } catch (Exception ignore) {}
                try { mint = ArenaContractApi.callMintCounts(web3j, address); } catch (Exception ignore) {}
                final BigInteger claimable = c;
                final BigInteger lineage = lpc;
                final ArenaContractApi.MintCounts mintCounts = mint;
                runOnUiThread(() -> {
                    if (seq != loadSeq.get()) return;
                    loading = false;
                    if (destroyed) return;
                    pbLoading.setVisibility(View.GONE);
                    fillDetails(s, claimable, lineage, mintCounts);
                });
            } catch (Exception e) {
                Log.e("AccountDetail", "load chain error", e);
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    if (seq != loadSeq.get()) return;
                    loading = false;
                    if (destroyed) return;
                    pbLoading.setVisibility(View.GONE);
                    showError(getString(R.string.detail_chain_failed_fmt, msg));
                });
            } finally {
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
            }
        }).start();
    }

    /** 把 playerSummary + claimable + lineagePending + 盟主令两口径(可铸/已铸)展开到各行；未查到的额度显示占位 */
    private void fillDetails(ArenaContractApi.PlayerSummary s, BigInteger claimable, BigInteger lineage, ArenaContractApi.MintCounts mintCounts) {
        int entriesToday = s.entriesToday != null ? s.entriesToday.intValue() : 0;
        int remaining = s.entriesRemainingToday != null ? s.entriesRemainingToday.intValue() : 0;

        valBalance.setText(formatToken(s.walletBalance));
        valAllowance.setText(formatToken(s.allowance));
        valEntriesToday.setText(String.valueOf(entriesToday));
        valEntriesRemaining.setText(String.valueOf(remaining));
        // 剩余 0 = 今天已打满：进度块换警示色，扫一眼就知道这个号不用再入场
        valEntriesRemaining.setTextColor(getColor(
                remaining == 0 ? R.color.danger : R.color.primary));
        // 入座是二值状态：文字与胶囊底色一起换，不只靠颜色（色弱用户也能读）
        valSeated.setText(s.seatedInCurrentRound
                ? R.string.detail_seat_yes : R.string.detail_seat_no);
        valSeated.setBackgroundResource(s.seatedInCurrentRound
                ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);
        valInMatchDeposit.setText(formatToken(s.inMatchDeposit));
        valNextFeeSource.setText(s.nextEntryFromPrize
                ? getString(R.string.detail_fee_from_prize) : getString(R.string.detail_fee_from_wallet));

        valBattles.setText(String.valueOf(s.battles != null ? s.battles.intValue() : 0));
        valChampionships.setText(String.valueOf(s.championships != null ? s.championships.intValue() : 0));
        // 三项可领走 KPI 语义：>0 才用领取绿，0 转灰，查不到显示占位（不伪造 0）
        setClaimKpi(valJiaziClaimable, s.jiaziClaimable);
        setClaimKpi(valArenaClaimable, claimable);
        setClaimKpi(valLineagePending, lineage);
        valJiaziReleased.setText(formatToken(s.jiaziReleased));
        valJiaziGranted.setText(formatToken(s.jiaziTotalGranted));
        setMintableCount(valMintableCount, mintCounts);
        valPrize.setText(formatToken(s.prize));
    }

    /** 盟主令两口径（可铸/已铸）：null→占位；否则「可铸 X / 已铸 Y」，可铸&gt;0 领取绿、为0 次要灰，与可领金额同色系 */
    private void setMintableCount(TextView tv, ArenaContractApi.MintCounts mc) {
        if (mc == null || mc.mintable == null) {
            tv.setText(R.string.common_placeholder);
            tv.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        tv.setText(getString(R.string.detail_mintable_value_fmt, mc.mintable, mc.minted == null ? 0 : mc.minted));
        tv.setTextColor(getColor(mc.mintable > 0 ? R.color.claim_green : R.color.text_secondary));
    }

    /**
     * 可领金额 KPI 着色：
     * <ul>
     *   <li>null：本轮没查到 → 显示占位并弱化，不能当成 0 展示</li>
     *   <li>0：确实没钱可领 → 显示 0.00 但转灰，绿色留给真正可领的钱</li>
     *   <li>&gt;0：领取绿，和主界面「批量领取」按钮、待领标签同一色系</li>
     * </ul>
     */
    private void setClaimKpi(TextView tv, BigInteger wei) {
        if (wei == null) {
            tv.setText(R.string.common_placeholder);
            tv.setTextColor(getColor(R.color.text_secondary));
            return;
        }
        tv.setText(formatToken(wei));
        tv.setTextColor(getColor(wei.signum() > 0
                ? R.color.claim_green : R.color.text_secondary));
    }

    // endregion

    // region 单号入场 / 领取

    private void handleEnterClick() {
        if (!guardOperable("ENTER")) return;
        logOp("ENTER", getString(R.string.detail_log_click_enter));
        final KeyListItem keyItem = resolveKeyItemByAddress();
        if (keyItem == null) {
            logOp("ENTER", getString(R.string.detail_key_missing));
            return;
        }
        // 先抢全局闸门：主界面批量任务在跑就直接拦下，不弹密码框、不发交易
        if (!acquireGlobalTask("ENTER")) return;
        // 抢到闸门立刻置运行态：密码框还开着时再点一次会被 guardOperable 挡掉，
        // 不会叠出第二个密码框；主界面此时也拿不到闸门
        setRunning(true);
        CryptoKeyPrompt.show(this, pwd -> startSingleTask(true, keyItem, pwd),
                () -> releaseGlobalTask("ENTER"));
    }

    private void handleClaimClick() {
        if (!guardOperable("CLAIM")) return;
        logOp("CLAIM", getString(R.string.detail_log_click_claim));
        final KeyListItem keyItem = resolveKeyItemByAddress();
        if (keyItem == null) {
            logOp("CLAIM", getString(R.string.detail_key_missing));
            return;
        }
        if (!acquireGlobalTask("CLAIM")) return;
        setRunning(true);
        CryptoKeyPrompt.show(this, pwd -> startSingleTask(false, keyItem, pwd),
                () -> releaseGlobalTask("CLAIM"));
    }

    /** 铸造盟主令：与入场/领取同一套三层门（本页门 → 全局闸门 → 密码门），通过后走单号铸造任务 */
    private void handleMintClick() {
        if (!guardOperable("MINT")) return;
        logOp("MINT", getString(R.string.detail_log_click_mint));
        final KeyListItem keyItem = resolveKeyItemByAddress();
        if (keyItem == null) {
            logOp("MINT", getString(R.string.detail_key_missing));
            return;
        }
        if (!acquireGlobalTask("MINT")) return;
        setRunning(true);
        CryptoKeyPrompt.show(this, pwd -> startMintTask(keyItem, pwd),
                () -> releaseGlobalTask("MINT"));
    }

    /**
     * 第二层门（全局）：占跨页闸门；失败说明另一入口正在跑，写日志拦下（两条链路不会并发签名）。
     *
     * <p>只能紧跟在 {@link #guardOperable(String)} 之后调用：先给本页理由、再抢锁，
     * 抢到后必须由「密码框取消回调」或「任务 finally」二者之一释放。</p>
     */
    private boolean acquireGlobalTask(String type) {
        if (AppGlobal.acquireTask()) return true;
        logOp(type, getString(R.string.detail_task_busy_other));
        return false;
    }

    /** 密码框被取消/关闭：放开闸门并复位按钮，否则整页永久卡在运行态 */
    private void releaseGlobalTask(String type) {
        logOp(type, getString(R.string.detail_pwd_canceled));
        AppGlobal.releaseTask();
        setRunning(false);
    }

    /**
     * 刷新：重拉链上明细。不弹任何提示，点击行为本身与加载失败都写入本页日志（并落库）。
     *
     * <p>刷新不是链上交易，日志类型传 null，日志管理页里显示为「日志」而不是误标成入场/领取。</p>
     */
    private void handleRefreshClick() {
        if (taskRunning) {
            logOp(null, getString(R.string.task_running));
            return;
        }
        // 在途去抖：每次点击都会新建 RPC 连接，连点 N 次白耗流量与电量（结果本来就会被 loadSeq 丢弃）
        if (loading) return;
        logOp(null, getString(R.string.detail_log_click_refresh));
        loadData();
    }

    /**
     * 密码校验通过后开跑。
     *
     * <p>进这里时全局闸门与运行态已由点击方占住，所以不再做第二次互斥判断；
     * 收尾顺序固定为「清密码 → 放闸门 → 判页面存活 → 刷 UI」：
     * 页面被销毁也必须放闸门，否则整个 App 后续任务全被拦死。</p>
     */
    private void startSingleTask(final boolean isEnter, final KeyListItem keyItem, final char[] pwd) {
        final int playMode = AppPrefs.get(this).getInt("play_mode", 0);
        final List<KeyListItem> one = new ArrayList<>();
        one.add(keyItem);
        // 与主界面 beginTask 对称：先清掉上一次可能残留的停止/取消标记。
        // BatchEnterTask 在 rounds==1（本页固定 1 场）时会在开跑前检查「打完这圈再停」，
        // 标记若残留，这次单号入场会被直接跳过（日志只留一行“已按要求停止”），用户会以为点了没反应。
        // 这里清是安全的：跨页闸门已在本页手上，主界面的停止请求不可能正在生效
        Web3AndroidUtil.resetStop();
        Web3AndroidUtil.resetCancel();
        // 把「固定补一场」写进日志，避免和主界面圈数配置混淆
        logOp(isEnter ? "ENTER" : "CLAIM", getString(isEnter
                ? R.string.detail_log_enter_single : R.string.detail_log_claim_start));
        new Thread(() -> {
            try {
                if (isEnter) {
                    BatchEnterTask.batchEnterAllRoundsResume(
                            getApplicationContext(), this, playMode, SINGLE_ENTER_ROUNDS, one, pwd);
                } else {
                    BatchClaimTask.batchClaimAllResume(
                            getApplicationContext(), this, playMode, one, pwd);
                }
            } catch (Exception e) {
                final String type = isEnter ? "ENTER" : "CLAIM";
                onLog(displayNo(keyItem), type,
                        getString(R.string.toast_op_failed_fmt, Web3AndroidUtil.safeMessage(e)));
            } finally {
                Arrays.fill(pwd, (char) 0);
                AppGlobal.releaseTask();
                if (destroyed) return;
                runOnUiThread(() -> {
                    setRunning(false);
                    loadData();
                });
            }
        }).start();
    }

    /**
     * 单号铸造盟主令：解密私钥 → 查可铸 index → 最多 {@link ArenaConfig#MANDATE_MINT_BATCH} 枚发一笔 mint 交易。
     *
     * <p>铸造不是领取的自动后续（官网也是独立按钮）：只有领讫满 60 天且 used() 未标已铸的俸禄
     * 才能换 NFT，所以先查 {@code callMintableIndices} 再决定发不发：查失败/无可铸都不发交易，
     * 不伪造成功。revert 不重试（同参数重发必再 revert，只白烧 gas），超时也不补发，
     * 交给用户刷新后重查——回读 balanceOf 自然反映真实持仓。收尾顺序与 {@link #startSingleTask} 一致：
     * 清密码 → 关连接 → 放闸门 → 判存活 → 刷 UI。</p>
     */
    private void startMintTask(final KeyListItem keyItem, final char[] pwd) {
        new Thread(() -> {
            Web3j web3j = null;
            final String keyNo = displayNo(keyItem);
            try {
                web3j = Web3AndroidUtil.dialAny();
                String plainPk = AppGlobal.getKeyStoreManager().decryptCipherText(keyItem.cipherText, pwd);
                ECKeyPair keyPair = Web3AndroidUtil.pkToKeyPair(plainPk);
                plainPk = null;
                List<BigInteger> indices = ArenaContractApi.callMintableIndices(web3j, address);
                if (indices == null) {
                    onLog(keyNo, "MINT", "❌ 可铸俸禄查询失败，未发交易，请稍后重试");
                    return;
                }
                if (indices.isEmpty()) {
                    onLog(keyNo, "MINT", "ℹ 无可铸盟主令（领讫俸禄已全部铸造），未发交易");
                    return;
                }
                int batch = Math.min(indices.size(), ArenaConfig.MANDATE_MINT_BATCH);
                List<BigInteger> slice = new ArrayList<>(indices.subList(0, batch));
                BigInteger nonce = web3j.ethGetTransactionCount(address,
                        DefaultBlockParameterName.PENDING).send().getTransactionCount();
                BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
                TransactionReceipt rcpt = ArenaContractApi.doMandateMint(
                        web3j, keyPair, slice, nonce, gasPrice, null);
                onLog(keyNo, "MINT", "✅ 盟主令铸造成功 " + batch + " 枚，txHash=" + rcpt.getTransactionHash()
                        + (indices.size() > batch ? "（还剩 " + (indices.size() - batch) + " 枚，请再点一次）" : ""));
            } catch (ArenaContractApi.RevertedTxException rtx) {
                onLog(keyNo, "MINT", "✖ 铸造交易回滚(revert)，不重试：" + Web3AndroidUtil.safeMessage(rtx));
            } catch (Exception e) {
                onLog(keyNo, "MINT", getString(R.string.toast_op_failed_fmt, Web3AndroidUtil.safeMessage(e)));
            } finally {
                Arrays.fill(pwd, (char) 0);
                if (web3j != null) {
                    try { web3j.shutdown(); } catch (Exception ignore) {}
                }
                AppGlobal.releaseTask();
                if (destroyed) return;
                runOnUiThread(() -> {
                    setRunning(false);
                    loadData();
                });
            }
        }).start();
    }

    /**
     * 操作前置门（第一层，只管本页）：不可操作行、本页任务进行中都在日志里说明原因并落库，不弹 Toast。
     *
     * <p><b>两层门必须按「本地先判、全局后判」顺序调</b>：这里只判本页的 {@code operable} 与
     * {@code taskRunning}（快、零开销、能区分“没私钥”和“本页正在跑”两种原因），
     * 通过后再由调用方 {@code acquireGlobalTask} 判跨页闸门。
     * 全局闸门不要搬进这里：那是 CAS 占锁，有副作用，放在纯校验方法里一旦被别处复用就会误占锁。
     * 反过来也不要跳过本层直抢全局——否则日志只会说“另一个任务在跑”，看不出真正原因。</p>
     *
     * @param type 日志类型，由调用方按按钮语义传入 ENTER/CLAIM
     */
    private boolean guardOperable(String type) {
        if (!operable) {
            logOp(type, getString(R.string.detail_debug_no_key));
            return false;
        }
        if (taskRunning) {
            logOp(type, getString(R.string.task_running));
            return false;
        }
        return true;
    }

    /**
     * 按地址从本地密钥表查回真实密钥（不经 Intent 传密文）。
     *
     * <p>只读 SQLite 不解密，走元数据实例，会话过期也能查到地址与密文，
     * 真正的解密在任务里用密码完成。查不到（历史旧密钥无地址、或地址池行）返回 null。</p>
     */
    private KeyListItem resolveKeyItemByAddress() {
        if (address == null) return null;
        try {
            KeyStoreManager mgr = AppGlobal.getMetaManager();
            List<KeyStoreManager.KeyEntry> list = mgr.loadKeyEntryList();
            String target = address.toLowerCase(Locale.US);
            for (KeyStoreManager.KeyEntry e : list) {
                if (e.address == null || e.address.isEmpty()) continue;
                if (!e.address.toLowerCase(Locale.US).equals(target)) continue;
                KeyListItem item = new KeyListItem();
                item.keyId = e.id;
                item.displayNo = String.format(Locale.getDefault(), "%03d", e.id);
                item.customCode = e.customCode;
                item.groupNo = e.groupNo;
                item.cipherText = e.cipherText;
                item.address = e.address;
                item.enabled = e.enabled;
                item.isUsable = true;
                return item;
            }
        } catch (Exception ex) {
            Log.e("AccountDetail", "resolve key error", ex);
        }
        return null;
    }

    /** 日志/失败归档用的展示编号：优先自定义编号，缺失用 3 位 displayNo */
    private static String displayNo(KeyListItem item) {
        if (item == null) return "";
        return (item.customCode != null && !item.customCode.isEmpty()) ? item.customCode : item.displayNo;
    }

    // endregion

    // region 日志上屏与落库（对齐主界面 onLog，额外带【详情】来源前缀）

    @Override
    public void onLog(final String keyNo, final String type, final String msg) {
        String safeKeyNo = (keyNo == null || keyNo.trim().isEmpty()) ? null : keyNo.trim();
        String safeType = (type == null || type.trim().isEmpty()) ? null : type.trim();
        final long timeMs = System.currentTimeMillis();
        appendLogToUi(safeKeyNo, msg, timeMs);
        // 用户主动触发的入场/领取才落库，逻辑与主界面一致；内容加【详情】前缀，日志管理页可区分来源
        final String dbMsg = getString(R.string.detail_log_prefix) + msg;
        // 落库用 Application Context：页面销毁后任务线程仍会写日志，不能拿已销毁的 Activity
        new Thread(() -> AppLogDbHelper.getInstance(getApplicationContext())
                .insertLog(safeKeyNo, safeType, dbMsg, timeMs)).start();
    }

    private void appendLogToUi(final String safeKeyNo, final String msg, final long timeMs) {
        runOnUiThread(() -> {
            if (destroyed) return;
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(sdf.format(new Date(timeMs))).append("]");
            if (safeKeyNo != null) sb.append("[").append(safeKeyNo).append("]");
            // 【详情】标识本页单号操作，与主界面批量操作日志一眼可分
            sb.append(getString(R.string.detail_log_prefix)).append(msg).append("\n");
            tvLog.append(sb.toString());
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    /**
     * 按钮操作反馈：本页不弹 Toast，统一写成一条日志——先上屏，再落库（带【详情】前缀）。
     *
     * <p>编号取当前账号的自编号；缺失时置 null，由日志页显示为空。</p>
     *
     * @param type 日志类型，沿用 ENTER/CLAIM；刷新这类非交易操作传 null，日志页显示为「日志」
     */
    private void logOp(final String type, final String msg) {
        String keyNo = (displayName != null && !displayName.trim().isEmpty()) ? displayName.trim() : null;
        onLog(keyNo, type, msg);
    }

    /** 本页单号任务是否在跑（供本页自己控按钮，不是批量回调接口的一部分） */
    public boolean isRunning() {
        return taskRunning;
    }

    /** 不可操作行（调试地址池）入场/领取/铸造从一开始就置灰，刷新保留（只读查询无害） */
    private void setActionEnabled(boolean enabled) {
        float alpha = enabled ? 1f : 0.4f;
        btnEnter.setEnabled(enabled);
        btnClaim.setEnabled(enabled);
        btnMint.setEnabled(enabled);
        btnEnter.setAlpha(alpha);
        btnClaim.setAlpha(alpha);
        btnMint.setAlpha(alpha);
    }

    /** 切换任务运行态：跑任务时三个按钮置灰不可点，避免重复提交抢同一 nonce */
    public void setRunning(boolean running) {
        this.taskRunning = running;
        runOnUiThread(() -> {
            if (destroyed) return;
            // 不可操作行（调试池）跑完也不能放开入场/领取
            boolean actionEnabled = !running && operable;
            float actionAlpha = actionEnabled ? 1f : 0.4f;
            btnEnter.setEnabled(actionEnabled);
            btnClaim.setEnabled(actionEnabled);
            btnMint.setEnabled(actionEnabled);
            btnEnter.setAlpha(actionAlpha);
            btnClaim.setAlpha(actionAlpha);
            btnMint.setAlpha(actionAlpha);
            btnRefresh.setEnabled(!running);
            btnRefresh.setAlpha(running ? 0.4f : 1f);
            if (running && scrollLog.getVisibility() != View.VISIBLE) {
                scrollLog.setVisibility(View.VISIBLE);
                tvLogToggle.setText(getString(R.string.arrow_expanded));
            }
        });
    }

    // endregion

    /** 原错误提示改走底部日志列表：仅上屏（带时间戳），不落库，避免把瞬时失败当作业务日志入库 */
    private void showError(final String msg) {
        appendLogToUi(null, "⚠ " + msg, System.currentTimeMillis());
    }

    /** 地址简短展示：0x + 前 4 位 … 后 4 位，与主界面日志截断口径一致 */
    private static String shortAddress(String a) {
        if (a == null || a.isEmpty()) return "--";
        if (a.length() <= 12) return a;
        return a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }

    /** 金额展示：wei 转 18 位小数并保留 2 位（四舍五入），与主界面同源 */
    private String formatToken(BigInteger wei) {
        if (wei == null) return "0.00";
        BigDecimal bd = new BigDecimal(wei)
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
        return bd.toPlainString();
    }

    /** 详情页不自行处理返回主界面刷新——主界面靠 {@code refreshStatsFromDetailOnResume} 重拉；这里只回收加载线程序号 */
    @Override
    protected void onDestroy() {
        // 先置销毁位：任务线程的 finally 不再碰控件，但闸门照旧在那边释放
        destroyed = true;
        super.onDestroy();
        loadSeq.incrementAndGet();
    }
}
