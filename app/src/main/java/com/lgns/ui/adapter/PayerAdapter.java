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

import com.google.android.material.card.MaterialCardView;
import com.lgns.R;
import com.lgns.model.PayerItem;

import java.util.List;
import java.util.Map;

/**
 * 支付方列表适配器：分组头 + 支付方行，行内支持启用开关、编辑、删除与拖拽排序。
 *
 * <p>与 {@link AddressAdapter} 结构一致，差异在于数据模型是 {@code PayerItem}，
 * 且需要额外标出「主号」——主号由页面通过 {@code setMasterId} 下发，行内加徽标区分，
 * 防止用户误删或误改出资账号。</p>
 */
public class PayerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // region 回调接口

    /** 行内交互回调，全部由页面实现：适配器只转发，不自己改数据 */
    public interface Listener {
        /** 点击编辑弹出修改弹窗 */
        void onEdit(PayerItem item);

        /** 删除该支付方（二次确认与落库由页面负责） */
        void onDelete(PayerItem item);

        /** 行尾图标：查看该支付方地址的链上转账记录 */
        void onQueryTransfers(PayerItem item);

        /** 启用 / 停用切换；只有启用的支付方才会被批量转账使用 */
        void onToggle(PayerItem item, boolean enabled);

        /** 从拖拽手柄开始拖动排序（交给 ItemTouchHelper） */
        void onStartDrag(RecyclerView.ViewHolder holder);

        /** 点击分组头折叠 / 展开 */
        void onToggleGroup(String groupName);
    }

    // endregion

    // region 行模型

    /** 列表行：分组头与支付方行共用，用 {@link #type} 区分 */
    public static class Row {
        /** 分组头行 */
        public static final int TYPE_HEADER = 0;
        /** 支付方行 */
        public static final int TYPE_ITEM = 1;

        /** 行类型，取值同 {@code TYPE_HEADER} / {@code TYPE_ITEM} */
        public int type;
        /** 所属分组名：组头行直接显示，支付方行用作胶囊文案与分组统计 */
        public String groupName = "";
        /** 支付方数据；组头行为 null，绑定时需先判 type */
        public PayerItem item;

        /** 构造分组头行（不带支付方数据） */
        public static Row header(String name) {
            Row r = new Row();
            r.type = TYPE_HEADER;
            r.groupName = name;
            return r;
        }

        /** 构造支付方行（groupName 用于胶囊文案与分组统计） */
        public static Row item(String groupName, PayerItem it) {
            Row r = new Row();
            r.type = TYPE_ITEM;
            r.groupName = groupName;
            r.item = it;
            return r;
        }
    }

    /**
     * 徽标调色盘：分组头按分组名、支付方按编号取色，同一实体颜色稳定。
     */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };

    /** 可见行（折叠的组只剩组头） */
    private final List<Row> visible;
    /** 全量行，用于统计每组人数 */
    private final List<Row> all;
    /** 分组名 → 是否展开，与页面共用 */
    private final Map<String, Boolean> expanded;
    private final Listener listener;
    /** 当前出资方（主号）的 id；为 -1 表示未设置，此时没有任何一行会高亮 */
    private long masterId = -1;

    // endregion

    // region 构造与主号标记

    /**
     * @param visible  可见行列表，由页面持有并就地重建
     * @param all      全量行列表，用于统计每组人数
     * @param expanded 分组展开状态，与页面共用同一个 Map
     */
    public PayerAdapter(Listener listener, List<Row> visible, List<Row> all,
                        Map<String, Boolean> expanded) {
        this.listener = listener;
        this.visible = visible;
        this.all = all;
        this.expanded = expanded;
    }

    /** 出资支付方（主号）变更后由页面回填，用于高亮整卡与「出资方」胶囊 */
    public void setMasterId(long masterId) {
        this.masterId = masterId;
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
        return new ItemVH(inflater.inflate(R.layout.item_payer, parent, false),
                (holder, item, checked) -> listener.onToggle(item, checked));
    }

    /**
     * 绑定一行：组头画颜色/人数/箭头；支付方行区分「出资方」与普通行两种视觉。
     * 主号行用浅橙底 + 橙色描边并在胶囊位置显示「出资方」，其余行显示所属分组。
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
        PayerItem it = row.item;
        boolean isMaster = it.id == masterId;
        String label = it.label();
        h.tvCode.setText(label);
        // 徽标取编号首字，和私钥管理列表同一套视觉
        h.tvBadge.setText(label.isEmpty() ? "?" : String.valueOf(label.charAt(0)).toUpperCase());
        h.tvBadge.setBackgroundTintList(ColorStateList.valueOf(
                BADGE_COLORS[Math.abs(label.hashCode() % BADGE_COLORS.length)]));
        h.tvAddr.setText(shortAddr(h, it.address));

        // 出资方整卡浅橙底 + 橙色描边，一眼区分
        if (isMaster) {
            h.cardView.setCardBackgroundColor(0xFFFFF8F0);
            h.cardView.setStrokeColor(0xFFFFE0B2);
            h.tvGroupTag.setVisibility(View.VISIBLE);
            h.tvGroupTag.setText(R.string.payer_master_pill);
            h.tvGroupTag.setTextColor(0xFFE65100);
            h.tvGroupTag.setBackgroundTintList(ColorStateList.valueOf(0xFFFFF3E0));
        } else {
            h.cardView.setCardBackgroundColor(0xFFFFFFFF);
            h.cardView.setStrokeColor(0xFFEDF1F6);
            String ungrouped = h.itemView.getContext().getString(R.string.common_ungrouped);
            if (row.groupName != null && !row.groupName.equals(ungrouped)) {
                h.tvGroupTag.setVisibility(View.VISIBLE);
                h.tvGroupTag.setText(row.groupName);
                h.tvGroupTag.setTextColor(0xFF78909C);
                h.tvGroupTag.setBackgroundTintList(ColorStateList.valueOf(0xFFF1F5F9));
            } else {
                h.tvGroupTag.setVisibility(View.GONE);
            }
        }

        // 程序化 setChecked 用 binding 屏蔽回调，否则复用 View 时旧值会被当成用户操作落库
        h.binding = true;
        h.boundItem = it;
        h.swEnabled.setChecked(it.enabled);
        h.binding = false;

        h.btnTransfers.setOnClickListener(v -> listener.onQueryTransfers(h.boundItem));
        h.btnEdit.setOnClickListener(v -> listener.onEdit(h.boundItem));
        h.btnDelete.setOnClickListener(v -> listener.onDelete(h.boundItem));
        h.btnDrag.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) listener.onStartDrag(h);
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    /** 地址缩略：6+4 字符，空地址回落到「未知」，避免长地址把卡片撑变形 */
    private String shortAddr(RecyclerView.ViewHolder h, String addr) {
        if (addr == null || addr.isEmpty()) {
            return h.itemView.getContext().getString(R.string.key_addr_unknown);
        }
        if (addr.length() > 10) {
            return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
        }
        return addr;
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

    /** 支付方行 ViewHolder：整卡（主号高亮）+ 启用开关 + 编辑/删除/拖拽按钮 */
    static class ItemVH extends RecyclerView.ViewHolder {
        /** 开关回调：ViewHolder 只把用户点击抛给适配器，避免程序化 setChecked 被当成操作落库 */
        interface ToggleCallback {
            void onToggle(ItemVH holder, PayerItem item, boolean checked);
        }

        final MaterialCardView cardView;
        final ImageView btnDrag;
        final TextView tvBadge, tvCode, tvGroupTag, tvAddr;
        final SwitchCompat swEnabled;
        final ImageButton btnEdit, btnDelete, btnTransfers;

        PayerItem boundItem;
        boolean binding;

        ItemVH(@NonNull View v, ToggleCallback callback) {
            super(v);
            cardView = v.findViewById(R.id.card_payer);
            btnDrag = v.findViewById(R.id.btn_drag);
            tvBadge = v.findViewById(R.id.tv_badge);
            tvCode = v.findViewById(R.id.tv_code);
            tvGroupTag = v.findViewById(R.id.tv_group_tag);
            tvAddr = v.findViewById(R.id.tv_addr);
            swEnabled = v.findViewById(R.id.sw_enabled);
            btnEdit = v.findViewById(R.id.btn_edit);
            btnDelete = v.findViewById(R.id.btn_delete);
            btnTransfers = v.findViewById(R.id.btn_transfers);
            // 监听器只注册一次：每次 bind 新建闭包会在复用 View 时用上一条支付方的回调串号写库
            swEnabled.setOnCheckedChangeListener((btn, checked) -> {
                if (binding) return;
                PayerItem item = boundItem;
                if (item == null || item.enabled == checked) return;
                callback.onToggle(this, item, checked);
            });
        }
    }

    // endregion

}
