package com.zfdang.zsmth_android.helpers;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.zfdang.zsmth_android.R;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「大爆炸」式文字选择框：把正文切成词条铺开，点选任意词条后复制到剪贴板。
 * 词条的选中态用一个 Set 记录，避免依赖 TextView 的 tag 被复用打乱。
 */
public class TextBombDialog extends Dialog {

  private final String content;
  private final List<String> pieces = new ArrayList<>();
  private final Set<Integer> selected = new LinkedHashSet<>();
  private FlowLayout flowLayout;
  private Button copyButton;

  public TextBombDialog(Context context, String content) {
    super(context, R.style.AppTheme_PopupOverlay);
    this.content = content == null ? "" : content;
  }

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.dialog_text_bomb);
    if (getWindow() != null) {
      getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT);
      getWindow().setGravity(Gravity.BOTTOM);
    }

    // 标题放在自定义布局里，不用系统 setTitle（否则与自定义内容重叠/被裁）
    ((TextView) findViewById(R.id.text_bomb_hint)).setText(R.string.text_bomb_hint);

    flowLayout = (FlowLayout) findViewById(R.id.text_bomb_flow);
    splitContent();

    for (int i = 0; i < pieces.size(); i++) {
      final int index = i;
      TextView chip = new TextView(getContext());
      chip.setText(pieces.get(i));
      chip.setTextSize(16);
      chip.setPadding(10, 8, 10, 8);
      chip.setBackgroundResource(R.drawable.text_bomb_chip_unselected);
      chip.setTextColor(0xFF222222);
      chip.setOnClickListener(new View.OnClickListener() {
        @Override public void onClick(View v) {
          if (selected.contains(index)) {
            selected.remove(index);
            v.setBackgroundResource(R.drawable.text_bomb_chip_unselected);
          } else {
            selected.add(index);
            v.setBackgroundResource(R.drawable.text_bomb_chip_selected);
          }
        }
      });
      flowLayout.addView(chip);
    }

    ((Button) findViewById(R.id.text_bomb_select_all)).setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        selected.clear();
        for (int i = 0; i < pieces.size(); i++) selected.add(i);
        refreshChips();
      }
    });
    ((Button) findViewById(R.id.text_bomb_clear)).setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        selected.clear();
        refreshChips();
      }
    });
    copyButton = (Button) findViewById(R.id.text_bomb_copy);
    copyButton.setText(R.string.text_bomb_copy_selected);
    copyButton.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
        StringBuilder sb = new StringBuilder();
        for (int i : selected) {
          sb.append(pieces.get(i));
        }
        String text = sb.toString();
        if (text.length() == 0) {
          Toast.makeText(getContext(), "请先选择文字", Toast.LENGTH_SHORT).show();
          return;
        }
        copyToClipboard(text);
        Toast.makeText(getContext(), "已复制 " + text.length() + " 个字到剪贴板", Toast.LENGTH_SHORT).show();
        dismiss();
      }
    });
  }

  private void refreshChips() {
    for (int i = 0; i < flowLayout.getChildCount(); i++) {
      View child = flowLayout.getChildAt(i);
      child.setBackgroundResource(selected.contains(i)
          ? R.drawable.text_bomb_chip_selected : R.drawable.text_bomb_chip_unselected);
    }
  }

  /** 按空白/标点/换行切成词条，保留标点本身，方便逐句选择 */
  private void splitContent() {
    pieces.clear();
    // 连续空白（含换行）单独成块或直接丢弃，中文标点作为切分点但保留
    Pattern p = Pattern.compile("[^\\s，。！？；：、,.!?;:]+|[，。！？；：、,.!?;:]|\\s+");
    Matcher m = p.matcher(content);
    while (m.find()) {
      String piece = m.group();
      if (piece.trim().isEmpty()) {
        continue; // 丢掉纯空白，避免选择框里一堆无意义空格块
      }
      pieces.add(piece);
    }
    if (pieces.isEmpty()) {
      pieces.add(content);
    }
  }

  private void copyToClipboard(String text) {
    Context ctx = getContext();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
      ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setPrimaryClip(ClipData.newPlainText("PostContent", text));
    } else {
      android.text.ClipboardManager cm =
          (android.text.ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
      cm.setText(text);
    }
  }

}
