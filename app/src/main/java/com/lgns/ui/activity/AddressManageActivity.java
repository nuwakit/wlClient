package com.lgns.ui.activity;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
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
import com.lgns.manager.AddressBookManager;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.model.AddressItem;
import com.lgns.ui.adapter.AddressAdapter;
import com.lgns.util.Web3AndroidUtil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接收方地址管理页：维护批量转账的目标地址、收益比例、收益数额、启用状态与展示顺序。
 *
 * <p>角色说明：本页管理的是「收钱的一方」，出资方在支付方管理页；两边比例共同决定批量转账的分配结果。</p>
 *
 * <p>比例与数额各管一种转账模式（系统配置页选定），两个字段都存在同一条记录里，
 * 切模式不会丢另一边已填的值。拦住的位置也不同：比例有 100% 这个固定上限，在本页保存时就能拦；
 * 数额的上限是支付方的链上余额（动态值），本页不拦，到批量转账页查完余额才判。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>分组折叠靠 {@code expandedMap} 记住展开态，重排或刷新后不弹回默认。</li>
 *   <li>拖拽只改展示顺序，松手后由 {@code saveSortOrder} 一次性落盘，避免拖动过程中频繁写库。</li>
 *   <li>比例与数额都可以走「默认值批量下发」（右上角一个入口，两行一起改），但用户手工改过的行不再被默认值覆盖。</li>
 * </ul>
 */
public class AddressManageActivity extends BaseActivity implements AddressAdapter.Listener {

    // region 视图与数据

    private AddressBookManager book;
    private AddressAdapter adapter;
    private RecyclerView rv;
    private View tvEmpty;
    private TextView tvRatioSummary;
    private ItemTouchHelper dragHelper;

    /** 完整行集（分组头 + 地址行），用于重建可见列表与落盘顺序 */
    private final List<AddressAdapter.Row> allRows = new ArrayList<>();
    /** 当前可见行集：折叠的分组只保留组头 */
    private final List<AddressAdapter.Row> visibleRows = new ArrayList<>();
    /** 分组名 → 是否展开；刷新时沿用旧值，新分组默认展开 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();
    /** 下拉选项：[（不分组）, ...分组名]，每次加载后重建 */
    private final List<String> groupOptions = new ArrayList<>();

    // clearView 时拿不到位置，靠它记住本次拖拽属于哪个分组
    private String draggingGroup;

    // endregion

    // region 生命周期

    /**
     * 搭界面 → 建列表适配器 → 挂拖拽回调，本身不读库。
     *
     * <p>数据加载放在 {@link #onResume()}：首次进入与从分组管理/编辑弹窗返回
     * 都走同一入口，避免两处各写一套刷新逻辑。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_address_manage);
        book = new AddressBookManager(this);

        rv = findViewById(R.id.rv_address);
        tvEmpty = findViewById(R.id.tv_empty);
        tvRatioSummary = findViewById(R.id.tv_ratio_summary);

        // 下拉第一项恒为“不分组”，数据没加载完也能保存
        groupOptions.add(getString(R.string.addr_group_none));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        findViewById(R.id.btn_ratio_default).setOnClickListener(v -> showDefaultsDialog());

        adapter = new AddressAdapter(this, visibleRows, allRows, expandedMap);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        attachDrag();

        FloatingActionButton fab = findViewById(R.id.fab_add);
        fab.setOnClickListener(v -> showEditDialog(null));
    }

    /** 每次回到页面都重读一次：分组管理/弹窗里的增删都会让内存里的行集过期 */
    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    // endregion

    // region 数据加载

