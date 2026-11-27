package com.zfdang.zsmth_android.helpers;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import com.zfdang.zsmth_android.Settings;

/**
 * 统一的轻震动反馈：翻页、长按弹菜单、底栏四个按钮的单击/双击都用这一套。
 *
 * <p>开关和时长复用「我的 → 阅读 → 翻页震动」的设置：开关关闭或时长 &lt;= 0 时静默不震，
 * 这样用户只要关一个开关就能全局关掉所有操作震动。
 */
public final class HapticFeedback {

  private HapticFeedback() {
  }

  public static void vibrate(Context context) {
    if (context == null) {
      return;
    }
    try {
      Settings st = Settings.getInstance();
      if (st == null || !st.isPageTurnVibrate() || st.getPageTurnVibrateMs() <= 0) {
        return;
      }
      Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
      if (v == null || !v.hasVibrator()) {
        return;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        v.vibrate(VibrationEffect.createOneShot(st.getPageTurnVibrateMs(),
            VibrationEffect.DEFAULT_AMPLITUDE));
      } else {
        v.vibrate(st.getPageTurnVibrateMs());
      }
    } catch (Throwable ignored) {
      // 震动失败(无权限/无马达)不影响主流程
    }
  }
}
