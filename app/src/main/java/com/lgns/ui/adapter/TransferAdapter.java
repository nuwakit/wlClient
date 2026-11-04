package com.lgns.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.model.TokenTransferItem;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 转账记录列表适配器：一行一笔 ERC-20 转账，方向（收/付）按本行地址与查询地址比对得出。
 *
 * <p>金额着色沿用钱包 App 惯例：收到绿色、付出中性深色；方向图标同色呼应。
 * 行点击整行转发给页面跳详情，适配器不自己起 Intent。</p>
 */
public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.VH> {

    /** 行点击回调，由页面实现跳转 */
    public interface Listener {
        void onTransferClick(TokenTransferItem item);
    }

    private final List<TokenTransferItem> data;
    private final String queryAddress;
    private final Listener listener;
    private final SimpleDateFormat sdf =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

    public TransferAdapter(List<TokenTransferItem> data, String queryAddress, Listener listener) {
        this.data = data;
        this.queryAddress = queryAddress == null ? "" : queryAddress.toLowerCase(Locale.US);
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transfer, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        TokenTransferItem it = data.get(position);
        boolean incoming = it.to != null
                && it.to.toLowerCase(Locale.US).equals(queryAddress);

        String symbol = (it.tokenSymbol == null || it.tokenSymbol.isEmpty())
                ? h.itemView.getContext().getString(R.string.txn_token)
                : it.tokenSymbol;
        h.tvTitle.setText(incoming
                ? h.itemView.getContext().getString(R.string.txn_incoming) + " " + symbol
                : h.itemView.getContext().getString(R.string.txn_outgoing) + " " + symbol);

        // 对手方：收到的显示来源，付出的显示去向
        h.tvCounterparty.setText(incoming ? shortAddr(it.from) : shortAddr(it.to));

        h.tvAmount.setText((incoming ? "+ " : "- ") + it.formattedAmount());
        int amountColor = incoming
                ? h.itemView.getContext().getColor(R.color.claim_green)
                : h.itemView.getContext().getColor(R.color.text_primary);
        h.tvAmount.setTextColor(amountColor);
        h.ivDirection.setColorFilter(amountColor);

        h.tvTime.setText(it.timestampMs > 0 ? sdf.format(new Date(it.timestampMs)) : "--");

        // 关联接收方的行：换高亮卡片底色并带出自编号；未关联行恢复白卡并隐标签（复用 View 必须两态都设）
        if (it.associated()) {
            h.itemView.setBackgroundResource(R.drawable.bg_card_assoc);
            h.tvCodeTag.setVisibility(View.VISIBLE);
            h.tvCodeTag.setText(it.receiverCode);
        } else {
            h.itemView.setBackgroundResource(R.drawable.bg_card_white);
            h.tvCodeTag.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> listener.onTransferClick(it));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    private String shortAddr(String addr) {
        if (addr == null || addr.isEmpty()) return "--";
        if (addr.length() <= 12) return addr;
        return addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4);
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivDirection;
        final TextView tvTitle, tvCounterparty, tvTime, tvAmount, tvCodeTag;

        VH(@NonNull View v) {
            super(v);
            ivDirection = v.findViewById(R.id.iv_direction);
            tvTitle = v.findViewById(R.id.tv_title);
            tvCounterparty = v.findViewById(R.id.tv_counterparty);
            tvTime = v.findViewById(R.id.tv_time);
            tvAmount = v.findViewById(R.id.tv_amount);
            tvCodeTag = v.findViewById(R.id.tv_code_tag);
        }
    }
}
