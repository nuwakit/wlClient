package com.lgns.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
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
import com.lgns.helper.PasswordStrengthDialog;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AddressBookManager;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.model.PayerItem;
import com.lgns.ui.adapter.PayerAdapter;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付方管理页：维护批量转账的出资方——私钥、编号、收益比例、启用状态、分组与展示顺序。
 *
 * <p>角色区分：「私钥管理」里的账号池负责打游戏，「支付方」只负责出 fLGNS。
 * 两套数据互不复用，混用会导致把打游戏账号误当出资账号。</p>
 *
 * <p>校验顺序固定为「私钥格式 → 是否重复 → 编号是否占用」，任一步失败都不落库，
 * 避免留下半条脏数据。</p>
 *
 * <p>改动只影响后续转账的分配，不会追溯已发生的交易。</p>
 */
public class PayerManageActivity extends BaseActivity implements PayerAdapter.Listener {

    // region 视图与数据

    private AddressBookManager book;
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
    // 出资支付方的 id，缓存后 bind 不必逐条查库
    private long masterId = -1;

    // endregion

    // region 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payer_manage);
        book = new AddressBookManager(this);

        rv = findViewById(R.id.rv_payer);
        tvEmpty = findViewById(R.id.tv_empty);
        cardSummary = findViewById(R.id.card_summary);
        tvSummaryTotal = findViewById(R.id.tv_summary_total);
        tvSummaryEnabled = findViewById(R.id.tv_summary_enabled);
        tvSummaryGroups = findViewById(R.id.tv_summary_groups);

        groupOptions.add(getString(R.string.addr_group_none));

        findViewById(R.id.btn_back).setOnClickListener(v -> onBackPressed());
        // 支付方的加解密密码与私钥管理互相独立，在这里单独改
        findViewById(R.id.btn_change_pwd).setOnClickListener(v -> showChangePayerPwdDialog());
        // 全局全选/反选：作用于所有支付方；分组级入口在组头上，同一套执行链路
        findViewById(R.id.bulk_sel_all).setOnClickListener(v -> applyBulkEnabled(null, true));
        findViewById(R.id.bulk_invert).setOnClickListener(v -> applyBulkEnabled(null, false));

