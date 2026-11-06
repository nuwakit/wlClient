package com.lgns.ui.activity;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.widget.SwitchCompat;
import android.widget.Toast;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.lgns.helper.PasswordStrengthDialog;
import com.lgns.helper.StatusTipDialog;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.AppLogDbHelper;
import com.lgns.manager.KeyStoreManager;
import com.lgns.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 私钥管理页（账号池）：管理参与入场与领取的账号——新增、编辑、删除、启用开关、
 * 分组与排序、设置主号以及修改加密口令。
 *
 * <p>安全约定：私钥只以密文落库，明文仅在解密后使用期间存在于内存；
 * 界面展示一律走短地址，不整串回显。</p>
 *
 * <p>主号在该页设定，批量充值、手续费充值、批量转账的出资方都取自这个主号；
 * 主号 id 缓存在内存里，避免长列表逐行查库造成滑动卡顿。</p>
 *
 * <p>列表按分组折叠：{@code allDisplayList} 是完整数据，{@code displayList} 是当前可见部分，
 * 折叠只影响后者，排序落盘以完整数据为准。</p>
 */
public class PrivateKeyManageActivity extends BaseActivity {

    // region 视图与数据

    private RecyclerView rvKeys;
    private View tvEmpty;
    private TextView tvSummaryAccounts, tvSummaryEnabled, tvSummaryGroups;
    private View cardSummary;
    private KeyAdapter adapter;
    private List<DisplayItem> allDisplayList = new ArrayList<>(); // 完整列表
    private List<DisplayItem> displayList = new ArrayList<>(); // 可见列表
    private Map<String, Boolean> groupExpandedMap = new LinkedHashMap<>(); // 分组展开状态
    // 主号 id 缓存：避免每个条目 bind 都去查库（长列表滑动时会卡顿）
    private long masterAccountId = -1;
    // 本次拖拽所在分组：clearView 时 getAdapterPosition 可能为 NO_POSITION，靠它兜底落库
    private String draggingGroup;

    /**
     * 本次停留是否做过「影响链上数据」的变更：新增/删除 key、启用开关（尤其关→开会把
     * 本次会话未查过的地址放回主界面）。只有这类变更才需要回主界面重拉链上统计；
     * 单改自定义编号/分组归属/组内排序/主号都不影响地址集合与其链上值，可原样继承。
     */
    private volatile boolean chainDirty = false;

    /** 分组头行：显示分组名与人数，拖动排序时不能被跨过 */
    private static final int TYPE_HEADER = 0;
    /** 私钥行：一行一个账号，长按可同组内拖动调整顺序 */
    private static final int TYPE_KEY = 1;

    /**
     * 分组胶囊颜色盘。按分组名 hash 取模选色，同一分组多次刷新颜色不变；
     * 不按行号取色，否则拖动排序后颜色会整体错位。
     */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    // endregion

    // region 列表数据模型

    /** 列表行：分组头与私钥行共用，用 {@link #type} 区分 */
    static class DisplayItem {
        /** 行类型：{@code TYPE_HEADER} 或 {@code TYPE_KEY} */
        int type;
        /** 所属分组名（含“未分组”兜底值），拖拽时靠它限制同组内换位 */
        String groupName;
        /** 私钥条目；分组头行为 null */
        KeyStoreManager.KeyEntry entry;

        /** 构造一个分组头行（不带密钥数据） */
        static DisplayItem header(String name) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = name;
            return d;
        }

