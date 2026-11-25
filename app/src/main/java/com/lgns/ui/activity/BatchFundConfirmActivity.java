package com.lgns.ui.activity;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.batch.BatchFundTask;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.ArenaConfig;
import com.lgns.model.KeyListItem;
import com.lgns.util.Web3AndroidUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 批量充值确认页：把主号给各账号补币的预演结果摊成清单，确认后再真正发起转账。
 *
 * <p>「预演 → 确认 → 执行」三段式：预演只读链上余额算差额，不产生交易；
 * 用户在这里核对金额与主号余额后点确认，才交给 {@link BatchFundTask} 执行。</p>
 *
 * <p>页面状态只有三种：加载中、出错（可重试）、有内容，统一通过 {@code showXxxState} 切换，
 * 避免出现列表与错误提示同时可见的自相矛盾界面。</p>
 */
public class BatchFundConfirmActivity extends BaseActivity {

    /** 加载中：列表未出，中央显示转圈 */
    private static final int STATE_LOADING = 0;
    /** 有内容：主号卡 + 待补清单 */
    private static final int STATE_CONTENT = 1;
    /** 出错（查不到账号/节点不可用）：中央显示错误与重试 */
    private static final int STATE_ERROR = 2;

    private TextView tvHeaderCount, tvStateTitle, tvStateMsg;
    private TextView tvDegradedTip;
    private RecyclerView rvFundList;
    private TextView btnStateAction;
    private View btnCancel, btnConfirm, layoutState, pbState;

    // 主号区展示数据（查询完成后填充）
    private String masterAddr = "";
    private String masterKeyNo = "";
    private String masterBalance = "0";
    private String totalAmount = "0";
    // 主号金额还处于「查询中」占位状态
    private boolean masterPending;

    private final List<FundItem> fundList = new ArrayList<>();
    private FundAdapter adapter;

