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
import com.lgns.batch.BatchCollectTask;
import com.lgns.batch.BatchTransferTask;
import com.lgns.contract.ArenaContractApi;
import com.lgns.helper.CryptoKeyPrompt;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.CollectBookManager;
import com.lgns.model.CollectPayerItem;
import com.lgns.model.PayerItem;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量归集页：把各归集支付方「超出阈值的 fLGNS 余额」集中转给唯一的主接收方。
 *
 * <p>与批量转账方向相反（多对一）：上半部分卡是收款的主接收方与阈值，
 * 下半部分明细是各支付方及它们本次会被归集的超出部分。</p>
 *
 * <p>数据流：先读库铺出启用名单，再单连接逐行查链上余额算超出部分；
 * 展示值只是参考，执行时 {@link BatchCollectTask} 会再按最新余额复核一遍，
 * 中途进账多归、出账少归，绝不转成负数。</p>
 *
 * <p>私钥密码走私钥管理体系（{@link CryptoKeyPrompt#show}）：归集支付方只存引用，
 * 解密用的始终是私钥管理那份密码。</p>
 */
public class BatchCollectActivity extends BaseActivity {

    /** 头部卡（主接收方 + 阈值）与明细行共用，position 0 固定为头部 */
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<BatchCollectTask.Row> rows = new ArrayList<>();
    private CollectAdapter adapter;

    private RecyclerView rvCollect;
    private View layoutState, pbState, btnConfirm;
    private TextView tvStateTitle, tvStateMsg, btnStateAction;

    private PayerItem receiver;
    /** 阈值（fLGNS 枚数字符串）与换算成 wei 的值，每次 reload 重读 */
    private String thresholdTokens = CollectBookManager.DEFAULT_THRESHOLD;
    private BigInteger thresholdWei = BigInteger.ZERO;
    /** 本次预计归集的合计（wei）：所有超阈值行的超出部分相加 */
    private BigInteger totalExcessWei = BigInteger.ZERO;
    /** 超出阈值的支付方数量（= 实际会发起的链上转账笔数） */
    private int excessRows;
    private boolean balanceQuerying;
    private boolean balanceFailed;
    /** 本轮余额查询的失败原因：点「开始归集」时再弹一次，不让它悄悄溜过去 */
    private String lastQueryError;
    private boolean running;
    /** 每次 reload 自增，丢弃过期的异步回调 */
    private int loadSeq;

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_collect);

        rvCollect = findViewById(R.id.rv_collect);
        layoutState = findViewById(R.id.layout_state);
        pbState = findViewById(R.id.pb_state);
        tvStateTitle = findViewById(R.id.tv_state_title);
        tvStateMsg = findViewById(R.id.tv_state_msg);
        btnStateAction = findViewById(R.id.btn_state_action);
        btnConfirm = findViewById(R.id.btn_confirm);

        adapter = new CollectAdapter();
        rvCollect.setLayoutManager(new LinearLayoutManager(this));
        rvCollect.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> onBackPressed());
        // 归集支付方在归集支付方管理里维护，这里留一个直达入口
        findViewById(R.id.btn_manage_collect_payer).setOnClickListener(v ->
                startActivity(new Intent(this, CollectPayerManageActivity.class)));
        btnConfirm.setOnClickListener(v -> startCollect());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 名单、阈值与主接收方都可能刚被改过，每次回来都重读
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
     * 两阶段重载：先读库把主接收方与支付方名单铺出来，再逐行查链上余额。
     *
     * <p>每次重载自增 {@code loadSeq}，旧回调回来时序号对不上就直接丢弃，
     * 避免用户在几个页面之间来回切换导致前一次的结果覆盖后一次。</p>
     */
    private void reload() {
        final int seq = ++loadSeq;
        balanceQuerying = true;
        balanceFailed = false;
        lastQueryError = null;
        if (rows.isEmpty()) {
            showLoadingState();
        } else {
            adapter.notifyDataSetChanged();
        }

        // 阶段一：只读库（阈值/主接收方/启用的归集支付方），马上就能渲染出来
        new Thread(() -> {
            CollectBookManager book = new CollectBookManager(this);
            thresholdTokens = book.getThreshold();
            PayerItem master = book.getReceiverMaster();
            final List<BatchCollectTask.Row> built = new ArrayList<>();
            for (CollectPayerItem it : book.loadPayerList()) {
                if (!it.enabled) continue;
                BatchCollectTask.Row r = new BatchCollectTask.Row();
                r.code = it.label();
                r.keyId = it.keyId;
                r.address = it.address;
                built.add(r);
            }
            final PayerItem fMaster = master;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                applyLocal(built, fMaster);
                queryChain(seq);
            });
        }).start();
    }

    private void applyLocal(List<BatchCollectTask.Row> data, PayerItem master) {
        receiver = master;
        thresholdWei = BatchCollectTask.thresholdWei(thresholdTokens);
        totalExcessWei = BigInteger.ZERO;
        excessRows = 0;

        rows.clear();
        rows.addAll(data);
        for (BatchCollectTask.Row r : rows) {
            r.balanceWei = BigInteger.ZERO;
            r.excessWei = BigInteger.ZERO;
        }

        if (rows.isEmpty()) {
            showEmptyState();
        } else {
            showContentState();
            adapter.notifyDataSetChanged();
        }
        refreshActions();
    }

    /** 阶段二：单连接逐行查支付方 fLGNS 余额，按阈值算本次归集额 */
    private void queryChain(int seq) {
        if (rows.isEmpty()) {
            balanceQuerying = false;
            refreshActions();
            return;
        }
        final List<BatchCollectTask.Row> targets = new ArrayList<>(rows);
        final BigInteger keep = thresholdWei;
        new Thread(() -> {
            String error = null;
            Web3j w3 = null;
            try {
                w3 = Web3AndroidUtil.dialAny();
                for (BatchCollectTask.Row r : targets) {
                    BigInteger bal = ArenaContractApi.callErc20Balance(w3, r.address);
                    r.balanceWei = bal;
                    BigInteger excess = bal.subtract(keep);
                    r.excessWei = excess.signum() > 0 ? excess : BigInteger.ZERO;
                }
            } catch (Exception e) {
                error = Web3AndroidUtil.safeMessage(e);
            } finally {
                if (w3 != null) {
                    try { w3.shutdown(); } catch (Exception ignored) { }
                }
            }
            final String fErr = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                applyChain(fErr);
            });
        }).start();
    }

    private void applyChain(String error) {
        balanceQuerying = false;
        balanceFailed = error != null;
        lastQueryError = error;

        totalExcessWei = BigInteger.ZERO;
        excessRows = 0;
        for (BatchCollectTask.Row r : rows) {
            if (r.excessWei != null && r.excessWei.signum() > 0) {
                totalExcessWei = totalExcessWei.add(r.excessWei);
                excessRows++;
            }
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
        // 与批量转账一致：只在「正在查询余额 / 归集中 / 没有支付方」时置灰。
        // 没设主接收方、余额都不超阈值都不置灰——点下去再告诉用户原因
        boolean ok = !running && !balanceQuerying && !rows.isEmpty();
        btnConfirm.setAlpha(ok ? 1f : 0.5f);
        btnConfirm.setClickable(ok);
    }

    // endregion

    // region 状态视图

    private void showLoadingState() {
        layoutState.setVisibility(View.VISIBLE);
        rvCollect.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.VISIBLE);
        tvStateTitle.setText(R.string.collect_state_loading_title);
        tvStateMsg.setText(R.string.collect_state_loading_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.btn_cancel), this::finish);
    }

    private void showEmptyState() {
        layoutState.setVisibility(View.VISIBLE);
        rvCollect.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.GONE);
        tvStateTitle.setText(R.string.collect_state_empty_title);
        tvStateMsg.setText(R.string.collect_state_empty_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.collect_go_payer),
                () -> startActivity(new Intent(this, CollectPayerManageActivity.class)));
    }

    private void showContentState() {
        layoutState.setVisibility(View.GONE);
        rvCollect.setVisibility(View.VISIBLE);
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

    // region 执行归集

    private void startCollect() {
        if (running) return;
        if (receiver == null || receiver.address == null || receiver.address.isEmpty()) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                    getString(R.string.collect_no_receiver), null,
                    getString(R.string.collect_go_receiver), () ->
                            startActivity(new Intent(this, CollectAddressManageActivity.class)),
                    getString(R.string.btn_cancel), null);
            return;
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.collect_state_empty_title, Toast.LENGTH_LONG).show();
            return;
        }
        if (balanceFailed) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                    getString(R.string.fund_query_fail_title),
                    getString(R.string.fund_query_fail_msg_fmt, lastQueryError),
                    getString(R.string.btn_retry), this::reload,
                    getString(R.string.btn_cancel), null);
            return;
        }
        // 查询还在跑：按钮此时是置灰的，这只是一道兜底
        if (balanceQuerying) {
            Toast.makeText(this, R.string.collect_querying, Toast.LENGTH_SHORT).show();
            return;
        }
        if (excessRows <= 0) {
            Toast.makeText(this, R.string.collect_no_excess, Toast.LENGTH_LONG).show();
            return;
        }

        // 归集支付方引用的是私钥管理，密码校验走私钥体系那套
        CryptoKeyPrompt.show(this, key -> {
            running = true;
            btnConfirm.setAlpha(0.5f);
            btnConfirm.setClickable(false);
            // 头部计数位置改显示「归集中…」
            adapter.notifyDataSetChanged();

            final String toAddr = receiver.address;
            new Thread(() -> {
                BatchCollectTask.Result result = null;
                String error = null;
                try {
                    result = BatchCollectTask.execute(toAddr, rows, key, msg ->
                            AppLogDbHelper.getInstance(this).insertLog("", "COLLECT", msg, System.currentTimeMillis()));
                } catch (Exception e) {
                    error = Web3AndroidUtil.safeMessage(e);
                } finally {
                    Arrays.fill(key, (char) 0);
                }
                final BatchCollectTask.Result fRes = result;
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
                    String msg = getString(R.string.collect_done_fmt, fRes.success, fRes.failed);
                    AppLogDbHelper.getInstance(this).insertLog("", "COLLECT", msg, System.currentTimeMillis());
                    StatusTipDialog.show(this,
                            fRes.failed > 0 ? StatusTipDialog.TONE_WARN : StatusTipDialog.TONE_SUCCESS,
                            R.drawable.ic_menu_check_svg,
                            getString(R.string.collect_result_title), msg,
                            getString(R.string.btn_ok), this::reload, null, null);
                });
            }).start();
        });
    }

    // endregion

    // region 列表适配器

    private class CollectAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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
                return new HeaderVH(inflater.inflate(R.layout.item_collect_header, parent, false));
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
            // 明细计数兼作执行进度位：归集中优先显示状态
            h.tvDetailCount.setText(running ? getString(R.string.collect_running)
                    : getString(R.string.collect_detail_count_fmt, rows.size(), excessRows));

            if (receiver == null) {
                h.tvReceiverNo.setText(R.string.collect_receiver_unset);
                h.tvReceiverAddr.setText("");
            } else {
                h.tvReceiverNo.setText(receiver.label());
                h.tvReceiverAddr.setText(shortAddr(receiver.address));
            }

            h.tvThresholdValue.setText(getString(R.string.fund_balance_fmt, thresholdTokens));

            if (balanceQuerying) {
                h.tvTotalCollect.setText(R.string.collect_querying);
            } else {
                h.tvTotalCollect.setText(getString(R.string.fund_balance_fmt,
                        BatchTransferTask.formatGas(totalExcessWei, 6)));
            }

            h.tvRuleLine.setText(getString(R.string.collect_summary_line_fmt, thresholdTokens));

            // 提示优先级：未设置主接收方 > 查询失败 > 全部未超阈值
            String tip = null;
            if (receiver == null) {
                tip = getString(R.string.collect_no_receiver);
            } else if (balanceQuerying || balanceFailed) {
                tip = balanceFailed ? getString(R.string.fund_query_fail_title) : null;
            } else if (excessRows <= 0) {
                tip = getString(R.string.collect_no_excess);
            }
            h.layoutReceiverTip.setVisibility(tip == null ? View.GONE : View.VISIBLE);
            if (tip != null) h.tvReceiverTip.setText(tip);
        }

        private void bindRow(RowVH h, int index) {
            BatchCollectTask.Row r = rows.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvCode.setText(r.code);
            h.tvAddr.setText(shortAddr(r.address));
            if (balanceQuerying) {
                h.tvRatio.setText(R.string.transfer_amount_querying);
                h.tvAmount.setText(R.string.transfer_amount_querying);
                return;
            }
            h.tvRatio.setText(getString(R.string.collect_row_balance_fmt,
                    BatchTransferTask.formatGas(r.balanceWei, 6)));
            BigInteger excess = r.excessWei == null ? BigInteger.ZERO : r.excessWei;
            h.tvAmount.setText(excess.signum() > 0
                    ? getString(R.string.collect_row_excess_fmt, BatchTransferTask.formatGas(excess, 6))
                    : getString(R.string.collect_row_no_excess));
        }

        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            return addr.length() > 10
                    ? addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4) : addr;
        }
    }

    /** 头部 ViewHolder：主接收方编号/地址、阈值、预计归集合计、规则说明与提示条 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvReceiverNo, tvReceiverAddr, tvThresholdValue, tvTotalCollect, tvRuleLine, tvReceiverTip, tvDetailCount;
        final View layoutReceiverTip;

        HeaderVH(View v) {
            super(v);
            tvReceiverNo = v.findViewById(R.id.tv_receiver_no);
            tvReceiverAddr = v.findViewById(R.id.tv_receiver_addr);
            tvThresholdValue = v.findViewById(R.id.tv_threshold_value);
            tvTotalCollect = v.findViewById(R.id.tv_total_collect);
            tvRuleLine = v.findViewById(R.id.tv_rule_line);
            tvReceiverTip = v.findViewById(R.id.tv_receiver_tip);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            layoutReceiverTip = v.findViewById(R.id.layout_receiver_tip);
        }
    }

    /** 明细行 ViewHolder：序号/编号/地址/余额/本次归集额（复用转账行布局） */
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
