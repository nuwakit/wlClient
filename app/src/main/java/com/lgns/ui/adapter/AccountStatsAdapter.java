package com.lgns.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lgns.R;
import com.lgns.model.ArenaConfig;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号统计列表适配器：把「分组头 + 账号行」摊平成单列表，分组头负责折叠/展开。
 *
 * <p>职责边界：只做展示与折叠态维护，统计口径与排序全部由页面算好后以
 * {@code DisplayItem} 列表塞进来；适配器不查链、不读存储。</p>
 *
 * <p>展开状态按分组名存在 {@code expandedMap} 里，刷新数据时保持用户当前的展开结果，
 * 避免每次定时刷新都把分组弹回默认状态。</p>
 */
public class AccountStatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** 分组头行：显示分组名、人数与折叠箭头，点击切换折叠 */
    private static final int TYPE_HEADER = 0;
    /** 账号行：一行一个账号的链上统计 */
    private static final int TYPE_ACCOUNT = 1;

    /**
     * 分组胶囊颜色盘。同一分组名按 hash 取模，颜色在同一次运行内稳定；
     * 不依赖分组顺序，重命名才会换色。
     */
    private static final int[] BADGE_COLORS = {
            0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC,
            0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A,
            0xFF8D6E63, 0xFF78909C
    };
    // region 数据源与分组状态

    /**
     * 账号行点击回调：点某一行进入该地址的详情页。
     * 只在账号行（TYPE_ACCOUNT）触发，分组头行的折叠点击不走这里。
     */
    public interface OnAccountClickListener {
        void onAccountClick(AccountStatsItem item);
    }

    /** 页面设置的行点击监听，null 时账号行不可点 */
    private OnAccountClickListener accountClickListener;

    /** 装配账号行点击回调（详情页跳转由此触发） */
    public void setOnAccountClickListener(OnAccountClickListener listener) {
        this.accountClickListener = listener;
    }

    /** 页面塞进来的完整数据（含所有分组与账号），用于计数与回填 */
    private final List<DisplayItem> allList = new ArrayList<>();
    /** 实际交给 RecyclerView 的可见列表：折叠的分组只保留组头 */
    private final List<DisplayItem> visibleList = new ArrayList<>();
    /** 分组名 → 是否展开；LinkedHashMap 保证组头顺序与传入顺序一致 */
    private final Map<String, Boolean> expandedMap = new LinkedHashMap<>();

    // endregion

    // region 数据模型

    /** 主界面一个账号行的数据快照：链上数值 + 展示用名称，不含密文与私钥 */
    public static class AccountStatsItem {
        /** 展示用标签（优先自定义编号，没填用 3 位 displayNo） */
        public String name;
        /** 账号地址，也是与链上结果匹配的 key */
        public String address;
        /** 钱包内 fLGNS 余额（wei） */
        public BigInteger balance;
        /** 今日已入场次数 */
        public int entriesToday;
        /** 今日剩余入场次数（为 0 表示已打满） */
        public int entriesRemaining;
        /** 今日冠军次数 */
        public int todayChampion;
        /** 累计冠军次数 */
        public int totalChampion;
        /** 可领奖金（wei） */
        public BigInteger prize;
        /** 待领取的甲子总数（wei），汇总行展示用 */
        public BigInteger totalEarnings;
        /** 所属分组名，用于按组折叠与上色 */
        public String groupNo;
        /** 甲子可提（wei）：随 playerSummary 免费带回，第一波即有值 */
        public BigInteger jiaziClaimable;
        /** 擂台可领（wei）：第三波逐地址 claimable() 查回，未查到时为 null */
        public BigInteger arenaClaimable;
        /** 师徒可领（wei）：第三波逐地址 lineagePending() 查回，未查到时为 null */
        public BigInteger lineagePending;
        /** 可铸盟主令枚数（领讫俸禄笔数−已铸NFT）：第三波逐地址 jiaziPage + balanceOf 统计，未查到时为 null */
        public Integer mintableCount;
        /** 已铸盟主令枚数（ChampionMandate.balanceOf，官网「已 Mint」）：与可铸成对回填，未查到时为 null */
        public Integer mintedCount;
        /** 福泽待领枚数（本期 rewardOf status=待领）：第三波逐地址 callFuzeSummary 回填，未查到时为 null */
        public Integer fuzePendingCount;
        /** 福泽待领合计（wei）：与待领枚数成对回填，口径同官网「待领 N 枚 · X fLGNS」 */
        public BigInteger fuzePendingValue;
        /** 第三波待领查询是否已对这行跑完：没跑完前五个胶囊统一占位，
         *  避免初始化时「甲子已显真值、其他还是 --」的半屏混合态 */
        public boolean tagsReady;
        /** 本轮结果还没回来（首次上屏/刷新中）：数值显示占位，状态位留空 */
        public boolean pending;
        /** 本轮查询结束但链上没返回这个地址：显示“未查到” */
        public boolean unknown;

        /** 账号行数据全量填充：只存字段不做校验，调用方保证地址非空 */
        public AccountStatsItem(String name, String address, BigInteger balance,
                                int entriesToday, int entriesRemaining,
                                int todayChampion, int totalChampion, BigInteger prize,
                                BigInteger totalEarnings) {
            this.name = name;
            this.address = address;
            this.balance = balance;
            this.entriesToday = entriesToday;
            this.entriesRemaining = entriesRemaining;
            this.todayChampion = todayChampion;
            this.totalChampion = totalChampion;
            this.prize = prize;
            this.totalEarnings = totalEarnings;
        }
    }

    /** 列表行：组头与账号行共用同一个实体，用 {@link #type} 区分 */
    public static class DisplayItem {
        /** 行类型，取值同 {@code TYPE_HEADER} / {@code TYPE_ACCOUNT} */
        public int type;
        /** 所属分组名：组头行直接显示，账号行用于折叠筛选与上色 */
        public String groupName;
        /** 账号数据；组头行为 null，绑定时必须先判 type 再取 */
        public AccountStatsItem account;

        /** 构造一个分组头行（type=TYPE_HEADER，不带账号数据） */
        public static DisplayItem header(String groupName) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_HEADER;
            d.groupName = groupName;
            return d;
        }

        /** 构造一个账号行（type=TYPE_ACCOUNT，必须带 account，否则绑定时会空指针） */
        public static DisplayItem account(String groupName, AccountStatsItem account) {
            DisplayItem d = new DisplayItem();
            d.type = TYPE_ACCOUNT;
            d.groupName = groupName;
            d.account = account;
            return d;
        }
    }

    // endregion

    // region 列表回调

    @Override
    public int getItemViewType(int position) {
        return visibleList.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_group_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_account_stats, parent, false);
        return new AccountVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        DisplayItem item = visibleList.get(position);
        if (holder instanceof HeaderVH) {
            HeaderVH h = (HeaderVH) holder;
            h.tvGroupName.setText(item.groupName);
            h.viewColor.setBackgroundColor(getBadgeColor(item.groupName));
            int count = 0;
            for (DisplayItem d : allList) {
                if (d.type == TYPE_ACCOUNT && d.groupName.equals(item.groupName)) count++;
            }
            h.tvGroupCount.setText(h.itemView.getContext().getString(R.string.common_count_fmt, count));
            boolean expanded = expandedMap.getOrDefault(item.groupName, true);
            h.ivExpand.setRotation(expanded ? 90 : 0);
            h.itemView.setOnClickListener(v -> toggleGroup(item.groupName));
        } else if (holder instanceof AccountVH) {
            AccountVH a = (AccountVH) holder;
            a.bind(item.account);
            // 账号行整行可点→详情页；分组头行的折叠在上方 header 分支单独处理
            if (accountClickListener != null && item.account != null) {
                a.itemView.setOnClickListener(v ->
                        accountClickListener.onAccountClick(item.account));
            } else {
                a.itemView.setOnClickListener(null);
                a.itemView.setClickable(false);
            }
        }
    }

    @Override
    public int getItemCount() {
        return visibleList.size();
    }

    // endregion

    // region 数据更新与分组展开

    /**
     * 整体换数据：保留各分组上一次的折叠态（新分组默认展开），再重建可见列表。
     * 定时刷新也走这里，所以不能把展开状态重置，否则用户每次刷新都会被弹回首屏形态。
     */
    public void setItems(List<DisplayItem> items) {
        Map<String, Boolean> prev = new LinkedHashMap<>(expandedMap);
        allList.clear();
        allList.addAll(items);
        expandedMap.clear();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_HEADER) {
                Boolean p = prev.get(d.groupName);
                expandedMap.put(d.groupName, p != null ? p : true);
            }
        }
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    /** 快照当前展示中的账号数据（地址→数据引用），供本地结构刷新时继承已加载的链上数值 */
    public Map<String, AccountStatsItem> snapshotAccountItems() {
        Map<String, AccountStatsItem> map = new java.util.HashMap<>();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_ACCOUNT && d.account != null && d.account.address != null) {
                map.put(d.account.address, d.account);
            }
        }
        return map;
    }

    /** 当前展示中的账号行（引用直连：分批回填时直接写字段，再调 {@link #notifyStatsChanged}） */
    public List<AccountStatsItem> accountItems() {
        List<AccountStatsItem> out = new ArrayList<>();
        for (DisplayItem d : allList) {
            if (d.type == TYPE_ACCOUNT && d.account != null) out.add(d.account);
        }
        return out;
    }

    /** 局部回填后刷新：行结构不变、只改数值，不会打乱滚动位置 */
    public void notifyStatsChanged() {
        notifyDataSetChanged();
    }

    private void toggleGroup(String groupName) {
        expandedMap.put(groupName, !expandedMap.getOrDefault(groupName, true));
        rebuildVisibleList();
        notifyDataSetChanged();
    }

    /** 按展开状态摊平：折叠的组只留组头，组内账号不进可见列表 */
    private void rebuildVisibleList() {
        visibleList.clear();
        String currentGroup = null;
        boolean currentExpanded = true;
        for (DisplayItem item : allList) {
            if (item.type == TYPE_HEADER) {
                currentGroup = item.groupName;
                currentExpanded = expandedMap.getOrDefault(currentGroup, true);
                visibleList.add(item);
            } else if (currentExpanded) {
                visibleList.add(item);
            }
        }
    }

    /** 分组名的稳定取色：同一分组每次绑定颜色一致，不会随滚动变色 */
    private int getBadgeColor(String name) {
        return BADGE_COLORS[Math.abs(name.hashCode()) % BADGE_COLORS.length];
    }

    // endregion

    // region ViewHolder

    /** 分组头 ViewHolder：组名 + 人数 + 颜色条 + 展开箭头 */
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

    /** 账号行 ViewHolder：标签/地址/余额/走势与状态位，具体取值在 bind 里回填 */
    static class AccountVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAddr, tvStatusTag;
        TextView tvBalance, tvEntries, tvTodayChampion, tvTotalChampion, tvPrize;
        /** 师徒分成解锁提示条：今日场次不足 10 场时显示，满额/未开打时隐藏 */
        TextView tvLineageHint;
        /** 待领小标签：甲子/擂台/师徒三个额度，外加可铸盟主令枚数与福泽待领，与批量领取口径一致 */
        TextView tvTagJiazi, tvTagArena, tvTagLineage, tvTagMint, tvTagFuze;

        AccountVH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_account_name);
            tvAddr = itemView.findViewById(R.id.tv_account_addr);
            tvStatusTag = itemView.findViewById(R.id.tv_status_tag);
            tvBalance = itemView.findViewById(R.id.tv_balance);
            tvEntries = itemView.findViewById(R.id.tv_entries);
            tvTodayChampion = itemView.findViewById(R.id.tv_today_champion);
            tvTotalChampion = itemView.findViewById(R.id.tv_total_champion);
            tvPrize = itemView.findViewById(R.id.tv_prize);
            tvLineageHint = itemView.findViewById(R.id.tv_lineage_hint);
            tvTagJiazi = itemView.findViewById(R.id.tv_tag_jiazi);
            tvTagArena = itemView.findViewById(R.id.tv_tag_arena);
            tvTagLineage = itemView.findViewById(R.id.tv_tag_lineage);
            tvTagMint = itemView.findViewById(R.id.tv_tag_mint);
            tvTagFuze = itemView.findViewById(R.id.tv_tag_fuze);
        }

        void bind(AccountStatsItem item) {
            android.content.Context ctx = itemView.getContext();
            tvName.setText(item.name);

            if (item.address != null && item.address.length() > 10) {
                tvAddr.setText(item.address.substring(0, 6) + "..." +
                        item.address.substring(item.address.length() - 4));
            } else {
                tvAddr.setText(item.address != null ? item.address : ctx.getString(R.string.common_placeholder));
            }

            // 还没查到结果：批量查询是一次性返回的，行与行不会先后完成，
            // 所以这里不伪造逐行“同步中”进度，只在全局汇总栏提示同步中；
            // 整行降透明度做骨架化处理，让“未就绪”和“已就绪”一眼能分（对标大厂骨架屏）
            if (item.pending) {
                itemView.setAlpha(0.45f);
                tvStatusTag.setVisibility(View.GONE);
                tvStatusTag.setText("");
                tvBalance.setText(R.string.common_placeholder);
                tvEntries.setText(R.string.common_placeholder);
                tvTodayChampion.setText(R.string.common_placeholder);
                tvTotalChampion.setText(R.string.common_placeholder);
                tvPrize.setText(R.string.common_placeholder);
                tvLineageHint.setVisibility(View.GONE);
                placeholderTags();
                return;
            }
            // 视图复用：非 pending 行必须把状态位与透明度恢复出来
            itemView.setAlpha(1f);
            tvStatusTag.setVisibility(View.VISIBLE);

            // 整行未查到：所有数值位显示 -- ，避免把“查不到”误读成“余额 0 / 今日未入场”
            if (item.unknown) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_unknown));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
                tvBalance.setText(R.string.common_placeholder);
                tvEntries.setText(R.string.common_placeholder);
                tvTodayChampion.setText(R.string.common_placeholder);
                tvTotalChampion.setText(R.string.common_placeholder);
                tvPrize.setText(R.string.common_placeholder);
                tvLineageHint.setVisibility(View.GONE);
                placeholderTags();
                return;
            }

            int maxEntries = item.entriesToday + item.entriesRemaining;
            if (item.entriesRemaining == 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_full));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_red);
            } else if (item.entriesToday > 0) {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_running));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_green);
            } else {
                tvStatusTag.setText(ctx.getString(R.string.stats_status_not_entered));
                tvStatusTag.setBackgroundResource(R.drawable.bg_tag_gray);
            }

            tvBalance.setText(formatToken(item.balance));
            tvEntries.setText(item.entriesToday + "/" + maxEntries);
            // 今日冠军单独查不到时用 -1 标记，显示 -- ，不混进真实的 0
            tvTodayChampion.setText(item.todayChampion < 0
                    ? ctx.getString(R.string.common_placeholder)
                    : String.valueOf(item.todayChampion));
            tvTotalChampion.setText(String.valueOf(item.totalChampion));
            tvPrize.setText(formatToken(item.totalEarnings));
            bindTags(item);

            // 师徒分成按「今天打了几场」线性解锁（合约 10%/场），且只认当天场次：
            // 昨天攒下的份额今天不补齐就白丢（D+2 由合约的 _burnExpiredLineage 烧掉）。
            // 只在「已经开打、还没打满」时提示：0 场的号每行都挂橙条会变成噪音，
            // 打满的号没有风险，两种情况都不显示。
            int fullMatches = ArenaConfig.FULL_ACTIVITY_MATCHES;
            if (item.entriesToday > 0 && item.entriesToday < fullMatches) {
                tvLineageHint.setVisibility(View.VISIBLE);
                tvLineageHint.setText(ctx.getString(R.string.stats_lineage_hint_fmt,
                        item.entriesToday, fullMatches, item.entriesToday * 100 / fullMatches));
            } else {
                tvLineageHint.setVisibility(View.GONE);
            }
        }

        private String formatToken(BigInteger wei) {
            if (wei == null) return "0.00";
            BigDecimal bd = new BigDecimal(wei)
                    .divide(new BigDecimal(BigInteger.TEN.pow(18)), 2, RoundingMode.HALF_UP);
            return bd.toPlainString();
        }

        /** 待领胶囊全部占位显示（pending/unknown 行不展示半成品数值，但位置先站住） */
        private void placeholderTags() {
            applyPlaceholder(tvTagJiazi, R.string.account_tag_jiazi_ph);
            applyPlaceholder(tvTagArena, R.string.account_tag_arena_ph);
            applyPlaceholder(tvTagLineage, R.string.account_tag_lineage_ph);
            applyPlaceholder(tvTagMint, R.string.account_tag_mint_ph);
            applyPlaceholder(tvTagFuze, R.string.account_tag_fuze_ph);
        }

        /**
         * 绑定甲子/擂台/师徒三个待领胶囊：
         * <p>数值为 null 表示本轮没查到，灰底显「名称 --」占位（不伪造 0，也不让胶囊
         * 回填后才弹出来抢位）；查到就显示，&gt;0 用绿底一眼看出有钱可领、=0 用灰底。
         * 甲子随 playerSummary 免费带回，擂台/师徒靠第三波逐地址查询。口径与批量领取一致。</p>
         */
        private void bindTags(AccountStatsItem item) {
            // 第三波还没跑完：五个胶囊统一占位，不先把第一波带回的甲子真值单独露出来
            if (!item.tagsReady) {
                placeholderTags();
                return;
            }
            android.content.Context ctx = itemView.getContext();
            applyTag(tvTagJiazi, item.jiaziClaimable,
                    ctx.getString(R.string.account_tag_jiazi_fmt, formatToken(item.jiaziClaimable)),
                    R.string.account_tag_jiazi_ph);
            applyTag(tvTagArena, item.arenaClaimable,
                    ctx.getString(R.string.account_tag_arena_fmt, formatToken(item.arenaClaimable)),
                    R.string.account_tag_arena_ph);
            applyTag(tvTagLineage, item.lineagePending,
                    ctx.getString(R.string.account_tag_lineage_fmt, formatToken(item.lineagePending)),
                    R.string.account_tag_lineage_ph);
            // 盟主令两口径：可铸=领讫俸禄笔数−已铸(ChampionMandate.balanceOf)、已铸=balanceOf，按枚而非金额，走独立计数着色
            applyCountTag(tvTagMint, item.mintableCount,
                    ctx.getString(R.string.account_tag_mint_fmt,
                            item.mintableCount, item.mintedCount == null ? 0 : item.mintedCount),
                    R.string.account_tag_mint_ph);
            // 福泽待领：本期 status=待领 的枚数与合计（与官网 rewards 页同口径），未查到显占位不伪造 0
            applyCountTag(tvTagFuze, item.fuzePendingCount,
                    ctx.getString(R.string.account_tag_fuze_fmt,
                            item.fuzePendingCount, formatToken(item.fuzePendingValue)),
                    R.string.account_tag_fuze_ph);
        }

        /** 枚数型胶囊：null→占位；否则显文本并按 &gt;0/0 切绿/灰底（与金额胶囊同一语义） */
        private void applyCountTag(TextView tv, Integer count, String label, int phRes) {
            if (count == null) {
                applyPlaceholder(tv, phRes);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(label);
            tv.setBackgroundResource(count > 0
                    ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);
        }

        /** 单个待领标签：null→占位；否则显文本并按 >0/0 切绿/灰底 */
        private void applyTag(TextView tv, BigInteger wei, String label, int phRes) {
            if (wei == null) {
                applyPlaceholder(tv, phRes);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(label);
            tv.setBackgroundResource(wei.signum() > 0
                    ? R.drawable.bg_tag_green : R.drawable.bg_tag_gray);
        }

        /** 胶囊占位态：灰底显「名称 --」，与真实 0 同色——没查到的不该看起来像有钱可领 */
        private void applyPlaceholder(TextView tv, int phRes) {
            tv.setVisibility(View.VISIBLE);
            tv.setText(phRes);
            tv.setBackgroundResource(R.drawable.bg_tag_gray);
        }
    }

    // endregion

}