    // 查询中：确认按钮禁用、返回仍可取消
    private volatile boolean querying;
    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_batch_fund_confirm);

        tvHeaderCount = findViewById(R.id.tv_header_count);
        tvDegradedTip = findViewById(R.id.tv_degraded_tip);
        rvFundList = findViewById(R.id.rv_fund_list);
        btnCancel = findViewById(R.id.btn_cancel);
        btnConfirm = findViewById(R.id.btn_confirm);
        layoutState = findViewById(R.id.layout_state);
        pbState = findViewById(R.id.pb_state);
        tvStateTitle = findViewById(R.id.tv_state_title);
        tvStateMsg = findViewById(R.id.tv_state_msg);
        btnStateAction = findViewById(R.id.btn_state_action);

        adapter = new FundAdapter();
        rvFundList.setLayoutManager(new LinearLayoutManager(this));
        rvFundList.setAdapter(adapter);

        // 返回按钮统一模式
        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        btnCancel.setOnClickListener(v -> onBackPressed());
        btnConfirm.setOnClickListener(v -> {
            if (querying || fundList.isEmpty()) return;
            setResult(RESULT_OK);
            finish();
        });

        // 页面先起来 → 本地 SQLite 数据立即铺行 → 链上余额边查边填
        showLoadingState();
        startLocalThenQuery();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        querying = false;   // 后台线程回来时丢弃结果，避免操作已销毁的 View
    }

    // endregion

    // region 预演与数据装载

    /**
     * 两阶段加载：
     * 1) 先读 SQLite 把账号清单（编号/地址/主号卡）铺出来，金额列用「查询中…」占位；
     * 2) 再跑链上余额查询，回来后原位替换。
     * 这样账号多时不会先被空屏盖住，用户能先看到“要给谁充值”。
     */
    private void startLocalThenQuery() {
        new Thread(() -> {
            List<KeyListItem> items;
            String localMasterNo = "";
            String localMasterAddr = "";
            long masterId = -1;
            try {
                items = buildSourceItems();
                // 主号 id 只是元数据，不因会话过期而读不到
                masterId = AppGlobal.getMetaManager().getMasterAccountId();
                for (KeyListItem it : items) {
                    if (it.keyId == masterId) {
                        localMasterAddr = it.address == null ? "" : it.address;
                        localMasterNo = (it.customCode != null && !it.customCode.isEmpty())
                                ? it.customCode : it.displayNo;
                        break;
                    }
                }
            } catch (Exception e) {
                items = new ArrayList<>();
            }
            final List<KeyListItem> fItems = items;
            final long fMasterId = masterId;
            final String fNo = localMasterNo;
            final String fAddr = localMasterAddr;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fItems.isEmpty()) {
                    showErrorState(getString(R.string.stats_no_enabled_account), "", null, null);
                    return;
                }
                renderLocalRows(fItems, fMasterId, fNo, fAddr);
                startQuery(fItems);
            });
        }).start();
    }

    // 本地数据先行：只填展示性字段，金额类全部占位
    private void renderLocalRows(List<KeyListItem> items, long masterId, String masterNo, String masterAddrLocal) {
        masterKeyNo = masterNo;
        masterAddr = masterAddrLocal;
        masterBalance = getString(R.string.fund_item_querying);
        totalAmount = getString(R.string.fund_item_querying);
        masterPending = true;

        fundList.clear();
        for (KeyListItem it : items) {
            if (it.keyId == masterId) continue;          // 主号只进卡片
            if (!it.enabled) continue;                   // 禁用账号不充值不上列表
            if (it.address == null || it.address.isEmpty()) continue;
            String no = (it.customCode != null && !it.customCode.isEmpty()) ? it.customCode : it.displayNo;
            FundItem fi = new FundItem(no, null, null, it.address, false);
            fi.state = FundItem.STATE_QUERYING;
            fundList.add(fi);
        }
        adapter.notifyDataSetChanged();
        showContentState();
        btnConfirm.setAlpha(0.5f);
        btnConfirm.setClickable(false);
    }

    private void startQuery() {
        startQuery(null);
    }

    /**
     * 第二阶段：链上预演。
     *
     * <p>{@code querying} 防重入：重试按钮连点不会并发发两次查询；已有本地行时只把金额列
     * 改回占位，列表不闪；每查完一个账号立刻回填一行，顶部胶囊同步显示进度。</p>
     */
    private void startQuery(List<KeyListItem> prebuilt) {
        if (querying) return;
        querying = true;
        btnConfirm.setAlpha(0.5f);
        btnConfirm.setClickable(false);
        // 重新查询先收起降级提示，避免上一轮的备用通道标记残留在新结果上
        tvDegradedTip.setVisibility(View.GONE);

        if (fundList.isEmpty()) {
            showLoadingState();
        } else {
            // 本地行已在：只把金额列改回占位，列表不消失、不闪屏
            for (FundItem fi : fundList) {
                fi.state = FundItem.STATE_QUERYING;
            }
            adapter.notifyDataSetChanged();
            // 顶部胶囊直接显示进度，与列表行同一时间轴推进
            tvHeaderCount.setText(getString(R.string.fund_progress_pill_fmt, 0, fundList.size()));
        }

        new Thread(() -> {
            BatchFundTask.FundPreviewResult preview;
            try {
                List<KeyListItem> src = prebuilt != null ? prebuilt : buildSourceItems();
                preview = BatchFundTask.queryFundPreview(src, (done, total, detail) ->
                        runOnUiThread(() -> {
                            if (!querying || isFinishing() || isDestroyed()) return;
                            tvHeaderCount.setText(getString(R.string.fund_progress_pill_fmt, done, total));
                            fillRow(detail);
                        }));
            } catch (Exception e) {
                preview = new BatchFundTask.FundPreviewResult();
                preview.error = e;
            }
            final BatchFundTask.FundPreviewResult fPreview = preview;
            runOnUiThread(() -> {
                querying = false;
                if (isFinishing() || isDestroyed()) return;
                applyPreview(fPreview);
            });
        }).start();
    }

    // 与主界面同源：库里按 sort_order 返回，编号规则与列表一致
    private List<KeyListItem> buildSourceItems() throws Exception {
        KeyStoreManager mgr = AppGlobal.getMetaManager();
        List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();
        List<KeyListItem> items = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            KeyStoreManager.KeyEntry e = entries.get(i);
            KeyListItem item = new KeyListItem();
            item.keyId = e.id;
            item.seqNo = i + 1;
            item.displayNo = String.format(Locale.getDefault(), "%03d", e.id);
            item.customCode = e.customCode;
            item.groupNo = e.groupNo;
            item.cipherText = e.cipherText;
            item.address = e.address;
            item.enabled = e.enabled;
            item.isChecked = true;
            items.add(item);
        }
        if (items.isEmpty()) throw new Exception(getString(R.string.stats_no_enabled_account));
        // 调试开关只影响主界面/锦标赛统计的展示行，充值页仍按真实私钥清单出数
        return items;
    }

    private void applyPreview(BatchFundTask.FundPreviewResult preview) {
        if (preview.error != null) {
            markRowsFailed();
            String msg = getString(R.string.fund_query_fail_msg_fmt, Web3AndroidUtil.safeMessage(preview.error));
            if (fundList.isEmpty()) {
                showErrorState(getString(R.string.fund_query_fail_title), msg,
                        getString(R.string.btn_retry), this::startQuery);
            } else {
                // 本地清单已在屏上：保留页面上下文，用弹框提示失败并支持重试
                StatusTipDialog.show(this, StatusTipDialog.TONE_ERROR, R.drawable.ic_warning_svg,
                        getString(R.string.fund_query_fail_title), msg,
                        getString(R.string.btn_retry), this::startQuery,
                        getString(R.string.btn_cancel), null);
            }
            return;
        }
        if (!preview.hasMaster || preview.masterEntry == null) {
            showErrorState(getString(R.string.master_not_set_title),
                    getString(R.string.master_not_set_msg),
                    getString(R.string.fund_go_settings), () -> {
                        startActivity(new android.content.Intent(this, PrivateKeyManageActivity.class));
                        finish();
                    });
            return;
        }

        masterAddr = preview.masterShowAddr != null ? preview.masterShowAddr : preview.masterEntry.address;
        masterKeyNo = preview.masterKeyNo == null ? "" : preview.masterKeyNo;
        masterBalance = formatWei(preview.masterShowBalance);
        totalAmount = preview.getTotalNeededFormatted().toPlainString();
        masterPending = false;   // 主号/合计已就绪，头部退出占位文案

        fundList.clear();
        for (BatchFundTask.FundPreviewResult.FundDetail d : preview.fundDetails) {
            fundList.add(new FundItem(d.keyNo, d.currentBalance.toPlainString(),
                    d.neededAmount.toPlainString(), d.address, d.sufficient));
        }
        adapter.notifyDataSetChanged();
        btnConfirm.setAlpha(1f);
        btnConfirm.setClickable(true);

        if (fundList.isEmpty()) {
            showErrorState(getString(R.string.stats_no_enabled_account), "", (CharSequence) null, null);
            return;
        }

        showContentState();
        // 降级通道出数：必须让用户知道余额来自浏览器备用接口而非 RPC
        tvDegradedTip.setVisibility(preview.degraded ? View.VISIBLE : View.GONE);
        if (!preview.needsFunding()) {
            // 无需充值不再弹框打断，页面给出结论并置灰确认
            btnConfirm.setAlpha(0.5f);
            btnConfirm.setClickable(false);
            // 单场足额下报「连打 N 场」会误导（目标根本没按 N 场预留），文案分流
            android.widget.Toast.makeText(this,
                    ArenaConfig.fundMode == ArenaConfig.FUND_MODE_SINGLE
                            ? getString(R.string.fund_no_need_target_single_fmt, fundTargetText())
                            : getString(R.string.fund_no_need_target_fmt, fundTargetText(), ArenaConfig.enterRounds),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 逐行回填：本地占位行（查询中…）拿到结果后原位替换，
     * 不用等全部账号查完，长列表也能边查边看。
     */
    private void fillRow(BatchFundTask.FundPreviewResult.FundDetail d) {
        if (d == null) return;
        for (int i = 0; i < fundList.size(); i++) {
            FundItem fi = fundList.get(i);
            if (fi.state != FundItem.STATE_QUERYING) continue;
            if (fi.keyNo == null || !fi.keyNo.equals(d.keyNo)) continue;
            fi.currentBalance = d.currentBalance == null ? "0" : d.currentBalance.toPlainString();
            fi.needAmount = d.neededAmount == null ? "0" : d.neededAmount.toPlainString();
            fi.sufficient = d.sufficient;
            fi.state = FundItem.STATE_READY;
            adapter.notifyItemChanged(i + 1);   // +1：0 号位是头部卡片
            return;
        }
    }

    // 查询失败：把还在占位的行标成失败，主号金额退回占位符
    private void markRowsFailed() {
        for (FundItem fi : fundList) {
            if (fi.state == FundItem.STATE_QUERYING) fi.state = FundItem.STATE_FAILED;
        }
        if (masterPending) {
            masterPending = false;
            masterBalance = getString(R.string.common_placeholder);
            totalAmount = getString(R.string.common_placeholder);
        }
        adapter.notifyDataSetChanged();
    }

    private String formatWei(BigInteger wei) {
        if (wei == null) return "0";
        return new BigDecimal(wei)
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * 当前充值目标金额（整数展示）：跟着连打圈数走，不是固定 100。
     *
     * <p>与 {@link BatchFundTask#queryFundPreview} 算差额用的是同一个来源
     * （{@link ArenaConfig#fundTargetFor(int)}），两边不会跑偏。</p>
     */
    private String fundTargetText() {
        return new BigDecimal(ArenaConfig.fundTargetFor(ArenaConfig.enterRounds))
                .divide(new BigDecimal(BigInteger.TEN.pow(18)), 0, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    // endregion

    // region 状态视图

    private void showLoadingState() {
        layoutState.setVisibility(View.VISIBLE);
        rvFundList.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.VISIBLE);
        tvStateTitle.setText(R.string.fund_loading_title);
        tvStateMsg.setText(R.string.fund_loading_msg);
        // 查询期间允许退出，不用等链上全部返回
        setStateAction(getString(R.string.btn_cancel), this::finish);
    }

    private void showErrorState(CharSequence title, CharSequence msg,
                                CharSequence actionText, Runnable action) {
        layoutState.setVisibility(View.VISIBLE);
        rvFundList.setVisibility(View.INVISIBLE);
        pbState.setVisibility(View.GONE);
        tvStateTitle.setText(title);
        if (msg == null || msg.length() == 0) {
            tvStateMsg.setVisibility(View.GONE);
        } else {
            tvStateMsg.setVisibility(View.VISIBLE);
            tvStateMsg.setText(msg);
        }
        setStateAction(actionText, action);
    }

    private void showContentState() {
        layoutState.setVisibility(View.GONE);
        rvFundList.setVisibility(View.VISIBLE);
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

    // region 数据模型

    /**
     * 充值确认页的单行数据：余额与缺口都是已格式化的字符串，直接绑到 TextView。
     *
     * <p>用 Parcelable 而不是内存共享是因为列表可能因系统回收重建；
     * 金额字段故意用 String 而不是 BigDecimal：一来要显示“查询中…”这类占位文案，
     * 二来避免 Parcel 序列化精度问题。</p>
     */
    public static class FundItem implements Parcelable {
        /** 金额列状态：链上余额还没回来，界面显示占位文案 */
        public static final int STATE_QUERYING = 0;
        /** 金额列状态：已拿到余额，正常显示 */
        public static final int STATE_READY = 1;
        /** 金额列状态：查询失败，行内标红提示重试 */
        public static final int STATE_FAILED = 2;

        /** 账号展示编号（优先自定义编号，没填回退到 3 位 displayNo） */
        public String keyNo;
        /** 当前余额，已格式化的小数字符串 */
        public String currentBalance;
        /** 还需补多少，已格式化的小数字符串；已足够时为 0 */
        public String needAmount;
        /** 收款地址，链上转账的实际目标 */
        public String address;
        /** 余额是否已满足入场费，决定行内金额的字体颜色 */
        public boolean sufficient;
        /** 金额列当前状态，默认 {@link #STATE_READY}；查询前由页面改成 {@link #STATE_QUERYING} */
        public int state = STATE_READY;

        /** 用本地已知信息先建行（余额、缺口为占位文案），链上结果回来后再逐行改写字段。 */
        public FundItem(String keyNo, String currentBalance, String needAmount, String address, boolean sufficient) {
            this.keyNo = keyNo;
            this.currentBalance = currentBalance;
            this.needAmount = needAmount;
            this.address = address;
            this.sufficient = sufficient;
        }

        /**
         * 从 Parcel 重建对象。
         *
         * <p>读取顺序必须与 {@link #writeToParcel} 的写入顺序逐字段一致，
         * 否则解析出来的字符串会互相串位而不报错。</p>
         */
        protected FundItem(Parcel in) {
            keyNo = in.readString();
            currentBalance = in.readString();
            needAmount = in.readString();
            address = in.readString();
            sufficient = in.readByte() != 0;
            state = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(keyNo);
            dest.writeString(currentBalance);
            dest.writeString(needAmount);
            dest.writeString(address);
            dest.writeByte((byte) (sufficient ? 1 : 0));
            dest.writeInt(state);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /** Parcelable 反序列化入口，系统在恢复列表时调用，内部走 {@link #FundItem(Parcel)} */
        public static final Creator<FundItem> CREATOR = new Creator<FundItem>() {
            @Override
            public FundItem createFromParcel(Parcel in) {
                return new FundItem(in);
            }

            @Override
            public FundItem[] newArray(int size) {
                return new FundItem[size];
            }
        };
    }

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_ITEM = 1;

    // endregion

    // region 列表适配器

    /** 头部（汇总卡/主号卡/明细标题）与明细行共用一个列表，滚动时一起上移 */
    class FundAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return position == 0 ? VIEW_TYPE_HEADER : VIEW_TYPE_ITEM;
        }

        @Override
        public int getItemCount() {
            return fundList.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == VIEW_TYPE_HEADER) {
                return new HeaderVH(inflater.inflate(R.layout.item_fund_header, parent, false));
            }
            return new ItemVH(inflater.inflate(R.layout.item_fund_confirm, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof HeaderVH) {
                bindHeader((HeaderVH) holder);
            } else {
                bindItem((ItemVH) holder, position - 1);
            }
        }

        private void bindHeader(HeaderVH h) {
            // 金额还没回时只显示占位文案，不把「查询中…」拼上 fLGNS 后缀
            if (masterPending) {
                h.tvFundTotal.setText(R.string.fund_item_querying);
                h.tvMasterBalance.setText(R.string.fund_item_querying);
            } else {
                h.tvFundTotal.setText(getString(R.string.fund_balance_fmt,
                        totalAmount == null ? getString(R.string.common_placeholder) : totalAmount));
                h.tvMasterBalance.setText(getString(R.string.fund_balance_fmt,
                        masterBalance == null ? getString(R.string.common_placeholder) : masterBalance));
            }
            // 明细标题右侧的胶囊：告清「为什么是这个数」——目标金额由补币模式推算，
            // 与 BatchFundTask 算差额用的同一个 ArenaConfig.fundTargetFor()，不会两边不一致
            h.tvDetailCount.setText(ArenaConfig.fundMode == ArenaConfig.FUND_MODE_SINGLE
                    ? getString(R.string.fund_detail_target_single_fmt, fundTargetText())
                    : getString(R.string.fund_detail_target_fmt,
                            fundTargetText(), ArenaConfig.enterRounds));
            // 查询中胶囊显示进度，不被重新绑定覆盖回总数
            if (!querying) {
                tvHeaderCount.setText(getString(R.string.fund_hero_count_fmt, fundList.size()));
            }

            h.tvMasterNo.setText(masterKeyNo == null || masterKeyNo.isEmpty()
                    ? getString(R.string.common_placeholder) : masterKeyNo);
            h.tvMasterAddr.setText(shortAddr(masterAddr));

            // 主号余额不够本次合计时提前提醒，避免逐笔转账全部失败
            if (masterPending) {
                h.layoutMasterTip.setVisibility(View.GONE);
                return;
            }
            BigDecimal total = parseDecimal(totalAmount);
            BigDecimal balance = parseDecimal(masterBalance);
            if (total != null && balance != null && total.signum() > 0 && balance.compareTo(total) < 0) {
                h.layoutMasterTip.setVisibility(View.VISIBLE);
                h.tvMasterTip.setText(getString(R.string.fund_master_insufficient_fmt,
                        total.subtract(balance).toPlainString()));
            } else {
                h.layoutMasterTip.setVisibility(View.GONE);
            }
        }

        private BigDecimal parseDecimal(String value) {
            if (value == null || value.isEmpty()) return null;
            try {
                return new BigDecimal(value);
            } catch (Exception e) {
                return null;
            }
        }

        // 与主界面账号行一致：0x1234...abcd
        private String shortAddr(String addr) {
            if (addr == null || addr.isEmpty()) return "";
            if (addr.length() > 10) {
                return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
            }
            return addr;
        }

        private void bindItem(ItemVH h, int index) {
            FundItem item = fundList.get(index);
            h.tvSeq.setText(String.valueOf(index + 1));
            h.tvKeyNo.setText(item.keyNo);
            h.tvAddr.setText(shortAddr(item.address));

            // 本地数据先行：链上金额未回时先占位，回来后原位替换
            if (item.state == FundItem.STATE_QUERYING) {
                h.tvCurrentBalance.setText(R.string.fund_item_querying);
                h.tvNeedAmount.setText(R.string.fund_item_querying);
                h.tvNeedAmount.setTextColor(0xFF9E9E9E);
                return;
            }
            if (item.state == FundItem.STATE_FAILED) {
                h.tvCurrentBalance.setText(R.string.common_placeholder);
                h.tvNeedAmount.setText(R.string.fund_item_query_failed);
                h.tvNeedAmount.setTextColor(0xFFC62828);
                return;
            }

            h.tvCurrentBalance.setText(getString(R.string.fund_item_balance_fmt, item.currentBalance));

            boolean zero = item.needAmount == null
                    || new java.math.BigDecimal(item.needAmount).signum() == 0;
            if (zero || item.sufficient) {
                h.tvNeedAmount.setText(R.string.fund_already_sufficient);
                h.tvNeedAmount.setTextColor(0xFF2E7D32);
            } else {
                h.tvNeedAmount.setText(getString(R.string.fund_amount_plus_fmt, item.needAmount));
                h.tvNeedAmount.setTextColor(0xFFE65100);
            }
        }
    }

    /** 头部 ViewHolder：合计金额、明细条数、主号卡片与「主号不够」提醒条 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvFundTotal, tvDetailCount;
        TextView tvMasterNo, tvMasterAddr, tvMasterBalance, tvMasterTip;
        View layoutMasterTip;

        HeaderVH(View v) {
            super(v);
            tvFundTotal = v.findViewById(R.id.tv_fund_total);
            tvDetailCount = v.findViewById(R.id.tv_detail_count);
            tvMasterNo = v.findViewById(R.id.tv_master_no);
            tvMasterAddr = v.findViewById(R.id.tv_master_addr);
            tvMasterBalance = v.findViewById(R.id.tv_master_balance);
            tvMasterTip = v.findViewById(R.id.tv_master_tip);
            layoutMasterTip = v.findViewById(R.id.layout_master_tip);
        }
    }

    /** 明细行 ViewHolder：序号/编号/地址/当前余额/待补金额 */
    static class ItemVH extends RecyclerView.ViewHolder {
        TextView tvSeq, tvKeyNo, tvAddr, tvCurrentBalance, tvNeedAmount;

        ItemVH(View v) {
            super(v);
            tvSeq = v.findViewById(R.id.tv_seq);
            tvKeyNo = v.findViewById(R.id.tv_key_no);
            tvAddr = v.findViewById(R.id.tv_addr);
            tvCurrentBalance = v.findViewById(R.id.tv_current_balance);
            tvNeedAmount = v.findViewById(R.id.tv_need_amount);
        }
    }
    // endregion

}