        /** 构造一个私钥行，并把分组名统一成列表展示口径（含“未分组”兜底） */
        static DisplayItem keyItem(String groupName, KeyStoreManager.KeyEntry e) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_KEY;
            d.entry = e;
            // 统一使用列表展示的分组名（含“未分组”兜底），保证标题计数与拖拽分组一致
            d.groupName = groupName;
            return d;
        }
    }

    // endregion

    // region 生命周期

    /**
     * 搭列表与拖拽回调，本身不读库；数据加载统一放在 {@link #onResume()}。
     *
     * <p>拖拽只允许长按私钥行发起，且限制在同组内换位（{@code onMove} 里判分组）。</p>
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_private_key_manage);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        rvKeys = findViewById(R.id.rv_keys);
        tvEmpty = findViewById(R.id.tv_empty);
        tvSummaryAccounts = findViewById(R.id.tv_summary_accounts);
        tvSummaryEnabled = findViewById(R.id.tv_summary_enabled);
        tvSummaryGroups = findViewById(R.id.tv_summary_groups);
        cardSummary = findViewById(R.id.card_summary);

        rvKeys.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KeyAdapter();
        rvKeys.setAdapter(adapter);

        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                if (target.getItemViewType() != TYPE_KEY) return false;
                int from = vh.getAdapterPosition();
                int to = target.getAdapterPosition();
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false;
                String fromGroup = displayList.get(from).groupName;
                String toGroup = displayList.get(to).groupName;
                if (!fromGroup.equals(toGroup)) return false;

                DisplayItem moved = displayList.get(from);
                DisplayItem swapped = displayList.get(to);
                draggingGroup = fromGroup;
                Collections.swap(displayList, from, to);
                // 完整列表同步交换，否则折叠再展开 / 重进页面后顺序会跳回旧值
                int fullFrom = allDisplayList.indexOf(moved);
                int fullTo = allDisplayList.indexOf(swapped);
                if (fullFrom >= 0 && fullTo >= 0) Collections.swap(allDisplayList, fullFrom, fullTo);
                adapter.notifyItemMoved(from, to);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {}

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(rv, vh);
                String group = draggingGroup;
                draggingGroup = null;
                if (group == null) {
                    int pos = vh.getAdapterPosition();
                    if (pos < 0 || pos >= displayList.size()) return;
                    group = displayList.get(pos).groupName;
                }
                saveGroupSortOrder(group);
            }
        };
        ItemTouchHelper helper = new ItemTouchHelper(callback);
        helper.attachToRecyclerView(rvKeys);

        FloatingActionButton fabAdd = findViewById(R.id.fab_add);
        fabAdd.setOnClickListener(v -> showAddKeyDialog());

        findViewById(R.id.btn_change_pwd).setOnClickListener(v -> showChangeCryptoKeyDialog());
    }

    /** 每次回到页面都重读一次：增删密钥、改分组、改启用状态都可能在别的页面发生 */
    @Override
    protected void onResume() {
        super.onResume();
        loadKeys();
    }

    /**
     * 离开页面时标记主界面「只重读本地清单」：本页可能从批量充值等非菜单入口进入，
     * 不通知的话主界面会继续用旧的启用状态与分组。
     */
    @Override
    protected void onPause() {
        super.onPause();
        // 本页也可能从「批量充值 → 去设置」等非菜单入口进入，
        // 离开时统一通知主界面重载本地私钥，避免主界面沿用旧的启用状态
        MainActivity ma = MainActivity.sInstance == null ? null : MainActivity.sInstance.get();
        if (ma != null && !ma.isFinishing()) {
            // 只有增删 key/启用开关才需主界面重拉链上；单改编号/分组/排序/主号只重建本地展示
            if (chainDirty) ma.setRefreshLocalOnlyOnResume();
            else ma.setRefreshGroupOnlyOnResume();
        }
    }

    // endregion

    // region 数据加载

    /**
     * 后台线程重建私钥列表：读库 → 去重 → 按自定义排序 → 按分组切块 → 切回 UI 线程一次性换引用。
     *
     * <p>★ 数据源用 {@code AppGlobal.getMetaManager()} 而不是 {@code getKeyStoreManager()}：
     * 本页从列表到开关、编辑、删除、拖动排序全是元数据读写，没有一处解密，
     * 走会话门只会让空闲超时后进来一片空白。</p>
     *
     * <p>顺序与分组：先按 {@code getCustomSortIdList()} 排（拖动后的顺序落在这里），
     * 库里新增但排序表里没有的 id 追加到末尾；再按 group_no 分到各分组，
     * 分组名不在分组表里的归入「未分组」，空分组不占位。</p>
     *
     * <p>线程约束：展开态在 UI 线程先快照，后台线程直接读会与 toggleGroup 并发修改
     * 最后在 {@code runOnUiThread} 里一次性替换引用，避免列表读到「清空了一半」的集合。</p>
     */
    private void loadKeys() {
        // 展开状态先在 UI 线程快照：后台线程直接读会与 toggleGroup 并发修改
        final Map<String, Boolean> prevExpandedMap = new LinkedHashMap<>(groupExpandedMap);
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                final long fMasterId = mgr.getMasterAccountId();
                List<KeyStoreManager.KeyEntry> entries = mgr.loadKeyEntryList();

                List<KeyStoreManager.KeyEntry> unique = new ArrayList<>();
                for (KeyStoreManager.KeyEntry e : entries) {
                    boolean dup = false;
                    for (KeyStoreManager.KeyEntry u : unique) {
                        if (u.id == e.id) { dup = true; break; }
                    }
                    if (!dup) unique.add(e);
                }
                entries = unique;

                List<Long> sortIds = mgr.getCustomSortIdList();

                List<KeyStoreManager.KeyEntry> sorted = new ArrayList<>();
                for (Long sid : sortIds) {
                    for (KeyStoreManager.KeyEntry e : entries) {
                        if (e.id == sid) { sorted.add(e); break; }
                    }
                }
                for (KeyStoreManager.KeyEntry e : entries) {
                    boolean found = false;
                    for (KeyStoreManager.KeyEntry s : sorted) {
                        if (s.id == e.id) { found = true; break; }
                    }
                    if (!found) sorted.add(e);
                }

                List<String> groups = AppLogDbHelper.getInstance(this).queryAllGroups();
                Map<String, List<KeyStoreManager.KeyEntry>> groupMap = new LinkedHashMap<>();
                for (String g : groups) groupMap.put(g, new ArrayList<>());
                List<KeyStoreManager.KeyEntry> ungrouped = new ArrayList<>();

                for (KeyStoreManager.KeyEntry e : sorted) {
                    String g = (e.groupNo != null && !e.groupNo.isEmpty()) ? e.groupNo : null;
                    if (g != null && groupMap.containsKey(g)) {
                        groupMap.get(g).add(e);
                    } else {
                        ungrouped.add(e);
                    }
                }

                // 构建完整列表：全部写局部变量，后台线程不碰 Adapter 正在遍历的字段集合
                List<DisplayItem> newAll = new ArrayList<>();
                Map<String, Boolean> newExpanded = new LinkedHashMap<>();
                for (Map.Entry<String, List<KeyStoreManager.KeyEntry>> me : groupMap.entrySet()) {
                    if (!me.getValue().isEmpty()) {
                        newAll.add(DisplayItem.header(me.getKey()));
                        Boolean prev = prevExpandedMap.get(me.getKey());
                        newExpanded.put(me.getKey(), prev != null ? prev : true);
                        for (KeyStoreManager.KeyEntry e : me.getValue()) {
                            newAll.add(DisplayItem.keyItem(me.getKey(), e));
                        }
                    }
                }
                if (!ungrouped.isEmpty()) {
                    String ungroupedName = getString(R.string.common_ungrouped);
                    newAll.add(DisplayItem.header(ungroupedName));
                    Boolean prevUngrouped = prevExpandedMap.get(ungroupedName);
                    newExpanded.put(ungroupedName, prevUngrouped != null ? prevUngrouped : true);
                    for (KeyStoreManager.KeyEntry e : ungrouped) {
                        newAll.add(DisplayItem.keyItem(ungroupedName, e));
                    }
                }

                int enabledCount = 0;
                for (KeyStoreManager.KeyEntry e : sorted) {
                    if (e.enabled) enabledCount++;
                }
                int groupCount = 0;
                for (Map.Entry<String, List<KeyStoreManager.KeyEntry>> me : groupMap.entrySet()) {
                    if (!me.getValue().isEmpty()) groupCount++;
                }
                if (!ungrouped.isEmpty()) groupCount++;

                boolean empty = sorted.isEmpty();
                final int fEnabledCount = enabledCount;
                final int fGroupCount = groupCount;
                final int fTotal = sorted.size();
                final List<DisplayItem> fAll = newAll;
                final Map<String, Boolean> fExpanded = newExpanded;
                runOnUiThread(() -> {
                    // 一次性换引用 + 重建可见列表，UI 线程不会读到“清空了一半”的集合
                    allDisplayList = fAll;
                    groupExpandedMap.clear();
                    groupExpandedMap.putAll(fExpanded);
                    rebuildVisibleList();
                    masterAccountId = fMasterId;
                    adapter.notifyDataSetChanged();
                    tvSummaryAccounts.setText(String.valueOf(fTotal));
                    tvSummaryEnabled.setText(String.valueOf(fEnabledCount));
                    tvSummaryGroups.setText(String.valueOf(fGroupCount));
                    cardSummary.setVisibility(empty ? View.GONE : View.VISIBLE);
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                Log.e("PrivateKeyManage", "loadKeys error", e);
                // 读库失败（数据库损坏、磁盘满等）时给出明确提示，避免页面空白却无任何反馈。
                // 会话过期不会走到这里：本页数据源已改用 getMetaManager()
                String reason = e.getMessage() != null ? e.getMessage() : "加载私钥列表失败";
                runOnUiThread(() -> Toast.makeText(this, reason, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // endregion

    // region 分组与排序

    /** 按当前展开态把完整列表摊平成可见列表：折叠的分组只保留标题行 */
    private void rebuildVisibleList() {
        displayList.clear();
        String currentGroup = null;
        boolean currentExpanded = false;
        for (DisplayItem item : allDisplayList) {
            if (item.type == TYPE_HEADER) {
                currentGroup = item.groupName;
                currentExpanded = groupExpandedMap.getOrDefault(currentGroup, false);
                displayList.add(item); // 分组标题始终可见
            } else if (currentExpanded) {
                displayList.add(item); // 只添加展开分组的数据项
            }
        }
    }

    /** 点击分组标题：切换展开态后重建可见列表（只改内存，不落盘） */
    private void toggleGroup(String groupName) {
        boolean expanded = groupExpandedMap.getOrDefault(groupName, false);
        groupExpandedMap.put(groupName, !expanded);
        rebuildVisibleList();
        adapter.notifyDataSetChanged();
    }

    /**
     * 拖拽结束后落盘组内顺序：只重排本组，其他分组的 id 与相对位置保持不变。
     * 写库放后台线程，主线程不因磁盘 IO 卡顿。
     */
    private void saveGroupSortOrder(String groupName) {
        // 拖拽后可见列表里本组的顺序，就是新的组内顺序
        List<Long> newGroupOrder = new ArrayList<>();
        for (DisplayItem d : displayList) {
            if (d.type == TYPE_KEY && d.groupName.equals(groupName)) newGroupOrder.add(d.entry.id);
        }
        if (newGroupOrder.isEmpty()) return;

        // 用新组内顺序依次替换完整列表中的本组位置，其余组原样保留
        List<Long> finalIds = new ArrayList<>();
        int idx = 0;
        for (DisplayItem d : allDisplayList) {
            if (d.type != TYPE_KEY) continue;
            if (d.groupName.equals(groupName)) {
                if (idx < newGroupOrder.size()) finalIds.add(newGroupOrder.get(idx++));
            } else {
                finalIds.add(d.entry.id);
            }
        }
        new Thread(() -> {
            try {
                KeyStoreManager mgr = AppGlobal.getMetaManager();
                // 兜底：库里有、列表里没有的 id 追加末尾；不再重复追加（旧实现会把其他组 id 加两遍）
                for (Long id : mgr.getCustomSortIdList()) {
                    if (!finalIds.contains(id)) finalIds.add(id);
                }
                mgr.saveCustomSortIdList(finalIds);
            } catch (Exception ignored) {}
        }).start();
    }

    // endregion

    // region 新增与编辑

    private void showAddKeyDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_key, null);
        EditText etCustomCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group_no);
        EditText etPk1 = view.findViewById(R.id.et_pk_part1);
        EditText etPk2 = view.findViewById(R.id.et_pk_part2);
        EditText etPwd = view.findViewById(R.id.et_user_crypto_key);
        ImageButton ivEye = view.findViewById(R.id.iv_eye_toggle);
        ImageButton ivCryptoHelp = view.findViewById(R.id.iv_crypto_help);

        ivCryptoHelp.setOnClickListener(v -> PasswordStrengthDialog.show(this));

        AppLogDbHelper groupDb = AppLogDbHelper.getInstance(this);
        List<String> groups = groupDb.queryAllGroups();
        if (groups.isEmpty()) {
            // 库里还没有任何分组：先把 default 真正落库，否则新账号的 group_no 指向不存在的组，列表里会归到“未分组”
            groupDb.insertGroup("default");
            groups.add("default");
        }
        spGroup.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groups));

        final boolean[] visible = {false};
        ivEye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            etPwd.setInputType(visible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL)
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            ivEye.setImageResource(visible[0] ? R.drawable.ic_eye_on_svg : R.drawable.ic_eye_off_svg);
        });

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
            String customCode = etCustomCode != null ? etCustomCode.getText().toString().trim() : "";
            String group = ((String) spGroup.getSelectedItem()).trim();
            String part1 = etPk1.getText().toString().trim();
            String part2 = etPk2.getText().toString().trim();
            String fullKey = (part1 + part2).trim();
            String pwdStr = etPwd.getText().toString().trim();

            String validateError = validateAddKeyInput(customCode, fullKey, pwdStr);
            if (validateError != null) {
                Toast.makeText(this, validateError, Toast.LENGTH_LONG).show();
                return;
            }

            char[] pwd = pwdStr.toCharArray();
            new Thread(() -> {
                try {
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    // 加密密码对标第一条私钥：GCM 没有独立校验位，拿列表第一条密文试解，
                    // 解得开才算同一份密码。不拦的话会出现「多条记录密码不一致」，
                    // 批量任务用会话密码只能解开一部分号，剩下的每到解密环节静默失败。
                    // 首条密文缺失属脏数据（旧版迁移遗留），无对象可对标时直接放行
                    List<KeyStoreManager.KeyEntry> existing = mgr.loadKeyEntryList();
                    if (!existing.isEmpty() && existing.get(0).cipherText != null
                            && !existing.get(0).cipherText.isEmpty()) {
                        try {
                            mgr.testDecrypt(existing.get(0).cipherText, pwd);
                        } catch (Exception ex) {
                            throw new Exception(getString(R.string.err_pwd_not_match_first_key));
                        }
                    }
                    mgr.addPrivateKey(fullKey, pwd, customCode, group);
                    java.util.Arrays.fill(pwd, (char) 0);
                    chainDirty = true;   // 新增 key 引入未查地址，回主界面需重拉链上
                    runOnUiThread(() -> {
                        Toast.makeText(this, R.string.toast_key_added, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        loadKeys();
                    });
                } catch (Exception e) {
                    java.util.Arrays.fill(pwd, (char) 0);
                    runOnUiThread(() -> Toast.makeText(this,
                            getString(R.string.toast_op_failed_fmt, e.getMessage()),
                            Toast.LENGTH_SHORT).show());
                }
            }).start();
        });
    }

    private String validateAddKeyInput(String customCode, String fullKey, String pwdStr) {
        if (fullKey.isEmpty()) {
            return getString(R.string.dialog_key_empty);
        }
        if (pwdStr.isEmpty()) {
            return getString(R.string.err_pwd_empty);
        }
        if (customCode.isEmpty()) {
            return getString(R.string.err_custom_code_empty);
        }
        if (customCode.length() > 10) {
            return getString(R.string.err_custom_code_too_long);
        }
        if (isCustomCodeUsed(customCode, -1)) {
            return getString(R.string.err_custom_code_duplicated);
        }
        String pkError = validatePrivateKey(fullKey);
        if (pkError != null) {
            return pkError;
        }
        String dupError = checkDuplicatePrivateKey(fullKey);
        if (dupError != null) {
            return dupError;
        }
        return null;
    }

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

    private String checkDuplicatePrivateKey(String rawPrivateKey) {
        try {
            String clean = rawPrivateKey.trim().toLowerCase();
            if (clean.startsWith("0x")) clean = clean.substring(2);

            String newAddr = "0x" + org.web3j.crypto.Keys.getAddress(
                    org.web3j.crypto.ECKeyPair.create(org.web3j.utils.Numeric.toBigInt(clean)));

            // 查重用元数据实例：比较的是已存地址，不需要密码
            KeyStoreManager mgr = AppGlobal.getMetaManager();
            List<KeyStoreManager.KeyEntry> allKeys = mgr.loadKeyEntryList();

            for (KeyStoreManager.KeyEntry entry : allKeys) {
                if (entry.address != null && entry.address.equalsIgnoreCase(newAddr)) {
                    String label = entry.customCode.isEmpty()
                            ? getString(R.string.key_default_code_fmt, entry.id)
                            : entry.customCode;
                    return getString(R.string.err_pk_duplicated_fmt, label, entry.address);
                }
            }
        } catch (Exception e) {
            return getString(R.string.err_pk_verify_failed_fmt, e.getMessage());
        }
        return null;
    }

    private void showEditDialog(int position) {
        KeyStoreManager.KeyEntry entry = displayList.get(position).entry;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_key, null);
        EditText etCode = view.findViewById(R.id.et_custom_code);
        Spinner spGroup = view.findViewById(R.id.sp_group);
        TextView tvAddr = view.findViewById(R.id.tv_addr);

        etCode.setText(entry.customCode != null ? entry.customCode : "");
        etCode.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        tvAddr.setText(entry.address != null ? entry.address : getString(R.string.key_addr_unknown));

        AppLogDbHelper groupDb = AppLogDbHelper.getInstance(this);
        List<String> groups = groupDb.queryAllGroups();
        if (groups.isEmpty()) {
            groupDb.insertGroup("default");
            groups.add("default");
        }
        ArrayAdapter<String> ga = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groups);
        spGroup.setAdapter(ga);
        if (entry.groupNo != null && !entry.groupNo.isEmpty()) {
            int idx = groups.indexOf(entry.groupNo);
            if (idx < 0) {
                // 原分组已被删除但账号还挂着旧分组名：补进下拉，避免默认选中第 0 项把账号静默换组
                groups.add(entry.groupNo);
                ga.notifyDataSetChanged();
                idx = groups.size() - 1;
            }
            spGroup.setSelection(idx);
        }

        CheckBox cbMaster = view.findViewById(R.id.cb_master);
        long currentMasterId = AppGlobal.getMetaManager().getMasterAccountId();
        cbMaster.setChecked(currentMasterId == entry.id);

        final long fCurrentMasterId = currentMasterId;

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.AppDialog)
                .setView(view)
                .create();
        dialog.show();

        view.findViewById(R.id.btn_dialog_cancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btn_dialog_confirm).setOnClickListener(v -> {
                    String newCode = etCode.getText().toString().trim();
                    // 与新增保持一致：编号不能重名，否则主界面/批量充值/失败日志按编号匹配时会串号
                    if (!newCode.isEmpty() && isCustomCodeUsed(newCode, entry.id)) {
                        Toast.makeText(this, R.string.err_custom_code_duplicated, Toast.LENGTH_LONG).show();
                        return;
                    }
                    String newGroup = (String) spGroup.getSelectedItem();
                    boolean isMaster = cbMaster.isChecked();
                    // 空串就存空串：下游大量 entry.customCode.isEmpty() 判断，存 null 会 NPE
                    entry.customCode = newCode;
                    entry.groupNo = newGroup;
                    dialog.dismiss();
                    new Thread(() -> {
                        try {
                            KeyStoreManager mgr = AppGlobal.getMetaManager();
                            // 只写编号/分组，不再顺带回写 enabled（enabled 已被开关链路单独维护）
                            mgr.updateKeyMeta((int) entry.id, entry.customCode, entry.groupNo);
                            if (isMaster && fCurrentMasterId != entry.id) {
                                mgr.setMasterAccountId(entry.id);
                                // 新设主号默认禁用：单列更新，不动其它字段
                                mgr.setKeyEnabled(entry.id, false);
                                entry.enabled = false;
                                runOnUiThread(() -> Toast.makeText(this, R.string.toast_master_set_disabled, Toast.LENGTH_SHORT).show());
                            } else if (!isMaster && fCurrentMasterId == entry.id) {
                                mgr.setMasterAccountId(-1);
                            }
                            runOnUiThread(() -> loadKeys());
                        } catch (Exception e) {
                            runOnUiThread(() -> Toast.makeText(this,
                                    getString(R.string.toast_save_failed_fmt, e.getMessage()),
                                    Toast.LENGTH_SHORT).show());
                        }
                    }).start();
         });
    }

    private boolean isCustomCodeUsed(String code, long excludeId) {
        try {
            KeyStoreManager mgr = AppGlobal.getMetaManager();
            for (KeyStoreManager.KeyEntry e : mgr.loadKeyEntryList()) {
                if (e.id != excludeId && code.equals(e.customCode)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // endregion

    // region 删除确认

    private void showDeleteConfirm(int position) {
        KeyStoreManager.KeyEntry entry = displayList.get(position).entry;
        String label = entry.customCode.isEmpty()
                ? getString(R.string.key_default_code_fmt, entry.id) : entry.customCode;
        StatusTipDialog.showDanger(this, StatusTipDialog.TONE_WARN,
                R.drawable.ic_delete_warn_svg,
                getString(R.string.dialog_delete_key_title),
                getString(R.string.key_delete_msg_v2_fmt, label, shortAddr(entry.address)),
                getString(R.string.key_delete_confirm_btn),
                () -> new Thread(() -> {
                    try {
                        KeyStoreManager mgr = AppGlobal.getMetaManager();
                        boolean wasMaster = mgr.getMasterAccountId() == entry.id;
                        List<KeyStoreManager.KeyEntry> all = mgr.loadKeyEntryList();
                        for (int i = 0; i < all.size(); i++) {
                            if (all.get(i).id == entry.id) {
                                mgr.removeByIndex(i);
                                break;
                            }
                        }
                        // 删掉的正好是主号时，清掉主号标记，避免批量充值指向一个不存在的账号
                        if (wasMaster) mgr.setMasterAccountId(-1);
                        chainDirty = true;   // 删除改变地址集合与分母，回主界面重拉
                        final boolean fWasMaster = wasMaster;
                        runOnUiThread(() -> {
                            Toast.makeText(this, fWasMaster
                                    ? R.string.toast_master_cleared_on_delete
                                    : R.string.toast_key_deleted, Toast.LENGTH_SHORT).show();
                            loadKeys();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(this, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show());
                    }
                }).start(),
                getString(R.string.btn_cancel), null);
    }

    // endregion

    // region 行内交互与辅助

    // 与主界面账号行一致：0x1234...abcd
    private String shortAddr(String addr) {
        if (addr == null || addr.isEmpty()) return getString(R.string.key_addr_unknown);
        if (addr.length() > 10) {
            return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
        }
        return addr;
    }

    private int getBadgeColor(String text) {
        // 先取模再取绝对值：Math.abs(Integer.MIN_VALUE) 仍为负数，会数组越界
        return BADGE_COLORS[Math.abs(text.hashCode() % BADGE_COLORS.length)];
    }

    // 只重算汇总数字，不整页 loadKeys，避免打断滚动
    private void refreshSummaryCounts() {
        int total = 0;
        int enabled = 0;
        for (DisplayItem d : allDisplayList) {
            if (d.type == TYPE_KEY && d.entry != null) {
                total++;
                if (d.entry.enabled) enabled++;
            }
        }
        tvSummaryAccounts.setText(String.valueOf(total));
        tvSummaryEnabled.setText(String.valueOf(enabled));
    }

    /**
     * 按条目对象反查当前可见位置。
     * 不用 ViewHolder 自带的位置 API：recyclerview 1.0.x 没有 getBindingAdapterPosition，
     * 而 bind 时的 position 是快照，折叠分组/拖拽排序后会错位。
     */
    private int positionOfEntry(KeyStoreManager.KeyEntry entry) {
        if (entry == null) return RecyclerView.NO_POSITION;
        for (int i = 0; i < displayList.size(); i++) {
            DisplayItem d = displayList.get(i);
            if (d.type == TYPE_KEY && d.entry == entry) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    /**
     * 开关唯一写入口：单条 UPDATE，失败回滚 UI。
     * 不做 load + saveKeyEntryList，避免并发互相覆盖、created 被重置。
     */
    private void onSwitchToggled(KeyVH holder, KeyStoreManager.KeyEntry entry, boolean checked) {
        final boolean previous = entry.enabled;
        // 先改内存，保证后续任何 rebind 读到的都是新值
        entry.enabled = checked;
        refreshSummaryCounts();

        boolean isMasterRow = holder.boundEntry == entry && holder.boundIsMaster;
        if (checked && isMasterRow) {
            Toast.makeText(this, R.string.toast_master_enabled_hint, Toast.LENGTH_LONG).show();
        }

        new Thread(() -> {
            int affected = 0;
            try {
                affected = AppGlobal.getMetaManager().setKeyEnabled(entry.id, checked);
            } catch (Exception e) {
                Log.e("PrivateKeyManage", "setKeyEnabled error", e);
            }
            if (affected > 0) {
                chainDirty = true;   // 启用集变了：关→开会引入本次未查的地址，主界面需重拉
                return;
            }

            final int rows = affected;
            runOnUiThread(() -> {
                // 落库失败：内存与 UI 一起回滚，避免“界面开了但库里没开”
                entry.enabled = previous;
                refreshSummaryCounts();
                if (holder.boundEntry == entry) {
                    holder.binding = true;
                    holder.swEnabled.setChecked(previous);
                    holder.binding = false;
                }
                Log.w("PrivateKeyManage", "启用状态保存失败, rows=" + rows + ", id=" + entry.id);
                Toast.makeText(this, R.string.toast_enabled_save_failed, Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // endregion

    // region 加密口令

    private void showChangeCryptoKeyDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_change_crypto_key, null);
        EditText etOld = view.findViewById(R.id.et_old_pwd);
        EditText etNew = view.findViewById(R.id.et_new_pwd);
        EditText etConfirm = view.findViewById(R.id.et_new_pwd_confirm);
        ImageButton ivEye = view.findViewById(R.id.iv_eye_toggle);

        final boolean[] visible = {false};
        ivEye.setOnClickListener(v -> {
            visible[0] = !visible[0];
            int type = visible[0]
                    ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL)
                    : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
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
                    // 改密用的是弹窗里的旧密码，旧密码能解开即已通过身份校验，与会话是否过期无关
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    int count = mgr.changeCryptoKey(oldPwd, newPwd);
                    // 刚验证过一遍密码：顺手把会话补回来，避免后续任务仍提示「会话已过期」
                    AppGlobal.ensureSession(this);
                    // 同步主界面缓存的会话密码，避免本会话后续操作仍用旧密码解密失败
                    MainActivity ma = MainActivity.sInstance == null ? null : MainActivity.sInstance.get();
                    if (ma != null && ma.tempUserCryptoKey != null) {
                        java.util.Arrays.fill(ma.tempUserCryptoKey, (char) 0);
                        ma.tempUserCryptoKey = newPwd.clone();
                    }
                    java.util.Arrays.fill(oldPwd, (char) 0);
                    java.util.Arrays.fill(newPwd, (char) 0);
                    runOnUiThread(() -> {
                        Toast.makeText(this,
                                getString(R.string.toast_change_pwd_success_fmt, count),
                                Toast.LENGTH_LONG).show();
                        dialog.dismiss();
                    });
                } catch (Exception e) {
                    java.util.Arrays.fill(oldPwd, (char) 0);
                    java.util.Arrays.fill(newPwd, (char) 0);
                    runOnUiThread(() -> {
                        btnConfirm.setEnabled(true);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_fmt, e.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    // endregion

    // region 列表适配器

    /** 私钥列表适配器：数据全部取自外部传入的 displayList，自身不持有业务状态 */
    private class KeyAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int HEADER_VIEW = 0;
        private static final int KEY_VIEW = 1;

        @Override
        public int getItemViewType(int position) {
            return displayList.get(position).type == TYPE_HEADER ? HEADER_VIEW : KEY_VIEW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == HEADER_VIEW) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group_header, parent, false);
                return new HeaderVH(v);
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_private_key, parent, false);
                return new KeyVH(v, PrivateKeyManageActivity.this::onSwitchToggled);
            }
        }

        /**
         * 绑定一个条目。
         *
         * <p>分组头：组名 + 颜色 + 人数（人数从完整列表里捞，所以折叠时计数也准确）+ 展开箭头旋转。</p>
         *
         * <p>私钥行：编号与首字徽标、主号高亮、分组胶囊、短地址；
         * 开关值回填前先把 {@code binding} 置 true 屏蔽回调，避免复用时把旧值当用户操作落库；
         * 编辑/删除则通过 {@code positionOfEntry} 现查位置，不用 bind 时的旧 position。</p>
         */
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            DisplayItem item = displayList.get(position);
            if (holder instanceof HeaderVH) {
                HeaderVH h = (HeaderVH) holder;
                h.tvGroupName.setText(item.groupName);
                h.viewColor.setBackgroundTintList(ColorStateList.valueOf(getBadgeColor(item.groupName)));
                int count = 0;
                for (DisplayItem d : allDisplayList) {
                    if (d.type == TYPE_KEY && d.groupName.equals(item.groupName)) count++;
                }
                h.tvGroupCount.setText(getString(R.string.common_count_fmt, count));
                // 展开/折叠图标旋转
                boolean expanded = groupExpandedMap.getOrDefault(item.groupName, false);
                h.ivExpand.setRotation(expanded ? 90 : 0);
                // 点击展开/折叠
                h.itemView.setOnClickListener(v -> toggleGroup(item.groupName));
            } else {
                KeyVH h = (KeyVH) holder;
                KeyStoreManager.KeyEntry entry = item.entry;
                String baseLabel = entry.customCode.isEmpty()
                        ? getString(R.string.key_default_code_fmt, entry.id) : entry.customCode;
                long masterId = masterAccountId;
                boolean isMaster = masterId == entry.id;
                h.tvCode.setText(baseLabel);
                // 徽标取原始编号首字
                h.tvBadge.setText(baseLabel.isEmpty() ? "?"
                        : String.valueOf(baseLabel.charAt(0)).toUpperCase());
                h.tvBadge.setBackgroundTintList(ColorStateList.valueOf(getBadgeColor(baseLabel)));

                // 主号整卡用浅橙底 + 橙色描边，一眼区分于普通账号
                if (isMaster) {
                    h.cardView.setCardBackgroundColor(0xFFFFF8F0);
                    h.cardView.setStrokeColor(0xFFFFE0B2);
                } else {
                    h.cardView.setCardBackgroundColor(0xFFFFFFFF);
                    h.cardView.setStrokeColor(0xFFEDF1F6);
                }

                // 主号用橙色胶囊突出，普通账号显示所属分组，未分组不占位
                if (isMaster) {
                    h.tvGroupTag.setVisibility(View.VISIBLE);
                    h.tvGroupTag.setText(R.string.key_master_pill);
                    h.tvGroupTag.setTextColor(0xFFE65100);
                    h.tvGroupTag.setBackgroundTintList(ColorStateList.valueOf(0xFFFFF3E0));
                } else if (item.groupName != null
                        && !item.groupName.equals(getString(R.string.common_ungrouped))) {
                    h.tvGroupTag.setVisibility(View.VISIBLE);
                    h.tvGroupTag.setText(item.groupName);
                    h.tvGroupTag.setTextColor(0xFF78909C);
                    h.tvGroupTag.setBackgroundTintList(ColorStateList.valueOf(0xFFF1F5F9));
                } else {
                    h.tvGroupTag.setVisibility(View.GONE);
                }

                h.tvAddr.setText(shortAddr(entry.address));
                // 程序化 setChecked 用 binding 屏蔽回调：否则复用 View 时旧记录的开关值会被当成用户操作落库
                h.binding = true;
                h.boundEntry = entry;
                h.boundIsMaster = isMaster;
                h.swEnabled.setChecked(entry.enabled);
                h.binding = false;
                h.btnEdit.setOnClickListener(v -> {
                    int p = positionOfEntry(h.boundEntry);
                    if (p != RecyclerView.NO_POSITION) showEditDialog(p);
                });
                h.btnDelete.setOnClickListener(v -> {
                    int p = positionOfEntry(h.boundEntry);
                    if (p != RecyclerView.NO_POSITION) showDeleteConfirm(p);
                });
            }
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }
    }

    /** 分组头 ViewHolder：颜色条 + 组名 + 人数 + 展开箭头 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        TextView tvGroupName, tvGroupCount;
        View viewColor;
        ImageView ivExpand;

        HeaderVH(View v) {
            super(v);
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            viewColor = v.findViewById(R.id.view_color);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }

    /** 私钥行视图：开关只注册一次监听，靠 boundEntry/binding 区分“回填”与“用户操作” */
    static class KeyVH extends RecyclerView.ViewHolder {
        /** 开关回调：只转发用户真实操作，程序化回填时由 {@code binding} 拦住 */
        interface ToggleCallback {
            void onToggle(KeyVH holder, KeyStoreManager.KeyEntry entry, boolean checked);
        }

        MaterialCardView cardView;
        TextView tvBadge, tvCode, tvAddr, tvGroupTag;
        SwitchCompat swEnabled;
        ImageButton btnEdit, btnDelete;
        /** 当前绑定到本 View 的账号：开关回调与编辑/删除都以它为准，不用旧 position 猜 */
        KeyStoreManager.KeyEntry boundEntry;
        /** 当前绑定的是否主号（由页面缓存的主号 id 判出） */
        boolean boundIsMaster;
        /** true 表示正在程序化回填开关值，此时回调里的变更只能忽略 */
        boolean binding;

        KeyVH(View v, ToggleCallback callback) {
            super(v);
            cardView = v.findViewById(R.id.card_key);
            tvBadge = v.findViewById(R.id.tv_badge);
            tvCode = v.findViewById(R.id.tv_key_code);
            tvAddr = v.findViewById(R.id.tv_key_addr);
            tvGroupTag = v.findViewById(R.id.tv_group_tag);
            swEnabled = v.findViewById(R.id.sw_enabled);
            btnEdit = v.findViewById(R.id.btn_edit);
            btnDelete = v.findViewById(R.id.btn_delete);
            // 监听器只注册一次：以前每次 bind 新建闭包，复用 View 时会用“上一条账号”的回调串号写库
            swEnabled.setOnCheckedChangeListener((btn, checked) -> {
                if (binding) return;
                KeyStoreManager.KeyEntry e = boundEntry;
                if (e == null || e.enabled == checked) return;
                callback.onToggle(this, e, checked);
            });
        }
    }
    // endregion

}
