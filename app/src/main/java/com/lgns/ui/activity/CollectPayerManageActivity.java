package com.lgns.ui.activity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.CollectBookManager;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.CollectPayerItem;
import com.lgns.model.PayerItem;
import com.lgns.ui.adapter.PayerAdapter;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 归集支付方管理页：维护批量归集的出资名单——每个账号把「超出阈值的 fLGNS」交给主接收方。
 *
 * <p>与支付方管理的本质差异：这里<b>不录入私钥</b>，只从私钥管理「导入引用」——
 * 表里存 key_id + 展示快照，密文与密码始终只留在私钥管理那一份，
 * 避免同一把私钥分裂出两套密码后互相解不开。</p>
 *
 * <p>没有主号概念（归集的“主”在接收方一侧），也没有改密入口；
 * 列表交互（分组/拖拽/全选反选/启停）与支付方管理完全同构。</p>
 */
public class CollectPayerManageActivity extends BaseActivity implements PayerAdapter.Listener {

    // region 视图与数据

    private CollectBookManager book;
    private PayerAdapter adapter;
    private RecyclerView rv;
    private View tvEmpty, cardSummary;
    private TextView tvSummaryTotal, tvSummaryEnabled, tvSummaryGroups;
    private ItemTouchHelper dragHelper;

    /** 完整行集（分组头 + 支付方行），顺序即分组顺序，用于重建可见列表与落盘排序 */
    private final List<PayerAdapter.Row> allRows = new ArrayList<>();
    /** 当前可见行集：折叠的分组只保留组头，适配器只读这一份 */
    private final List<PayerAdapter.Row> visibleRows = new ArrayList<>();
    /** 分组名 → 是否展开；刷新时沿用旧值，新分组默认展开 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();
    // 下拉选项：[（不分组）, ...分组名]
    private final List<String> groupOptions = new ArrayList<>();

    // clearView 时拿不到位置，靠它记住本次拖拽属于哪个分组
    private String draggingGroup;

    // endregion

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collect_payer_manage);
        book = new CollectBookManager(this);

        rv = findViewById(R.id.rv_payer);
        tvEmpty = findViewById(R.id.tv_empty);
        cardSummary = findViewById(R.id.card_summary);
        tvSummaryTotal = findViewById(R.id.tv_summary_total);
        tvSummaryEnabled = findViewById(R.id.tv_summary_enabled);
        tvSummaryGroups = findViewById(R.id.tv_summary_groups);

        groupOptions.add(getString(R.string.addr_group_none));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        // 全局全选/反选：作用于所有归集支付方；分组级入口在组头上，同一套执行链路
        findViewById(R.id.bulk_sel_all).setOnClickListener(v -> applyBulkEnabled(null, true));
        findViewById(R.id.bulk_invert).setOnClickListener(v -> applyBulkEnabled(null, false));
        // 归集支付方唯一的来源：从私钥管理导入引用
        findViewById(R.id.btn_import_key).setOnClickListener(v -> showImportDialog());
        findViewById(R.id.btn_empty_import).setOnClickListener(v -> showImportDialog());

        adapter = new PayerAdapter(this, visibleRows, allRows, expandedMap);
        adapter.setMasterId(-1);   // 归集支付方没有主号，任何行都不该画主号卡
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        attachDrag();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // endregion

    // region 数据加载

    /**
     * 后台加载归集支付方列表：读引用表 → 按分组切块 → 切回 UI 线程换数据并刷统计。
     *
     * <p>分组规则与支付方管理一致：只列分组表里真实存在的组，空分组不占位置，
     * 组名失效的记录归入「未分组」；展开态沿用上一次的快照。</p>
     */
    private void loadData() {
        // 展开状态在 UI 线程快照，后台线程不直接读会被 toggle 并发修改的集合
        final Map<String, Boolean> prevExpanded = new LinkedHashMap<>(expandedMap);
        new Thread(() -> {
            List<PayerItem> data = new ArrayList<>(book.loadPayerList());
            List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();

            Map<String, List<PayerItem>> groupMap = new LinkedHashMap<>();
            for (String g : groups) groupMap.put(g, new ArrayList<>());
            List<PayerItem> ungrouped = new ArrayList<>();
            for (PayerItem it : data) {
                String g = it.groupNo == null ? "" : it.groupNo;
                if (!g.isEmpty() && groupMap.containsKey(g)) {
                    groupMap.get(g).add(it);
                } else {
                    ungrouped.add(it);
                }
            }

            List<PayerAdapter.Row> newAll = new ArrayList<>();
            Map<String, Boolean> newExpanded = new LinkedHashMap<>();
            for (Map.Entry<String, List<PayerItem>> me : groupMap.entrySet()) {
                if (me.getValue().isEmpty()) continue;   // 空分组不占位置
                newAll.add(PayerAdapter.Row.header(me.getKey()));
                Boolean prev = prevExpanded.get(me.getKey());
                newExpanded.put(me.getKey(), prev != null ? prev : true);
                for (PayerItem it : me.getValue()) {
                    newAll.add(PayerAdapter.Row.item(me.getKey(), it));
                }
            }
            if (!ungrouped.isEmpty()) {
                String name = getString(R.string.common_ungrouped);
                newAll.add(PayerAdapter.Row.header(name));
                Boolean prev = prevExpanded.get(name);
                newExpanded.put(name, prev != null ? prev : true);
                for (PayerItem it : ungrouped) {
                    newAll.add(PayerAdapter.Row.item(name, it));
                }
            }

            int enabled = 0;
            for (PayerItem it : data) {
                if (it.enabled) enabled++;
            }
            int groupCount = 0;
            for (Map.Entry<String, List<PayerItem>> me : groupMap.entrySet()) {
                if (!me.getValue().isEmpty()) groupCount++;
            }
            if (!ungrouped.isEmpty()) groupCount++;

            final int fEnabled = enabled;
            final int fGroups = groupCount;
            final int fTotal = data.size();
            final List<String> fGroupNames = new ArrayList<>(groups);

            runOnUiThread(() -> {
                if (isFinishing()) return;
                allRows.clear();
                allRows.addAll(newAll);
                expandedMap.clear();
                expandedMap.putAll(newExpanded);
                groupOptions.clear();
                groupOptions.add(getString(R.string.addr_group_none));
                groupOptions.addAll(fGroupNames);

                rebuildVisibleRows();
                adapter.notifyDataSetChanged();

                boolean empty = allRows.isEmpty();
                cardSummary.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rv.setVisibility(empty ? View.GONE : View.VISIBLE);

                tvSummaryTotal.setText(String.valueOf(fTotal));
                tvSummaryEnabled.setText(String.valueOf(fEnabled));
                tvSummaryGroups.setText(String.valueOf(fGroups));
            });
        }).start();
    }

