package com.lgns.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.contract.BlockscoutApi;
import com.lgns.manager.AddressBookManager;
import com.lgns.model.AddressItem;
import com.lgns.model.TokenTransferItem;
import com.lgns.ui.adapter.TransferAdapter;
import com.lgns.util.Web3AndroidUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 转账记录列表页：从支付方管理行尾图标进入，展示该地址的链上 ERC-20 转账（最新在前）。
 *
 * <p>数据走 {@link BlockscoutApi#fetchTokenTransfers}（只读、后台线程），不经私钥；
 * 点某行进 {@link TransferDetailActivity} 看单交易详情。空态与失败态分开：
 * 查询失败不能伪装成「没有转账过」。</p>
 */
public class TransferListActivity extends BaseActivity implements TransferAdapter.Listener {

    public static final String EXTRA_ADDRESS = "extra_address";
    public static final String EXTRA_LABEL = "extra_label";

    private String address;
    private String label;

    private RecyclerView rv;
    private ProgressBar pbLoading;
    private View layoutEmpty;
    private TextView tvSubtitle, tvEmptyTitle, tvEmptyMsg;

    private final List<TokenTransferItem> data = new ArrayList<>();
    private TransferAdapter adapter;
    /** 在途去抖：连点返回/重进只认最后一次，旧线程结果作废 */
    private volatile boolean destroyed = false;
    private final java.util.concurrent.atomic.AtomicInteger loadSeq =
            new java.util.concurrent.atomic.AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer_list);

        address = getIntent().getStringExtra(EXTRA_ADDRESS);
        label = getIntent().getStringExtra(EXTRA_LABEL);

        rv = findViewById(R.id.rv_transfer);
        pbLoading = findViewById(R.id.pb_loading);
        layoutEmpty = findViewById(R.id.layout_empty);
        tvSubtitle = findViewById(R.id.tv_subtitle);
        tvEmptyTitle = findViewById(R.id.tv_empty_title);
        tvEmptyMsg = findViewById(R.id.tv_empty_msg);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvSubtitle.setText(label == null || label.isEmpty() ? shortAddr(address) : label);

        adapter = new TransferAdapter(data, address, this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        loadData();
    }

    private void loadData() {
        final int seq = loadSeq.incrementAndGet();
        pbLoading.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        new Thread(() -> {
            List<TokenTransferItem> list;
            String err = null;
            try {
                list = BlockscoutApi.fetchTokenTransfers(address);
                associateAndSort(list);
            } catch (Exception e) {
                list = null;
                err = Web3AndroidUtil.safeMessage(e);
            }
            final List<TokenTransferItem> result = list;
            final String failMsg = err;
            runOnUiThread(() -> {
                if (destroyed || seq != loadSeq.get()) return;
                pbLoading.setVisibility(View.GONE);
                if (result == null) {
                    showEmpty(failMsg != null ? failMsg : getString(R.string.common_error), true);
                    return;
                }
                data.clear();
                data.addAll(result);
                adapter.notifyDataSetChanged();
                if (data.isEmpty()) {
                    showEmpty(getString(R.string.txn_empty_msg), false);
                } else {
                    layoutEmpty.setVisibility(View.GONE);
                    rv.setVisibility(View.VISIBLE);
                    tvSubtitle.setText(getString(R.string.txn_list_subtitle_fmt,
                            label == null || label.isEmpty() ? shortAddr(address) : label,
                            data.size()));
                }
            });
        }).start();
    }

    /**
     * 按「接收方管理」名单给转账打关联标记并重排：
     * <ol>
     *   <li>对手方（付出的 to / 收到的 from）命中接收方地址的，记其自编号与在接收方列表中的序号；</li>
     *   <li>排序：关联行优先，且按接收方列表顺序（序号升序）；同接收方内、以及未关联行，都按时间新在前。</li>
     * </ol>
     *
     * <p>接收方地址在库中转小写存，Blockscout 返回的是校验和混合大小写，故统一转小写比对。</p>
     */
    private void associateAndSort(List<TokenTransferItem> items) {
        if (items == null || items.isEmpty()) return;
        List<AddressItem> receivers = new AddressBookManager(this).loadAddressList();
        Map<String, Integer> rankMap = new HashMap<>();
        Map<String, String> codeMap = new HashMap<>();
        for (int i = 0; i < receivers.size(); i++) {
            AddressItem a = receivers.get(i);
            if (a.address == null || a.address.isEmpty()) continue;
            String k = a.address.toLowerCase(Locale.US);
            if (!rankMap.containsKey(k)) {
                rankMap.put(k, i);
                codeMap.put(k, a.label());
            }
        }

        String q = address == null ? "" : address.toLowerCase(Locale.US);
        for (TokenTransferItem it : items) {
            boolean incoming = it.to != null && it.to.toLowerCase(Locale.US).equals(q);
            String counterparty = incoming ? it.from : it.to;
            if (counterparty == null || counterparty.isEmpty()) continue;
            String ck = counterparty.toLowerCase(Locale.US);
            Integer rank = rankMap.get(ck);
            if (rank != null) {
                it.receiverRank = rank;
                it.receiverCode = codeMap.get(ck);
            }
        }

        Collections.sort(items, (x, y) -> {
            boolean ax = x.associated(), ay = y.associated();
            if (ax != ay) return ax ? -1 : 1;                 // 关联行整体靠前
            if (ax && x.receiverRank != y.receiverRank) {
                return Integer.compare(x.receiverRank, y.receiverRank); // 按接收方列表顺序
            }
            return Long.compare(y.timestampMs, x.timestampMs);           // 其余按时间新在前
        });
    }

    private void showEmpty(String msg, boolean failed) {
        rv.setVisibility(View.GONE);
        layoutEmpty.setVisibility(View.VISIBLE);
        tvEmptyTitle.setText(failed
                ? getString(R.string.txn_load_failed_fmt, msg)
                : getString(R.string.txn_empty_title));
        tvEmptyMsg.setText(failed ? getString(R.string.txn_loading) : msg);
    }

    @Override
    public void onTransferClick(TokenTransferItem item) {
        Intent intent = new Intent(this, TransferDetailActivity.class);
        intent.putExtra(TransferDetailActivity.EXTRA_TX_HASH, item.txHash);
        intent.putExtra(TransferDetailActivity.EXTRA_FROM, item.from);
        intent.putExtra(TransferDetailActivity.EXTRA_TO, item.to);
        intent.putExtra(TransferDetailActivity.EXTRA_SYMBOL, item.tokenSymbol);
        intent.putExtra(TransferDetailActivity.EXTRA_DECIMALS, item.tokenDecimals);
        intent.putExtra(TransferDetailActivity.EXTRA_AMOUNT_WEI, item.amountWei);
        intent.putExtra(TransferDetailActivity.EXTRA_TIMESTAMP_MS, item.timestampMs);
        intent.putExtra(TransferDetailActivity.EXTRA_BLOCK, item.blockNumber);
        intent.putExtra(TransferDetailActivity.EXTRA_QUERY_ADDR, address);
        startActivity(intent);
    }

    private String shortAddr(String addr) {
        if (addr == null || addr.isEmpty()) return "--";
        if (addr.length() <= 12) return addr;
        return addr.substring(0, 6) + "…" + addr.substring(addr.length() - 4);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadSeq.incrementAndGet();
        super.onDestroy();
    }
}
