package com.zfdang.zsmth_android;

import android.content.Context;

/**
 * 友盟统计已移除（v26.12.54）。
 * 保留此类及全部 public 方法签名作为空实现，让调用点无需改动即可编译；
 * hasChoice 恒返回 true（表示"已做过选择"），避免首次启动弹隐私统计对话框。
 */
public final class AnalyticsPrivacy {
  private AnalyticsPrivacy() {}

  /** 恒 true：统计已移除，视为用户已选择，不再弹隐私统计对话框 */
  public static boolean hasChoice(Context context) {
    return true;
  }

  public static boolean isEnabled(Context context) {
    return false;
  }

  public static void saveChoice(Context context, boolean enabled) {
    // 友盟已移除，无需保存
  }

  public static synchronized void initialize(Context context) {
    // 友盟已移除，不做任何初始化
  }

  public static void onResume(Context context) {
    // no-op
  }

  public static void onPause(Context context) {
    // no-op
  }
}
