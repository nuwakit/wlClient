package com.lgns.helper;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.lgns.R;
import com.lgns.manager.AppGlobal;
import com.lgns.manager.KeyStoreManager;
import com.lgns.util.Web3AndroidUtil;

import java.util.List;

/**
 * 独立页面用的解密密码弹窗：拿第一条密文试解，通过才回调。
 *
 * <p>为什么要试解而不是明文比对：应用不存密码本身，能解开任意一条密文就是唯一凭证
 * （AES-GCM 的认证标签会直接报错）。</p>
 *
 * <p>回调线程：{@code onKey} 在主线程触发，拿到的 char[] 归调用方所有，
 * 用完必须自己 {@code Arrays.fill} 清零。试解在后台线程做，避免 PBKDF2 卡住 UI。</p>
 */
public final class CryptoKeyPrompt {

    /** 密码校验通过后的回调，参数为可直接用于解密的会话密钥 */
    public interface Callback {
        /** 在主线程回调；{@code key} 由调用方负责清零 */
        void onKey(char[] key);
    }

    /** 工具类，禁止实例化 */
    private CryptoKeyPrompt() { }

    /**
     * 私钥场景：用库里第一条密钥的密文校验密码。
     *
     * <p>库里没有密钥时不校验，直接放行——否则新用户永远解不了锁。</p>
     */
    public static void show(Activity activity, Callback callback) {
        showInternal(activity, null, callback, null);
    }

    /**
     * 带「取消回调」的重载：调用方可以在弹窗期间占住资源（如全局任务闸门），
     * 用户点取消/返回键/触摸外部关掉弹窗时必须收到通知，否则资源永远不释放。
     */
    public static void show(Activity activity, Callback callback, Runnable onCancel) {
        showInternal(activity, null, callback, onCancel);
    }

    /**
     * 支付方专用：支付方的加解密密码与私钥管理相互独立，
     * 所以不能用私钥密文校验，只能拿支付方自己的密文试解。
     */
    public static void showPayer(Activity activity, String payerCipherText, Callback callback) {
        showInternal(activity, payerCipherText, callback, null);
    }

    /**
     * 弹窗主体（两类场景共用）。
     *
     * <p>{@code payerCipherText} 为 null 是私钥场景，非 null 是支付方场景：
     * 两者只差「拿谁的密文试解」与标题文案。确认按钮在校验期间置灰，
     * 成功后先关弹窗再回调，弹窗已被关掉时直接清零密钥、不回调。</p>
     *
     * <p>{@code onCancel} 非 null 时，未确认就关闭弹窗（取消键/返回键/触摸外部）会回调它：
     * 调用方靠它释放「弹窗期间占住的」全局闸门与按钮态，否则一次取消就把页面卡死。</p>
     */
    private static void showInternal(Activity activity, String payerCipherText,
                                     Callback callback, Runnable onCancel) {
        // 提前返回也要回调取消：否则调用方占住的闸门/按钮态会永久卡死
        if (activity == null || activity.isFinishing()) {
            if (onCancel != null) onCancel.run();
            return;
        }

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_decrypt_password, null);
        final EditText etPwd = view.findViewById(R.id.et_decrypt_pwd);
        final TextView tvError = view.findViewById(R.id.tv_error);
        final View btnConfirm = view.findViewById(R.id.btn_pwd_confirm);

        // 支付方场景把标题/副标题改成支付方专用，避免用户输成私钥密码
        if (payerCipherText != null) {
            ((TextView) view.findViewById(R.id.tv_decrypt_title)).setText(R.string.payer_decrypt_pwd_title);
            ((TextView) view.findViewById(R.id.tv_decrypt_subtitle)).setText(R.string.payer_decrypt_pwd_subtitle);
        }

        // 帮助入口指向私钥管理，这里只做校验不做引导，先隐藏避免多一层跳转
        View btnHelp = view.findViewById(R.id.btn_pwd_help);
        if (btnHelp != null) btnHelp.setVisibility(View.GONE);

        final AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AppDialog)
                .setView(view)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        // 未确认就关闭（取消键 / 返回键 / 触摸外部）= 放弃本次操作，回调给调用方释放资源
        final boolean[] confirmed = { false };
        dialog.setOnDismissListener(d -> {
            if (!confirmed[0] && onCancel != null) onCancel.run();
        });
        dialog.show();
        etPwd.requestFocus();

        etPwd.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                tvError.setVisibility(View.GONE);
            }
        });

        view.findViewById(R.id.btn_pwd_cancel).setOnClickListener(v -> dialog.dismiss());
        btnConfirm.setOnClickListener(v -> {
            final String pwd = etPwd.getText().toString();
            if (pwd.isEmpty()) {
                tvError.setText(R.string.err_pwd_empty);
                tvError.setVisibility(View.VISIBLE);
                return;
            }
            final char[] key = pwd.toCharArray();
            btnConfirm.setEnabled(false);
            new Thread(() -> {
                String err = null;
                try {
                    // 试解只用到「密文 + 密码」，不需要解密会话：
                    // 用会话实例的话，会话一过期这里就抛 IllegalStateException，
                    // 会被当成密码错误显示出来，密码框就永远解不开了
                    KeyStoreManager mgr = AppGlobal.getMetaManager();
                    if (payerCipherText != null) {
                        // 支付方：用该支付方自己的密文试解
                        if (!payerCipherText.isEmpty()) mgr.testDecrypt(payerCipherText, key);
                    } else {
                        List<KeyStoreManager.KeyEntry> list = mgr.loadKeyEntryList();
                        // 库里还有密钥就用第一条试解，解开才说明密码正确
                        if (!list.isEmpty()) mgr.testDecrypt(list.get(0).cipherText, key);
                    }
                    // 试解通过 = 密码正确：把会话补回来，后续 decryptCipherText 链路才可用。
                    // 外层再包一层 try：会话重建失败不应该被误报成“密码错误”
                    try {
                        AppGlobal.ensureSession(activity);
                    } catch (Exception ignore) {
                    }
                } catch (Exception e) {
                    err = Web3AndroidUtil.safeMessage(e);
                }
                final String fErr = err;
                activity.runOnUiThread(() -> {
                    btnConfirm.setEnabled(true);
                    if (fErr == null) {
                        if (dialog.isShowing()) {
                            // 先置位：dismiss 会同步触发 onDismissListener，顺序反了会把「已确认」误判成「取消」
                            confirmed[0] = true;
                            dialog.dismiss();
                            callback.onKey(key);
                        } else {
                            java.util.Arrays.fill(key, (char) 0);
                        }
                    } else {
                        tvError.setText(R.string.dialog_pwd_error_msg);
                        tvError.setVisibility(View.VISIBLE);
                    }
                });
            }).start();
        });
    }
}
