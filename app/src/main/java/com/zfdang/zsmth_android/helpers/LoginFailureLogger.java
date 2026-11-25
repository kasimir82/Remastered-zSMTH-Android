package com.zfdang.zsmth_android.helpers;

/**
 * 登录失败事件日志（兼容入口）。
 *
 * 已统一到 DiagLogger 的单一日志文件 zsmth_debug.log，格式：
 *   时间 [模块名][代码/FAIL] 详细内容
 * 本类保留原 API(log(event, detail))，内部转发到 DiagLogger，调用方无需改动。
 */
public final class LoginFailureLogger {

  private LoginFailureLogger() {}

  /**
   * 记录一条登录异常事件。
   * @param event 事件类型（作为日志代码），如 timeout / too_frequent / captcha / http_error
   * @param detail 详情
   */
  public static synchronized void log(String event, String detail) {
    DiagLogger.log("Login", event == null ? "UNKNOWN" : event.toUpperCase(java.util.Locale.US),
        DiagLogger.FAIL, detail);
  }
}
