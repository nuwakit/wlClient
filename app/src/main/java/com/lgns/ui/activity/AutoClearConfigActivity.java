package com.lgns.ui.activity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import com.lgns.R;
import com.lgns.helper.StatusTipDialog;
import com.lgns.model.ArenaConfig;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.AppGlobal;
import com.lgns.util.Web3AndroidUtil;

/**
 * 自动清理配置页：设置账号间隔、日志间隔、清理方式与播放模式、批量转账方式等，保存后交给后台任务执行。
 *
 * <p>职责边界：页面只负责读写配置与开关联动，真正的清理动作由后台 Worker 定时触发；
 * 页面不持有清理逻辑，避免配置与执行口径分裂。</p>
 *
 * <p>配置读取失败时会把错误写进日志库并提示用户，防止出现「看似保存成功、实际没生效」。</p>
 */
public class AutoClearConfigActivity extends BaseActivity {

    /** 私钥自动清除开关（关闭时下面的间隔输入置灰且不参保存校验） */
    private SwitchCompat swAutoEnable;
    /** 私钥自动清除间隔数值，与 spTimeUnit 组成「N 小时/天」 */
    private EditText etAutoClearVal;
    /** 间隔单位下拉：索引与 ArenaConfig.autoClearIntervalUnitIndex 一一对应 */
    private Spinner spTimeUnit;
    /** 入场模式下拉：索引存入 ArenaConfig.playModeIndex，主界面按它决定打不打 */
    private Spinner spPlayMode;
    /**
     * 连打场数下拉：选中索引 +1 存入 ArenaConfig.enterRounds。
     *
     * <p>选项由 arrays.xml 的 enter_rounds_array 提供（1~12 场，12 是合约硬上限），主界面点一次
     * 入场就按这个圈数连打，圈与圈之间等上一轮封盘。0 位置对应 1 场，所以存取都要 ±1。
     * 出厂默认 1 场（点一次打一场，沿用旧版习惯）；标「不建议」的是 12 除不尽的值，
     * 想打满 12 场会因此多点一次。</p>
     */
    private Spinner spEnterRounds;
    /**
     * 补币模式下拉：选中索引直接存入 ArenaConfig.fundMode（0=单场足额，1=多场足额）。
     *
     * <p>选项来自 arrays.xml 的 fund_mode_array，两项与 ArenaConfig.FUND_MODE_* 一一对应，
     * 所以存取都不需要 ±1。它决定批量入场前置补币与批量充值的目标余额口径：
     * 单场只补本场门票 100，多场按连打圈数查表预留（与 fundTargetFor 同一函数，不会跑偏）。</p>
     */
    private Spinner spFundMode;
    /** 当前补币模式的一句话说明，随下拉选择实时切换 */
    private TextView tvFundModeDesc;
    /**
     * 批量转账方式下拉：选中索引直接存入 ArenaConfig.transferMode（0=按收益比例，1=按收益数额）。
     *
     * <p>选项来自 arrays.xml 的 transfer_mode_array，两项与 ArenaConfig.TRANSFER_MODE_* 一一对应，
     * 所以存取都不需要 ±1；改了只影响批量转账页拿哪个字段当分配依据，不碰已存的比例与数额。</p>
     */
    private Spinner spTransferMode;
    /** 当前转账方式的一句话说明，随下拉选择实时切换 */
    private TextView tvTransferModeDesc;
    /** 自动补币开关：开启时批量入场前先由主号补齐门票（ArenaConfig.autoReplenishEnable） */
    private SwitchCompat swAutoReplenish;
    /** 日志自动清除开关（关闭时停掉后台清理调度） */
    private SwitchCompat swLogClearEnable;
    /** 日志保留时长数值，与 spLogTimeUnit 组成「N 小时/天」 */
    private EditText etLogClearVal;
    /** 日志保留单位下拉，索引存入 ArenaConfig.logClearIntervalUnitIndex */
    private Spinner spLogTimeUnit;
    /** 日志间隔整块布局（含标题与输入行），开关关闭时整体半透明 */
    private LinearLayout layoutLogInterval;
    /** 当前生效状态卡片：同时展示私钥与日志两个开关的状态 */
    private TextView tvAutoClearStatus;

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_clear);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        // 保存按钮已改成标题栏图标，点击逻辑不变
        findViewById(R.id.btn_save_auto_clear).setOnClickListener(v -> saveConfigs());
        // 原页面顶部的黄色提示条改为按需弹窗，入口在标题栏
        findViewById(R.id.btn_tip_warning).setOnClickListener(v -> showClearRuleDialog());
        findViewById(R.id.btn_init_db).setOnClickListener(v -> showInitDbDialog());

        initViews();
        loadConfigs();
    }

    // endregion

    // region 规则说明弹窗

    /**
     * 清理规则说明：原来常驻页面顶部的黄色提示条，改为点标题栏「⚠」按需查看。
     *
     * <p>文案直接复用既有的 auto_clear_tip_text 与 auto_clear_desc_note_v2，
     * 不再另写一份说明，避免弹窗与文档两处口径不一致。</p>
     */
    private void showClearRuleDialog() {
        String msg = getString(R.string.auto_clear_tip_text) + "\n\n"
                + getString(R.string.auto_clear_desc_note_v2);
        StatusTipDialog.show(this, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                getString(R.string.auto_clear_tip_dialog_title), msg);
    }

    // endregion

    // region 数据库初始化

    /**
     * 初始化数据库：清空日志与私钥记录并重建表结构，但保留分组表。
     *
     * <p>属于不可撤销操作，按钮在弹窗内自绘并锁定到执行结束；
     * 失败时恢复按钮文案与可点状态，同时把异常提示出来，避免用户以为已清空。</p>
     */
    private void showInitDbDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_init_db, null);
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        // 按钮在弹窗布局内自绘：左取消、右危险确认，避免系统按钮栏贴边裁切
        final TextView btnConfirm = view.findViewById(R.id.btn_dialog_confirm);
        final TextView btnCancel = view.findViewById(R.id.btn_dialog_cancel);

        btnConfirm.setOnClickListener(v -> {
            // 初始化期间锁定两个按钮，避免重复点击反复重建表结构
            btnConfirm.setEnabled(false);
            btnCancel.setEnabled(false);
            btnConfirm.setText(R.string.db_init_running);
            new Thread(() -> {
                try {
                    AppLogDbHelper.getInstance(getApplicationContext()).recreateTablesExceptGroups();
                    runOnUiThread(() -> {
                        if (dialog.isShowing()) dialog.dismiss();
                        Toast.makeText(this, R.string.toast_db_initialized, Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) {
                    Log.e("AutoClearConfig", "初始化数据库失败", e);
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        btnCancel.setEnabled(true);
                        btnConfirm.setText(R.string.db_init_confirm);
                        Toast.makeText(this,
                                getString(R.string.toast_init_failed_fmt, Web3AndroidUtil.safeMessage(e)),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });

        // 取消只关闭弹窗，不触发任何数据变更
        btnCancel.setOnClickListener(v -> dialog.dismiss());
    }

    // endregion

    // region 视图初始化

    /** 取控件引用并绑定两个开关的联动：开关一改，对应间隔区立即跟着置灰/激活 */
    private void initViews() {
        swAutoEnable = findViewById(R.id.sw_auto_enable);
        etAutoClearVal = findViewById(R.id.et_auto_clear_val);
        spTimeUnit = findViewById(R.id.sp_time_unit);
        spPlayMode = findViewById(R.id.sp_play_mode);
        spEnterRounds = findViewById(R.id.sp_enter_rounds);
        spFundMode = findViewById(R.id.sp_fund_mode);
        tvFundModeDesc = findViewById(R.id.tv_fund_mode_desc);
        spTransferMode = findViewById(R.id.sp_transfer_mode);
        tvTransferModeDesc = findViewById(R.id.tv_transfer_mode_desc);
        swAutoReplenish = findViewById(R.id.sw_auto_replenish);
        swLogClearEnable = findViewById(R.id.sw_log_clear_enable);
        etLogClearVal = findViewById(R.id.et_log_clear_val);
        spLogTimeUnit = findViewById(R.id.sp_log_time_unit);
        layoutLogInterval = findViewById(R.id.layout_log_interval);
        tvAutoClearStatus = findViewById(R.id.tv_auto_clear_status);

        // 两个开关各自管自己的间隔输入，关闭时置灰并禁用，避免改了却不生效
        swAutoEnable.setOnCheckedChangeListener((b, checked) -> {
            applyKeyIntervalEnabled(checked);
            updateStatusText();
        });
        swLogClearEnable.setOnCheckedChangeListener((b, checked) -> {
            applyLogIntervalEnabled(checked);
            updateStatusText();
        });

        // 说明文字跟着下拉走：两个模式的拦点不同（比例在收款方页拦，数额在转账页拦），
        // 不写清楚很容易让用户以为改完不生效
        spTransferMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyTransferModeDesc(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        // 补币模式说明同样实时切换：单场足额要讲清「冠军下一场会再补一次」，
        // 否则用户只看到补币次数变多会以为出了问题
        spFundMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyFundModeDesc(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    // endregion

    // region 配置读写

    /**
     * 读配置并回填控件。
     *
     * <p>三个下拉索引都做越界保护：覆盖安装后选项数量变少（如入场模式旧版 3 项、新版 2 项）时，
     * 越界值会重置为默认并写一条 CONFIG_WARN 日志，而不是把页面显示错或直接崩。
     * 整体读取失败则全部回落默认值，同样写日志留痕。</p>
     */
    private void loadConfigs() {
        try {
            ArenaConfig.loadConfig(this);
            swAutoEnable.setChecked(ArenaConfig.autoClearEnable);
            etAutoClearVal.setText(String.valueOf(ArenaConfig.autoClearIntervalValue));

            // 边界检查：防止索引超出数组范围（覆盖安装兼容）
            int timeUnitIndex = ArenaConfig.autoClearIntervalUnitIndex;
            if (timeUnitIndex < 0 || timeUnitIndex >= spTimeUnit.getCount()) {
                String warnMsg = "配置项 autoClearIntervalUnitIndex 越界: " + timeUnitIndex + "，已重置为默认值 1(小时)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                timeUnitIndex = 1; // 默认：小时
            }
            spTimeUnit.setSelection(timeUnitIndex);

            // 入场模式：旧版本有3个选项(0/1/2)，新版本只有2个(0/1)
            int playModeIndex = ArenaConfig.playModeIndex;
            if (playModeIndex < 0 || playModeIndex >= spPlayMode.getCount()) {
                String warnMsg = "配置项 playModeIndex 越界: " + playModeIndex + "，已重置为默认值 0(能打就打)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                playModeIndex = 0; // 默认：能打就打
            }
            spPlayMode.setSelection(playModeIndex);

            // 连打场数：存储值是「场数」(1~12)，下拉索引是「场数-1」，两端都要换算。
            // 上限取「下拉项数」与「合约日上限」的较小者：万一 arrays.xml 少列了几项，
            // 也不能把索引指到数组之外（setSelection 越界不会崩，但会静默选中 0 号）
            int rounds = ArenaConfig.enterRounds;
            int maxRounds = Math.min(spEnterRounds.getCount(), ArenaConfig.MAX_ENTRIES_PER_DAY);
            if (rounds < 1 || rounds > maxRounds) {
                String warnMsg = "配置项 enterRounds 越界: " + rounds + "，已重置为默认值 "
                        + ArenaConfig.DEFAULT_ENTER_ROUNDS + "(场)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                rounds = Math.min(ArenaConfig.DEFAULT_ENTER_ROUNDS, maxRounds);
            }
            spEnterRounds.setSelection(rounds - 1);

            // 补币模式：只有 0/1 两个合法值，越界（包括旧版本没这个 key 时手动改脏）回默认的单场足额
            int fMode = ArenaConfig.fundMode;
            if (fMode < 0 || fMode >= spFundMode.getCount()) {
                String warnMsg = "配置项 fundMode 越界: " + fMode + "，已重置为默认值 "
                        + ArenaConfig.DEFAULT_FUND_MODE + "(单场足额)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                fMode = Math.min(ArenaConfig.DEFAULT_FUND_MODE, spFundMode.getCount() - 1);
            }
            spFundMode.setSelection(fMode);
            applyFundModeDesc(fMode);

            // 批量转账方式：只有 0/1 两个合法值，越界（包括旧版本没这个 key 时手动改脏）回默认比例模式
            int tMode = ArenaConfig.transferMode;
            if (tMode < 0 || tMode >= spTransferMode.getCount()) {
                String warnMsg = "配置项 transferMode 越界: " + tMode + "，已重置为默认值 "
                        + ArenaConfig.DEFAULT_TRANSFER_MODE + "(按收益比例)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                tMode = Math.min(ArenaConfig.DEFAULT_TRANSFER_MODE, spTransferMode.getCount() - 1);
            }
            spTransferMode.setSelection(tMode);
            applyTransferModeDesc(tMode);

            swAutoReplenish.setChecked(ArenaConfig.autoReplenishEnable);

            etLogClearVal.setText(String.valueOf(ArenaConfig.logClearIntervalValue));

            int logTimeUnitIndex = ArenaConfig.logClearIntervalUnitIndex;
            if (logTimeUnitIndex < 0 || logTimeUnitIndex >= spLogTimeUnit.getCount()) {
                String warnMsg = "配置项 logClearIntervalUnitIndex 越界: " + logTimeUnitIndex + "，已重置为默认值 2(天)";
                Log.w("AutoClearConfig", warnMsg);
                writeErrorToDb("CONFIG_WARN", warnMsg);
                logTimeUnitIndex = 2; // 默认：天
            }
            spLogTimeUnit.setSelection(logTimeUnitIndex);

            swLogClearEnable.setChecked(ArenaConfig.logClearEnable);
            applyKeyIntervalEnabled(swAutoEnable.isChecked());
            applyLogIntervalEnabled(swLogClearEnable.isChecked());
            updateStatusText();
        } catch (Exception e) {
            String errMsg = "加载配置异常: " + e.getClass().getSimpleName() + " - " + e.getMessage();
            Log.e("AutoClearConfig", errMsg, e);
            writeErrorToDb("CONFIG_ERROR", errMsg);

            // 使用默认值
            swAutoEnable.setChecked(false);
            etAutoClearVal.setText("1");
            spTimeUnit.setSelection(1);
            spPlayMode.setSelection(0);
            spEnterRounds.setSelection(Math.min(ArenaConfig.DEFAULT_ENTER_ROUNDS,
                    spEnterRounds.getCount()) - 1);
            int fallbackFund = Math.min(ArenaConfig.DEFAULT_FUND_MODE,
                    Math.max(0, spFundMode.getCount() - 1));
            spFundMode.setSelection(fallbackFund);
            applyFundModeDesc(fallbackFund);
            int fallbackMode = Math.min(ArenaConfig.DEFAULT_TRANSFER_MODE,
                    Math.max(0, spTransferMode.getCount() - 1));
            spTransferMode.setSelection(fallbackMode);
            applyTransferModeDesc(fallbackMode);
            swAutoReplenish.setChecked(true);
            swLogClearEnable.setChecked(true);
            etLogClearVal.setText("1");
            spLogTimeUnit.setSelection(2);
            applyKeyIntervalEnabled(false);
            applyLogIntervalEnabled(true);
            updateStatusText();
        }
    }

    /**
     * 将配置错误写入日志数据库，方便后续分析
     */
    private void writeErrorToDb(String tag, String message) {
        try {
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            if (dbHelper != null) {
                dbHelper.insertLog(null, tag, message, System.currentTimeMillis());
            }
        } catch (Exception ignore) {
            Log.e("AutoClearConfig", "写入配置错误日志失败", ignore);
        }
    }

    /**
     * 保存配置：先校验两个间隔都是正整数，再写回 ArenaConfig 并落盘。
     *
     * <p>保存后要做两件收尾：写一条 CONFIG 日志方便事后对账；
     * 按日志清除开关重启/停止后台调度，否则改了间隔要等下次重启才生效。</p>
     */
    private void saveConfigs() {
        try {
            int intervalValue = Integer.parseInt(etAutoClearVal.getText().toString());
            if (intervalValue <= 0) {
                Toast.makeText(this, R.string.err_clear_interval_positive, Toast.LENGTH_SHORT).show();
                return;
            }

            int logIntervalValue = Integer.parseInt(etLogClearVal.getText().toString());
            if (logIntervalValue <= 0) {
                Toast.makeText(this, R.string.err_log_interval_positive, Toast.LENGTH_SHORT).show();
                return;
            }

            ArenaConfig.autoClearEnable = swAutoEnable.isChecked();
            ArenaConfig.autoClearIntervalValue = intervalValue;
            ArenaConfig.autoClearIntervalUnitIndex = spTimeUnit.getSelectedItemPosition();
            ArenaConfig.playModeIndex = spPlayMode.getSelectedItemPosition();
            ArenaConfig.enterRounds = spEnterRounds.getSelectedItemPosition() + 1;
            ArenaConfig.fundMode = spFundMode.getSelectedItemPosition();
            ArenaConfig.transferMode = spTransferMode.getSelectedItemPosition();
            ArenaConfig.autoReplenishEnable = swAutoReplenish.isChecked();
            ArenaConfig.logClearEnable = swLogClearEnable.isChecked();
            ArenaConfig.logClearIntervalValue = logIntervalValue;
            ArenaConfig.logClearIntervalUnitIndex = spLogTimeUnit.getSelectedItemPosition();

            ArenaConfig.saveConfig(this);

            // 记录日志到数据库
            String configLog = "系统配置已保存: " +
                    "私钥自动清除=" + (ArenaConfig.autoClearEnable ? "开启" : "关闭") + ", " +
                    "私钥清除间隔=" + ArenaConfig.autoClearIntervalValue + " " + getTimeUnitText(ArenaConfig.autoClearIntervalUnitIndex) + ", " +
                    "入场模式=" + getPlayModeText(ArenaConfig.playModeIndex) + ", " +
                    "连打场数=" + ArenaConfig.enterRounds + " 场, " +
                    "补币模式=" + getFundModeText(ArenaConfig.fundMode) + ", " +
                    "批量转账=" + getTransferModeText(ArenaConfig.transferMode) + ", " +
                    "自动补币=" + (ArenaConfig.autoReplenishEnable ? "开启" : "关闭") + ", " +
                    "日志自动清除=" + (ArenaConfig.logClearEnable ? "开启" : "关闭") + ", " +
                    "日志保留=" + ArenaConfig.logClearIntervalValue + " " + getTimeUnitText(ArenaConfig.logClearIntervalUnitIndex);
            AppLogDbHelper dbHelper = AppLogDbHelper.getInstance(this);
            dbHelper.insertLog(null, "CONFIG", configLog, System.currentTimeMillis());
            AppGlobal.logConfigChange(configLog);

            // 日志自动清除开关决定是否重启定时清理任务
            if (ArenaConfig.logClearEnable) {
                long logClearMinutes = AppGlobal.getIntervalInMinutes(
                        ArenaConfig.logClearIntervalValue, ArenaConfig.logClearIntervalUnitIndex);
                AppGlobal.startLogClearScheduler(logClearMinutes);
            } else {
                AppGlobal.stopLogClearScheduler();
            }

            Toast.makeText(this, R.string.toast_config_saved, Toast.LENGTH_SHORT).show();
            Log.d("AutoClearConfigActivity", configLog);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.err_invalid_number, Toast.LENGTH_SHORT).show();
        }
    }

    // endregion

    // region 文案与开关联动

    /** 单位索引转文案，越界时回退到「小时」，用于日志拼接 */
    private String getTimeUnitText(int unitIndex) {
        String[] timeUnits = getResources().getStringArray(R.array.time_unit_array);
        if (unitIndex >= 0 && unitIndex < timeUnits.length) {
            return timeUnits[unitIndex];
        }
        return getString(R.string.unit_hour);
    }

    /** 入场模式索引转文案，越界时回退到默认模式，用于日志拼接 */
    private String getPlayModeText(int modeIndex) {
        String[] playModes = getResources().getStringArray(R.array.play_mode_array);
        if (modeIndex >= 0 && modeIndex < playModes.length) {
            return playModes[modeIndex];
        }
        return getString(R.string.play_mode_flexible);
    }

    /** 批量转账方式索引转文案，越界时回退到默认的比例模式，只用于日志拼接 */
    private String getTransferModeText(int modeIndex) {
        String[] modes = getResources().getStringArray(R.array.transfer_mode_array);
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        }
        return getString(R.string.transfer_mode_ratio);
    }

    /** 补币模式索引转文案，越界时回退到默认的单场足额，只用于日志拼接 */
    private String getFundModeText(int modeIndex) {
        String[] modes = getResources().getStringArray(R.array.fund_mode_array);
        if (modeIndex >= 0 && modeIndex < modes.length) {
            return modes[modeIndex];
        }
        return getString(R.string.fund_mode_single);
    }

    /**
     * 按当前选中的转账方式刷新一句话说明。
     *
     * <p>越界时不报错也不改选中项，只回退到比例说明：保存前还有一道越界保护，
     * 这里只负责把界面文字弄得可读，不让它显示成空白。</p>
     */
    private void applyTransferModeDesc(int modeIndex) {
        if (tvTransferModeDesc == null) return;
        tvTransferModeDesc.setText(modeIndex == ArenaConfig.TRANSFER_MODE_AMOUNT
                ? R.string.auto_clear_transfer_mode_desc_amount
                : R.string.auto_clear_transfer_mode_desc_ratio);
    }

    /**
     * 按当前选中的补币模式刷新一句话说明。
     * 与 applyTransferModeDesc 同一套规则：越界只回退显示，不改选中项。
     */
    private void applyFundModeDesc(int modeIndex) {
        if (tvFundModeDesc == null) return;
        tvFundModeDesc.setText(modeIndex == ArenaConfig.FUND_MODE_MULTI
                ? R.string.auto_clear_fund_mode_desc_multi
                : R.string.auto_clear_fund_mode_desc_single);
    }

    /** 私钥间隔区的启用态：整块透明度 + 输入框/下拉可编辑性同步切换 */
    private void applyKeyIntervalEnabled(boolean enabled) {
        View layout = findViewById(R.id.layout_time_setting);
        if (layout != null) layout.setAlpha(enabled ? 1f : 0.4f);
        etAutoClearVal.setEnabled(enabled);
        spTimeUnit.setEnabled(enabled);
    }

    /** 日志间隔区的启用态：与私钥区同一套置灰规则，两区视觉一致 */
    private void applyLogIntervalEnabled(boolean enabled) {
        if (layoutLogInterval != null) layoutLogInterval.setAlpha(enabled ? 1f : 0.4f);
        etLogClearVal.setEnabled(enabled);
        spLogTimeUnit.setEnabled(enabled);
    }

    // 当前状态卡片：同时展示两个开关，避免只看到私钥状态
    private void updateStatusText() {
        if (tvAutoClearStatus == null) return;
        String keyState = getString(swAutoEnable.isChecked() ? R.string.state_enabled : R.string.state_disabled);
        String logState = getString(swLogClearEnable.isChecked() ? R.string.state_enabled : R.string.state_disabled);
        tvAutoClearStatus.setText(getString(R.string.auto_clear_status_pair_fmt, keyState, logState));
    }

    // endregion

}
