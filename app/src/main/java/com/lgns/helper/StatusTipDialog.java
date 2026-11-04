package com.lgns.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;

import com.lgns.R;

/**
 * 通用状态提示弹窗：查询中 / 成功 / 提醒 / 失败统一视觉。
 *
 * <p>按钮在布局内自绘，避免系统按钮栏在 AppDialog 样式下贴边。
 * 所有入口都对 activity 做了 null/正在结束的保护，返回 null 时调用方无需额外判空即可跳过。</p>
 */
public final class StatusTipDialog {

    /** 中性：普通提示（蓝色） */
    public static final int TONE_INFO = 0;
    /** 成功：完成类提示（绿色） */
    public static final int TONE_SUCCESS = 1;
    /** 提醒：需要用户注意但不阻断（橙色） */
    public static final int TONE_WARN = 2;
    /** 错误：失败结果（红色） */
    public static final int TONE_ERROR = 3;

    /** 工具类，禁止实例化 */
    private StatusTipDialog() {
    }

    /** 查询中：无按钮、不可取消，调用方拿到实例后自行 dismiss */
    @Nullable
    public static AlertDialog showLoading(Activity activity, CharSequence title, CharSequence msg) {
        if (activity == null || activity.isFinishing()) return null;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_status_tip, null);
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setView(view)
                .create();

        FrameLayout badge = view.findViewById(R.id.layout_tip_badge);
        ImageView icon = view.findViewById(R.id.iv_tip_icon);
        ProgressBar progress = view.findViewById(R.id.pb_tip_loading);
        TextView tvTitle = view.findViewById(R.id.tv_tip_title);
        TextView tvMsg = view.findViewById(R.id.tv_tip_msg);

        tintBadge(badge, Color.parseColor("#FFE3F2FD"));
        icon.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);

        tvTitle.setText(title);
        if (msg == null || msg.length() == 0) {
            tvMsg.setVisibility(View.GONE);
        } else {
            tvMsg.setText(msg);
        }
        view.findViewById(R.id.layout_tip_buttons).setVisibility(View.GONE);

        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        return dialog;
    }

    /**
     * 重载：只要一个确认按钮的简单提示（按钮文案默认「知道了」）。
     */
    public static AlertDialog show(Activity activity, int tone, @DrawableRes int iconRes,
                                   CharSequence title, CharSequence msg) {
        return show(activity, tone, iconRes, title, msg,
                activity == null ? null : activity.getString(R.string.btn_ok), null, null, null);
    }

    /**
     * 完整参数版：主按钮必有，次按钮可选（不传就只显示一个按钮）。
     *
     * @param primaryText   主按钮文案，为空则用「知道了」
     * @param onPrimary     主按钮额外动作（弹窗已关闭后执行），可为空
     * @param secondaryText 次要按钮文案，为空则不显示
     * @param onSecondary   次要按钮额外动作，可为空
     */
    public static AlertDialog show(Activity activity, int tone, @DrawableRes int iconRes,
                                   CharSequence title, CharSequence msg,
                                   CharSequence primaryText, @Nullable Runnable onPrimary,
                                   @Nullable CharSequence secondaryText,
                                   @Nullable Runnable onSecondary) {
        return showInternal(activity, tone, iconRes, title, msg,
                primaryText, onPrimary, secondaryText, onSecondary, false);
    }

    /** 危险操作（删除等）：主按钮用警示红突出 */
    public static AlertDialog showDanger(Activity activity, int tone, @DrawableRes int iconRes,
                                         CharSequence title, CharSequence msg,
                                         CharSequence primaryText, @Nullable Runnable onPrimary,
                                         @Nullable CharSequence secondaryText,
                                         @Nullable Runnable onSecondary) {
        return showInternal(activity, tone, iconRes, title, msg,
                primaryText, onPrimary, secondaryText, onSecondary, true);
    }

    /**
     * 弹窗构建主体：配色 → 图标/进度条二选一 → 文案 → 按钮。
     *
     * <p>{@code danger} 只影响主按钮背景（危险操作用红色）；
     * 多行文案改为左对齐，单行仍居中，这是为了让分条说明好读。</p>
     */
    private static AlertDialog showInternal(Activity activity, int tone, @DrawableRes int iconRes,
                                            CharSequence title, CharSequence msg,
                                            CharSequence primaryText, @Nullable Runnable onPrimary,
                                            @Nullable CharSequence secondaryText,
                                            @Nullable Runnable onSecondary, boolean danger) {
        if (activity == null || activity.isFinishing()) return null;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_status_tip, null);
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setView(view)
                .create();

        int badgeColor;
        int iconColor;
        switch (tone) {
            case TONE_SUCCESS:
                badgeColor = Color.parseColor("#FFE8F5E9");
                iconColor = Color.parseColor("#FF2E7D32");
                break;
            case TONE_WARN:
                badgeColor = Color.parseColor("#FFFFF3E0");
                iconColor = Color.parseColor("#FFEF6C00");
                break;
            case TONE_ERROR:
                badgeColor = Color.parseColor("#FFFDECEA");
                iconColor = Color.parseColor("#FFC62828");
                break;
            case TONE_INFO:
            default:
                badgeColor = Color.parseColor("#FFE3F2FD");
                iconColor = Color.parseColor("#FF1976D2");
                break;
        }

        FrameLayout badge = view.findViewById(R.id.layout_tip_badge);
        ImageView icon = view.findViewById(R.id.iv_tip_icon);
        ProgressBar progress = view.findViewById(R.id.pb_tip_loading);
        TextView tvTitle = view.findViewById(R.id.tv_tip_title);
        TextView tvMsg = view.findViewById(R.id.tv_tip_msg);
        TextView btnPrimary = view.findViewById(R.id.btn_tip_primary);
        TextView btnSecondary = view.findViewById(R.id.btn_tip_secondary);
        View space = view.findViewById(R.id.space_tip_btn);

        tintBadge(badge, badgeColor);
        progress.setVisibility(View.GONE);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));

        tvTitle.setText(title);
        if (msg == null || msg.length() == 0) {
            tvMsg.setVisibility(View.GONE);
        } else {
            tvMsg.setText(msg);
            // 多行说明左对齐便于分条阅读，单行短文案保持居中
            boolean multiline = msg.toString().contains("\n");
            tvMsg.setGravity(multiline ? (Gravity.START | Gravity.CENTER_VERTICAL) : Gravity.CENTER);
        }

        if (primaryText == null || primaryText.length() == 0) {
            btnPrimary.setText(R.string.btn_ok);
        } else {
            btnPrimary.setText(primaryText);
        }
        if (danger) {
            btnPrimary.setBackgroundResource(R.drawable.bg_btn_danger);
        }

        if (secondaryText != null && secondaryText.length() > 0) {
            btnSecondary.setVisibility(View.VISIBLE);
            space.setVisibility(View.VISIBLE);
            btnSecondary.setText(secondaryText);
            btnSecondary.setOnClickListener(v -> {
                dialog.dismiss();
                if (onSecondary != null) onSecondary.run();
            });
        }

        btnPrimary.setOnClickListener(v -> {
            dialog.dismiss();
            if (onPrimary != null) onPrimary.run();
        });
        dialog.show();
        return dialog;
    }

    /** 给返回的 drawable 上 tint：先 mutate 避免污染所有用到同一张图的视图 */
    private static void tintBadge(FrameLayout badge, int color) {
        if (badge.getBackground() != null) {
            badge.getBackground().mutate().setTint(color);
        }
    }

}
