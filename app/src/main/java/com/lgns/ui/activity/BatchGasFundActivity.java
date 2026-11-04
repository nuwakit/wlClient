package com.lgns.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.batch.BatchGasFundTask;
import com.lgns.helper.CryptoKeyPrompt;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AddressBookManager;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.util.Web3AndroidUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量充值手续费页：给每个启用账号补手续费币（ERC20 DAI），出资方是「私钥管理」的主号。
 *
 * <p>口径说明：</p>
 * <ul>
 *   <li>目标是用户填的金额，实际只补「目标 − 当前余额」，余额已够的行跳过。</li>
 *   <li>主号要掏的不只是充值额，还有自己那几笔 transfer 的网络费，所以汇总值里含
 *       {@code gasReserve}；判断主号够不够必须用同一个口径，否则会出现「提示够、执行报不足」。</li>
 *   <li>金额单位一律是 DAI，gas 只是计量单位，界面不写 Gas。</li>
 * </ul>
 *
 * <p>页面只做采集、汇总与展示，真正的签名转账在 {@link BatchGasFundTask} 里完成。</p>
 */
public class BatchGasFundActivity extends BaseActivity {

    /** 分组头行 */
    private static final int VIEW_TYPE_HEADER = 0;
    /** 账号行 */
    private static final int VIEW_TYPE_ITEM = 1;

    private final List<BatchGasFundTask.Row> rows = new ArrayList<>();
    private GasAdapter adapter;
    private TextView tvTotalNeed, tvProgress;
    private View btnConfirm;

