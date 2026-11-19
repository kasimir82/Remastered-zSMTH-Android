package com.zfdang.zsmth_android.helpers;

import android.util.Log;

import com.zfdang.SMTHApplication;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 把登录失败/受限事件追加写到本地文件，方便真机上直接取走分析。
 *
 * 文件路径：/data/data/<包名>/files/login_failures.log
 * 可用 adb 取回：
 *   adb shell run-as com.zfdang.zsmth_android cat files/login_failures.log
 *
 * 只记录失败/受限/超时等异常事件，成功登录不写（避免文件无限增长）。
 * 文件超过 MAX_BYTES 时截断保留尾部，始终保留最近的记录。
 */
public final class LoginFailureLogger {
  private static final String TAG = "LoginLog";
  private static final String FILE_NAME = "login_failures.log";
  private static final long MAX_BYTES = 256 * 1024; // 256KB 上限

  private LoginFailureLogger() {}

  /**
   * 记录一条登录异常事件。
   * @param event 事件类型，如 timeout / too_frequent / captcha / http_error / js_dialog
   * @param detail 详情（弹窗原文、错误码、cookie 状态等）
   */
  public static synchronized void log(String event, String detail) {
    try {
      File dir = SMTHApplication.getAppContext().getFilesDir();
      if (dir == null) return;
      File file = new File(dir, FILE_NAME);

      // 超限时截断，只保留尾部一半，避免反复截断
      if (file.exists() && file.length() > MAX_BYTES) {
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rws");
        try {
          long keep = MAX_BYTES / 2;
          long start = file.length() - keep;
          raf.seek(start);
          byte[] buf = new byte[(int) keep];
          int n = raf.read(buf);
          raf.setLength(0);
          raf.seek(0);
          raf.write(buf, 0, Math.max(n, 0));
        } finally {
          raf.close();
        }
      }

      String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
      String line = time + " | " + event + " | " + (detail == null ? "" : detail) + "\n";
      BufferedWriter w = new BufferedWriter(new FileWriter(file, true));
      try {
        w.write(line);
      } finally {
        w.close();
      }
      Log.d(TAG, "logged: " + line.trim());
    } catch (Throwable t) {
      // 写日志失败绝不能影响登录流程
      Log.w(TAG, "write log failed", t);
    }
  }
}
