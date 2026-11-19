package com.zfdang.zsmth_android.helpers;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;

import com.zfdang.zsmth_android.R;

/**
 * 复制帖子内容对话框：展示标题+完整正文，文字可选中，
 * 由用户长按调用系统自带的文字选择器自由复制。
 */
public class CopyTextDialog extends Dialog {

  private final String text;

  public CopyTextDialog(Context context, String text) {
    super(context, R.style.AppTheme_PopupOverlay);
    this.text = text == null ? "" : text;
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.dialog_copy_text);
    if (getWindow() != null) {
      getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT);
      getWindow().setGravity(Gravity.BOTTOM);
    }
    ((TextView) findViewById(R.id.copy_text_content)).setText(text);
    findViewById(R.id.copy_text_close).setOnClickListener(v -> dismiss());
  }
}
