package com.zfdang.zsmth_android.models;

/**
 * this class is used to build AlertDialog for long press on post item
 * Created by zfdang on 2016-3-28.
 */
public class PostActionAlertDialogItem {
  public final String text;
  public final int icon;
  /** 操作标识，代替列表下标，增删菜单项不会导致错位 */
  public final int action;

  public PostActionAlertDialogItem(String text, Integer icon) {
    this(text, icon, -1);
  }

  public PostActionAlertDialogItem(String text, Integer icon, int action) {
    this.text = text;
    this.icon = icon;
    this.action = action;
  }

  @Override public String toString() {
    return text;
  }
}
