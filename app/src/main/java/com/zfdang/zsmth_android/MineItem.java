package com.zfdang.zsmth_android;

/**
 * 「我的」页面的列表条目模型。
 * 一个条目可以是用户卡片、分组标题、可点击行、带开关的行。
 */
public class MineItem {

  public static final int TYPE_USER = 0;
  public static final int TYPE_SECTION = 1;
  public static final int TYPE_ROW = 2;
  public static final int TYPE_SWITCH = 3;

  public final int type;
  public final String key;
  public final String title;
  public final String summary;
  public final int iconRes;
  public boolean checked;
  public int badgeCount; // >0 时在标题后显示红字 (N)

  private MineItem(int type, String key, String title, String summary, int iconRes, boolean checked) {
    this.type = type;
    this.key = key;
    this.title = title;
    this.summary = summary;
    this.iconRes = iconRes;
    this.checked = checked;
    this.badgeCount = 0;
  }

  public static MineItem userCard() {
    return new MineItem(TYPE_USER, "user_card", null, null, 0, false);
  }

  public static MineItem action(String key) {
    return new MineItem(TYPE_ROW, key, null, null, 0, false);
  }

  public static MineItem section(String title) {
    return new MineItem(TYPE_SECTION, null, title, null, 0, false);
  }

  public static MineItem row(String key, int iconRes, String title, String summary) {
    return new MineItem(TYPE_ROW, key, title, summary, iconRes, false);
  }

  /** 带未读数字的列表行（标题后追加红字 (N)，N<=0 不显示） */
  public static MineItem rowWithBadge(String key, int iconRes, String title, String summary, int badgeCount) {
    MineItem item = new MineItem(TYPE_ROW, key, title, summary, iconRes, false);
    item.badgeCount = badgeCount;
    return item;
  }

  public static MineItem toggle(String key, int iconRes, String title, String summary, boolean checked) {
    return new MineItem(TYPE_SWITCH, key, title, summary, iconRes, checked);
  }
}
