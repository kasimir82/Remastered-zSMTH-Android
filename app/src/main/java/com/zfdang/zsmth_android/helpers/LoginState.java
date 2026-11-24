package com.zfdang.zsmth_android.helpers;

/**
 * 登录状态三态（互斥）。
 *
 * 设计目的：把过去散落的 user_online / last_login_success / cookie用户名 等标志
 * 收敛成一个明确的状态，让「是否允许自动登录」「是否弹登录框」等行为有唯一依据。
 *
 * - LOGGED_IN        : 服务端会话有效（NewExpress 版面验证通过）
 * - USER_LOGGED_OUT  : 用户主动点了「注销」，禁止一切自动登录，只能用户手动登录恢复
 * - SESSION_LOST     : 会话过期/被踢/从未登录，允许自动登录（受冷却、锁、节流约束）
 */
public enum LoginState {
    LOGGED_IN,
    USER_LOGGED_OUT,
    SESSION_LOST;

    public static LoginState fromString(String s) {
        if (s == null) return SESSION_LOST;
        if (LOGGED_IN.name().equals(s)) return LOGGED_IN;
        if (USER_LOGGED_OUT.name().equals(s)) return USER_LOGGED_OUT;
        return SESSION_LOST;
    }
}
