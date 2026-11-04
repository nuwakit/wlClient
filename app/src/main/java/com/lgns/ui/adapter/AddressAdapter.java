package com.lgns.ui.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.model.AddressItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 接收方地址列表适配器：分组头 + 地址行，行内支持启用开关、编辑、删除与拖拽排序。
 *
 * <p>职责边界：适配器只负责渲染与把交互事件抛给 {@link Listener}，
 * 数据变更（增删改、比例重算、落盘）由页面统一处理后再回灌。</p>
 *
 * <p>比例与数额两列同时展示（数额为 0 的行不显示胶囊）：具体哪个参与分配由系统配置页的
 * 批量转账方式决定，不在这里猜模式，让用户在任意模式下都能看到自己填过的全部数据。</p>
 *
 * <p>分组同样支持整体折叠，折叠态存在适配器内部，刷新时按分组名保留。</p>
 */
public class AddressAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // region 回调接口

    /**
     * 行内交互全部回调给页面：适配器只把「用户动作 + 对应条目」交回去，不自己改数据。
     */
    public interface Listener {
        /** 编辑该地址的编号 / 地址 / 收益比例 / 收益数额 */
        void onEdit(AddressItem item);

        /** 删除该地址（二次确认与落库由页面负责） */
        void onDelete(AddressItem item);

        /** 启用 / 停用切换；批量转账只会用到启用的地址 */
        void onToggle(AddressItem item, boolean enabled);

        /** 从拖拽手柄开始拖动排序（交给 ItemTouchHelper） */
        void onStartDrag(RecyclerView.ViewHolder holder);

        /** 点击分组头折叠 / 展开 */
        void onToggleGroup(String groupName);
    }

    // endregion

    // region 行模型

    /** 列表行：分组头与地址行共用，用 {@link #type} 区分 */
    public static class Row {
        /** 分组头行 */
        public static final int TYPE_HEADER = 0;
        /** 地址行 */
        public static final int TYPE_ITEM = 1;

        /** 行类型，取值同 {@code TYPE_HEADER} / {@code TYPE_ITEM} */
        public int type;
        /** 所属分组名：组头行直接显示，地址行用于折叠筛选 */
        public String groupName = "";
        /** 地址数据；组头行为 null，绑定时需先判 type */
        public AddressItem item;

        /** 构造分组头行（不带地址数据） */
        public static Row header(String name) {
            Row r = new Row();
            r.type = TYPE_HEADER;
            r.groupName = name;
            return r;
        }

        /** 构造地址行（groupName 要与所属分组头一致，折叠时靠它过滤） */
        public static Row item(String groupName, AddressItem it) {
            Row r = new Row();
            r.type = TYPE_ITEM;
            r.groupName = groupName;
            r.item = it;
            return r;
        }
    }

    /**
     * 徽标调色盘：分组头按分组名取色、地址行按地址取色，
     * 同一个实体每次绑定颜色一致，不随滚动位置变化。
     */
    private static final int[] BADGE_COLORS = {
            0xFF1976D2, 0xFF00897B, 0xFF8E24AA, 0xFFEF6C00,
            0xFF546E7A, 0xFF43A047, 0xFFD81B60, 0xFF3949AB
    };

    /** 可见行（折叠的组只留组头），由页面持有并在排序 / 折叠后重建 */
    private final List<Row> visible;
    /** 全量行：折叠的行不在 visible 里，统计每组人数必须看 all */
    private final List<Row> all;
    /** 分组名 → 是否展开；与页面共用同一个 Map，重建适配器不会丢折叠态 */
    private final Map<String, Boolean> expanded;
    private final Listener listener;

    // endregion

    // region 构造

    /**
     * @param visible  可见行列表，由页面持有并就地重建（适配器不拷贝，刷新才能生效）
     * @param all      全量行列表，分组人数统计靠它
     * @param expanded 分组展开状态，与页面共用同一个 Map，重建适配器不丢折叠态
     */
    public AddressAdapter(Listener listener, List<Row> visible, List<Row> all,
                          Map<String, Boolean> expanded) {
        this.listener = listener;
        this.visible = visible;
        this.all = all;
        this.expanded = expanded;
    }

    // endregion

    // region 列表回调

    @Override
    public int getItemViewType(int position) {
        return visible.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Row.TYPE_HEADER) {
            return new HeaderVH(inflater.inflate(R.layout.item_group_header, parent, false));
        }
        return new ItemVH(inflater.inflate(R.layout.item_address, parent, false));
    }

    /**
     * 绑定一行：组头只画颜色、人数与箭头；地址行回填字段并挂上编辑 / 删除 / 开关 / 拖拽。
     * 复用 ViewHolder 时先把监听器摘掉再赋值，否则旧数据会被当成用户操作回写。
     */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = visible.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.tvGroupName.setText(row.groupName);
            h.viewColor.setBackgroundTintList(ColorStateList.valueOf(
                    BADGE_COLORS[Math.abs(row.groupName.hashCode()) % BADGE_COLORS.length]));
            int count = 0;
            for (Row r : all) {
                if (r.type == Row.TYPE_ITEM && r.groupName.equals(row.groupName)) count++;
            }
            h.tvGroupCount.setText(h.itemView.getContext().getString(R.string.common_count_fmt, count));
            boolean open = expanded.getOrDefault(row.groupName, false);
            h.ivExpand.setRotation(open ? 90 : 0);
            h.itemView.setOnClickListener(v -> listener.onToggleGroup(row.groupName));
            return;
        }

        ItemVH h = (ItemVH) holder;
        AddressItem it = row.item;
        h.tvBadge.setText(it.label());
        h.tvBadge.setBackgroundTintList(ColorStateList.valueOf(
                BADGE_COLORS[Math.abs(it.address.hashCode()) % BADGE_COLORS.length]));
        h.tvCode.setText(it.label());
        h.tvAddr.setText(it.address);
        h.tvRatio.setText(it.ratio + "%");
        // 数额为 0 时整块隐掉：按数额转账时它本来就不参与，按比例转账时又全是无效信息，
        // 满屏「0 fLGNS」只会把真正设了数额的那几行淹掉
        BigDecimal amt;
        try {
            amt = new BigDecimal(it.amount == null || it.amount.trim().isEmpty() ? "0" : it.amount.trim());
        } catch (Exception e) {
            amt = BigDecimal.ZERO;
        }
        if (amt.signum() > 0) {
            h.tvAmount.setVisibility(View.VISIBLE);
            h.tvAmount.setText(amt.stripTrailingZeros().toPlainString() + " fLGNS");
        } else {
            h.tvAmount.setVisibility(View.GONE);
        }

        // 先摘监听再设值：滚动复用时不能把旧数据当成用户操作回写
        h.sw.setOnCheckedChangeListener(null);
        h.sw.setChecked(it.enabled);
        h.sw.setOnCheckedChangeListener((btn, checked) -> listener.onToggle(it, checked));

        h.btnEdit.setOnClickListener(v -> listener.onEdit(it));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(it));
        h.btnDrag.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) listener.onStartDrag(h);
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    // endregion

    // region ViewHolder

    /** 分组头 ViewHolder：组名 + 人数 + 左侧颜色条 + 展开箭头 */
    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvGroupName, tvGroupCount;
        final View viewColor;
        final ImageView ivExpand;

        HeaderVH(@NonNull View v) {
            super(v);
            tvGroupName = v.findViewById(R.id.tv_group_name);
            tvGroupCount = v.findViewById(R.id.tv_group_count);
            viewColor = v.findViewById(R.id.view_color);
            ivExpand = v.findViewById(R.id.iv_expand);
        }
    }

    /** 地址行 ViewHolder：徽标/编号/比例/数额/地址 + 启用开关 + 编辑/删除/拖拽按钮 */
    static class ItemVH extends RecyclerView.ViewHolder {
        final ImageView btnDrag;
        final TextView tvBadge, tvCode, tvRatio, tvAmount, tvAddr;
        final SwitchCompat sw;
        final ImageButton btnEdit, btnDelete;

        ItemVH(@NonNull View v) {
            super(v);
            btnDrag = v.findViewById(R.id.btn_drag);
            tvBadge = v.findViewById(R.id.tv_badge);
            tvCode = v.findViewById(R.id.tv_code);
            tvRatio = v.findViewById(R.id.tv_ratio);
            tvAmount = v.findViewById(R.id.tv_amount);
            tvAddr = v.findViewById(R.id.tv_addr);
            sw = v.findViewById(R.id.sw_enabled);
            btnEdit = v.findViewById(R.id.btn_edit);
            btnDelete = v.findViewById(R.id.btn_delete);
        }
    }

    // endregion

}
