package com.lgns.ui.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.lgns.R;
import com.lgns.contract.BlockscoutApi;
import com.lgns.model.TxDetail;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 交易详情页：对标钱包 App 的单笔转账详情，展示发/收款方、金额、网络费、哈希、区块、时间与可复制的查询链接。
 *
 * <p>列表已带过来的字段（from/to/金额/代币/时间/区块）直接上屏；网络费、状态、确认数不在
 * token-transfers 事件里，按 txHash 再走一次 {@link BlockscoutApi#fetchTxDetail} 补齐，
 * 未回来前显示占位，失败也保持占位而非伪造 0。</p>
 *
 * <p>每个可复制字段右侧带复制按钮，「查询链接」额外提供在浏览器打开。</p>
 */
public class TransferDetailActivity extends BaseActivity {

    public static final String EXTRA_TX_HASH = "extra_tx_hash";
    public static final String EXTRA_FROM = "extra_from";
    public static final String EXTRA_TO = "extra_to";
    public static final String EXTRA_SYMBOL = "extra_symbol";
    public static final String EXTRA_DECIMALS = "extra_decimals";
    public static final String EXTRA_AMOUNT_WEI = "extra_amount_wei";
    public static final String EXTRA_TIMESTAMP_MS = "extra_timestamp_ms";
    public static final String EXTRA_BLOCK = "extra_block";
    public static final String EXTRA_QUERY_ADDR = "extra_query_addr";

    private String txHash, from, to, symbol, amountWei, queryAddr;
    private int decimals;
    private long timestampMs, blockNumber;

    private ProgressBar pbLoading;
    private LinearLayout rows;
    // 异步补齐的行控件引用
    private TextView tvFeeValue, tvStatusValue, tvConfirmValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer_detail);

        Intent g = getIntent();
        txHash = g.getStringExtra(EXTRA_TX_HASH);
        from = g.getStringExtra(EXTRA_FROM);
        to = g.getStringExtra(EXTRA_TO);
        symbol = g.getStringExtra(EXTRA_SYMBOL);
        decimals = g.getIntExtra(EXTRA_DECIMALS, 18);
        amountWei = g.getStringExtra(EXTRA_AMOUNT_WEI);
        timestampMs = g.getLongExtra(EXTRA_TIMESTAMP_MS, 0);
        blockNumber = g.getLongExtra(EXTRA_BLOCK, 0);
        queryAddr = g.getStringExtra(EXTRA_QUERY_ADDR);

        pbLoading = findViewById(R.id.pb_detail_loading);
        rows = findViewById(R.id.ll_detail_rows);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        boolean incoming = !TextUtils.isEmpty(to) && to.toLowerCase(Locale.US)
                .equals((queryAddr == null ? "" : queryAddr).toLowerCase(Locale.US));

        TextView tvDirection = findViewById(R.id.tv_direction);
        TextView tvAmount = findViewById(R.id.tv_amount);
        TextView tvSymbol = findViewById(R.id.tv_symbol);
        tvDirection.setText(incoming ? R.string.txn_incoming : R.string.txn_outgoing);
        tvAmount.setText((incoming ? "+ " : "- ") + formatWei(amountWei, decimals));
        tvAmount.setTextColor(getColor(incoming ? R.color.claim_green : R.color.text_primary));
        tvSymbol.setText(TextUtils.isEmpty(symbol) ? getString(R.string.txn_token) : symbol);

        buildRows();
        loadFee();
    }

    /** 先铺静态行（列表已带数据），网络费/状态/确认数留占位待异步补 */
    private void buildRows() {
        addRow(R.string.txn_from, shortAddr(from), from);
        addRow(R.string.txn_to, shortAddr(to), to);
        tvFeeValue = addPendingRow(R.string.txn_fee);
        tvStatusValue = addPendingRow(R.string.txn_status);
        addRow(R.string.txn_hash, shortAddr(txHash), txHash);
        addRow(R.string.txn_block, String.valueOf(blockNumber), String.valueOf(blockNumber));
        tvConfirmValue = addPendingRow(R.string.txn_confirmations);
        addRow(R.string.txn_time, formatTime(timestampMs), null);

        // 查询链接：整行值 + 复制 + 在浏览器打开
        String link = BlockscoutApi.txPageUrl(txHash);
        addLinkRow(R.string.txn_query_link, link);
    }

    /**
     * 一行「标签 + 值 +（可选）复制按钮」。copyText 非空才显示复制按钮，
     * 值本身可为长地址/哈希，用 monospace 小字并允许换行。
     */
    private void addRow(int labelRes, String value, String copyText) {
        addDividerIfNeeded();
        LinearLayout row = newRow();

        TextView tvLabel = newLabel(labelRes);
        TextView tvValue = newValue(value);
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vlp.setMarginStart(dp(10));
        row.addView(tvValue, vlp);

        if (!TextUtils.isEmpty(copyText)) {
            row.addView(newCopyBtn(labelRes, copyText));
        }
        rows.addView(row);
    }

    /** 待异步填充的行：先放占位 "--"，返回数值控件引用供回调回填 */
    private TextView addPendingRow(int labelRes) {
        addDividerIfNeeded();
        LinearLayout row = newRow();
        TextView tvLabel = newLabel(labelRes);
        TextView tvValue = newValue(getString(R.string.common_placeholder));
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vlp.setMarginStart(dp(10));
        row.addView(tvValue, vlp);
        rows.addView(row);
        return tvValue;
    }

    /** 查询链接行：可换行完整 URL + 复制 + 在浏览器打开 */
    private void addLinkRow(int labelRes, String link) {
        addDividerIfNeeded();
        LinearLayout row = newRow();
        TextView tvLabel = newLabel(labelRes);
        // 链接不铺满整行：只显「域名/tx/短哈希」，复制与打开仍用完整 link
        String display = BlockscoutApi.BASE_URL.replaceFirst("^https?://", "")
                + "/tx/" + shortAddr(txHash);
        TextView tvValue = newValue(display);
        tvValue.setTextSize(11.5f);
        tvValue.setTextColor(getColor(R.color.primary));
        row.addView(tvLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        vlp.setMarginStart(dp(10));
        row.addView(tvValue, vlp);
        row.addView(newCopyBtn(labelRes, link));
        rows.addView(row);

        TextView tvOpen = new TextView(this);
        tvOpen.setText(R.string.txn_open_browser);
        tvOpen.setTextSize(12f);
        tvOpen.setTextColor(getColor(R.color.primary));
        tvOpen.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tvOpen.setPadding(0, dp(2), 0, dp(8));
        tvOpen.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.common_error, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        olp.setMarginStart(dp(10));
        rows.addView(tvOpen, olp);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        return row;
    }

    private TextView newLabel(int labelRes) {
        TextView tv = new TextView(this);
        tv.setText(labelRes);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.text_secondary));
        return tv;
    }

    private TextView newValue(String value) {
        TextView tv = new TextView(this);
        tv.setText(TextUtils.isEmpty(value) ? getString(R.string.common_placeholder) : value);
        tv.setTextSize(13);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setGravity(Gravity.END);
        return tv;
    }

    private ImageButton newCopyBtn(int labelRes, String copyText) {
        ImageButton btn = new ImageButton(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMarginStart(dp(6));
        btn.setLayoutParams(lp);
        btn.setBackgroundResource(R.drawable.bg_icon_soft);
        btn.setImageResource(R.drawable.ic_copy_svg);
        btn.setPadding(dp(6), dp(6), dp(6), dp(6));
        btn.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.primary)));
        btn.setContentDescription(getString(R.string.txn_cd_copy));
        btn.setOnClickListener(v -> copyToClipboard(getString(labelRes), copyText));
        return btn;
    }

    private void addDividerIfNeeded() {
        if (rows.getChildCount() == 0) return;
        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.divider));
        rows.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
    }

    /** 后台补网络费/状态/确认数；失败保持占位，不影响已展示的静态字段 */
    private void loadFee() {
        if (TextUtils.isEmpty(txHash)) return;
        pbLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final TxDetail d = BlockscoutApi.fetchTxDetail(txHash);
            runOnUiThread(() -> {
                pbLoading.setVisibility(View.GONE);
                if (isFinishing() || d == null) return;
                String fee = d.formattedFee();
                if (fee != null) tvFeeValue.setText(fee + " " + getString(R.string.txn_fee_unit));
                tvStatusValue.setText(statusLabel(d.status));
                if (d.confirmations > 0) tvConfirmValue.setText(String.valueOf(d.confirmations));
            });
        }).start();
    }

    private String statusLabel(String status) {
        if ("ok".equalsIgnoreCase(status)) return getString(R.string.txn_status_ok);
        if ("error".equalsIgnoreCase(status)) return getString(R.string.txn_status_error);
        if (status == null || status.isEmpty()) return getString(R.string.common_placeholder);
        return getString(R.string.txn_status_pending);
    }

    private void copyToClipboard(String fieldLabel, String text) {
        if (TextUtils.isEmpty(text)) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.txn_clip_label), text));
            Toast.makeText(this, getString(R.string.txn_copied_fmt, fieldLabel),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.toast_copy_failed_fmt, e.getMessage()),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(long ms) {
        if (ms <= 0) return getString(R.string.common_placeholder);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(ms));
    }

    /** wei 按代币小数位转可读串：保留到该代币本身精度（如 fLGNS 18 位），去尾随 0 */
    private String formatWei(String wei, int dec) {
        try {
            BigDecimal bd = new BigDecimal(TextUtils.isEmpty(wei) ? "0" : wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(dec)), dec, RoundingMode.DOWN)
                    .stripTrailingZeros();
            String s = bd.toPlainString();
            if (s.contains("E") || s.contains("e")) s = bd.setScale(0, RoundingMode.DOWN).toPlainString();
            return s.isEmpty() ? "0" : s;
        } catch (Exception e) {
            return wei;
        }
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 地址/哈希缩写：0x + 前 6 … 后 4，与列表行同口径；详情页只缩展示，复制仍用完整值 */
    private String shortAddr(String a) {
        if (a == null || a.isEmpty()) return getString(R.string.common_placeholder);
        if (a.length() <= 14) return a;
        return a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }
}
