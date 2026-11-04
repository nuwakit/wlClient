package com.lgns.helper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.Nullable;

import com.lgns.R;

/**
 * 密码强度说明弹窗：用于展示本地加密现状与离线爆破估算。
 * 主界面「?」入口、主界面安全提醒、私钥管理页共用同一套布局，避免多处文案与样式分叉。
 */
public final class PasswordStrengthDialog {

    /** 工具类，禁止实例化 */
    private PasswordStrengthDialog() {
    }

    /** 打开说明弹窗，关闭后不做额外处理 */
    public static void show(Activity activity) {
        show(activity, null);
    }

    /**
     * 打开说明弹窗，支持关闭回调（用于「先看说明、关掉后回到安全提醒」这种链路）。
     *
     * @param onDismiss 关闭后的回调，可为空
     */
    public static void show(Activity activity, @Nullable DialogInterface.OnDismissListener onDismiss) {
        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_password_strength, null);
        // 按钮在布局内自绘，避免系统按钮栏在 AppDialog 样式下贴边
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setView(view)
                .create();
        if (onDismiss != null) {
            dialog.setOnDismissListener(onDismiss);
        }
        dialog.show();
        view.findViewById(R.id.btn_pwd_strength_ok).setOnClickListener(v -> dialog.dismiss());
    }
}
