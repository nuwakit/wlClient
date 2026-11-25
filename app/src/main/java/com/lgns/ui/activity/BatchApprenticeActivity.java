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
import com.lgns.batch.BatchApprenticeTask;
import com.lgns.contract.ArenaContractApi;
import com.lgns.helper.CryptoKeyPrompt;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.ApprenticeBookManager;
import com.lgns.model.ApprenticeItem;
import com.lgns.model.PayerItem;
import com.lgns.util.Web3AndroidUtil;

import org.web3j.protocol.Web3j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量拜师页：让各启用门徒各自发一笔 bind 交易，拜入唯一的主师傅门下。
 *
 * <p>与批量归集同构（多对一）：上半部分卡是目标主师傅与「待拜师/已拜师」计数，
 * 下半部分明细是每个门徒当前的拜师状态（未拜师 / 已拜本师 / 已拜他人）。</p>
 *
 * <p>数据流：先读库铺出启用名单，再单连接逐行查 {@code masterOf}；
 * 页面状态只是参考，执行时 {@link BatchApprenticeTask} 会再逐号现查一遍——
 * bind 是一次性绑定，状态判定差一次就是白烧一笔手续费。</p>
 *
 * <p>私钥密码走私钥管理体系（{@link CryptoKeyPrompt#show}）：门徒只存引用，
 * 解密用的始终是私钥管理那份密码；bind 由门徒自己的私钥签名。</p>
 */
public class BatchApprenticeActivity extends BaseActivity {

    /** 头部卡（主师傅 + 计数）与明细行共用，position 0 固定为头部 */
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final List<BatchApprenticeTask.Row> rows = new ArrayList<>();
    private ApprenticeAdapter adapter;

    private RecyclerView rvApprentice;
    private View layoutState, pbState, btnConfirm;
    private TextView tvStateTitle, tvStateMsg, btnStateAction;

    private PayerItem master;
    /** 未拜师的门徒数（= 实际会发起的链上 bind 笔数） */
    private int pendingRows;
    /** 已拜师的门徒数（含已拜本师与已拜他人，仅头部卡展示用） */
    private int boundRows;
    private boolean stateQuerying;
    private boolean stateQueryFailed;
    /** 本轮拜师状态查询的失败原因：点「开始拜师」时再弹一次，不让它悄悄溜过去 */
    private String lastQueryError;
    private boolean running;
    /** 每次 reload 自增，丢弃过期的异步回调 */
    private int loadSeq;

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_apprentice);

        rvApprentice = findViewById(R.id.rv_apprentice);
        layoutState = findViewById(R.id.layout_state);
        pbState = findViewById(R.id.pb_state);
        tvStateTitle = findViewById(R.id.tv_state_title);
        tvStateMsg = findViewById(R.id.tv_state_msg);
        btnStateAction = findViewById(R.id.btn_state_action);
        btnConfirm = findViewById(R.id.btn_confirm);

        adapter = new ApprenticeAdapter();
        rvApprentice.setLayoutManager(new LinearLayoutManager(this));
        rvApprentice.setAdapter(adapter);

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_cancel).setOnClickListener(v -> onBackPressed());
        // 门徒在门徒管理里维护，这里留一个直达入口
        findViewById(R.id.btn_manage_disciple).setOnClickListener(v ->
                startActivity(new Intent(this, DiscipleManageActivity.class)));
        btnConfirm.setOnClickListener(v -> startApprentice());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 名单与主师傅都可能刚被改过，每次回来都重读
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
     * 两阶段重载：先读库把主师傅与门徒名单铺出来，再逐行查链上拜师状态。
     *
     * <p>每次重载自增 {@code loadSeq}，旧回调回来时序号对不上就直接丢弃，
     * 避免用户在几个页面之间来回切换导致前一次的结果覆盖后一次。</p>
     */
    private void reload() {
        final int seq = ++loadSeq;
        stateQuerying = true;
        stateQueryFailed = false;
        lastQueryError = null;
        if (rows.isEmpty()) {
            showLoadingState();
        } else {
            adapter.notifyDataSetChanged();
        }

        // 阶段一：只读库（主师傅/启用的门徒），马上就能渲染出来
        new Thread(() -> {
            ApprenticeBookManager book = new ApprenticeBookManager(this);
            PayerItem m = book.getMasterMaster();
            final List<BatchApprenticeTask.Row> built = new ArrayList<>();
            for (ApprenticeItem it : book.loadApprenticeList()) {
                if (!it.enabled) continue;
                BatchApprenticeTask.Row r = new BatchApprenticeTask.Row();
                r.code = it.label();
                r.keyId = it.keyId;
                r.address = it.address;
                built.add(r);
            }
            final PayerItem fMaster = m;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                applyLocal(built, fMaster);
                queryChain(seq);
            });
        }).start();
    }

    private void applyLocal(List<BatchApprenticeTask.Row> data, PayerItem m) {
        master = m;
        pendingRows = 0;
        boundRows = 0;

        rows.clear();
        rows.addAll(data);
        for (BatchApprenticeTask.Row r : rows) {
            r.masterOfAddr = null;   // null = 还没查到，不能当「未拜师」
        }

        if (rows.isEmpty()) {
            showEmptyState();
        } else {
            showContentState();
            adapter.notifyDataSetChanged();
        }
        refreshActions();
    }

    /** 阶段二：单连接逐行查 masterOf，回填各门徒当前拜的师傅 */
    private void queryChain(int seq) {
        if (rows.isEmpty()) {
            stateQuerying = false;
            refreshActions();
            return;
        }
        final List<BatchApprenticeTask.Row> targets = new ArrayList<>(rows);
        new Thread(() -> {
            String error = null;
            Web3j w3 = null;
            try {
                w3 = Web3AndroidUtil.dialAny();
                for (BatchApprenticeTask.Row r : targets) {
                    r.masterOfAddr = ArenaContractApi.callMasterOf(w3, r.address);
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
        stateQuerying = false;
        stateQueryFailed = error != null;
        lastQueryError = error;

        pendingRows = 0;
        boundRows = 0;
        for (BatchApprenticeTask.Row r : rows) {
            if (r.masterOfAddr == null) continue;
            if (r.masterOfAddr.isEmpty()) pendingRows++;
            else boundRows++;
        }
        // 明细行的「已拜本师/已拜他人」在 bindRow 里逐行现比，不依赖这里的计数

        adapter.notifyDataSetChanged();
        refreshActions();

        if (stateQueryFailed && !rows.isEmpty()) {
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
        // 与批量归集一致：只在「正在查询状态 / 拜师中 / 没有门徒」时置灰。
        // 没设主师傅、全部都已拜师都不置灰——点下去再告诉用户原因
        boolean ok = !running && !stateQuerying && !rows.isEmpty();
        btnConfirm.setAlpha(ok ? 1f : 0.5f);
        btnConfirm.setClickable(ok);
    }

    // endregion

    // region 状态视图

    private void showLoadingState() {
        layoutState.setVisibility(View.VISIBLE);
        rvApprentice.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.VISIBLE);
        tvStateTitle.setText(R.string.apprentice_state_loading_title);
        tvStateMsg.setText(R.string.apprentice_state_loading_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.btn_cancel), this::finish);
    }

    private void showEmptyState() {
        layoutState.setVisibility(View.VISIBLE);
        rvApprentice.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.GONE);
        tvStateTitle.setText(R.string.apprentice_state_empty_title);
        tvStateMsg.setText(R.string.apprentice_state_empty_msg);
        tvStateMsg.setVisibility(View.VISIBLE);
        setStateAction(getString(R.string.apprentice_go_disciple),
                () -> startActivity(new Intent(this, DiscipleManageActivity.class)));
    }

    private void showContentState() {
        layoutState.setVisibility(View.GONE);
        rvApprentice.setVisibility(View.VISIBLE);
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

    // region 执行拜师

    private void startApprentice() {
        if (running) return;
        if (master == null || master.address == null || master.address.isEmpty()) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_WARN, R.drawable.ic_warning_svg,
                    getString(R.string.apprentice_no_master), null,
                    getString(R.string.apprentice_go_master), () ->
                            startActivity(new Intent(this, MasterManageActivity.class)),
                    getString(R.string.btn_cancel), null);
            return;
        }
        if (rows.isEmpty()) {
            Toast.makeText(this, R.string.apprentice_state_empty_title, Toast.LENGTH_LONG).show();
            return;
        }
        if (stateQueryFailed) {
            StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                    getString(R.string.fund_query_fail_title),
                    getString(R.string.fund_query_fail_msg_fmt, lastQueryError),
                    getString(R.string.btn_retry), this::reload,
                    getString(R.string.btn_cancel), null);
            return;
        }
        // 查询还在跑：按钮此时是置灰的，这只是一道兜底
        if (stateQuerying) {
            Toast.makeText(this, R.string.apprentice_querying, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingRows <= 0) {
            Toast.makeText(this, R.string.apprentice_nothing_to_bind, Toast.LENGTH_LONG).show();
            return;
        }

        // 门徒引用的是私钥管理，密码校验走私钥体系那套
        CryptoKeyPrompt.show(this, key -> {
            running = true;
            btnConfirm.setAlpha(0.5f);
            btnConfirm.setClickable(false);
            // 头部计数位置改显示「拜师中…」
            adapter.notifyDataSetChanged();

            final String toMaster = master.address;
            new Thread(() -> {
                BatchApprenticeTask.Result result = null;
                String error = null;
                try {
                    result = BatchApprenticeTask.execute(toMaster, rows, key, msg ->
                            AppLogDbHelper.getInstance(this).insertLog("", "APPRENTICE", msg, System.currentTimeMillis()));
                } catch (Exception e) {
                    error = Web3AndroidUtil.safeMessage(e);
                } finally {
                    Arrays.fill(key, (char) 0);
                }
                final BatchApprenticeTask.Result fRes = result;
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
                    String msg = getString(R.string.apprentice_done_fmt, fRes.success, fRes.skipped, fRes.failed);
                    AppLogDbHelper.getInstance(this).insertLog("", "APPRENTICE", msg, System.currentTimeMillis());
                    StatusTipDialog.show(this,
                            fRes.failed > 0 ? StatusTipDialog.TONE_WARN : StatusTipDialog.TONE_SUCCESS,
                            R.drawable.ic_menu_check_svg,
                            getString(R.string.apprentice_result_title), msg,
                            getString(R.string.btn_ok), this::reload, null, null);
                });
            }).start();
        });
    }

    // endregion

    // region 列表适配器

    private class ApprenticeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

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
                return new HeaderVH(inflater.inflate(R.layout.item_apprentice_header, parent, false));
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
            // 明细计数兼作执行进度位：拜师中优先显示状态
            h.tvDetailCount.setText(running ? getString(R.string.apprentice_running)
                    : getString(R.string.apprentice_detail_count_fmt, rows.size(), pendingRows));

            if (master == null) {
                h.tvMasterNo.setText(R.string.apprentice_master_unset);
                h.tvMasterAddr.setText("");
            } else {
                h.tvMasterNo.setText(master.label());
                h.tvMasterAddr.setText(shortAddr(master.address));
            }

            if (stateQuerying) {
                h.tvPendingCount.setText(R.string.apprentice_querying);
                h.tvBoundCount.setText(R.string.apprentice_querying);
            } else {
                h.tvPendingCount.setText(getString(R.string.apprentice_pending_count_fmt, pendingRows));
                h.tvBoundCount.setText(getString(R.string.apprentice_pending_count_fmt, boundRows));
            }

            // 提示优先级：未设置主师傅 > 查询失败 > 全部已拜师
            String tip = null;
            if (master == null) {
                tip = getString(R.string.apprentice_no_master);
            } else if (stateQuerying || stateQueryFailed) {
                tip = stateQueryFailed ? getString(R.string.fund_query_fail_title) : null;
            } else if (pendingRows <= 0) {
                tip = getString(R.string.apprentice_nothing_to_bind);
            }
            h.layoutMasterTip.setVisibility(tip == null ? View.GONE : View.VISIBLE);
            if (tip != null) h.tvMasterTip.setText(tip);
        }

        private void bindRow(RowVH h, int index) {
            BatchApprenticeTask.Row r = rows.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvCode.setText(r.code);
            h.tvAddr.setText(shortAddr(r.address));
            if (stateQuerying) {
                h.tvRatio.setText(R.string.transfer_amount_querying);
                h.tvAmount.setText(R.string.transfer_amount_querying);
                return;
            }
            // 三态一目了然：未拜师（会发交易）/ 已拜本师（跳过）/ 已拜他人（注定失败）
            if (r.masterOfAddr == null) {
                h.tvRatio.setText(R.string.apprentice_row_state_query_fail);
                h.tvAmount.setText("");
            } else if (r.masterOfAddr.isEmpty()) {
                h.tvRatio.setText(R.string.apprentice_row_state_unbound);
                h.tvAmount.setText("");
            } else if (master != null && r.masterOfAddr.equalsIgnoreCase(master.address)) {
                h.tvRatio.setText(R.string.apprentice_row_state_same);
                h.tvAmount.setText("");
            } else {
                h.tvRatio.setText(R.string.apprentice_row_state_other);
                h.tvAmount.setText(shortAddr(r.masterOfAddr));
            }
        }

        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            return addr.length() > 10
                    ? addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4) : addr;
        }
    }

    /** 头部 ViewHolder：主师傅编号/地址、待拜师/已拜师计数、提示条与明细计数 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvMasterNo, tvMasterAddr, tvPendingCount, tvBoundCount, tvMasterTip, tvDetailCount;
        final View layoutMasterTip;

        HeaderVH(View v) {
            super(v);
            tvMasterNo = v.findViewById(R.id.tv_master_no);
            tvMasterAddr = v.findViewById(R.id.tv_master_addr);
            tvPendingCount = v.findViewById(R.id.tv_pending_count);
            tvBoundCount = v.findViewById(R.id.tv_bound_count);
            tvMasterTip = v.findViewById(R.id.tv_master_tip);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            layoutMasterTip = v.findViewById(R.id.layout_master_tip);
        }
    }

    /** 明细行 ViewHolder：序号/编号/地址/拜师状态/当前师傅（复用转账行布局） */
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
