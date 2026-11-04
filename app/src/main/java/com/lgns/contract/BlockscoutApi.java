package com.lgns.contract;

import android.util.Log;

import com.lgns.model.ArenaConfig;
import com.lgns.model.TokenTransferItem;
import com.lgns.model.TxDetail;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Blockscout 浏览器只读降级通道：RPC 节点全不可达时，至少还能查余额与链高度。
 *
 * <p>实测能力边界（2026-10-27 探测）：</p>
 * <ul>
 *   <li>可用：原生币余额、ERC20 余额（fLGNS / gasDAI，与 playerSummary.walletBalance 同口径）、
 *   链高度（{@code /api/v2/blocks}，用于判断「链活着、只是 RPC 挂了」）；</li>
 *   <li>不可用：proxy 模块被禁用（eth_call 不代理），playerSummary / jiaziCount
 *   这类合约读取<b>不在</b>降级范围内。</li>
 * </ul>
 *
 * <p>所有方法失败返回 null、不抛异常：降级通道自己不能变成新的崩溃点。
 * 必须在后台线程调用（内部是同步 HTTP）。</p>
 */
public class BlockscoutApi {

    private static final String TAG = "BlockscoutApi";

    /** 官方浏览器（Blockscout），Etherscan 兼容 API + 部分 v2 端点 */
    public static final String BASE_URL = "https://browser.anubispace.org";

    /**
     * fLGNS（擂台合约 {@code TOKEN()} 返回的代币）地址。
     *
     * <p>来源：链上 tokentx 反查合约交互记录 + tokeninfo 核对
     * （symbol=fLGNS、decimals=18、总量 2.1 亿），实测 tokenbalance
     * 与 playerSummary.walletBalance 完全一致，可放心当同口径读数。</p>
     */
    public static final String FLGNS_TOKEN_ADDR = "0xffffbe730e2cde54d8928d36f547e41deb66aaaa";

    private static final int TIMEOUT_MS = 15_000;

    private BlockscoutApi() {}

