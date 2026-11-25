package com.lgns.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
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

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lgns.R;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.ApprenticeBookManager;
import com.lgns.model.PayerItem;
import com.lgns.ui.adapter.PayerAdapter;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 师傅管理页：维护批量拜师的目标名单，并指定唯一的「主师傅」。
 *
 * <p>与归集接收方管理同构：这里的「主号」是拜师交易的目标地址（bind 的入参），
 * 列表复用 {@link PayerAdapter}，主号胶囊文案换成「主师傅」。</p>
 *
 * <p>与归集接收方页的差异：拜师没有阈值概念，右上角齿轮按钮整个去掉，
 * 摘要行改为显示「当前主师傅」——批量拜师只认这一个目标。</p>
 */
public class MasterManageActivity extends BaseActivity implements PayerAdapter.Listener {

    // region 视图与数据

    private ApprenticeBookManager book;
    private PayerAdapter adapter;
    private RecyclerView rv;
    private View tvEmpty;
    private TextView tvMasterSummary;
    private ItemTouchHelper dragHelper;

    /** 完整行集（分组头 + 师傅行），顺序即分组顺序，用于重建可见列表与落盘排序 */
    private final List<PayerAdapter.Row> allRows = new ArrayList<>();
    /** 当前可见行集：折叠的分组只保留组头，适配器只读这一份 */
    private final List<PayerAdapter.Row> visibleRows = new ArrayList<>();
    /** 分组名 → 是否展开；刷新时沿用旧值，新分组默认展开 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();
    // 下拉选项：[（不分组）, ...分组名]
    private final List<String> groupOptions = new ArrayList<>();

    // clearView 时拿不到位置，靠它记住本次拖拽属于哪个分组
    private String draggingGroup;
    // 主师傅的 id，缓存后 bind 不必逐条查库
    private long masterId = -1;

    // endregion

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_master_manage);
        book = new ApprenticeBookManager(this);

        rv = findViewById(R.id.rv_master);
        tvEmpty = findViewById(R.id.tv_empty);
        tvMasterSummary = findViewById(R.id.tv_master_summary);

        groupOptions.add(getString(R.string.addr_group_none));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        // 全局全选/反选：作用于所有师傅；分组级入口在组头上，同一套执行链路
        findViewById(R.id.bulk_sel_all).setOnClickListener(v -> applyBulkEnabled(null, true));
        findViewById(R.id.bulk_invert).setOnClickListener(v -> applyBulkEnabled(null, false));

        adapter = new PayerAdapter(this, visibleRows, allRows, expandedMap);
        adapter.setMasterPillText(getString(R.string.master_pill));
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        attachDrag();

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showAddDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // endregion

    // region 数据加载

    /**
     * 后台加载师傅列表：读记录 → 按分组切块 → 切回 UI 线程换数据并刷主师傅摘要。
     *
     * <p>分组规则与归集接收方页一致：只列分组表里真实存在的组，空分组不占位置，
     * 组名失效的记录归入「未分组」；展开态沿用上一次的快照。</p>
     */
    private void loadData() {
        // 展开状态在 UI 线程快照，后台线程不直接读会被 toggle 并发修改的集合
        final Map<String, Boolean> prevExpanded = new LinkedHashMap<>(expandedMap);
        new Thread(() -> {
            List<PayerItem> data = book.loadMasterList();
            List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
            long id = book.getMasterMasterId();
            PayerItem master = book.getMasterMaster();

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

            final long fMasterId = id;
            final String fSummary = master == null
                    ? getString(R.string.master_current_summary_none)
                    : getString(R.string.master_current_summary_fmt,
                            master.customCode == null || master.customCode.isEmpty()
                                    ? "?" : master.customCode,
                            shortAddr(master.address));
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

                masterId = fMasterId;
                adapter.setMasterId(fMasterId);
                rebuildVisibleRows();
                adapter.notifyDataSetChanged();

                boolean empty = allRows.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rv.setVisibility(empty ? View.GONE : View.VISIBLE);
                tvMasterSummary.setText(fSummary);
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
     * 主师傅也参与切换——它停用后批量拜师会回落到第一个启用行，由页面在拜师页兜底提示。
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
                    book.setMasterEnabled(it.id, want);
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
                loadData();   // 重拉一次：开关与摘要都以库里真实值为准
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
        new Thread(() -> book.saveMasterSortOrder(ids)).start();
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
     * 行尾「转账记录」图标：跳到该师傅地址的链上转账列表页。
     * 只传地址与展示名，列表页自己走只读接口取数。
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

    /** 删除前二次确认；删的是主师傅时额外提示「请重新指定」，删完重拉列表 */
    @Override
    public void onDelete(PayerItem item) {
        if (item == null) return;
        final boolean wasMaster = item.id == masterId;
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                R.drawable.ic_delete_warn_svg,
                getString(R.string.master_delete_title),
                getString(R.string.master_delete_msg_fmt, item.label()),
                getString(R.string.payer_delete_confirm_btn),
                () -> new Thread(() -> {
                    book.deleteMaster(item.id);
                    runOnUiThread(() -> {
                        Toast.makeText(this, wasMaster
                                ? R.string.master_cleared_on_delete
                                : R.string.toast_key_deleted, Toast.LENGTH_SHORT).show();
                        loadData();
                    });
                }).start(),
                getString(R.string.btn_cancel), null);
    }

    /**
     * 启用开关：先改内存（界面立即响应），再后台落库；
     * 失败则回滚内存并刷新行，避免出现「界面显示已启用、库里其实没开」的分歧。
     */
    @Override
    public void onToggle(PayerItem item, boolean enabled) {
        final boolean previous = item.enabled;
        item.enabled = enabled;
        new Thread(() -> {
            boolean ok = true;
            try {
                book.setMasterEnabled(item.id, enabled);
            } catch (Exception e) {
                ok = false;
            }
            if (ok) return;
            runOnUiThread(() -> {
                // 落库失败：内存与 UI 一起回滚，避免“界面开了但库里没开”
                item.enabled = previous;
                adapter.notifyDataSetChanged();
                Toast.makeText(this, R.string.toast_enabled_save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // endregion

    // region 新增与编辑

    /** 新增师傅弹窗：主师傅勾选隐藏——库里没有主号时第一条自动成为主师傅 */
    private void showAddDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_master_edit, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        tvTitle.setText(R.string.master_add_title);
        view.findViewById(R.id.layout_set_master).setVisibility(View.GONE);
        openEditDialogCommon(view, null);
    }

    /** 编辑师傅弹窗：显示「设为主师傅」勾选，勾选变化即时落 META */
    private void showEditDialog(PayerItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_master_edit, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        tvTitle.setText(R.string.master_edit_title);
        // 布局里勾选区默认 gone（新增时不显示），编辑时必须显式亮出来，
        // 否则「设为主师傅」永远看不见（与归集接收方页同病同治）
        view.findViewById(R.id.layout_set_master).setVisibility(View.VISIBLE);
        EditText etAddress = view.findViewById(R.id.et_master_address);
        EditText etCode = view.findViewById(R.id.et_master_code);
        etAddress.setText(item.address != null ? item.address : "");
        etCode.setText(item.customCode != null ? item.customCode : "");
        CheckBox cbMaster = view.findViewById(R.id.cb_set_master);
        cbMaster.setChecked(masterId == item.id);
        openEditDialogCommon(view, item);
    }

    /**
     * 新增/编辑共用绑定与保存：item 为 null 表示新增。
     *
     * <p>编号重名与地址查重都在保存前拦下——地址重复会让批量拜师「按地址对行」串号。
     * 原分组已被删除时把旧组名补进下拉，否则默认选中第 0 项会静默换组。</p>
     */
    private void openEditDialogCommon(View view, PayerItem item) {
        EditText etAddress = view.findViewById(R.id.et_master_address);
        EditText etCode = view.findViewById(R.id.et_master_code);
        Spinner spGroup = view.findViewById(R.id.sp_master_group);
        CheckBox cbMaster = view.findViewById(R.id.cb_set_master);
        TextView tvError = view.findViewById(R.id.tv_master_err);

        final List<String> options = new ArrayList<>(groupOptions);
        ArrayAdapter<String> ga = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        ga.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGroup.setAdapter(ga);

        String g = item == null || item.groupNo == null ? "" : item.groupNo;
        int idx;
        if (g.isEmpty()) {
            idx = 0;   // 未分组：落到下拉第一项（不分组）
        } else {
            idx = options.indexOf(g);
            if (idx < 0) {
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

        view.findViewById(R.id.btn_mst_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_mst_save).setOnClickListener(v -> {
            String addr = etAddress.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            if (code.length() > 10) {
                tvError.setVisibility(View.VISIBLE);
                tvError.setText(R.string.err_custom_code_too_long);
                return;
            }
            if (!code.isEmpty() && isCodeUsed(code, item == null ? -1 : item.id)) {
                tvError.setVisibility(View.VISIBLE);
                tvError.setText(R.string.err_custom_code_duplicated);
                return;
            }
            int pos = spGroup.getSelectedItemPosition();
            final String groupNo = (pos <= 0 || pos >= options.size()) ? "" : options.get(pos);
            final boolean isMaster = cbMaster.getVisibility() == View.VISIBLE && cbMaster.isChecked();
            tvError.setVisibility(View.GONE);
            new Thread(() -> {
                try {
                    long savedId;
                    if (item == null) {
                        savedId = book.addMaster(code, groupNo, addr).id;
                    } else {
                        book.updateMaster(item.id, code, groupNo, addr);
                        savedId = item.id;
                    }
                    // 主师傅只在编辑弹窗里指定：勾选切主号，取消勾选且原本是主号则清空
                    if (item != null) {
                        if (isMaster && masterId != savedId) {
                            book.setMasterMasterId(savedId);
                        } else if (!isMaster && masterId == savedId) {
                            book.setMasterMasterId(-1);
                        }
                    }
                    runOnUiThread(() -> {
                        if (item != null && isMaster && masterId != savedId) {
                            Toast.makeText(this, R.string.master_set_master_done, Toast.LENGTH_SHORT).show();
                        }
                        dialog.dismiss();
                        loadData();
                    });
                } catch (Exception e) {
                    final String msg = Web3AndroidUtil.safeMessage(e);
                    runOnUiThread(() -> {
                        tvError.setVisibility(View.VISIBLE);
                        tvError.setText(msg);
                    });
                }
            }).start();
        });
    }

    /** 编号不能重名，否则拜师明细按编号匹配时会串号（与归集接收方同口径） */
    private boolean isCodeUsed(String code, long excludeId) {
        for (PayerItem e : book.loadMasterList()) {
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
