package com.lgns.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.batch.BatchTransferTask;
import com.lgns.contract.ArenaContractApi;
import com.lgns.helper.CryptoKeyPrompt;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AddressBookManager;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.model.ArenaConfig;
import com.lgns.model.PayerItem;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量转账页：把支付方主号的 fLGNS 余额转给各接收地址。
 *
 * <p>分配口径由系统配置页的「批量转账」（{@code ArenaConfig.transferMode}）选定：</p>
 * <ul>
 *   <li><b>按收益比例</b>（默认）：把余额当 100%，按每行的百分比拆开；</li>
 *   <li><b>按收益数额</b>：每行固定拿接收方管理里填的枚数，余额只用来判够不够。</li>
 * </ul>
 * 数额模式有一条硬校验：<b>收款方累计数额不得大于支付方余额</b>，超出就整批拦下，
 * 不会自动少转——转一半留半成品比一笔都不转难收拾得多。
 *
 * <p>两个前置条件都要满足才会放行：支付方 fLGNS 够分，且 DAI 够付这 N 笔转账的网络费；
 * 任一不足都只提示不执行，避免转到一半断供留下半成品。</p>
 *
 * <p>数据流：先本地算出分配方案让用户看清每笔金额，再查链上余额复核，
 * 复核结果通过 {@code refreshActions} 决定按钮是否可用。</p>
 */
public class BatchTransferActivity extends BaseActivity {

    /** 分组头行 */
    private static final int TYPE_HEADER = 0;
    /** 接收方行 */
    private static final int TYPE_ITEM = 1;

    private final List<BatchTransferTask.Row> rows = new ArrayList<>();
    private TransferAdapter adapter;

    private RecyclerView rvTransfer;
    private View layoutState, pbState, btnConfirm;
    private TextView tvStateTitle, tvStateMsg, btnStateAction;