    /** 按展开态把完整行集摊平成可见行集：分组标题常驻，组内行只在展开时进入 */
    private void rebuildVisibleRows() {
        visibleRows.clear();
        String currentGroup = null;
        boolean currentExpanded = false;
        for (PayerAdapter.Row r : allRows) {
            if (r.type == PayerAdapter.Row.TYPE_HEADER) {
                currentGroup = r.groupName;
                currentExpanded = expandedMap.getOrDefault(currentGroup, false);
                visibleRows.add(r);          // 分组标题常驻
            } else if (currentExpanded) {
                visibleRows.add(r);
            }
        }
    }

    // endregion

    // region 导入私钥

    /** 弹窗里的一行：私钥记录 + 是否已导入 + 本次是否勾选 */
    private static class PickRow {
        final KeyStoreManager.KeyEntry entry;
        /** 已建立引用的行置灰，防止同一把私钥出现两条归集支付方 */
        boolean exists;
        boolean picked;

        PickRow(KeyStoreManager.KeyEntry entry) {
            this.entry = entry;
        }

        /** 地址缺失的旧私钥不能导入：归集靠快照地址防串号，没地址连核对都做不了 */
        boolean selectable() {
            return !exists && entry.address != null && !entry.address.isEmpty();
        }
    }

    /**
     * 导入私钥弹窗：列出私钥管理全部账号，勾选后批量建引用。
     *
     * <p>读库放后台（私钥可能上千条），勾选项只在弹窗内存活；
     * 导入落库也放后台，完成后回调 {@code loadData()} 刷新列表。</p>
     */
    private void showImportDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_collect_import_key, null);
        RecyclerView rvKeys = view.findViewById(R.id.rv_import_keys);
        View tvImportEmpty = view.findViewById(R.id.tv_import_empty);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        final List<PickRow> pickRows = new ArrayList<>();
        final RecyclerView.Adapter<?> pickAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_collect_import_key, parent, false)) { };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                PickRow pr = pickRows.get(position);
                CheckBox cb = holder.itemView.findViewById(R.id.cb_pick);
                TextView tvBadge = holder.itemView.findViewById(R.id.tv_badge);
                TextView tvCode = holder.itemView.findViewById(R.id.tv_code);
                TextView tvAddr = holder.itemView.findViewById(R.id.tv_addr);
                TextView tvState = holder.itemView.findViewById(R.id.tv_state);

                String label = pr.entry.customCode == null || pr.entry.customCode.isEmpty()
                        ? (pr.entry.address == null || pr.entry.address.isEmpty()
                                ? "?" : shortAddr(pr.entry.address))
                        : pr.entry.customCode;
                tvCode.setText(label);
                tvBadge.setText(label.isEmpty() ? "?"
                        : String.valueOf(label.charAt(0)).toUpperCase());
                tvBadge.setBackgroundTintList(ColorStateList.valueOf(
                        BADGE_COLORS[Math.abs(label.hashCode() % BADGE_COLORS.length)]));
                tvAddr.setText(pr.entry.address == null || pr.entry.address.isEmpty()
                        ? getString(R.string.collect_key_no_addr) : shortAddr(pr.entry.address));

                cb.setOnCheckedChangeListener(null);
                cb.setEnabled(pr.selectable());
                cb.setChecked(pr.picked);
                cb.setOnCheckedChangeListener((b, checked) -> pr.picked = checked);
                if (pr.exists) {
                    tvState.setVisibility(View.VISIBLE);
                    tvState.setText(R.string.collect_import_state_done);
                } else if (!pr.selectable()) {
                    tvState.setVisibility(View.VISIBLE);
                    tvState.setText(R.string.collect_key_no_addr);
                } else {
                    tvState.setVisibility(View.GONE);
                }
                holder.itemView.setOnClickListener(v -> {
                    if (pr.selectable()) cb.setChecked(!cb.isChecked());
                });
            }

            @Override
            public int getItemCount() {
                return pickRows.size();
            }
        };
        rvKeys.setLayoutManager(new LinearLayoutManager(this));
        rvKeys.setAdapter(pickAdapter);

        // 读私钥列表 + 已导入判定都涉及查库，放后台线程，回来再灌数据
        new Thread(() -> {
            // 纯元数据读取走 getMetaManager：会话过期/直启未解锁时照常返回，不抛「会话已过期」
            final List<KeyStoreManager.KeyEntry> keys =
                    AppGlobal.getMetaManager().loadKeyEntryList();
            final List<PickRow> rows = new ArrayList<>();
            for (KeyStoreManager.KeyEntry k : keys) {
                PickRow pr = new PickRow(k);
                pr.exists = book.collectPayerKeyExists(k.id);
                rows.add(pr);
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                pickRows.clear();
                pickRows.addAll(rows);
                pickAdapter.notifyDataSetChanged();
                // 空态用独立 TextView 切换，不塞给 RecyclerView，避免布局套布局
                boolean empty = pickRows.isEmpty();
                rvKeys.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvImportEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            });
        }).start();

        // 全选/反选只作用于可勾选行：已导入的永远保持灰着
        view.findViewById(R.id.dlg_sel_all).setOnClickListener(v -> {
            for (PickRow pr : pickRows) {
                if (pr.selectable()) pr.picked = true;
            }
            pickAdapter.notifyDataSetChanged();
        });
        view.findViewById(R.id.dlg_invert).setOnClickListener(v -> {
            for (PickRow pr : pickRows) {
                if (pr.selectable()) pr.picked = !pr.picked;
            }
            pickAdapter.notifyDataSetChanged();
        });

        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            final List<KeyStoreManager.KeyEntry> selected = new ArrayList<>();
            for (PickRow pr : pickRows) {
                if (pr.picked && pr.selectable()) selected.add(pr.entry);
            }
            if (selected.isEmpty()) {
                Toast.makeText(this, pickRows.isEmpty()
                                ? R.string.collect_import_empty
                                : R.string.collect_import_none_selected,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(() -> {
                int imported = book.importFromKeys(selected);
                runOnUiThread(() -> {
                    if (imported > 0) {
                        Toast.makeText(this, getString(R.string.collect_import_saved_fmt, imported),
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, R.string.collect_import_all_exists, Toast.LENGTH_LONG).show();
                    }
                    dialog.dismiss();
                    loadData();
                });
            }).start();
        });
    }

    /** 徽标取色盘：与 PayerAdapter 私有一致，保证弹窗与列表同一账号同一颜色 */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    // endregion

    // region 列表交互回调

    /** 点击分组标题：切换展开态后重建可见列表（只改内存，不落盘） */
    @Override
    public void onToggleGroup(String groupName) {
        boolean open = expandedMap.getOrDefault(groupName, false);
        expandedMap.put(groupName, !open);
        rebuildVisibleRows();
        adapter.notifyDataSetChanged();
    }

    /** 组级全选 / 反选：groupName 由适配器传入，非空 */
    @Override
    public void onGroupBatch(String groupName, boolean selectAll) {
        applyBulkEnabled(groupName, selectAll);
    }

    /** 批量启用切换重入 guard：全选/反选逐条写库，连点会双倍写库 */
    private volatile boolean bulkRunning = false;

    /**
     * 批量启用切换（全选/反选）：groupName 为 null 时作用全部分组。
     * 与支付方管理同口径：这里没有主号要保护，任何行都参与切换。
     */
    private void applyBulkEnabled(String groupName, boolean selectAll) {
        if (bulkRunning) {
            Toast.makeText(this, R.string.bulk_running_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<PayerItem> targets = new ArrayList<>();
        for (PayerAdapter.Row r : allRows) {
            if (r.type != PayerAdapter.Row.TYPE_ITEM || r.item == null) continue;
            if (groupName != null && !groupName.equals(r.groupName)) continue;
            targets.add(r.item);
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.bulk_no_target, Toast.LENGTH_SHORT).show();
            return;
        }
        bulkRunning = true;
        new Thread(() -> {
            boolean failed = false;
            for (PayerItem it : targets) {
                boolean want = selectAll || !it.enabled;
                if (it.enabled == want) continue;
                try {
                    book.setPayerEnabled(it.id, want);
                    it.enabled = want;
                } catch (Exception e) {
                    failed = true;
                }
            }
            final boolean fFailed = failed;
            runOnUiThread(() -> {
                bulkRunning = false;
                if (fFailed) {
                    Toast.makeText(this, R.string.toast_enabled_save_failed, Toast.LENGTH_SHORT).show();
                }
                loadData();   // 重拉一次：开关与概览统计都以库里真实值为准
            });
        }).start();
    }

    private void attachDrag() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0) return false;
                if (from >= visibleRows.size() || to >= visibleRows.size()) return false;
                PayerAdapter.Row a = visibleRows.get(from);
                PayerAdapter.Row b = visibleRows.get(to);
                if (a.type != PayerAdapter.Row.TYPE_ITEM || b.type != PayerAdapter.Row.TYPE_ITEM) {
                    return false;
                }
                if (!a.groupName.equals(b.groupName)) return false;   // 跨组拖拽不生效

                draggingGroup = a.groupName;
                Collections.swap(visibleRows, from, to);
                // 完整列表同步交换，否则折叠再展开会跳回旧顺序
                int fa = allRows.indexOf(a);
                int fb = allRows.indexOf(b);
                if (fa >= 0 && fb >= 0) Collections.swap(allRows, fa, fb);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) { }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(recyclerView, vh);
                if (draggingGroup == null) return;
                draggingGroup = null;
                saveSortOrder();
            }
        };
        dragHelper = new ItemTouchHelper(cb);
        dragHelper.attachToRecyclerView(rv);
    }

    /** 按完整列表顺序整体落库：组内顺序正确，全局顺序也稳定 */
    private void saveSortOrder() {
        List<Long> ids = new ArrayList<>();
        for (PayerAdapter.Row r : allRows) {
            if (r.type == PayerAdapter.Row.TYPE_ITEM) ids.add(r.item.id);
        }
        new Thread(() -> book.savePayerSortOrder(ids)).start();
    }

    @Override
    public void onStartDrag(RecyclerView.ViewHolder holder) {
        if (dragHelper != null) dragHelper.startDrag(holder);
    }

    /** 行内编辑按钮：直接用当前行数据打开编辑弹窗，不需要重新查库 */
    @Override
    public void onEdit(PayerItem item) {
        if (item != null) showEditDialog(item);
    }

    /**
     * 行尾「转账记录」图标：跳到该支付方的链上转账列表页。
     * 只传地址与展示名（无密文/私钥），列表页自己走只读接口取数。
     */
    @Override
    public void onQueryTransfers(PayerItem item) {
        if (item == null || item.address == null || item.address.isEmpty()) {
            Toast.makeText(this, R.string.txn_no_address, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, TransferListActivity.class);
        intent.putExtra(TransferListActivity.EXTRA_ADDRESS, item.address);
        intent.putExtra(TransferListActivity.EXTRA_LABEL, item.label());
        startActivity(intent);
    }

    /** 删除前二次确认：只断引用，私钥管理里的原记录不动，所以文案特意点明这一点 */
    @Override
    public void onDelete(PayerItem item) {
        if (item == null) return;
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                R.drawable.ic_delete_warn_svg,
                getString(R.string.collect_payer_delete_title),
                getString(R.string.collect_payer_delete_msg_fmt, item.label(), shortAddr(item.address)),
                getString(R.string.payer_delete_confirm_btn),
                () -> new Thread(() -> {
                    book.deletePayer(item.id);
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.toast_key_deleted, Toast.LENGTH_SHORT).show();
                        loadData();
                    });
                }).start(),
                getString(R.string.btn_cancel), null);
    }

    /**
     * 启用开关：先改内存与汇总（界面立即响应），再后台落库；
     * 失败则回滚内存并刷新行，避免出现「界面显示已启用、库里其实没开」的分歧。
     */
    @Override
    public void onToggle(PayerItem item, boolean enabled) {
        final boolean previous = item.enabled;
        item.enabled = enabled;
        refreshSummaryCounts();
        new Thread(() -> {
            boolean ok = true;
            try {
                book.setPayerEnabled(item.id, enabled);
            } catch (Exception e) {
                ok = false;
            }
            if (ok) return;
            runOnUiThread(() -> {
                // 落库失败：内存与 UI 一起回滚，避免“界面开了但库里没开”
                item.enabled = previous;
                refreshSummaryCounts();
                adapter.notifyDataSetChanged();
                Toast.makeText(this, R.string.toast_enabled_save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // endregion

    // region 汇总

    /** 只重算「已启用」这一个数字：总条数与分组数只随列表重建变化，开关不影响它们 */
    private void refreshSummaryCounts() {
        int enabled = 0;
        for (PayerAdapter.Row r : allRows) {
            if (r.type == PayerAdapter.Row.TYPE_ITEM && r.item.enabled) enabled++;
        }
        tvSummaryEnabled.setText(String.valueOf(enabled));
    }

    // endregion

    // region 编辑

    /**
     * 编辑弹窗：复用支付方的编辑布局，但归集支付方只有编号与分组可改——
     * 地址是私钥推导的快照、主号勾选整块隐藏，改引用只能删除后重新导入。
     *
     * <p>原分组已被删除时把旧组名补进下拉，否则默认选中第 0 项会静默换组。</p>
     */
    private void showEditDialog(PayerItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_payer_edit, null);
        TextView tvTitle = view.findViewById(R.id.tv_edit_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_edit_subtitle);
        EditText etCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);
        TextView tvAddr = view.findViewById(R.id.tv_addr);
        CheckBox cbMaster = view.findViewById(R.id.cb_master);

        tvTitle.setText(R.string.collect_payer_edit_title);
        tvSubtitle.setText(R.string.collect_payer_edit_subtitle);
        cbMaster.setVisibility(View.GONE);   // 归集支付方没有主号
        etCode.setText(item.customCode != null ? item.customCode : "");
        tvAddr.setText(item.address != null ? item.address : getString(R.string.key_addr_unknown));

        final List<String> options = new ArrayList<>(groupOptions);
        ArrayAdapter<String> ga = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGroup.setAdapter(ga);

        String g = item.groupNo == null ? "" : item.groupNo;
        int idx;
        if (g.isEmpty()) {
            idx = 0;   // 未分组：落到下拉第一项（不分组）
        } else {
            idx = options.indexOf(g);
            if (idx < 0) {
                // 原分组已被删除但记录还挂着旧分组名：补进下拉，避免保存时静默换组
                options.add(g);
                ga.notifyDataSetChanged();
                idx = options.size() - 1;
            }
        }
        spGroup.setSelection(idx);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            String newCode = etCode.getText().toString().trim();
            if (!newCode.isEmpty() && isCodeUsed(newCode, item.id)) {
                Toast.makeText(this, R.string.err_custom_code_duplicated, Toast.LENGTH_LONG).show();
                return;
            }
            int pos = spGroup.getSelectedItemPosition();
            final String newGroup = (pos <= 0 || pos >= options.size()) ? "" : options.get(pos);
            dialog.dismiss();
            new Thread(() -> {
                try {
                    book.updatePayerMeta(item.id, newCode, newGroup);
                    runOnUiThread(this::loadData);
                } catch (Exception e) {
                    final String msg = Web3AndroidUtil.safeMessage(e);
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.toast_save_failed_fmt, msg), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
    }

    /** 编号不能重名，否则归集明细按编号匹配时会串号（与支付方管理同口径） */
    private boolean isCodeUsed(String code, long excludeId) {
        for (CollectPayerItem e : book.loadPayerList()) {
            if (e.id != excludeId && code.equals(e.customCode)) return true;
        }
        return false;
    }

    // endregion

    // region 辅助方法

    /** 地址缩略：与主界面账号行同口径，0x1234...abcd */
    private String shortAddr(String addr) {
        if (addr == null || addr.isEmpty()) return getString(R.string.key_addr_unknown);
        if (addr.length() > 10) {
            return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
        }
        return addr;
    }
    // endregion

}