        adapter = new PayerAdapter(this, visibleRows, allRows, expandedMap);
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
     * 后台加载支付方列表：读记录 → 按分组切块 → 切回 UI 线程换数据并刷统计。
     *
     * <p>分组规则：只列分组表里真实存在的组，空分组不占位置，组名失效的记录归入「未分组」。
     * 展开态沿用上一次的快照，避免每次 onResume 都把分组弹回默认形态。</p>
     */
    private void loadData() {
        // 展开状态在 UI 线程快照，后台线程不直接读会被 toggle 并发修改的集合
        final Map<String, Boolean> prevExpanded = new LinkedHashMap<>(expandedMap);
        new Thread(() -> {
            List<PayerItem> data = book.loadPayerList();
            List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
            long id = book.getPayerMasterId();

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
            final long fMasterId = id;
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
     *
     * <p>与行内开关同一个写入口 {@code setPayerEnabled}；主号是出资方、不参与
     * 批量转账收款侧，但它的启用态本就由用户自由控制（不像私钥管理页固定禁用），
     * 所以这里不跳过任何行。有失败不静默：收尾弹一次提示并整表重拉。</p>
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
     *
     * <p>只传地址与展示名（无密文/私钥），列表页自己走 Blockscout 只读接口取数。</p>
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

    /** 删除前二次确认；删的是主号时额外提示「主号已清空」，删完重拉列表 */
    @Override
    public void onDelete(PayerItem item) {
        if (item == null) return;
        final boolean wasMaster = item.id == masterId;
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                R.drawable.ic_delete_warn_svg,
                getString(R.string.payer_delete_title),
                getString(R.string.payer_delete_msg_fmt, item.label(), shortAddr(item.address)),
                getString(R.string.payer_delete_confirm_btn),
                () -> new Thread(() -> {
                    book.deletePayer(item.id);
                    runOnUiThread(() -> {
                        Toast.makeText(this, wasMaster
                                ? R.string.payer_master_cleared_on_delete
                                : R.string.toast_key_deleted, Toast.LENGTH_SHORT).show();
                        loadData();
                    });
                }).start(),
                getString(R.string.btn_cancel), null);
    }

    /**
     * 启用开关：先改内存与汇总（界面立即响应），再后台落库；失败则回滚内存并刷新行，
     * 避免出现「界面显示已启用、库里其实没开」的分歧。
     */
    @Override
    public void onToggle(PayerItem item, boolean enabled) {
        final boolean previous = item.enabled;
        // 先改内存，保证后续 rebind 读到的都是新值
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

    // region 新增与校验

    /**
     * 新增支付方弹窗：编号/分组/私钥两段输入/加密密码。
     *
     * <p>密码会拿库里第一条支付方密文试解一次——支付方与私钥管理用两套独立密码，
     * 这里先验证，免得存进去拖到批量转账时才报解密失败。</p>
     */
    private void showAddDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_payer_add, null);
        EditText etCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etPwd = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEye = view.findViewById(R.id.iv_eye_toggle);
        ImageButton ivHelp = view.findViewById(R.id.iv_crypto_help);

        ivHelp.setOnClickListener(v -> PasswordStrengthDialog.show(this));

        final List<String> options = new ArrayList<>(groupOptions);
        ArrayAdapter<String> sa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, options);
        sa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spGroup.setAdapter(sa);
        spGroup.setSelection(0);

        final boolean[] visible = {false};
        ivEye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            etPwd.setInputType(visible[0]
                    ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL)
                    : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ivEye.setImageResource(visible[0] ? R.drawable.ic_eye_on_svg : R.drawable.ic_eye_off_svg);
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            String pk = (etPk1.getText().toString() + etPk2.getText().toString()).trim();
            String pwdStr = etPwd.getText().toString().trim();

            String err = validatePayerInput(code, pk, pwdStr);
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                return;
            }
            int pos = spGroup.getSelectedItemPosition();
            final String groupNo = (pos <= 0 || pos >= options.size()) ? "" : options.get(pos);
            final char[] cryptoKey = pwdStr.toCharArray();
            new Thread(() -> {
                // 支付方自己有独立的加密密码：拿库里第一条支付方密文试解，
                // 解开才说明密码对，避免存进去到批量转账时才报解密失败
                try {
                    // 试解用的是弹窗里现输的密码：走会话门会让会话过期后永远报“密码不匹配”
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    List<PayerItem> existing = book.loadPayerList();
                    if (!existing.isEmpty()) mgr.testDecrypt(existing.get(0).cipherText, cryptoKey);
                } catch (Exception e) {
                    Arrays.fill(cryptoKey, (char) 0);
                    runOnUiThread(() -> Toast.makeText(this,
                            R.string.payer_err_pwd_mismatch, Toast.LENGTH_LONG).show());
                    return;
                }
                try {
                    book.addPayer(pk, cryptoKey, code, groupNo);
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.toast_key_added, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadData();
                    });
                } catch (Exception e) {
                    final String msg = Web3AndroidUtil.safeMessage(e);
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.toast_op_failed_fmt, msg), Toast.LENGTH_LONG).show());
                } finally {
                    Arrays.fill(cryptoKey, (char) 0);
                }
            }).start();
        });
    }

    /**
     * 新增支付方的输入校验：与私钥管理 validateAddKeyInput 同款——
     * 同样的校验项、同样的先后顺序、同样的提示文案，避免两处规则不一致让人踩坑。
     */
    private String validatePayerInput(String code, String pk, String pwd) {
        if (TextUtils.isEmpty(pk)) {
            return getString(R.string.dialog_key_empty);
        }
        if (TextUtils.isEmpty(pwd)) {
            return getString(R.string.err_pwd_empty);
        }
        if (TextUtils.isEmpty(code)) {
            return getString(R.string.err_custom_code_empty);
        }
        if (code.length() > 10) {
            return getString(R.string.err_custom_code_too_long);
        }
        if (isCodeUsed(code, -1)) {
            return getString(R.string.err_custom_code_duplicated);
        }
        String pkError = validatePrivateKey(pk);
        if (pkError != null) {
            return pkError;
        }
        return checkDuplicatePayerKey(pk);
    }

    /** 与私钥管理一致：去 0x 前缀、转小写后只允许 0-9a-f */
    private String validatePrivateKey(String pk) {
        String clean = pk.trim().toLowerCase();
        if (clean.startsWith("0x")) clean = clean.substring(2);
        if (clean.isEmpty()) {
            return getString(R.string.err_pk_content_empty);
        }
        if (!clean.matches("[0-9a-f]+")) {
            return getString(R.string.err_pk_format);
        }
        return null;
    }

    /**
     * 与私钥管理一致：先由私钥推导地址，再与已有记录比对，重复的直接拦下，
     * 否则同一个私钥会在支付方列表里出现多条，批量转账时按编号匹配容易串号。
     */
    private String checkDuplicatePayerKey(String rawPrivateKey) {
        try {
            String clean = rawPrivateKey.trim().toLowerCase();
            if (clean.startsWith("0x")) clean = clean.substring(2);

            String newAddr = "0x" + org.web3j.crypto.Keys.getAddress(
                    org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(clean)));

            for (PayerItem item : book.loadPayerList()) {
                if (item.address != null && item.address.equalsIgnoreCase(newAddr)) {
                    return getString(R.string.err_pk_duplicated_fmt, item.label(), item.address);
                }
            }
        } catch (Exception e) {
            return getString(R.string.err_pk_verify_failed_fmt, e.getMessage());
        }
        return null;
    }

    /** 与私钥管理保持一致：编号不能重名，否则转账日志按编号匹配时会串号 */
    private boolean isCodeUsed(String code, long excludeId) {
        for (PayerItem e : book.loadPayerList()) {
            if (e.id != excludeId && code.equals(e.customCode)) return true;
        }
        return false;
    }

    // endregion

    // region 编辑与口令

    /**
     * 修改支付方加解密密码：与私钥管理的密码完全独立，只重加密 payer_entry 里的密文。
     * 与私钥管理一致：旧密码要能解开全部支付方才落库，否则一条不动。
     */
    private void showChangePayerPwdDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_crypto_key, null);
        TextView tvTitle = view.findViewById(R.id.tv_pwd_title);
        TextView tvSubtitle = view.findViewById(R.id.tv_pwd_subtitle);
        EditText etOld = view.findViewById(R.id.et_old_pwd);
        EditText etNew = view.findViewById(R.id.et_new_pwd);
        EditText etConfirm = view.findViewById(R.id.et_new_pwd_confirm);
        ImageButton ivEye = view.findViewById(R.id.iv_eye_toggle);

        tvTitle.setText(R.string.payer_change_pwd_title);
        tvSubtitle.setText(R.string.payer_change_pwd_subtitle);

        final boolean[] visible = {false};
        ivEye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int type = visible[0]
                    ? (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_NORMAL)
                    : (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            etOld.setInputType(type);
            etNew.setInputType(type);
            etConfirm.setInputType(type);
            ivEye.setImageResource(visible[0] ? R.drawable.ic_eye_on_svg : R.drawable.ic_eye_off_svg);
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        TextView btnConfirm = view.findViewById(R.id.btn_dialog_confirm);
        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            String oldStr = etOld.getText().toString();
            String newStr = etNew.getText().toString();
            String confirmStr = etConfirm.getText().toString();
            String err = null;
            if (oldStr.isEmpty()) err = getString(R.string.err_old_pwd_empty);
            else if (newStr.isEmpty()) err = getString(R.string.err_new_pwd_empty);
            else if (!newStr.equals(confirmStr)) err = getString(R.string.err_new_pwd_mismatch);
            else if (newStr.equals(oldStr)) err = getString(R.string.err_new_pwd_same_as_old);
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                return;
            }
            char[] oldPwd = oldStr.toCharArray();
            char[] newPwd = newStr.toCharArray();
            btnConfirm.setEnabled(false);
            new Thread(() -> {
                try {
                    int count = book.changePayerCryptoKey(oldPwd, newPwd);
                    Arrays.fill(oldPwd, (char) 0);
                    Arrays.fill(newPwd, (char) 0);
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                                getString(R.string.payer_change_pwd_success_fmt, count),
                                Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    Arrays.fill(oldPwd, (char) 0);
                    Arrays.fill(newPwd, (char) 0);
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_fmt, Web3AndroidUtil.safeMessage(e)),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    /**
     * 编辑弹窗：改编号 / 分组 / 是否主号，不动私钥与密码。
     *
     * <p>编号重名校验排除自身；原分组已被删除时把旧组名补进下拉，
     * 否则下拉会默认选中第 0 项（不分组），一保存就把支付方静默换了组。</p>
     *
     * <p>主号只有一个：勾选则写入本行 id，取消勾选且本行原本是主号则清为 -1。</p>
     */
    private void showEditDialog(PayerItem item) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_payer_edit, null);
        EditText etCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);
        TextView tvAddr = view.findViewById(R.id.tv_addr);
        CheckBox cbMaster = view.findViewById(R.id.cb_master);

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
                // 原分组已被删除但支付方还挂着旧分组名：补进下拉，避免默认选中第 0 项把支付方静默换组
                options.add(g);
                ga.notifyDataSetChanged();
                idx = options.size() - 1;
            }
        }
        spGroup.setSelection(idx);

        final long currentMasterId = masterId;
        cbMaster.setChecked(currentMasterId == item.id);

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
            final boolean isMaster = cbMaster.isChecked();
            final boolean masterChanged = isMaster != (currentMasterId == item.id);
            dialog.dismiss();
            new Thread(() -> {
                try {
                    book.updatePayerMeta(item.id, newCode, newGroup);
                    if (isMaster && currentMasterId != item.id) {
                        book.setPayerMasterId(item.id);
                    } else if (!isMaster && currentMasterId == item.id) {
                        book.setPayerMasterId(-1);
                    }
                    runOnUiThread(() -> {
                        if (masterChanged && isMaster) {
                            Toast.makeText(this, R.string.payer_set_master_done, Toast.LENGTH_SHORT).show();
                        }
                        loadData();
                    });
                } catch (Exception e) {
                    final String msg = Web3AndroidUtil.safeMessage(e);
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.toast_save_failed_fmt, msg), Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
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