    /**
     * 每行的默认充值目标（DAI）。默认 5 来自实测口径：一个账号入场 + 领取的手续费大约
     * 够用一个月，充一次不用频繁操作；用户改过就记住新值（存在 AddressBookManager）。
     */
    private String defaultAmount = AddressBookManager.DEFAULT_GAS_FUND;
    private String masterNo = "";
    private String masterAddr = "";
    private BigInteger masterBalance = null;
    /** 当前 gasPrice，用于估算「还要预留多少 DAI 付转账网络费」，与 BatchGasFundTask 同口径 */
    private BigInteger gasPrice = null;
    private boolean masterPending = true;
    /** 充值执行中：防止重复点击；页面销毁后置 false，让后台线程自行放弃后续行 */
    private volatile boolean running;
    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_gas_fund);

        tvTotalNeed = findViewById(R.id.tv_total_need);
        tvProgress = findViewById(R.id.tv_progress);
        btnConfirm = findViewById(R.id.btn_confirm);

        adapter = new GasAdapter();
        RecyclerView rv = findViewById(R.id.rv_gas_list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_gas_default).setOnClickListener(v -> showDefaultDialog());
        findViewById(R.id.btn_gas_cost_info).setOnClickListener(v -> showMonthlyCost());
        btnConfirm.setOnClickListener(v -> startFund());

        defaultAmount = new AddressBookManager(this).getGasFundDefault();
        startLocalThenQuery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
    }

    // endregion

    // region 账号装载与余额查询

    /**
     * 两阶段加载：先读库把账号行铺出来（目标是默认金额），再逐行回填链上 DAI 余额。
     * 账上没钱但行先出来，用户能先看清楚「要给谁充多少」，不用等余额查完才有界面。
     */
    private void startLocalThenQuery() {
        new Thread(() -> {
            List<BatchGasFundTask.Row> built = new ArrayList<>();
            String no = "";
            String addr = "";
            try {
                built = BatchGasFundTask.buildRows();
                // 读主号与清单只是元数据：会话过期也要能先把行铺出来
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                long masterId = mgr.getMasterAccountId();
                for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                    if (e.id == masterId) {
                        no = (e.customCode != null && !e.customCode.isEmpty())
                                ? e.customCode : String.format(java.util.Locale.getDefault(), "%03d", e.id);
                        addr = e.address == null ? "" : e.address;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // 会话锁定 / 读库失败：下面按“无可充值账号”提示，不再往下走
            }

            final List<BatchGasFundTask.Row> fRows = built;
            final String fNo = no;
            final String fAddr = addr;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (fRows.isEmpty()) {
                    Toast.makeText(this, R.string.gas_fund_no_account, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                masterNo = fNo;
                masterAddr = fAddr;
                rows.clear();
                BigDecimal target = parseDecimal(defaultAmount);
                for (BatchGasFundTask.Row r : fRows) {
                    r.amount = target;
                    r.state = BatchGasFundTask.Row.STATE_QUERYING;
                    rows.add(r);
                }
                adapter.notifyDataSetChanged();
                refreshSummary();
                startQuery();
            });
        }).start();
    }

    /** 逐行查余额，查一条回填一条；全部查完后补上主号的 DAI 余额与 gasPrice（汇总要用它们算预留） */
    private void startQuery() {
        new Thread(() -> {
            BatchGasFundTask.queryBalances(rows, (done, total, row) -> runOnUiThread(() -> {
                if (isFinishing()) return;
                tvProgress.setText(getString(R.string.gas_fund_progress_fmt, done, total));
                adapter.notifyDataSetChanged();
                refreshSummary();
            }));
            // 顺手把主号 Gas 余额也查回来，卡片上能直接看出够不够
            try {
                org.web3j.protocol.Web3j w3 = Web3AndroidUtil.createWeb3j();
                final BigInteger bal = com.lgns.contract.ArenaContractApi.callGasTokenBalance(w3, masterAddr);
                BigInteger price = null;
                try { price = w3.ethGasPrice().send().getGasPrice(); } catch (Exception ignore) { }
                final BigInteger fPrice = price;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    masterBalance = bal;
                    gasPrice = fPrice;
                    masterPending = false;
                    tvProgress.setText("");
                    adapter.notifyDataSetChanged();
                    refreshSummary();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    masterPending = false;
                    adapter.notifyDataSetChanged();
                });
            }
        }).start();
    }

    // endregion

    // region 汇总与网络费估算

    /** 汇总待补金额与笔数：只统计缺口 > 0 的行；没有缺口时确认按钮置灰 */
    private void refreshSummary() {
        BigInteger need = BigInteger.ZERO;
        int payCnt = 0;
        for (BatchGasFundTask.Row r : rows) {
            BigInteger n = r.needWei();
            need = need.add(n);
            if (n.signum() > 0) payCnt++;
        }
        boolean any = need.signum() > 0;
        tvTotalNeed.setText(getString(R.string.gas_fund_footer_fmt, payCnt,
                BatchGasFundTask.formatAmount(need)));
        btnConfirm.setAlpha(any ? 1f : 0.5f);
        btnConfirm.setClickable(any);
    }

    /**
     * 主号除了要付出充值额，自己那几笔 transfer 也要花 DAI，
     * 这里用与 BatchGasFundTask 相同的参数算出预留，避免页面说“够了”实际执行却不够。
     */
    private BigInteger gasReserve(int payCnt) {
        if (gasPrice == null || payCnt <= 0) return BigInteger.ZERO;
        return gasPrice.multiply(ArenaConfig.GAS_LIMIT_GAS_TOKEN_TRANSFER)
                .multiply(BigInteger.valueOf(payCnt));
    }

    // endregion

    // region 金额与成本弹窗

    // ---------- 默认值 / 单行金额 ----------

    /** 每月手续费说明：数字取自链上实测中位数，推导过程见 strings_recharge.xml 的 gas_cost_body 注释 */
    private void showMonthlyCost() {
        StatusTipDialog.show(this, StatusTipDialog.TONE_INFO, R.drawable.ic_menu_timer_svg,
                getString(R.string.gas_cost_title), getString(R.string.gas_cost_body));
    }

    private void showDefaultDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_single_value, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_subtitle);
        TextView tvSuffix = view.findViewById(R.id.tv_suffix);
        TextView tvError = view.findViewById(R.id.tv_error);
        EditText etValue = view.findViewById(R.id.et_value);

        tvTitle.setText(R.string.gas_fund_default_title);
        tvSubtitle.setText(R.string.gas_fund_default_msg);
        tvSuffix.setText(R.string.gas_fund_unit);
        etValue.setText(defaultAmount);
        etValue.setSelection(etValue.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_save).setOnClickListener(v -> {
            String raw = etValue.getText().toString().trim();
            if (TextUtils.isEmpty(raw)) {
                tvError.setText(R.string.err_input_empty);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            BigDecimal val = parseDecimal(raw);
            if (val.signum() <= 0) {
                tvError.setText(R.string.gas_fund_err_amount);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            defaultAmount = val.stripTrailingZeros().toPlainString();
            new AddressBookManager(this).setGasFundDefault(defaultAmount);
            // 只改没手动编辑过的行，用户改过的金额保持原样
            for (BatchGasFundTask.Row r : rows) {
                if (!r.edited) r.amount = val;
            }
            adapter.notifyDataSetChanged();
            refreshSummary();
            dialog.dismiss();
            Toast.makeText(this, getString(R.string.gas_fund_default_saved_fmt, defaultAmount),
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void showRowAmountDialog(BatchGasFundTask.Row row) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_single_value, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_subtitle);
        TextView tvSuffix = view.findViewById(R.id.tv_suffix);
        TextView tvError = view.findViewById(R.id.tv_error);
        EditText etValue = view.findViewById(R.id.et_value);

        tvTitle.setText(R.string.gas_fund_edit_title);
        tvSubtitle.setText(getString(R.string.gas_fund_edit_msg_fmt, row.keyNo,
                BatchGasFundTask.formatAmount(row.targetWei())));
        tvSuffix.setText(R.string.gas_fund_unit);
        etValue.setText(row.amount.stripTrailingZeros().toPlainString());
        etValue.setSelection(etValue.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_save).setOnClickListener(v -> {
            String raw = etValue.getText().toString().trim();
            // 留空 = 回退到当前默认值，并清掉手动编辑标记
            if (TextUtils.isEmpty(raw)) {
                row.amount = parseDecimal(defaultAmount);
                row.edited = false;
                adapter.notifyDataSetChanged();
                refreshSummary();
                dialog.dismiss();
                return;
            }
            BigDecimal val = parseDecimal(raw);
            if (val.signum() <= 0) {
                tvError.setText(R.string.gas_fund_err_amount);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            row.amount = val;
            row.edited = true;
            adapter.notifyDataSetChanged();
            refreshSummary();
            dialog.dismiss();
        });
    }

    private static BigDecimal parseDecimal(String s) {
        try {
            return new BigDecimal(s == null ? "" : s.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    // endregion

    // region 执行充值

    // ---------- 执行 ----------

    private void startFund() {
        if (running) return;
        CryptoKeyPrompt.show(this, key -> {
            running = true;
            btnConfirm.setAlpha(0.5f);
            btnConfirm.setClickable(false);
            adapter.notifyDataSetChanged();

            new Thread(() -> {
                BatchGasFundTask.ExecResult result = null;
                String error = null;
                try {
                    result = BatchGasFundTask.execute(rows, key, msg -> runOnUiThread(() -> {
                        tvProgress.setText(msg);
                        AppLogDbHelper.getInstance(this).insertLog("", "GAS", msg, System.currentTimeMillis());
                    }));
                } catch (Exception e) {
                    error = Web3AndroidUtil.safeMessage(e);
                } finally {
                    Arrays.fill(key, (char) 0);
                }

                final BatchGasFundTask.ExecResult fRes = result;
                final String fErr = error;
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    running = false;
                    tvProgress.setText("");
                    refreshSummary();
                    adapter.notifyDataSetChanged();
                    if (fErr != null) {
                        StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                                getString(R.string.gas_fund_fail_title), fErr);
                        return;
                    }
                    String msg = getString(R.string.gas_fund_done_fmt, fRes.success, fRes.failed);
                    AppLogDbHelper.getInstance(this).insertLog("", "GAS", msg, System.currentTimeMillis());
                    StatusTipDialog.show(this,
                            fRes.failed > 0 ? StatusTipDialog.TONE_WARN : StatusTipDialog.TONE_SUCCESS,
                            R.drawable.ic_menu_check_svg,
                            getString(R.string.gas_fund_result_title), msg,
                            getString(R.string.btn_ok), this::finish, null, null);
                });
            }).start();
        });
    }

    // endregion

    // region 列表适配器

    /** 头部（本次合计/默认金额/主号卡）+ 明细行共用一个列表，position 0 固定为头部 */
    class GasAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return rows.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_gas_fund_header, parent, false));
            }
            return new RowVH(inflater.inflate(R.layout.item_gas_fund_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                bindHeader((HeaderVH) holder);
            } else {
                bindRow((RowVH) holder, position - 1);
            }
        }

        private void bindHeader(HeaderVH h) {
            BigInteger need = BigInteger.ZERO;
            int payCnt = 0;
            for (BatchGasFundTask.Row r : rows) {
                BigInteger n = r.needWei();
                need = need.add(n);
                if (n.signum() > 0) payCnt++;
            }
            h.tvTotalNeed.setText(getString(R.string.gas_fund_total_value_fmt,
                    BatchGasFundTask.formatAmount(need)));
            h.tvDefaultAmount.setText(getString(R.string.gas_fund_default_line_fmt, defaultAmount));
            h.tvDetailCount.setText(getString(R.string.gas_fund_detail_count_fmt, rows.size()));
            h.tvMasterNo.setText(masterNo.isEmpty() ? getString(R.string.common_placeholder) : masterNo);
            h.tvMasterAddr.setText(shortAddr(masterAddr));

            if (masterPending || masterBalance == null) {
                h.tvMasterBalance.setText(getString(R.string.fund_item_querying));
                h.layoutMasterTip.setVisibility(View.GONE);
                return;
            }
            h.tvMasterBalance.setText(getString(R.string.gas_fund_payer_balance_fmt,
                    BatchGasFundTask.formatAmount(masterBalance)));
            // 与 execute 同口径：主号要掏的是「充值额 + 自己那几笔转账的网络费」
            BigInteger required = need.add(gasReserve(payCnt));
            if (payCnt > 0 && masterBalance.compareTo(required) < 0) {
                h.layoutMasterTip.setVisibility(View.VISIBLE);
                h.layoutMasterTip.setText(getString(R.string.gas_fund_master_insufficient_fmt,
                        BatchGasFundTask.formatAmount(required.subtract(masterBalance)), payCnt));
            } else {
                h.layoutMasterTip.setVisibility(View.GONE);
            }
        }

        private void bindRow(RowVH h, int index) {
            BatchGasFundTask.Row r = rows.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvKeyNo.setText(r.keyNo);
            h.tvAddr.setText(shortAddr(r.address));

            if (r.state == BatchGasFundTask.Row.STATE_QUERYING) {
                h.tvBalance.setText(R.string.fund_item_querying);
                h.tvNeed.setText(R.string.fund_item_querying);
                h.tvNeed.setTextColor(0xFF9E9E9E);
            } else if (r.state == BatchGasFundTask.Row.STATE_FAILED) {
                h.tvBalance.setText(R.string.common_placeholder);
                h.tvNeed.setText(R.string.fund_item_query_failed);
                h.tvNeed.setTextColor(0xFFC62828);
            } else {
                h.tvBalance.setText(getString(R.string.gas_fund_balance_fmt,
                        BatchGasFundTask.formatAmount(r.balanceWei)));
                BigInteger need = r.needWei();
                if (need.signum() == 0) {
                    h.tvNeed.setText(R.string.gas_fund_enough);
                    h.tvNeed.setTextColor(0xFF2E7D32);
                } else {
                    h.tvNeed.setText(getString(R.string.gas_fund_need_fmt,
                            BatchGasFundTask.formatAmount(need)));
                    h.tvNeed.setTextColor(0xFFE65100);
                }
            }

            h.btnEdit.setOnClickListener(v -> {
                if (running) return;
                showRowAmountDialog(r);
            });
        }

        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            return addr.length() > 10 ? addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4) : addr;
        }
    }

    /** 头部 ViewHolder：合计待补、默认金额、明细条数、主号卡片与不够提示 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvTotalNeed, tvDefaultAmount, tvDetailCount;
        final TextView tvMasterNo, tvMasterAddr, tvMasterBalance, layoutMasterTip;

        HeaderVH(View v) {
            super(v);
            tvTotalNeed = v.findViewById(R.id.tv_total_need);
            tvDefaultAmount = v.findViewById(R.id.tv_default_amount);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            tvMasterNo = v.findViewById(R.id.tv_master_no);
            tvMasterAddr = v.findViewById(R.id.tv_master_addr);
            tvMasterBalance = v.findViewById(R.id.tv_master_balance);
            layoutMasterTip = v.findViewById(R.id.layout_master_tip);
        }
    }

    /** 明细行 ViewHolder：序号/编号/地址/当前余额/待补；右侧按钮打开单行改额弹窗 */
    static class RowVH extends RecyclerView.ViewHolder {
        final TextView tvSeq, tvKeyNo, tvAddr, tvBalance, tvNeed;
        final View btnEdit;

        RowVH(View v) {
            super(v);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvAddr = v.findViewById(R.id.tv_addr);
            tvBalance = v.findViewById(R.id.tv_balance);
            tvNeed = v.findViewById(R.id.tv_need);
            btnEdit = v.findViewById(R.id.btn_edit_amount);
        }
    }
    // endregion

}
