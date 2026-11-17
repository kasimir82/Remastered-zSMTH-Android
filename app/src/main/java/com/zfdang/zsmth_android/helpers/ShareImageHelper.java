package com.zfdang.zsmth_android.helpers;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.zfdang.zsmth_android.fresco.FrescoUtils;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 把水木的图片分享给其它应用。
 *
 * 系统分享只接受 content:// 形式的文件，所以先把图片落到 cacheDir/attachments/
 * （FileProvider 已经在 AndroidManifest 里声明了这个路径），再交给 ACTION_SEND。
 * 优先复用 Fresco 的磁盘缓存，缓存里没有时再用带 Cookie 的 OkHttp 下载原图。
 */
public class ShareImageHelper {
  private static final String TAG = "ShareImage";

  /** 与 attachment_paths.xml 中的 path 保持一致 */
  private static final String SHARE_DIR = "attachments";
  /** 分享用的临时文件保留时长，过期的会在下次分享时顺手清掉 */
  private static final long SHARE_FILE_MAX_AGE = 24 * 60 * 60 * 1000L;

  public static void shareImage(final Context context, final String imageUrl) {
    if (context == null) {
      return;
    }
    if (TextUtils.isEmpty(imageUrl)) {
      Toast.makeText(context, "这张图片没有可分享的地址", Toast.LENGTH_SHORT).show();
      return;
    }

    Toast.makeText(context, "正在准备分享…", Toast.LENGTH_SHORT).show();

    final Context appContext = context.getApplicationContext();
    final Handler mainHandler = new Handler(Looper.getMainLooper());

    new Thread(new Runnable() {
      @Override public void run() {
        try {
          final File file = prepareShareFile(appContext, imageUrl);
          if (file == null) {
            mainHandler.post(new Runnable() {
              @Override public void run() {
                Toast.makeText(appContext, "图片准备失败，请稍后重试", Toast.LENGTH_SHORT).show();
              }
            });
            return;
          }
          final Intent intent = buildShareIntent(appContext, file);
          mainHandler.post(new Runnable() {
            @Override public void run() {
              try {
                context.startActivity(Intent.createChooser(intent, "分享图片"));
              } catch (Exception e) {
                Log.e(TAG, "startActivity: " + Log.getStackTraceString(e));
                Toast.makeText(appContext, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
              }
            }
          });
        } catch (Exception e) {
          Log.e(TAG, "shareImage: " + Log.getStackTraceString(e));
          mainHandler.post(new Runnable() {
            @Override public void run() {
              Toast.makeText(appContext, "分享失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
          });
        }
      }
    }).start();
  }

  private static File prepareShareFile(Context context, String imageUrl) throws IOException {
    File dir = new File(context.getCacheDir(), SHARE_DIR);
    if (!dir.exists() && !dir.mkdirs()) {
      throw new IOException("无法创建分享目录");
    }
    cleanExpiredShareFiles(dir);

    String baseName = "share_" + Integer.toHexString(imageUrl.hashCode());
    File temp = new File(dir, baseName + ".tmp");
    if (temp.exists() && !temp.delete()) {
      Log.w(TAG, "prepareShareFile: 旧临时文件删除失败");
    }

    File cached = FrescoUtils.getCachedImageOnDisk(Uri.parse(imageUrl));
    if (cached != null && cached.exists() && cached.length() > 0) {
      copyFile(cached, temp);
    } else {
      downloadFile(imageUrl, temp);
    }

    if (!temp.exists() || temp.length() == 0) {
      return null;
    }

    // 缓存里的文件名是 hash，靠文件头判断真实格式，GIF 动画也能原样分享出去
    String extension = detectExtension(temp);
    File target = new File(dir, baseName + extension);
    if (target.exists() && !target.delete()) {
      Log.w(TAG, "prepareShareFile: 旧分享文件删除失败");
    }
    if (!temp.renameTo(target)) {
      // 重命名失败时退化为复制
      copyFile(temp, target);
      //noinspection ResultOfMethodCallIgnored
      temp.delete();
    }
    return target;
  }

  private static void copyFile(File source, File target) throws IOException {
    try (InputStream in = new BufferedInputStream(new FileInputStream(source));
        FileOutputStream out = new FileOutputStream(target)) {
      byte[] buffer = new byte[8 * 1024];
      int length;
      while ((length = in.read(buffer)) > 0) {
        out.write(buffer, 0, length);
      }
      out.flush();
    }
  }

  private static void downloadFile(String url, File target) throws IOException {
    Request request = new Request.Builder().url(url).build();
    Response response = SMTHHelper.getInstance().mHttpClient.newCall(request).execute();
    try {
      if (!response.isSuccessful() || response.body() == null) {
        throw new IOException("HTTP " + response.code());
      }
      try (InputStream in = new BufferedInputStream(response.body().byteStream());
          FileOutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[8 * 1024];
        int length;
        while ((length = in.read(buffer)) > 0) {
          out.write(buffer, 0, length);
        }
        out.flush();
      }
    } finally {
      response.close();
    }
  }

  private static String detectExtension(File file) {
    try (FileInputStream in = new FileInputStream(file)) {
      byte[] head = new byte[4];
      int read = in.read(head);
      if (read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8) {
        return ".jpg";
      }
      if (read >= 3 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F') {
        return ".gif";
      }
      if (read >= 4 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G') {
        return ".png";
      }
    } catch (IOException e) {
      Log.e(TAG, "detectExtension: " + Log.getStackTraceString(e));
    }
    return ".jpg";
  }

  private static Intent buildShareIntent(Context context, File file) {
    Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".attachments", file);
    String mimeType;
    if (file.getName().endsWith(".gif")) {
      mimeType = "image/gif";
    } else if (file.getName().endsWith(".png")) {
      mimeType = "image/png";
    } else {
      mimeType = "image/jpeg";
    }

    Intent intent = new Intent(Intent.ACTION_SEND);
    intent.setType(mimeType);
    intent.putExtra(Intent.EXTRA_STREAM, uri);
    intent.putExtra(Intent.EXTRA_SUBJECT, "来自水木社区的图片");
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    // 有些应用只认 ClipData 里的 URI
    intent.setClipData(ClipData.newRawUri("image", uri));
    return intent;
  }

  private static void cleanExpiredShareFiles(File dir) {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    long now = System.currentTimeMillis();
    for (File file : files) {
      String name = file.getName();
      if (!name.startsWith("share_")) {
        continue;
      }
      if (now - file.lastModified() > SHARE_FILE_MAX_AGE) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }
  }
}
