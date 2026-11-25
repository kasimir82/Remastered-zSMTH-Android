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
    // 必须把版面名写入topic：replySpannable按(boardEngName,topicID)查阅读进度快照，
    // 之前boardName参数传进来却没用，导致刷新后新解析的topic没有boardEngName、
    // 查不到快照，回复数颜色(蓝色已读)全部丢失。
    if (item.getBoardEngName() == null || item.getBoardEngName().isEmpty()) {
      item.setBoardEngName(boardName);
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
