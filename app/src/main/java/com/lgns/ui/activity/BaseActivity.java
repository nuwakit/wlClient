package com.lgns.ui.activity;

import androidx.appcompat.app.AppCompatActivity;

import com.lgns.manager.AppGlobal;

/**
 * 所有页面的基类：把「用户还在操作」这一个事实接到会话计时器上。
 *
 * <p>为什么必须收口在基类：空闲上锁靠 {@code AppGlobal} 里的
 * {@code LAST_ACTIVE_TS} 判定，而它原来只在解密链路（{@code getKeyStoreManager()}）
 * 与批量任务里被刷新。用户在主界面滚列表、点齿轮、翻子页面统统不算活动，
 * 于是「App 一直开着、人也一直在看」照样被判超时，30 分钟后弹出输密码框。
 * 触摸事件统一在 {@link #onUserInteraction()} 收口，一处覆盖全部页面，
 * 不用逐个控件挂 onTouchListener。</p>
 *
 * <p>两条语义边界：</p>
 * <ul>
 *   <li>{@link AppGlobal#touch()} 只重置计时器，既不会重建已失效的会话，
 *   也不会绕过任何解密门——解锁仍然只能靠密码；</li>
 *   <li>这里只维护「前台」标志，不改变上锁策略。回前台算不算活动由
 *   {@link #onResume()} 里的 touch 决定。</li>
 * </ul>
 */
public abstract class BaseActivity extends AppCompatActivity {

    /** onResume ~ onPause 之间为 true：空闲上锁回调靠它决定「立刻弹」还是「等回前台再弹」 */
    private boolean foreground;

    /**
     * 回到前台：置前台标志并续期。
     *
     * <p>这里也要 touch：否则 App 从后台切回来的那一瞬间，计时器可能已经超过阈值，
     * 用户刚看到界面就被判「空闲」。</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        AppGlobal.touch();
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
    }

    /**
     * 任何触摸/按键分发都会走到这里（Activity 层的统一入口）。
     *
     * <p>选它而不是给控件挂监听：列表滚动、输入法按键、弹窗外的点按都能覆盖到，
     * 且新增页面只要继承本类就自动生效。</p>
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        AppGlobal.touch();
    }

    /** 当前页面是否在前台（onResume 之后、onPause 之前）；后台创建的弹窗一律不该弹 */
    public boolean isForeground() {
        return foreground;
    }
}
