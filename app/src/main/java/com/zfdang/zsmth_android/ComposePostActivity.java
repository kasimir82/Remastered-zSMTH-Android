package com.zfdang.zsmth_android;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.net.Uri;
import android.text.TextUtils;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.KeyboardLess;
import com.zfdang.zsmth_android.helpers.LoginFailureLogger;
import com.zfdang.zsmth_android.helpers.PublishMethodHelper;
import com.zfdang.zsmth_android.helpers.StringUtils;
import com.zfdang.zsmth_android.models.ComposePostContext;
import com.zfdang.zsmth_android.newsmth.AjaxResponse;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;
import java.util.ArrayList;
import okhttp3.MediaType;
import okhttp3.RequestBody;

public class ComposePostActivity extends SMTHBaseActivity {

  public static final int COMPOSE_ACTIVITY_REQUEST_CODE = 107;
  private static final String TAG = "ComposePostActivity";

  /** 敏感词流程日志：仅 debug 版输出到 logcat，release 版记录完整正文有隐私风险 */
  private static void flowLog(String msg) {
    if (BuildConfig.DEBUG) {
      android.util.Log.d("SMTHFlow", msg);
    }
  }
  private final String UPLOAD_TEMPLATE = " [upload=%d][/upload] ";

  private Button mButton;
  private LinearLayout mUserRow;
  private EditText mUserID;
  private EditText mTitle;
  private LinearLayout mAttachRow;
  private EditText mAttachments;
  private Switch mCompress;
  private EditText mContent;
  private ArrayList<String> mPhotos;
  private TextView mContentCount;

  private ComposePostContext mPostContext;

  // used to show progress while publishing
  private static int totalSteps = 1;
  private static int currentStep = 1;

  private int postPublishResult = 0;
  private String postPublishMessage = null;
  private AjaxResponse lastResponse = null;

  // 敏感词审核拦截：站方返回 code=0405 / msg 含「不当内容」时，保留原文弹窗让用户选转换方式
  private boolean sensitiveBlocked;
  // 当前这次发帖是否是「转换后重试」，避免转换后的内容再次被拦截后无限弹窗
  private boolean isRetriedPublish;
  // 触发敏感词时的原始正文（转换失败/取消时可恢复）
  private String blockedOriginalContent;

  private File cameraAttachment;
  private boolean importingImages;
  private boolean reportSending;
  private final ActivityResultLauncher<PickVisualMediaRequest> imagePicker =
      registerForActivityResult(new ActivityResultContracts.PickMultipleVisualMedia(5), this::importImages);
  private final ActivityResultLauncher<Uri> cameraPicker =
      registerForActivityResult(new ActivityResultContracts.TakePicture(), saved -> {
        if (saved && cameraAttachment != null) addAttachment(cameraAttachment.getAbsolutePath());
        else if (cameraAttachment != null) cameraAttachment.delete();
        cameraAttachment = null;
      });

  public void startImageSelector() {
    new AlertDialog.Builder(this).setTitle("图片附件（最多5张）")
        .setItems(new String[]{"从相册添加", "拍照添加", "清空附件"}, (dialog, choice) -> {
          if (choice == 2) {
            String content = mContent.getText().toString();
            for (int i = 1; i <= mPhotos.size(); i++) {
              content = content.replace(String.format(Locale.ROOT, UPLOAD_TEMPLATE, i), "");
            }
            mContent.setText(content);
            mContent.setSelection(content.length());
            mPhotos.clear();
            mAttachments.setText("共有0个附件");
          } else if (mPhotos.size() >= 5) {
            Toast.makeText(this, "最多添加5张图片，请先清空附件再选择", Toast.LENGTH_LONG).show();
          } else if (choice == 0) {
            imagePicker.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
          } else {
            try {
              File directory = new File(getCacheDir(), "attachments");
              if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create attachment directory");
              cameraAttachment = File.createTempFile("camera-", ".jpg", directory);
              cameraPicker.launch(FileProvider.getUriForFile(this, getPackageName() + ".attachments", cameraAttachment));
            } catch (IOException | RuntimeException error) {
              if (cameraAttachment != null) cameraAttachment.delete();
              cameraAttachment = null;
              Toast.makeText(this, "无法打开相机，请从相册选择图片", Toast.LENGTH_LONG).show();
            }
          }
        }).setNegativeButton("取消", null).show();
  }

