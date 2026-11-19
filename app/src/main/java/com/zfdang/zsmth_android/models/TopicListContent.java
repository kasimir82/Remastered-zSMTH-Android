package com.zfdang.zsmth_android.models;

import com.zfdang.zsmth_android.helpers.BlockListManager;

import java.util.ArrayList;
import java.util.List;

// this ca

/**
 * this class is shared by HotTopicFragment & BoardTopicFragment
 * Android template wizards.
 */
public class TopicListContent {

  public static final List<Topic> HOT_TOPICS = new ArrayList<Topic>();

  /** @return true 表示已加入列表; false 表示命中屏蔽规则被丢弃 */
  public static boolean addHotTopic(Topic item) {
    if (item == null) return false;
    if (BlockListManager.getInstance().isBlocked(item.getAuthor(), item.getTitle())) {
      return false;
    }
    HOT_TOPICS.add(item);
    return true;
  }

  public static void clearHotTopics() {
    HOT_TOPICS.clear();
  }

  public static final List<Topic> BOARD_TOPICS = new ArrayList<Topic>();

  /** @return true 表示已加入列表; false 表示命中屏蔽规则被丢弃 */
  public static boolean addBoardTopic(Topic item, String boardName) {
    if (item == null) return false;
    if (BlockListManager.getInstance().isBlocked(item.getAuthor(), item.getTitle())) {
      return false;
    }
    BOARD_TOPICS.add(item);
    return true;
  }

  public static void clearBoardTopics() {
    BOARD_TOPICS.clear();
  }

  /** 判断帖子是否命中屏蔽规则（供加载全部页全局排序时用） */
  public static boolean isTopicBlocked(Topic item, String boardName) {
    if (item == null) return true;
    return BlockListManager.getInstance().isBlocked(item.getAuthor(), item.getTitle());
  }
}