    /**
     * 当前链高度；null = 浏览器也连不上。
     *
     * <p>非 null 且接近公共节点高度 → 链在正常出块，故障只在 RPC 网关层，稍后重试即可。</p>
     */
    public static Long fetchLatestBlockHeight() {
        try {
            JSONObject root = new JSONObject(httpGet(BASE_URL + "/api/v2/blocks"));
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) return null;
            return items.getJSONObject(0).optLong("height", -1);
        } catch (Exception e) {
            Log.d(TAG, "fetchLatestBlockHeight 失败: " + e);
            return null;
        }
    }

    /** 任意 ERC20 余额（wei 原值）；null = 查询失败，不能当「余额为 0」 */
    public static BigInteger fetchTokenBalance(String tokenAddr, String owner) {
        try {
            String url = BASE_URL + "/api?module=account&action=tokenbalance"
                    + "&contractaddress=" + tokenAddr + "&address=" + owner;
            JSONObject root = new JSONObject(httpGet(url));
            if (!"1".equals(root.optString("status", ""))) return null;
            String result = root.optString("result", null);
            if (result == null || result.isEmpty()) return null;
            return new BigInteger(result.trim());
        } catch (Exception e) {
            Log.d(TAG, "fetchTokenBalance 失败: " + e);
            return null;
        }
    }

    /** fLGNS 余额（wei 原值）——与 playerSummary.walletBalance 同口径 */
    public static BigInteger fetchFlgnsBalance(String owner) {
        return fetchTokenBalance(FLGNS_TOKEN_ADDR, owner);
    }

    /** gasDAI（Gas 币）余额（wei 原值）——与 callGasTokenBalance 同口径 */
    public static BigInteger fetchGasTokenBalance(String owner) {
        return fetchTokenBalance(ArenaConfig.GAS_TOKEN_ADDR, owner);
    }

    /** 原生余额（wei 原值）：本链与 gasDAI.balanceOf 恒等，仅展示用 */
    public static BigInteger fetchNativeBalance(String owner) {
        try {
            String url = BASE_URL + "/api?module=account&action=balance&address=" + owner;
            JSONObject root = new JSONObject(httpGet(url));
            if (!"1".equals(root.optString("status", ""))) return null;
            return new BigInteger(root.getString("result").trim());
        } catch (Exception e) {
            Log.d(TAG, "fetchNativeBalance 失败: " + e);
            return null;
        }
    }

    /**
     * 某地址的代币转账记录（最新在前），供「支付方 → 转账记录」列表页展示。
     *
     * <p>走 {@code /api/v2/addresses/{addr}/token-transfers}：v1 接口在本浏览器上返回 400，
     * 前端实际调的就是 v2。默认取第一页（约 50 条），够列表首屏；翻页未做。
     * 失败返回空列表而非 null，页面据此走空态，不把「查询失败」当成「没有转账过」。</p>
     */
    public static List<TokenTransferItem> fetchTokenTransfers(String addr) {
        List<TokenTransferItem> out = new ArrayList<>();
        if (addr == null || addr.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(httpGet(BASE_URL + "/api/v2/addresses/" + addr + "/token-transfers"));
            JSONArray items = root.optJSONArray("items");
            if (items == null) return out;
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.optJSONObject(i);
                if (o == null) continue;
                TokenTransferItem it = new TokenTransferItem();
                it.from = optAddr(o.optJSONObject("from"));
                it.to = optAddr(o.optJSONObject("to"));
                JSONObject token = o.optJSONObject("token");
                if (token != null) {
                    it.tokenSymbol = token.optString("symbol", "");
                    it.tokenAddress = token.optString("address_hash", "");
                }
                JSONObject total = o.optJSONObject("total");
                if (total != null) {
                    it.amountWei = total.optString("value", "0");
                    it.tokenDecimals = parseDecimals(total.optString("decimals", ""));
                }
                it.blockNumber = o.optLong("block_number", 0);
                it.txHash = o.optString("transaction_hash", "");
                it.timestampMs = parseIsoMillis(o.optString("timestamp", ""));
                out.add(it);
            }
        } catch (Exception e) {
            Log.d(TAG, "fetchTokenTransfers 失败: " + e);
        }
        return out;
    }

    /**
     * 按交易哈希查基础信息（网络费/状态/确认数），供详情页补 gas。
     *
     * <p>失败返回 null，页面把网络费显示成占位而不是 0。fee 取 {@code fee.value}（权威实际 gas 费，wei）。</p>
     */
    public static TxDetail fetchTxDetail(String hash) {
        if (hash == null || hash.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(httpGet(BASE_URL + "/api/v2/transactions/" + hash));
            TxDetail d = new TxDetail();
            d.hash = root.optString("hash", hash);
            d.status = root.optString("status", "");
            d.blockNumber = root.optLong("block_number", 0);
            d.confirmations = root.optLong("confirmations", 0);
            d.timestampMs = parseIsoMillis(root.optString("timestamp", ""));
            JSONObject fee = root.optJSONObject("fee");
            if (fee != null) d.feeWei = fee.optString("value", null);
            return d;
        } catch (Exception e) {
            Log.d(TAG, "fetchTxDetail 失败: " + e);
            return null;
        }
    }

    /** 浏览器交易详情页链接，供「查询链接」复制/打开 */
    public static String txPageUrl(String hash) {
        return BASE_URL + "/tx/" + (hash == null ? "" : hash);
    }

    /** 地址对象里只取 hash；null 安全 */
    private static String optAddr(JSONObject o) {
        return o == null ? "" : o.optString("hash", "");
    }

    /** decimals 是字符串，空/异常回落到 18（ERC-20 主流） */
    private static int parseDecimals(String s) {
        try {
            if (s == null || s.isEmpty()) return 18;
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 18;
        }
    }

    /**
     * 解析 Blockscout 的 ISO 时间（形如 {@code 2026-10-30T11:32:06.000000Z}）为 epoch 毫秒。
     *
     * <p>后端带 6 位微秒，{@code SimpleDateFormat} 的 {@code SSS} 只吃 3 位毫秒，
     * 所以先把小数部分截到 3 位再解析；解析失败返回 0，由页面按「时间未知」处理。</p>
     */
    private static long parseIsoMillis(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            String s = iso;
            int dot = s.indexOf('.');
            if (dot >= 0) {
                int z = s.indexOf('Z', dot);
                if (z < 0) z = s.length();
                String frac = s.substring(dot + 1, Math.min(z, dot + 4));
                while (frac.length() < 3) frac += "0";
                s = s.substring(0, dot + 1) + frac + "Z";
            }
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date dt = sdf.parse(s);
            return dt != null ? dt.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            InputStream is = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