    private PayerItem payer;
    private BigDecimal ratioSum = BigDecimal.ZERO;
    /** 支付方 fLGNS 余额，null = 还没查回来 */
    private BigInteger payerBalance;
    /** 支付方原生币余额：只用于提示够不够付网络费 */
    private BigInteger nativeBalance;
    private BigInteger gasPrice = BigInteger.ZERO;
    /** 本次计划转出的合计（fLGNS，wei）：比例模式就是余额，数额模式是各行数额相加 */
    private BigInteger totalWei = BigInteger.ZERO;
    /** 当前是不是「按收益数额」模式（系统配置页选定，每次 reload 重读） */
    private boolean amountMode;
    /** 数额模式下收款方累计应得（wei）：只取决于本地库，不等链上余额回来就能算 */
    private BigInteger planTotalWei = BigInteger.ZERO;
    /** 数额模式下累计数额 > 支付方余额：整批拦下不执行 */
    private boolean amountOverflow;
    /** 计划转出金额 > 0 的接收方数量，同时用作预估的链上转账笔数 */
    private int payRows;
    private boolean balanceQuerying;
    private boolean balanceFailed;
    private boolean running;
    /** 每次 reload 自增，丢弃过期的异步回调 */
    private int loadSeq;
    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_transfer);

        rvTransfer = findViewById(R.id.rv_transfer);
        layoutState = findViewById(R.id.layout_state);
        pbState = findViewById(R.id.pb_state);
        tvStateTitle = findViewById(R.id.tv_state_title);
        tvStateMsg = findViewById(R.id.tv_state_msg);
        btnStateAction = findViewById(R.id.btn_state_action);
        btnConfirm = findViewById(R.id.btn_confirm);

        adapter = new TransferAdapter();
        rvTransfer.setLayoutManager(new LinearLayoutManager(this));
        rvTransfer.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> onBackPressed());
        // 出资方在支付方管理里维护，这里留一个直达入口
        findViewById(R.id.btn_manage_payer).setOnClickListener(v ->
                startActivity(new Intent(this, PayerManageActivity.class)));
        btnConfirm.setOnClickListener(v -> startTransfer());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 接收方比例、支付方与余额都可能刚被改过，每次回来都重读
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
    }

    // endregion

    // region 数据装载

    /**
     * 两阶段重载：先读库把接收方名单与比例铺出来，再查支付方余额与 gasPrice。
     *
     * <p>每次重载自增 {@code loadSeq}，旧回调回来时序号对不上就直接丢弃，
     * 避免用户在两个页面之间来回切换导致前一次的结果覆盖后一次。</p>
     */
    private void reload() {
        final int seq = ++loadSeq;
        balanceQuerying = true;
        balanceFailed = false;
        if (rows.isEmpty()) {
            showLoadingState();
        } else {
            adapter.notifyDataSetChanged();
        }

        // 阶段一：只读库（分配模式/接收方名单与比例、数额/出资方），马上就能渲染出来
        new Thread(() -> {
            ArenaConfig.loadConfig(this);
            AddressBookManager book = new AddressBookManager(this);
            List<BatchTransferTask.Row> built = BatchTransferTask.buildRows(book.loadEnabledAddressList());
            PayerItem master = book.getPayerMaster();
            BigDecimal sum = book.enabledRatioSum(-1);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                applyLocal(built, master, sum);
                queryChain(seq);
            });
        }).start();
    }

    private void applyLocal(List<BatchTransferTask.Row> data, PayerItem master, BigDecimal sum) {
        payer = master;
        ratioSum = sum == null ? BigDecimal.ZERO : sum;
        payerBalance = null;
        nativeBalance = null;
        gasPrice = BigInteger.ZERO;
        totalWei = BigInteger.ZERO;
        amountMode = ArenaConfig.transferMode == ArenaConfig.TRANSFER_MODE_AMOUNT;
        planTotalWei = BigInteger.ZERO;
        amountOverflow = false;

        rows.clear();
        rows.addAll(data);

        payRows = 0;
        for (BatchTransferTask.Row r : rows) {
            // 分配依据跟着模式走：比例模式看 ratio，数额模式看 amount
            try {
                String basis = amountMode ? r.amount : r.ratio;
                if (new BigDecimal(basis == null || basis.trim().isEmpty() ? "0" : basis.trim()).signum() > 0) payRows++;
            } catch (Exception ignored) { }
            r.amountWei = BigInteger.ZERO;
        }
        // 数额模式的每份金额只跟本地库有关，不必等链上余额回来
        if (amountMode) planTotalWei = BatchTransferTask.allocateByAmount(rows);

        if (rows.isEmpty()) {
            showEmptyState();
        } else {
            showContentState();
            adapter.notifyDataSetChanged();
        }
        refreshActions();
    }

    /** 阶段二：查支付方 fLGNS 余额、原生币余额与 gasPrice */
    private void queryChain(int seq) {
        if (payer == null) {
            balanceQuerying = false;
            adapter.notifyDataSetChanged();
            refreshActions();
            return;
        }
        final String addr = payer.address;
        new Thread(() -> {
            BigInteger flgns = null;
            BigInteger nativeBal = null;
            BigInteger price = BigInteger.ZERO;
            String error = null;
            Web3j w3 = null;
            try {
                w3 = Web3AndroidUtil.dialAny();
                flgns = ArenaContractApi.callErc20Balance(w3, addr);
                nativeBal = ArenaContractApi.callNativeBalance(w3, addr);
                price = w3.ethGasPrice().send().getGasPrice();
            } catch (Exception e) {
                error = Web3AndroidUtil.safeMessage(e);
            } finally {
                if (w3 != null) {
                    try { w3.shutdown(); } catch (Exception ignored) { }
                }
            }

            final BigInteger fFlgns = flgns;
            final BigInteger fNative = nativeBal;
            final BigInteger fPrice = price;
            final String fErr = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                applyChain(fFlgns, fNative, fPrice, fErr);
            });
        }).start();
    }

    private void applyChain(BigInteger flgns, BigInteger nativeBal, BigInteger price, String error) {
        payerBalance = flgns;
        nativeBalance = nativeBal;
        gasPrice = price == null ? BigInteger.ZERO : price;
        balanceQuerying = false;
        balanceFailed = error != null;

        // 可分配总额的含义跟着模式变：比例模式把余额当 100% 拆开（原生币只付网络费，不从这里扣），
        // 数额模式计划总额已经定下，余额只是「够不够」的判据，不参与拆分
        BigInteger balance = (payer != null && payerBalance != null) ? payerBalance : BigInteger.ZERO;
        if (amountMode) {
            planTotalWei = BatchTransferTask.allocateByAmount(rows);
            // 余额为 0 单独走「转不出累计数额」的提示，这里只管「有钱但不够」
            amountOverflow = balance.signum() > 0 && planTotalWei.compareTo(balance) > 0;
            totalWei = planTotalWei;
        } else if (balance.signum() > 0) {
            totalWei = balance;
            BatchTransferTask.allocateWei(totalWei, rows);
        } else {
            totalWei = BigInteger.ZERO;
            for (BatchTransferTask.Row r : rows) r.amountWei = BigInteger.ZERO;
        }

        adapter.notifyDataSetChanged();
        refreshActions();

        if (balanceFailed && !rows.isEmpty()) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                    getString(R.string.fund_query_fail_title),
                    getString(R.string.fund_query_fail_msg_fmt, error),
                    getString(R.string.btn_retry), this::reload,
                    getString(R.string.btn_cancel), null);
        }
    }

    // endregion

    // region 按钮状态

    private void refreshActions() {
        // 与批量充值一致：只在「正在查询余额 / 转账中 / 没有接收方」时置灰。
        // 没设支付方、余额为 0、余额查失败都不置灰——点下去再告诉用户原因，
        // 否则按钮一直是灰的，用户不知道是本页没就绪还是链上真没钱。
        boolean ok = !running && !balanceQuerying && !rows.isEmpty();
        btnConfirm.setAlpha(ok ? 1f : 0.5f);
        btnConfirm.setClickable(ok);
    }

    // endregion

    // region 状态视图

    // ---------- 三种页面状态：加载 / 空态 / 内容 ----------

    private void showLoadingState() {
        layoutState.setVisibility(View.VISIBLE);
        rvTransfer.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.VISIBLE);
        tvStateTitle.setText(R.string.transfer_state_loading_title);
        tvStateMsg.setText(R.string.transfer_state_loading_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.btn_cancel), this::finish);
    }

    private void showEmptyState() {
        layoutState.setVisibility(View.VISIBLE);
        rvTransfer.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.GONE);
        tvStateTitle.setText(R.string.transfer_state_empty_title);
        tvStateMsg.setText(R.string.transfer_state_empty_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.transfer_go_address),
                () -> startActivity(new Intent(this, AddressManageActivity.class)));
    }

    private void showContentState() {
        layoutState.setVisibility(View.GONE);
        rvTransfer.setVisibility(View.VISIBLE);
    }

    private void setStateAction(CharSequence text, Runnable action) {
        if (text == null || text.length() == 0) {
            btnStateAction.setVisibility(View.GONE);
            return;
        }
        btnStateAction.setVisibility(View.VISIBLE);
        btnStateAction.setText(text);
        btnStateAction.setOnClickListener(v -> {
            if (action != null) action.run();
        });
    }

    // endregion

    // region 执行转账

    /** 支付方 fLGNS 余额（wei）：没查回来按 0 处理，避开满屏的 null 判断 */
    private BigInteger payerBalanceWei() {
        return payerBalance == null ? BigInteger.ZERO : payerBalance;
    }

    // ---------- 执行 ----------

    private void startTransfer() {
        if (running) return;
        if (payer == null) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                    getString(R.string.transfer_no_payer), getString(R.string.transfer_payer_tip_unset),
                    getString(R.string.title_payer_manage), () ->
                            startActivity(new Intent(this, PayerManageActivity.class)),
                    getString(R.string.btn_cancel), null);
            return;
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.transfer_no_address, Toast.LENGTH_LONG).show();
            return;
        }
        if (balanceFailed) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                    getString(R.string.fund_query_fail_title),
                    getString(R.string.transfer_payer_tip_query_fail),
                    getString(R.string.btn_retry), this::reload,
                    getString(R.string.btn_cancel), null);
            return;
        }
        // 查询还在跑：按钮此时是置灰的，这只是一道兜底
        if (balanceQuerying) {
            Toast.makeText(this, R.string.transfer_amount_querying, Toast.LENGTH_SHORT).show();
            return;
        }
        // 数额模式的硬校验：收款方累计数额不得大于支付方余额（比例模式没这个问题，它分的一定不超过余额）
        if (amountMode && planTotalWei.compareTo(payerBalanceWei()) > 0) {
            BigInteger balance = payerBalanceWei();
            if (balance.signum() <= 0) {
                // 余额为 0 时谈不上「还差多少」，一句提示就够
                Toast.makeText(this, R.string.transfer_payer_tip_no_balance_amount, Toast.LENGTH_LONG).show();
                return;
            }
            StatusTipDialog.show(this, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                    getString(R.string.transfer_over_title),
                    getString(R.string.transfer_over_msg_fmt,
                            BatchTransferTask.formatGas(planTotalWei, 6),
                            BatchTransferTask.formatGas(balance, 6),
                            getString(R.string.fund_balance_fmt,
                                    BatchTransferTask.formatGas(planTotalWei.subtract(balance), 6))),
                    getString(R.string.btn_retry), this::reload,
                    getString(R.string.btn_ok), null);
            return;
        }
        if (totalWei.signum() <= 0) {
            // 比例模式是余额为 0，数额模式是所有收款方数额都填了 0，原因不一样就别共用一句
            Toast.makeText(this, amountMode ? R.string.transfer_amount_zero_tip
                    : R.string.transfer_payer_tip_no_balance, Toast.LENGTH_LONG).show();
            return;
        }

        // 支付方用的是自己那套密码，所以拿这一条的密文去校验
        CryptoKeyPrompt.showPayer(this, payer.cipherText, key -> {
            running = true;
            btnConfirm.setAlpha(0.5f);
            btnConfirm.setClickable(false);
            // 头部计数位置改显示「转账中…」
            adapter.notifyDataSetChanged();

            new Thread(() -> {
                BatchTransferTask.Result result = null;
                String error = null;
                try {
                    result = BatchTransferTask.execute(payer, rows, key, msg ->
                            AppLogDbHelper.getInstance(this).insertLog("", "TRANSFER", msg, System.currentTimeMillis()));
                } catch (Exception e) {
                    error = Web3AndroidUtil.safeMessage(e);
                } finally {
                    Arrays.fill(key, (char) 0);
                }
                final BatchTransferTask.Result fRes = result;
                final String fErr = error;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    running = false;
                    adapter.notifyDataSetChanged();
                    refreshActions();
                    if (fErr != null) {
                        StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                                getString(R.string.transfer_fail_title), fErr);
                        return;
                    }
                    String msg = getString(R.string.transfer_done_fmt, fRes.success, fRes.failed);
                    AppLogDbHelper.getInstance(this).insertLog("", "TRANSFER", msg, System.currentTimeMillis());
                    StatusTipDialog.show(this,
                            fRes.failed > 0 ? StatusTipDialog.TONE_WARN : StatusTipDialog.TONE_SUCCESS,
                            R.drawable.ic_menu_check_svg,
                            getString(R.string.transfer_result_title), msg,
                            getString(R.string.btn_ok), () -> reload(), null, null);
                });
            }).start();
        });
    }

    // endregion

    // region 列表适配器

    /** 头部（支付方卡片 + 比例汇总）与明细行共用，position 0 固定为头部 */
    private class TransferAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return rows.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_transfer_header, parent, false));
            }
            return new RowVH(inflater.inflate(R.layout.item_transfer_row, parent, false));
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
            // 明细计数兼作执行进度位：转账中优先显示状态
            h.tvDetailCount.setText(running ? getString(R.string.transfer_running)
                    : getString(R.string.transfer_detail_count_fmt, rows.size()));

            if (payer == null) {
                h.tvPayerNo.setText(R.string.transfer_payer_unset);
                h.tvPayerAddr.setText("");
            } else {
                h.tvPayerNo.setText(payer.label());
                h.tvPayerAddr.setText(shortAddr(payer.address));
            }

            if (payer != null && balanceQuerying) {
                h.tvPayerBalance.setText(R.string.transfer_amount_querying);
                // 数额模式的计划总额不依赖余额，本地已算出来，没必要跟着显示「查询中」
                h.tvTotalAlloc.setText(amountMode
                        ? getString(R.string.fund_balance_fmt, BatchTransferTask.formatGas(planTotalWei, 6))
                        : getString(R.string.transfer_amount_querying));
            } else if (payerBalance == null) {
                h.tvPayerBalance.setText(R.string.common_placeholder);
                h.tvTotalAlloc.setText(R.string.common_placeholder);
            } else {
                h.tvPayerBalance.setText(getString(R.string.fund_balance_fmt,
                        BatchTransferTask.formatGas(payerBalance, 6)));
                h.tvTotalAlloc.setText(getString(R.string.fund_balance_fmt,
                        BatchTransferTask.formatGas(amountMode ? planTotalWei : totalWei, 6)));
            }

            // 「可分配总额」在数额模式里其实是「累计需转出」，同一个位置两种语义，得标清楚
            h.tvTotalLabel.setText(amountMode
                    ? R.string.transfer_total_short_amount : R.string.transfer_total_short);

            if (amountMode) {
                // 数额没有 100% 这个上限可对照，所以对照的是支付方余额本身
                h.tvRatioLine.setText(payerBalance == null
                        ? getString(R.string.addr_amount_total_fmt, BatchTransferTask.formatGas(planTotalWei, 6))
                        : getString(R.string.transfer_amount_line_fmt,
                                BatchTransferTask.formatGas(planTotalWei, 6),
                                BatchTransferTask.formatGas(payerBalance, 6)));
            } else {
                BigDecimal leftRatio = AddressBookManager.RATIO_MAX.subtract(ratioSum);
                if (leftRatio.signum() < 0) leftRatio = BigDecimal.ZERO;
                h.tvRatioLine.setText(getString(R.string.transfer_ratio_line_fmt,
                        ratioSum.stripTrailingZeros().toPlainString(),
                        leftRatio.stripTrailingZeros().toPlainString()));
            }

            // 提示优先级：未设置支付方 > 查询失败 > 累计数额超额 > 余额为 0 > 数额全为 0 > 原生币不够付网络费
            BigInteger balance = payerBalance == null ? BigInteger.ZERO : payerBalance;
            String tip = null;
            if (payer == null) {
                tip = getString(R.string.transfer_payer_tip_unset);
            } else if (balanceQuerying) {
                tip = null;
            } else if (balanceFailed) {
                tip = getString(R.string.fund_query_fail_title);
            } else if (amountOverflow) {
                tip = getString(R.string.transfer_payer_tip_over_fmt,
                        BatchTransferTask.formatGas(planTotalWei, 6),
                        BatchTransferTask.formatGas(balance, 6),
                        getString(R.string.fund_balance_fmt,
                                BatchTransferTask.formatGas(planTotalWei.subtract(balance), 6)));
            } else if (balance.signum() <= 0) {
                tip = getString(amountMode && planTotalWei.signum() > 0
                        ? R.string.transfer_payer_tip_no_balance_amount : R.string.transfer_payer_tip_no_balance);
            } else if (amountMode && planTotalWei.signum() <= 0) {
                tip = getString(R.string.transfer_amount_zero_tip);
            } else if (nativeBalance != null && gasPrice.signum() > 0
                    && nativeBalance.compareTo(BatchTransferTask.gasNeed(gasPrice, payRows)) < 0) {
                tip = getString(R.string.transfer_payer_tip_gas_fmt, payRows);
            }
            h.layoutPayerTip.setVisibility(tip == null ? View.GONE : View.VISIBLE);
            if (tip != null) h.tvPayerTip.setText(tip);
        }

        private void bindRow(RowVH h, int index) {
            BatchTransferTask.Row r = rows.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvCode.setText(r.code);
            h.tvAddr.setText(shortAddr(r.address));
            // 行上的百分数与枚数只会用得上一个，跟着模式显示当前当作分配依据的那一个
            h.tvRatio.setText(amountMode
                    ? getString(R.string.transfer_row_amount_fmt, BatchTransferTask.formatGas(r.amountWei, 6))
                    : getString(R.string.transfer_row_ratio_fmt, r.ratio));

            if (balanceQuerying) {
                h.tvAmount.setText(R.string.transfer_amount_querying);
                return;
            }
            BigInteger amount = r.amountWei == null ? BigInteger.ZERO : r.amountWei;
            h.tvAmount.setText(getString(R.string.fund_balance_fmt,
                    BatchTransferTask.formatGas(amount, 6)));
        }

        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            return addr.length() > 10
                    ? addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4) : addr;
        }
    }

    /** 头部 ViewHolder：支付方编号/地址/余额、合计可分配、比例/数额汇总与提示条 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvPayerNo, tvPayerAddr, tvPayerBalance, tvTotalLabel, tvTotalAlloc, tvRatioLine, tvPayerTip, tvDetailCount;
        final View layoutPayerTip;

        HeaderVH(View v) {
            super(v);
            tvPayerNo = v.findViewById(R.id.tv_payer_no);
            tvPayerAddr = v.findViewById(R.id.tv_payer_addr);
            tvPayerBalance = v.findViewById(R.id.tv_payer_balance);
            tvTotalLabel = v.findViewById(R.id.tv_total_label);
            tvTotalAlloc = v.findViewById(R.id.tv_total_alloc);
            tvRatioLine = v.findViewById(R.id.tv_ratio_line);
            tvPayerTip = v.findViewById(R.id.tv_payer_tip);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            layoutPayerTip = v.findViewById(R.id.layout_payer_tip);
        }
    }

    /** 明细行 ViewHolder：序号/编号/比例/地址/本次转出 */
    static class RowVH extends RecyclerView.ViewHolder {
        final TextView tvSeq, tvCode, tvRatio, tvAddr, tvAmount;

        RowVH(View v) {
            super(v);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvCode = v.findViewById(R.id.tv_code);
            tvRatio = v.findViewById(R.id.tv_ratio);
            tvAddr = v.findViewById(R.id.tv_addr);
            tvAmount = v.findViewById(R.id.tv_amount);
        }
    }
    // endregion

}