  private void importImages(List<Uri> images) {
    if (images.isEmpty()) return;
    final int remaining = 5 - mPhotos.size();
    importingImages = true;
    mButton.setEnabled(false);
    new Thread(() -> {
      ArrayList<String> imported = new ArrayList<>();
      boolean failed = false;
      File directory = new File(getCacheDir(), "attachments");
      for (Uri image : images.subList(0, Math.min(remaining, images.size()))) {
        File attachment = null;
        try {
          if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create attachment directory");
          String extension = "image/gif".equals(getContentResolver().getType(image)) ? ".gif" : ".jpg";
          attachment = File.createTempFile("image-", extension, directory);
          try (InputStream input = getContentResolver().openInputStream(image);
               FileOutputStream output = new FileOutputStream(attachment)) {
            if (input == null) throw new IOException("Cannot open selected image");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
          }
          if (attachment.length() == 0) throw new IOException("Empty image");
          imported.add(attachment.getAbsolutePath());
        } catch (IOException | RuntimeException error) {
          if (attachment != null) attachment.delete();
          failed = true;
        }
      }
      final boolean showError = failed;
      runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) return;
        for (String filename : imported) addAttachment(filename);
        importingImages = false;
        mButton.setEnabled(true);
        if (showError) Toast.makeText(this, "部分图片读取失败，请重新选择", Toast.LENGTH_LONG).show();
        if (images.size() > remaining) Toast.makeText(this, "最多5个附件，已保留前" + remaining + "张图片", Toast.LENGTH_LONG).show();
      });
    }, "attachment-import").start();
  }

  private void addAttachment(String filename) {
    if (mPhotos.size() >= 5) return;
    mPhotos.add(filename);
    mAttachments.setText(String.format("共有%d个附件", mPhotos.size()));
    mContent.getText().insert(Math.max(0, mContent.getSelectionStart()), String.format(Locale.ROOT, UPLOAD_TEMPLATE, mPhotos.size()));
  }

  @Override protected void onSaveInstanceState(Bundle outState) {
    outState.putStringArrayList("attachments", mPhotos);
    if (cameraAttachment != null) outState.putString("cameraAttachment", cameraAttachment.getAbsolutePath());
    super.onSaveInstanceState(outState);
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
      @Override public void handleOnBackPressed() { handleBackAction(); }
    });
    setContentView(R.layout.activity_compose_post);
        setupBottomNavigation();

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setDisplayHomeAsUpEnabled(true);
    }

    mPhotos = savedInstanceState == null ? new ArrayList<>() : savedInstanceState.getStringArrayList("attachments");
    if (mPhotos == null) mPhotos = new ArrayList<>();
    if (savedInstanceState != null && savedInstanceState.containsKey("cameraAttachment")) {
      cameraAttachment = new File(savedInstanceState.getString("cameraAttachment"));
    }

    mUserRow = findViewById(R.id.compose_post_userid_row);
    mUserID = findViewById(R.id.compose_post_userid);
    mTitle = findViewById(R.id.compose_post_title);
    mAttachRow = findViewById(R.id.compose_post_attach_row);
    mAttachments = findViewById(R.id.compose_post_attach);
    mAttachments.setText(String.format("共有%d个附件", mPhotos.size()));
    mCompress = findViewById(R.id.compose_post_attach_switch);
    mContent = findViewById(R.id.compose_post_content);
    mContentCount = findViewById(R.id.compose_post_content_label);

    mButton = findViewById(R.id.compose_post_attach_button);
    mButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        KeyboardLess.$hide(v.getContext(), mContent);
        startImageSelector();
      }
    });

    mContent.addTextChangedListener(new TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
      }

      @Override public void afterTextChanged(Editable s) {
        mContentCount.setText(String.format("文章字数:%d", s.length()));
      }
    });

    // init controls from Intent
    initFromIntent();

    restorePostContentFromCache();

    // open keypads
    KeyboardLess.$show(this, mContent);
  }

  // three methods to manage content cache
  private void restorePostContentFromCache() {
    if (isChildSafetyReport()) return;
    final String content = Settings.getInstance().getPostCache();
    if (content != null && content.length() > 0 && content.length() != mContent.getText().toString().length()) {
      AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(ComposePostActivity.this);
      alertDialogBuilder.setTitle("恢复缓存的内容?")
          .setMessage(StringUtils.getEllipsizedMidString(content, 240))
          .setCancelable(false)
          .setPositiveButton("恢复内容", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              // if this button is clicked, close current activity
              mContent.setText(content);
            }
          })
          .setNegativeButton("放弃内容", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
              // if this button is clicked, just close the dialog box and do nothing
              dialog.cancel();
            }
          })
          .create()
          .show();
    }
  }

  private void savePostContentToCache() {
    if (isChildSafetyReport()) return;
    String content = mContent.getText().toString();
    Settings.getInstance().setPostCache(content);
  }

  private void clearPostContentCache() {
    if (!isChildSafetyReport()) Settings.getInstance().setPostCache("");
  }

  @Override protected void onPause() {
    // this method will also be called after activity.finish(), so we will have to set mContent.SetText("") before .finish()
    super.onPause();
    savePostContentToCache();
  }

  public void initFromIntent() {
    // get ComposePostContext from caller
    Intent intent = getIntent();
    mPostContext = intent.getParcelableExtra(SMTHApplication.COMPOSE_POST_CONTEXT);
    assert mPostContext != null;
    //        Log.d(TAG, "initFromIntent: " + mPostContext.toString());

    // there are totally 5 different cases: ( 3 & 4 can be handled in the same way)
    // 1. new post:   invalid post && !isThroughMail
    // 2. reply post: valid post && !isThroughMail
    // 3. reply post through mail: valid post && isThroughMail
    // 4. reply mail: valid post && isThroughMail
    // 5. write new mail: invalid post && isThroughMail

    // set title, hide userRow / attachRow if necessary
    if (mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_POST) {
      mUserRow.setVisibility(View.GONE);
      setTitle(String.format("发表文章@%s", mPostContext.getBoardEngName()));
      // set focus to title
      mTitle.requestFocus();
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_REPLY_POST) {
      mUserRow.setVisibility(View.GONE);
      setTitle(String.format("回复文章@%s", mPostContext.getBoardEngName()));
      // set focus to content
      mContent.requestFocus();
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_EDIT_POST) {
      mUserRow.setVisibility(View.GONE);
      mAttachRow.setVisibility(View.GONE);
      setTitle(String.format("修改文章@%s", mPostContext.getBoardEngName()));
      // set focus to content
      mContent.requestFocus();
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_MAIL) {
      mAttachRow.setVisibility(View.GONE);
      setTitle("写新信件");
      // set focus to recipient
      mUserID.requestFocus();
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_MAIL_TO_USER) {
      mAttachRow.setVisibility(View.GONE);
      setTitle("写新信件");
      mUserID.setText(mPostContext.getPostAuthor());
      mUserID.setEnabled(false);
      // set focus to content
      mContent.requestFocus();
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_REPLY_MAIL) {
      mAttachRow.setVisibility(View.GONE);
      setTitle("回复信件");
      mUserID.setText(mPostContext.getPostAuthor());
      mUserID.setEnabled(false);
      // set focus to content
      mContent.requestFocus();
      getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    if (isChildSafetyReport()) {
      mAttachRow.setVisibility(View.GONE);
      setTitle(R.string.child_safety_report_action);
      mUserID.setText("sysop");
      mUserID.setEnabled(false);
      mTitle.setText(R.string.child_safety_report_subject);
      mTitle.setEnabled(false);
      mContent.setText(mPostContext.getPostContent());
      mContent.setHint(R.string.child_safety_report_hint);
      mContent.requestFocus();
      return;
    }

    // set post title & content
    if (mPostContext.isValidPost()) {
      // have valid post information
      if (mPostContext.getComposingMode() == ComposePostContext.MODE_EDIT_POST) {
        mTitle.setText(mPostContext.getPostTitle());
        mContent.setText(mPostContext.getPostContent());
      } else {
        String title = mPostContext.getPostTitle();
        if (title != null && !title.startsWith("Re:")) {
          title = String.format("Re: %s", title);
        }
        mTitle.setText(title);

        String[] lines = mPostContext.getPostContent().split("\n");
        StringBuilder wordList = new StringBuilder();
        wordList.append("\n\n");
        wordList.append(String.format("【 在 %s 的大作中提到: 】", mPostContext.getPostAuthor())).append("\n");
        int quoted = 0;
        for (int i = 0; i < lines.length && quoted < 5; i++) {
          String line = lines[i].trim();
          if (line.startsWith("--")) {
            // this might be the start of signature, ignore following lines in quote
            break;
          }
          // 跳过来源行/修改行与空行，只引用别人发的正文
          if (line.isEmpty() || line.startsWith("※ 来源:") || line.startsWith("※ 来源：")
              || line.startsWith("※ 修改:") || line.startsWith("※ 修改：")) {
            continue;
          }
          wordList.append(String.format(": %s", line)).append("\n");
          quoted++;
        }
        mContent.setText(new String(wordList));
      }

      // if the content is not empty, set cursor to the beginning
      mContent.setSelection(0);
    }

  }

  @Override public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.compose_post_menu, menu);
    return super.onCreateOptionsMenu(menu);
  }

  private void handleBackAction() {
    onBackAction();
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    int code = item.getItemId();
    if (code == android.R.id.home) {
      onBackAction();
    } else if (code == R.id.compose_post_publish) {
      publishPost();
    }
    return super.onOptionsItemSelected(item);
  }

  public static class BytesContainer {
    public String filename;
    public byte[] bytes;

    public BytesContainer(String filename, byte[] bytes) {
      this.bytes = bytes;
      this.filename = filename;
    }
  }

  private boolean isChildSafetyReport() {
    return mPostContext != null
        && mPostContext.getComposingMode() == ComposePostContext.MODE_CHILD_SAFETY_REPORT;
  }

  public void publishPost() {
    // 回帖场景：点发送先弹方式选择窗(正常/图片/竖排)，用户自己决定，无需长按菜单。
    // 已选过图片/竖排并完成转换的(isRetriedPublish=true)，再次点发送直接真发，避免重复弹窗。
    if (!isRetriedPublish && mPostContext != null
        && mPostContext.getComposingMode() == ComposePostContext.MODE_REPLY_POST) {
      String content = mContent.getText().toString();
      com.zfdang.zsmth_android.helpers.PublishMethodHelper.SplitResult split =
          com.zfdang.zsmth_android.helpers.PublishMethodHelper.split(content);
      if (split.userText.trim().isEmpty()) {
        Toast.makeText(this, "请先输入回复内容", Toast.LENGTH_SHORT).show();
        return;
      }
      blockedOriginalContent = content;
      showPublishMethodDialog();
      return;
    }
    // 用户主动点发送：这是一次全新发布，重置「转换后重试」标记
    isRetriedPublish = false;
    doPublishPost();
  }

  /** 敏感词拦截后的发布方式选择弹窗（按钮式，验证码图片 / 竖排） */
  private void showPublishMethodDialog() {
    View v = getLayoutInflater().inflate(R.layout.dialog_publish_method, null);
    final AlertDialog dialog = new AlertDialog.Builder(this)
        .setView(v)
        .setCancelable(true)
        .create();

    v.findViewById(R.id.pm_btn_normal).setOnClickListener(x -> {
      flowLog("USER CHOICE = NORMAL");
      dialog.dismiss();
      blockedOriginalContent = null; // 正常发送用编辑框当前内容
      isRetriedPublish = false;
      doPublishPost();
    });
    v.findViewById(R.id.pm_btn_jpg).setOnClickListener(x -> {
      flowLog("USER CHOICE = CAPTCHA IMAGE");
      dialog.dismiss();
      publishAsJpg();
    });
    v.findViewById(R.id.pm_btn_vertical).setOnClickListener(x -> {
      flowLog("USER CHOICE = VERTICAL");
      dialog.dismiss();
      publishAsVertical();
    });
    v.findViewById(R.id.pm_btn_cancel).setOnClickListener(x -> {
      flowLog("USER CHOICE = CANCEL");
      dialog.dismiss();
    });
    flowLog("===== METHOD DIALOG SHOWN =====");
    dialog.show();
  }

  /** 方式①：用户输入转 captcha 风格图片，作为附件；正文只保留引用块(+简短说明) */
  private void publishAsJpg() {
    // 敏感词拦截后用保存的原文转换，避免依赖已被改动的编辑框
    String content = (blockedOriginalContent != null && !blockedOriginalContent.isEmpty())
        ? blockedOriginalContent : mContent.getText().toString();
    isRetriedPublish = true;
    PublishMethodHelper.SplitResult split = PublishMethodHelper.split(content);
    if (split.userText.trim().isEmpty()) {
      Toast.makeText(this, "没有可转换的正文内容", Toast.LENGTH_SHORT).show();
      return;
    }
    // 文字渲染成 captcha 风格图片（每行7字、扭曲混淆、JPEG Q10）
    PublishMethodHelper.ImageResult imgs =
        PublishMethodHelper.textToCaptchaImages(this, split.userText);
    if (imgs.files.isEmpty()) {
      Toast.makeText(this, "生成图片失败", Toast.LENGTH_SHORT).show();
      return;
    }
    if (mPhotos == null) mPhotos = new ArrayList<>();
    // 先移除之前转换生成的图片(text-image-* / captcha-*)，避免重发时累积/同名冲突 1205
    java.util.Iterator<String> it = mPhotos.iterator();
    while (it.hasNext()) {
      String p = it.next();
      if (p != null) {
        String name = new File(p).getName();
        if (name.startsWith("text-image-") || name.startsWith("captcha-")) it.remove();
      }
    }
    // 最多5个附件，留位置给新图
    int canAdd = 5 - mPhotos.size();
    if (canAdd <= 0) {
      Toast.makeText(this, "附件已达上限(5个)", Toast.LENGTH_SHORT).show();
      return;
    }
    int added = 0;
    for (File f : imgs.files) {
      if (added >= canAdd) break;
      mPhotos.add(f.getAbsolutePath());
      added++;
    }
    mAttachments.setText(String.format("共有%d个附件", mPhotos.size()));

    // 正文：用户输入替换为简短说明，引用块只保留「大作中提到」行 + …
    String newContent = "(正文见图片附件)\n" + PublishMethodHelper.abbreviateQuote(split.quotePart);
    mContent.setText(newContent);
    flowLog("===== CAPTCHA RESULT =====");
    flowLog("images=" + added + " originalUserText=" + split.userText);
    flowLog("newContent=" + newContent);
    Toast.makeText(this, "已生成 " + added + " 张图片附件，请检查后点发送", Toast.LENGTH_LONG).show();
    // 不自动发送：避免触发站方「两次发文间隔过密」限制，由用户确认后自己点发送
  }

  /** 方式②：用户输入转竖排诗歌风格文本，保留引用块 */
  private void publishAsVertical() {
    String content = (blockedOriginalContent != null && !blockedOriginalContent.isEmpty())
        ? blockedOriginalContent : mContent.getText().toString();
    isRetriedPublish = true;
    PublishMethodHelper.SplitResult split = PublishMethodHelper.split(content);
    if (split.userText.trim().isEmpty()) {
      Toast.makeText(this, "没有可转换的正文内容", Toast.LENGTH_SHORT).show();
      return;
    }
    // 转成竖排文本（已去除无用的下划线填充）
    String vertical = PublishMethodHelper.toVerticalWithFillers(split.userText);
    // 引用块只保留「大作中提到」行 + …，去掉可能含敏感词的引用正文
    String quote = split.hasQuote ? "\n" + PublishMethodHelper.abbreviateQuote(split.quotePart) : "";
    String newContent = vertical + quote;
    mContent.setText(newContent);
    flowLog("===== VERTICAL RESULT =====");
    flowLog(newContent);
    Toast.makeText(this, "已转为竖排风格，请检查后点发送", Toast.LENGTH_LONG).show();
    // 不自动发送：由用户确认后自己点发送
  }

  private void doPublishPost() {
    if (isChildSafetyReport() && reportSending) return;
    if (isChildSafetyReport() && mContent.getText().toString().trim().isEmpty()) {
      mContent.setError(getString(R.string.child_safety_report_hint));
      mContent.requestFocus();
      return;
    }
    if (isChildSafetyReport()
        && (!com.zfdang.zsmth_android.helpers.LoginStateManager.isLoggedIn())) {
      new AlertDialog.Builder(this)
          .setMessage(R.string.child_safety_login_required)
          .setPositiveButton(R.string.action_login,
              (dialog, which) -> startActivity(new Intent(this, LoginActivity.class)))
          .setNegativeButton(android.R.string.cancel, null).show();
      return;
    }
    if (importingImages) {
      Toast.makeText(this, "正在读取图片，请稍候", Toast.LENGTH_SHORT).show();
      return;
    }

    reportSending = isChildSafetyReport();
    postPublishResult = AjaxResponse.AJAX_RESULT_OK;
    postPublishMessage = "";
    lastResponse = null;

    final String progressHint = isChildSafetyReport() ? "提交举报中(%d/%d)..." : "发表文章中(%d/%d)...";
    ComposePostActivity.totalSteps = 1;
    ComposePostActivity.currentStep = 1;
    if (mPhotos != null) {
      ComposePostActivity.totalSteps += mPhotos.size();
    }

    showProgress(String.format(progressHint, ComposePostActivity.currentStep, ComposePostActivity.totalSteps));

    final SMTHHelper helper = SMTHHelper.getInstance();

    // update attachments
    final boolean bCompress = this.mCompress.isChecked();
    Observable<AjaxResponse> resp1 = Observable.fromIterable(mPhotos).map(new Function<String, BytesContainer>() {
      @Override public BytesContainer apply(@NonNull String filename) throws Exception {
        // captcha 图已是 JPEG Q10，直接用原始字节上传，避免被 saveBitmapToFile 以 Q85 重编码放大
        if (new File(filename).getName().startsWith("captcha-")) {
          java.io.File cf = new java.io.File(filename);
          byte[] raw = new byte[(int) cf.length()];
          try (java.io.FileInputStream fis = new java.io.FileInputStream(cf)) {
            int off = 0;
            int n;
            while (off < raw.length && (n = fis.read(raw, off, raw.length - off)) > 0) off += n;
          }
          return new BytesContainer(filename, raw);
        }
        byte[] bytes = SMTHHelper.getBitmapBytesWithResize(filename, bCompress);
        return new BytesContainer(filename, bytes);
      }
    }).map(new Function<BytesContainer, AjaxResponse>() {
      @Override public AjaxResponse apply(@NonNull BytesContainer container) throws Exception {
        // MIME 与实际编码一致：PNG 用 image/png，其它仍 image/jpeg
        String mime = container.filename.toLowerCase(java.util.Locale.US).endsWith(".png")
            ? "image/png" : "image/jpeg";
        RequestBody requestBody = RequestBody.create(MediaType.parse(mime), container.bytes);
        AjaxResponse resp =
            helper.wService.uploadAttachment(mPostContext.getBoardEngName(), StringUtils.getLastStringSegment(container.filename),
                requestBody).blockingFirst();
        if (resp != null) {
          return resp;
        } else {
          Log.d(TAG, "call: " + "failed to upload attachment " + container.filename);
          return null;
        }
      }
    });

    // publish post
    String postContent = mContent.getText().toString();
    if (!isChildSafetyReport() && Settings.getInstance().bUseSignature()) {
      postContent += "\n" + String.format("#发自%s", Settings.getInstance().getSignature());
    }
    // [流程日志] 发送前的完整请求内容
    flowLog("===== SEND REQUEST =====");
    flowLog("mode=" + mPostContext.getComposingMode()
        + " board=" + mPostContext.getBoardEngName()
        + " title=" + mTitle.getText().toString()
        + " attachments=" + (mPhotos == null ? 0 : mPhotos.size())
        + " isRetried=" + isRetriedPublish);
    flowLog("POST CONTENT >>>\n" + postContent + "\n<<< END CONTENT");
    Observable<AjaxResponse> resp2 = null;
    if (isChildSafetyReport() || mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_MAIL
        || mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_MAIL_TO_USER
        || mPostContext.getComposingMode() == ComposePostContext.MODE_REPLY_MAIL) {
      String userid = isChildSafetyReport() ? "sysop" : mUserID.getText().toString().trim();
      resp2 = SMTHHelper.sendMail(userid, mTitle.getText().toString(), postContent);
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_NEW_POST
        || mPostContext.getComposingMode() == ComposePostContext.MODE_REPLY_POST) {
      resp2 =
          SMTHHelper.publishPost(mPostContext.getBoardEngName(), mTitle.getText().toString(), postContent, "0", mPostContext.getPostId());
    } else if (mPostContext.getComposingMode() == ComposePostContext.MODE_EDIT_POST) {
      postContent = mContent.getText().toString();
      if (Settings.getInstance().bUseSignature()) {
        postContent += "\n" + String.format("#修改自%s", Settings.getInstance().getSignature());
      }
      resp2 = SMTHHelper.editPost(mPostContext.getBoardEngName(), mPostContext.getPostId(), mTitle.getText().toString(), postContent);
    }

    // process all these tasks one by one
    Observable.concat(resp1, resp2)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(new Observer<AjaxResponse>() {
          @Override public void onSubscribe(@NonNull Disposable disposable) {

          }

          @Override public void onNext(@NonNull AjaxResponse ajaxResponse) {
            // 详细记录站方返回的原始数据，供分析敏感词/审核拦截（写入本地文件）
            String respDetail = "st=" + ajaxResponse.getAjax_st()
                + " code=" + ajaxResponse.getAjax_code()
                + " msg=" + ajaxResponse.getAjax_msg()
                + " content=" + ajaxResponse.getContent()
                + " group_id=" + ajaxResponse.getGroup_id();
            Log.d(TAG, "publish response: " + respDetail + " | " + ajaxResponse.toString());
            com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("publish_response", respDetail);

            // 检测站方的「含不当内容需审核」拦截：code=0405 或 msg 含关键词。
            // 站方此时 ajax_st 仍是 1(看似成功)，但实际未发出，必须拦截并让用户换方式重发。
            String code = ajaxResponse.getAjax_code();
            String msg = ajaxResponse.getAjax_msg();
            boolean isSensitive = "0405".equals(code)
                || (msg != null && (msg.contains("不当内容") || msg.contains("需经审核")));
            // [流程日志] 服务器响应 + 拦截判断
            flowLog("===== SERVER RESPONSE =====");
            flowLog(respDetail);
            flowLog("isSensitive=" + isSensitive + " isRetried=" + isRetriedPublish);
            if (isSensitive && !isRetriedPublish) {
              sensitiveBlocked = true;
              Log.w(TAG, "sensitive content blocked by server: code=" + code + " msg=" + msg);
              LoginFailureLogger.log("sensitive_blocked", "code=" + code + " msg=" + msg);
              return; // 不走 st 判断，也不更新 step，交给 onComplete 弹窗
            }

            // 站方发帖频率限制(0403)：给明确提示，避免用户困惑
            if ("0403".equals(code) && (msg != null && msg.contains("间隔"))) {
              Toast.makeText(ComposePostActivity.this,
                  "发帖太频繁，站方限制了频率。请稍等 5-10 秒后再点发送。",
                  Toast.LENGTH_LONG).show();
            }

            if (ajaxResponse.getAjax_st() != AjaxResponse.AJAX_RESULT_OK) {
              postPublishResult = AjaxResponse.AJAX_RESULT_FAILED;
              postPublishMessage += ajaxResponse.getAjax_msg() + "\n";
            }
            lastResponse = ajaxResponse;
            ComposePostActivity.currentStep++;
            showProgress(String.format(progressHint, ComposePostActivity.currentStep, ComposePostActivity.totalSteps));
          }

          @Override public void onError(@NonNull Throwable e) {
            reportSending = false;
            dismissProgress();
            Toast.makeText(SMTHApplication.getAppContext(), "发生错误:\n" + e.toString(), Toast.LENGTH_LONG).show();

          }

          @Override public void onComplete() {
            reportSending = false;
            dismissProgress();

            // 敏感词拦截：保留原文，弹出方式选择对话框让用户换方式重发
            if (sensitiveBlocked) {
              sensitiveBlocked = false;
              blockedOriginalContent = mContent.getText().toString();
              flowLog("===== SENSITIVE BLOCKED -> DIALOG =====");
              flowLog("blockedOriginalContent=\n" + blockedOriginalContent);
              Toast.makeText(ComposePostActivity.this,
                  "站方提示内容需审核，文章未发出。请选择其它发布方式。", Toast.LENGTH_LONG).show();
              showPublishMethodDialog();
              return;
            }

            if (isChildSafetyReport() && lastResponse == null) {
              postPublishResult = AjaxResponse.AJAX_RESULT_FAILED;
              postPublishMessage = "未收到服务器确认，请检查发件箱后再重试。";
            }
            if (isChildSafetyReport() && lastResponse != null
                && lastResponse.getAjax_msg() != null
                && lastResponse.getAjax_msg().contains("本文可能含有不当内容")) {
              postPublishResult = AjaxResponse.AJAX_RESULT_FAILED;
              postPublishMessage = lastResponse.getAjax_msg();
            }

            String message;
            if (postPublishResult != AjaxResponse.AJAX_RESULT_OK) {
              message = "操作失败! \n错误信息:\n" + postPublishMessage;
              Toast.makeText(ComposePostActivity.this, message, Toast.LENGTH_LONG).show();
            } else {
              if (lastResponse != null) {
                // if we have valid last response, use the message.
                message = lastResponse.getAjax_msg();
              } else {
                // otherwise, compose the message by ourself
                message = "成功!";
              }
              if (isChildSafetyReport()) message = getString(R.string.child_safety_report_success);
              Toast.makeText(SMTHApplication.getAppContext(), message, Toast.LENGTH_LONG).show();

              KeyboardLess.$hide(ComposePostActivity.this, mContent);

              if (message != null && !message.contains("本文可能含有不当内容")) {
                mContent.setText("");
                clearPostContentCache();
                ComposePostActivity.this.finish();
              }
            }

          }
        });
  }

  public void onBackAction() {
    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(ComposePostActivity.this);
    alertDialogBuilder.setTitle("退出确认")
        .setMessage("结束编辑，或者停留在当前界面继续编辑？")
        .setCancelable(false)
        .setPositiveButton("结束编辑", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            // if this button is clicked, close current activity
            KeyboardLess.$hide(ComposePostActivity.this, mContent);
            mContent.setText("");
            clearPostContentCache();
            ComposePostActivity.this.finish();
          }
        })
        .setNegativeButton("继续编辑", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int id) {
            // if this button is clicked, just close the dialog box and do nothing
            dialog.cancel();
          }
        })
        .create()
        .show();
  }
}