    /**
     * 后台加载接收方列表：读库 → 按分组切块 → 回 UI 线程换数据。
     *
     * <p>同时算一次全局占用：比例走「已用 / 剩余」（上限固定 100%），数额走「启用行合计」
     * （没有固定上限，只当参考）。两行一起放在顶部，用户不用切模式就能看到自己占了多少。</p>
     */
    private void loadData() {
        // 展开状态在 UI 线程快照，后台线程不直接读会被 toggle 并发修改的集合
        final Map<String, Boolean> prevExpanded = new LinkedHashMap<>(expandedMap);
        new Thread(() -> {
            List<AddressItem> data = book.loadAddressList();
            List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
            BigDecimal used = book.enabledRatioSum(-1);
            BigDecimal amountUsed = book.enabledAmountSum(-1);

            Map<String, List<AddressItem>> groupMap = new LinkedHashMap<>();
            for (String g : groups) groupMap.put(g, new ArrayList<>());
            List<AddressItem> ungrouped = new ArrayList<>();
            for (AddressItem it : data) {
                String g = it.groupNo == null ? "" : it.groupNo;
                if (!g.isEmpty() && groupMap.containsKey(g)) {
                    groupMap.get(g).add(it);
                } else {
                    ungrouped.add(it);
                }
            }

            List<AddressAdapter.Row> newAll = new ArrayList<>();
            Map<String, Boolean> newExpanded = new LinkedHashMap<>();
            for (Map.Entry<String, List<AddressItem>> me : groupMap.entrySet()) {
                if (me.getValue().isEmpty()) continue;   // 空分组不占位置
                newAll.add(AddressAdapter.Row.header(me.getKey()));
                Boolean prev = prevExpanded.get(me.getKey());
                newExpanded.put(me.getKey(), prev != null ? prev : true);
                for (AddressItem it : me.getValue()) {
                    newAll.add(AddressAdapter.Row.item(me.getKey(), it));
                }
            }
            if (!ungrouped.isEmpty()) {
                String name = getString(R.string.common_ungrouped);
                newAll.add(AddressAdapter.Row.header(name));
                Boolean prev = prevExpanded.get(name);
                newExpanded.put(name, prev != null ? prev : true);
                for (AddressItem it : ungrouped) {
                    newAll.add(AddressAdapter.Row.item(name, it));
                }
            }

            final List<String> fGroups = new ArrayList<>(groups);
            final BigDecimal fAmountUsed = amountUsed;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                allRows.clear();
                allRows.addAll(newAll);
                expandedMap.clear();
                expandedMap.putAll(newExpanded);
                groupOptions.clear();
                groupOptions.add(getString(R.string.addr_group_none));
                groupOptions.addAll(fGroups);

                rebuildVisibleRows();
                adapter.notifyDataSetChanged();

                boolean empty = allRows.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rv.setVisibility(empty ? View.GONE : View.VISIBLE);

                BigDecimal left = AddressBookManager.RATIO_MAX.subtract(used);
                if (left.signum() < 0) left = BigDecimal.ZERO;
                String summaryLine = getString(R.string.addr_ratio_total_fmt,
                        used.toPlainString(), left.toPlainString());
                // 数额合计只在设过的时候才占一行：默认全是 0，多一行「数额合计 0 fLGNS」只是噪声
                String amountLine = fAmountUsed.signum() > 0
                        ? getString(R.string.addr_amount_total_fmt,
                        fAmountUsed.stripTrailingZeros().toPlainString())
                        : null;
                tvRatioSummary.setText(amountLine == null
                        ? summaryLine : summaryLine + "\n" + amountLine);
            });
        }).start();
    }

    /** 按展开态把完整行集摊平成可见行集：分组标题常驻，组内行只在展开时进入 */
    private void rebuildVisibleRows() {
        visibleRows.clear();
        String currentGroup = null;
        boolean currentExpanded = false;
        for (AddressAdapter.Row r : allRows) {
            if (r.type == AddressAdapter.Row.TYPE_HEADER) {
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

    /**
     * 拖拽排序。只用左侧手柄启动（长按整行不生效），且只允许同组内换位；
     * 拖动过程只改内存，松手（clearView）后一次性落盘。
     */
    private void attachDrag() {
        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;   // 只认左侧手柄，避免长按整行误拖
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from < 0 || to < 0) return false;
                if (from >= visibleRows.size() || to >= visibleRows.size()) return false;
                AddressAdapter.Row a = visibleRows.get(from);
                AddressAdapter.Row b = visibleRows.get(to);
                if (a.type != AddressAdapter.Row.TYPE_ITEM || b.type != AddressAdapter.Row.TYPE_ITEM) {
                    return false;
                }
                if (!a.groupName.equals(b.groupName)) return false;   // 跨组拖拽不生效

                draggingGroup = a.groupName;
                Collections.swap(visibleRows, from, to);
                // 完整列表同步交换，否则折叠再展开序号会跳回旧值
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
        for (AddressAdapter.Row r : allRows) {
            if (r.type == AddressAdapter.Row.TYPE_ITEM) ids.add(r.item.id);
        }
        new Thread(() -> book.saveAddressSortOrder(ids)).start();
    }

    @Override
    public void onStartDrag(RecyclerView.ViewHolder holder) {
        if (dragHelper != null) dragHelper.startDrag(holder);
    }

    /** 行内编辑按钮：直接用当前行数据打开编辑弹窗，不需要重新查库 */
    @Override
    public void onEdit(AddressItem item) {
        showEditDialog(item);
    }

    /**
     * 行内删除：先弹危险确认，确认后在子线程删库并重拉列表。
     *
     * <p>不做乐观删除：删库失败时列表必须与库里一致，否则用户会以为已删除。
     */
    @Override
    public void onDelete(AddressItem item) {
        if (item == null) return;
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                R.drawable.ic_delete_warn_svg,
                getString(R.string.addr_delete_title),
                getString(R.string.addr_delete_msg_fmt, item.address),
                getString(R.string.btn_delete),
                () -> new Thread(() -> {
                    book.deleteAddress(item.id);
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.toast_key_deleted, Toast.LENGTH_SHORT).show();
                        loadData();
                    });
                }).start(),
                getString(R.string.btn_cancel), null);
    }

    /**
     * 启用开关：直接落库后重拉整表，不做乐观更新——
     * 启用会受「全局比例上限」约束，失败时开关必须回到库里的真实值。
     */
    @Override
    public void onToggle(AddressItem item, boolean enabled) {
        new Thread(() -> {
            try {
                book.setAddressEnabled(item.id, enabled);
                runOnUiThread(this::loadData);
            } catch (Exception e) {
                final String msg = Web3AndroidUtil.safeMessage(e);
                runOnUiThread(() -> {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    // 比例超限被拦：整表重载，开关回到库里的真实状态
                    loadData();
                });
            }
        }).start();
    }

    // endregion

    // region 编辑与默认值

    /**
     * 新增/编辑接收方弹窗：地址、编号、收益比例、收益数额、分组。
     *
     * <p>{@code editing == null} 表示新增，比例与数额各自预填自己的默认值（方便批量铺同样值的地址）；
     * 保存失败（地址非法、比例超限等）直接在弹窗内红字提示，不关窗、不丢已填内容。</p>
     *
     * <p>数额为空就是 0：按数额模式时这一号分到 0，{@code BatchTransferTask.execute} 会直接跳过不发交易，
     * 所以“只填比例、不填数额”在两种模式下都是安全的。</p>
     */
    private void showEditDialog(AddressItem editing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_address_edit, null);
        TextView tvTitle = view.findViewById(R.id.tv_title);
        TextView tvError = view.findViewById(R.id.tv_error);
        EditText etAddress = view.findViewById(R.id.et_address);
        EditText etCode = view.findViewById(R.id.et_code);
        EditText etRatio = view.findViewById(R.id.et_ratio);
        EditText etAmount = view.findViewById(R.id.et_amount);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);

        tvTitle.setText(editing == null ? R.string.addr_add_title : R.string.addr_edit_title);

        final List<String> options = new ArrayList<>(groupOptions);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGroup.setAdapter(sa);

        if (editing == null) {
            etRatio.setText(book.getRatioDefault());
            String defAmount = book.getAmountDefault();
            // 默认数额为 0 时不预填：“0”占着输入框会让人以为是已设金额，留空更像“没填”
            if (!AddressBookManager.normalizeAmount(defAmount).equals(AddressBookManager.DEFAULT_AMOUNT)) {
                etAmount.setText(defAmount);
            }
            spGroup.setSelection(0);
        } else {
            etAddress.setText(editing.address);
            etCode.setText(editing.customCode);
            etRatio.setText(editing.ratio);
            etAmount.setText(editing.amount);
            String g = editing.groupNo == null ? "" : editing.groupNo;
            int idx = options.indexOf(g);
            spGroup.setSelection(idx > 0 ? idx : 0);   // 找不到（组已删）就落到“不分组”
        }
        etAddress.setSelection(etAddress.getText().length());

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_save).setOnClickListener(v -> {
            String addr = etAddress.getText().toString().trim();
            String code = etCode.getText().toString().trim();
            String ratio = etRatio.getText().toString().trim();
            String amount = etAmount.getText().toString().trim();
            int pos = spGroup.getSelectedItemPosition();
            String groupNo = (pos <= 0 || pos >= options.size()) ? "" : options.get(pos);
            tvError.setVisibility(View.GONE);
            new Thread(() -> {
                try {
                    if (editing == null) {
                        book.addAddress(code, groupNo, addr, ratio, amount);
                    } else {
                        book.updateAddress(editing.id, code, groupNo, addr, ratio, amount, editing.enabled);
                    }
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        loadData();
                    });
                } catch (Exception e) {
                    final String msg = Web3AndroidUtil.safeMessage(e);
                    runOnUiThread(() -> {
                        tvError.setText(msg);
                        tvError.setVisibility(View.VISIBLE);
                    });
                }
            }).start();
        });
    }

    /**
     * 新增接收方的默认值：比例与数额放在同一个弹窗里一次填完。
     *
     * <p>只改下一个新增地址的预填值，不动已有记录（「批量下发」由加载时统一重算已用比例，
     * 这里不递归改库）。两个值各存各的：按收益比例模式只读比例那一行，按收益数额模式
     * 只读数额那一行，一起改不会互相影响。</p>
     *
     * <p>数额允许留空（等于 0，新增时不预填）；比例要求非空，因为它有 100% 上限，
     * 空值容易被当成误触。</p>
     */
    private void showDefaultsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_address_defaults, null);
        TextView tvError = view.findViewById(R.id.tv_error);
        EditText etRatio = view.findViewById(R.id.et_ratio_default);
        EditText etAmount = view.findViewById(R.id.et_amount_default);

        etRatio.setText(book.getRatioDefault());
        etRatio.setSelection(etRatio.getText().length());
        etAmount.setText(book.getAmountDefault());

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }

        view.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_save).setOnClickListener(v -> {
            String ratio = etRatio.getText().toString().trim();
            String amount = etAmount.getText().toString().trim();
            if (TextUtils.isEmpty(ratio)) {
                tvError.setText(getString(R.string.addr_field_empty_fmt,
                        getString(R.string.addr_ratio_label)));
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            book.setRatioDefault(ratio);
            book.setAmountDefault(amount);
            dialog.dismiss();
            Toast.makeText(this, R.string.addr_defaults_saved, Toast.LENGTH_SHORT).show();
        });
    }

    // endregion

}
