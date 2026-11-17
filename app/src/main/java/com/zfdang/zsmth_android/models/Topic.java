package com.zfdang.zsmth_android.models;

/**
 * Created by zfdang on 2016-3-14.
 */

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 */
public class Topic implements Parcelable {

  static private final int POST_PER_PAGE = 10;

  // 分隔符，只有一个category的名称
  public boolean isCategory;
  private String category;

  // 正常的主题
  private String boardEngName;
  private String boardChsName;

  private String topicID;
  private String title;
  private String author;
  private String publishDate;
  private String replier;
  private String replyDate;

  // 是否是置顶的主题
  public boolean isSticky;

  // status of topic
  private String score = null;
  private String likes = null;
  private String replyCounts = null;
  private boolean hasAttach;

  private int totalPageNo = 0;
  private int totalPostNo = 0;

  // this is actually a category, used in guidance fragment to seperate hot topics
  public Topic(String category) {
    isCategory = true;
    this.category = category;
  }

  public Topic() {
    isCategory = false;
    this.boardChsName = "";
    this.boardEngName = "";
    this.author = "";
  }

  public void setTotalPostNoFromString(String totalPostNoString) {
    try {
      this.totalPostNo = Integer.parseInt(totalPostNoString);
      if (this.totalPostNo % Topic.POST_PER_PAGE == 0) {
        this.totalPageNo = this.totalPostNo / Topic.POST_PER_PAGE;
      } else {
        this.totalPageNo = this.totalPostNo / Topic.POST_PER_PAGE + 1;
      }
    } catch (Exception e) {
      Log.d("Topic", Log.getStackTraceString(e));
    }
  }

  public int getTotalPageNo() {
    return totalPageNo;
  }

  public String getTotalPostNoAsStr() {
    if (this.totalPostNo == 0) return "";

    String result = String.format("%d", this.totalPostNo);
    return result;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  public String getBoardEngName() {
    return boardEngName;
  }

  public String getTopicURL() {
    return boardEngName;
  }

  public void setBoardEngName(String boardEngName) {
    this.boardEngName = boardEngName;
  }

  public String getBoardChsName() {
    return boardChsName;
  }

  public void setBoardChsName(String boardChsName) {
    this.boardChsName = boardChsName;
  }

  public String getBoardName() {
    if (boardChsName != null && boardChsName.length() > 0) {
      return boardChsName + " [" + boardEngName + "]";
    } else {
      return boardEngName;
    }
  }

  public String getTopicID() {
    return topicID;
  }

  public void setTopicID(String topicID) {
    this.topicID = topicID;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getPublishDate() {
    return publishDate;
  }

  public void setPublishDate(String publishDate) {
    this.publishDate = publishDate;
  }

  private static final java.util.regex.Pattern DATE_FULL =
      java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
  private static final java.util.regex.Pattern DATE_SHORT =
      java.util.regex.Pattern.compile("(\\d{1,2})-(\\d{1,2})");
  private static final java.util.regex.Pattern TIME_ONLY =
      java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})");

  /**
   * 「按发表时间排序」用的排序键，值越大表示越新。
   * 版面列表里的发布时间通常是 yyyy-MM-dd，较早的页面或用相对时间时可能是「今天 / 昨天 / MM-dd / HH:mm」。
   */
  public long getPublishSortKey() {
    String date = publishDate == null ? "" : publishDate.trim();
    if (date.length() == 0) {
      return 0;
    }
    java.util.regex.Matcher full = DATE_FULL.matcher(date);
    if (full.find()) {
      return Long.parseLong(full.group(1)) * 10000L
          + Long.parseLong(full.group(2)) * 100L
          + Long.parseLong(full.group(3));
    }
    if (date.startsWith("今天")) {
      return 99999999L;
    }
    if (date.startsWith("昨天")) {
      return 99999998L;
    }
    java.util.regex.Matcher shortDate = DATE_SHORT.matcher(date);
    if (shortDate.find()) {
      int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
      return year * 10000L + Long.parseLong(shortDate.group(1)) * 100L + Long.parseLong(shortDate.group(2));
    }
    if (TIME_ONLY.matcher(date).find()) {
      // 只给了时刻，说明就是今天发的
      return 99999999L;
    }
    return 0;
  }

  public String getReplier() {
    return replier;
  }

  public void setReplier(String replier) {
    this.replier = replier;
  }

  public String getReplyDate() {
    return replyDate;
  }

  public void setReplyDate(String replyDate) {
    this.replyDate = replyDate;
  }

  public boolean hasAttach() {
    return hasAttach;
  }

  public void setHasAttach(boolean hasAttach) {
    this.hasAttach = hasAttach;
  }

  public String getLikes() {
    return likes;
  }

  public void setLikes(String likes) {
    this.likes = likes;
  }

  public String getReplyCounts() {
    return replyCounts;
  }

  public void setReplyCounts(String replyCounts) {
    this.replyCounts = replyCounts;
  }

  public String getScore() {
    return score;
  }

  public void setScore(String score) {
    this.score = score;
  }

  public String getStatusSummary() {
    String result = String.format("回复: %-4s", replyCounts);
    if (likes != null && likes.length() > 0) {
      result += String.format("    Likes: %-4s    评分: %-4s", likes, score);
    }
    return result;
  }

  @Override public String toString() {
    if (isCategory) {
      return "Category " + this.category;
    } else {
      if (isSticky) {
        return String.format("置顶: (%s) %s by %s, %s @ %s", this.topicID, this.title, this.author, this.publishDate, this.boardEngName);
      } else {
        return String.format("(%s) %s by %s, %s @ %s", this.topicID, this.title, this.author, this.publishDate, this.boardEngName);
      }
    }
  }

  @Override public int describeContents() {
    return 0;
  }

  @Override public void writeToParcel(Parcel dest, int flags) {
    dest.writeByte(isCategory ? (byte) 1 : (byte) 0);
    dest.writeString(this.category);
    dest.writeString(this.boardEngName);
    dest.writeString(this.boardChsName);
    dest.writeString(this.topicID);
    dest.writeString(this.title);
    dest.writeString(this.author);
    dest.writeString(this.publishDate);
    dest.writeString(this.replier);
    dest.writeString(this.replyDate);
    dest.writeByte(isSticky ? (byte) 1 : (byte) 0);
    dest.writeString(this.score);
    dest.writeString(this.likes);
    dest.writeString(this.replyCounts);
    dest.writeByte(hasAttach ? (byte) 1 : (byte) 0);
    dest.writeInt(this.totalPageNo);
    dest.writeInt(this.totalPostNo);
  }

  protected Topic(Parcel in) {
    this.isCategory = in.readByte() != 0;
    this.category = in.readString();
    this.boardEngName = in.readString();
    this.boardChsName = in.readString();
    this.topicID = in.readString();
    this.title = in.readString();
    this.author = in.readString();
    this.publishDate = in.readString();
    this.replier = in.readString();
    this.replyDate = in.readString();
    this.isSticky = in.readByte() != 0;
    this.score = in.readString();
    this.likes = in.readString();
    this.replyCounts = in.readString();
    this.hasAttach = in.readByte() != 0;
    this.totalPageNo = in.readInt();
    this.totalPostNo = in.readInt();
  }

  public static final Creator<Topic> CREATOR = new Creator<Topic>() {
    @Override public Topic createFromParcel(Parcel source) {
      return new Topic(source);
    }

    @Override public Topic[] newArray(int size) {
      return new Topic[size];
    }
  };
}