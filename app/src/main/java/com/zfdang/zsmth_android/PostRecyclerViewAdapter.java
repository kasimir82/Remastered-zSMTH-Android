package com.zfdang.zsmth_android;

import android.app.Activity;

import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.klinker.android.link_builder.LinkBuilder;
import com.klinker.android.link_builder.LinkConsumableTextView;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.fresco.WrapContentDraweeView;
import com.zfdang.zsmth_android.helpers.ActivityUtils;
import com.zfdang.zsmth_android.helpers.QuoteTextHelper;
import com.zfdang.zsmth_android.models.ContentSegment;
import com.zfdang.zsmth_android.models.Post;
import java.util.List;

/**
 * used by HotPostFragment & BoardPostFragment
 */
public class PostRecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

  private static final int TYPE_POST = 0;
  private static final int TYPE_END = 1;

  private final List<Post> mPosts;
  private final Activity mListener;
  private RecyclerView mRecyclerView;
  /** 已加载到最后一页时，在列表末尾显示「本贴结束」标记 */
  private boolean mShowEndMarker = false;

  public PostRecyclerViewAdapter(List<Post> posts, Activity listener) {
    mPosts = posts;
    mListener = listener;
  }

  public void setShowEndMarker(boolean show) {
    if (mShowEndMarker == show) {
      return;
    }
    mShowEndMarker = show;
    if (show) {
      notifyItemInserted(mPosts.size());
      // 插入后延迟一帧校准高度（视口高 - 最后一楼高），让最后一楼能顶到与标题齐平。
      // 只在插入时触发一次，绝不放 onBind 里——改 LayoutParams 会触发重绑定导致死循环 ANR。
      if (mRecyclerView != null) {
        mRecyclerView.post(() -> {
          RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
          if (lm != null) {
            View endV = lm.findViewByPosition(mPosts.size());
            if (endV != null) adjustEndMarkerHeight(endV, 5);
          }
        });
      }
    } else {
      notifyItemRemoved(mPosts.size());
    }
  }

  public boolean isShowingEndMarker() {
    return mShowEndMarker;
  }

  @Override public int getItemViewType(int position) {
    return (position == mPosts.size() && mShowEndMarker) ? TYPE_END : TYPE_POST;
  }

  @Override public void onAttachedToRecyclerView(RecyclerView recyclerView) {
    super.onAttachedToRecyclerView(recyclerView);
    mRecyclerView = recyclerView;
  }

  @Override public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
    if (viewType == TYPE_END) {
      View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.post_list_end, parent, false);
      // 关键：EndMarker 一创建就把高度写死为视口高的80%，不能等它滚进视口再校准(findViewByPosition 找不到)。
      // 否则翻到倒数第二/三楼时它高度还是 WRAP_CONTENT(-2)，底部空间不够导致楼顶不到顶。
      int viewport = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom();
      if (viewport <= 0) {
        // parent 还没测好，退而用屏幕高度
        viewport = parent.getContext().getResources().getDisplayMetrics().heightPixels;
      }
      int markerH = viewport * 8 / 10; // 视口高的80%
      RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) view.getLayoutParams();
      if (lp == null) lp = new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, markerH);
      else lp.height = markerH;
      view.setLayoutParams(lp);
      return new EndViewHolder(view);
    }
    View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.post_item, parent, false);
    return new ViewHolder(view);
  }

  /**
   * 结束标记高度固定 = 视口高（不再用 视口-末楼高 动态算）。
   * 原因：动态算依赖末楼高度测量，图片加载前后 lastH 会变，导致偶发倒数第二楼对不齐。
   * 写死视口高后，末楼和倒数第二楼都有足够空间顶到视口上沿，且「本贴结束」文字顶部对齐
   * 仍紧跟末楼下方(布局是 gravity=center_horizontal + paddingTop=28dp，非垂直居中)。
   */
  private void adjustEndMarkerHeight(final View endView, final int retryLeft) {
    if (mRecyclerView == null || !mRecyclerView.isAttachedToWindow() || !mShowEndMarker) return;
    RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
    if (!(lm instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
    int viewport = mRecyclerView.getHeight() - mRecyclerView.getPaddingTop() - mRecyclerView.getPaddingBottom();
    if (viewport <= 0) {
      // 视口还没测好，下一帧再试，最多 5 次
      if (retryLeft > 0) endView.post(() -> adjustEndMarkerHeight(endView, retryLeft - 1));
      return;
    }
    adjustEndMarkerHeightNow();
  }

  /** 立即校准结束标记高度 = 视口高（供滚动停止时调用） */
  public void adjustEndMarkerHeightNow() {
    if (mRecyclerView == null || !mRecyclerView.isAttachedToWindow() || !mShowEndMarker) return;
    RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
    if (!(lm instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
    View endView = lm.findViewByPosition(mPosts.size());
    if (endView == null) return;
    int viewport = mRecyclerView.getHeight() - mRecyclerView.getPaddingTop() - mRecyclerView.getPaddingBottom();
    if (viewport <= 0) return;
    int markerH = viewport * 8 / 10; // 固定为视口高的80%，不依赖末楼高度
    RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) endView.getLayoutParams();
    if (lp != null && Math.abs(lp.height - markerH) > dpToPx(endView, 8)) {
      lp.height = markerH;
      endView.setLayoutParams(lp);
    }
  }

  private static int dpToPx(View v, int dp) {
    return Math.round(dp * v.getResources().getDisplayMetrics().density);
  }

  private void bindTextSegment(TextView tv, ContentSegment content, boolean addLinks) {
    QuoteTextHelper.bind(tv, content, mListener, addLinks);
  }

  /**
   * 图片本身不设任何点击/长按监听：短按事件冒泡到楼层 view 的手势层触发翻页，
   * 长按由 RecyclerViewGestureListener 识别到 image_tag 后回调 onImageLongClicked 弹菜单。
   * 这里只打上 image_tag 供识别（含图片在楼层里的序号）。
   */
  private void bindImageClicks(View image, final Post post, final ContentSegment content) {
    image.setTag(R.id.image_tag, content.getImgIndex());
  }

  //    MailContentActivity.inflateContentViewGroup is using the same logic, please make sure they are consistent
  public void inflateContentViewGroup(ViewGroup viewGroup, TextView contentView, final Post post) {
    // remove all child view in viewgroup
    viewGroup.removeAllViews();

    List<ContentSegment> contents = post.getContentSegments();
    if (contents == null) return;

    if (contents.size() > 0) {
      // there are multiple segments, add the first contentView first
      // contentView is always available, we don't have to inflate it again
      ContentSegment content = contents.get(0);
      bindTextSegment(contentView, content, false);

      viewGroup.addView(contentView);
    }

    // http://stackoverflow.com/questions/13438473/clicking-html-link-in-textview-fires-weird-androidruntimeexception
    final LayoutInflater inflater = mListener.getLayoutInflater();
    for (int i = 1; i < contents.size(); i++) {
      ContentSegment content = contents.get(i);

      if (content.getType() == ContentSegment.SEGMENT_IMAGE) {
        // Log.d("CreateView", "Image: " + content.getUrl());

        // 图片网格模式已移除，统一使用图文混排：图片按原文位置插在文字中间
        WrapContentDraweeView image = (WrapContentDraweeView) inflater.inflate(R.layout.post_item_imageview, viewGroup, false);
        image.setImageFromStringURL(content.getUrl());
        bindImageClicks(image, post, content);
        viewGroup.addView(image);

      } else if (content.getType() == ContentSegment.SEGMENT_TEXT) {
        // Log.d("CreateView", "Text: " + content.getSpanned().toString());

        LinkConsumableTextView tv = (LinkConsumableTextView) inflater.inflate(R.layout.post_item_content, viewGroup, false);
        bindTextSegment(tv, content, true);

        // Add the text view to the parent layout
        viewGroup.addView(tv);
      }
    }
  }

  @Override public void onBindViewHolder(final RecyclerView.ViewHolder baseHolder, final int position) {
    if (baseHolder instanceof EndViewHolder) {
      // 点「本贴结束」空白区域 → 返回，由 RecyclerViewGestureListener.onSingleTapUp
      // 统一判断点击坐标处理（子 View 的点击会被 RecyclerView 的 OnTouchListener 拦截）。
      return; // 结束标记没有其它需要绑定的数据
    }
    final ViewHolder holder = (ViewHolder) baseHolder;
    holder.mPost = mPosts.get(position);
    Post post = holder.mPost;

    // 只显示纯用户ID，不显示 "ID(昵称)" 的括号昵称部分（太长）
    holder.mPostAuthor.setText(post.getRawAuthor());
    holder.mPostPublishDate.setText(post.getFormatedDate());
    holder.mPostIndex.setText(post.getPosition());

    inflateContentViewGroup(holder.mViewGroup, holder.mPostContent, post);

    // http://stackoverflow.com/questions/4415528/how-to-pass-the-onclick-event-to-its-parent-on-android
    // http://stackoverflow.com/questions/24885223/why-doesnt-recyclerview-have-onitemclicklistener-and-how-recyclerview-is-dif
    holder.mView.setOnClickListener(new View.OnClickListener() {
      @Override public void onClick(View v) {
      }
    });
    // item view 也要挂 onTouch：引文 TextView 的三角点击需要子 View 先收到事件。
    // 同时 RecyclerView 本身也挂了 onTouch(见 PostListActivity) 覆盖 item 之间的分割线：
    // 子 View 消费(如点引文三角)则不会传给 RecyclerView；未消费(如点分割线)则由 RecyclerView 处理翻页。
    holder.mView.setOnTouchListener(new View.OnTouchListener() {
      @Override public boolean onTouch(View v, MotionEvent event) {
        if (mListener != null && mListener instanceof View.OnTouchListener) {
          return ((View.OnTouchListener) mListener).onTouch(v, event);
        }
        return false;
      }
    });
    // 红色"回"字：点击直接回帖。
    // 用OnTouchListener消费ACTION_UP并触发回帖(返回true)：既不冒泡到父View手势层触发翻页/长按，
    // 也避免setOnTouchListener返回true吞掉onClick的问题。
    final Post replyPost = post;
    holder.mPostReplyBtn.setOnTouchListener(new View.OnTouchListener() {
      @Override public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP && mListener instanceof PostListActivity) {
          com.zfdang.zsmth_android.helpers.HapticFeedback.vibrate(v.getContext());
          ((PostListActivity) mListener).startReplyToPost(replyPost);
        }
        return true; // 完全消费，不传给父View的翻页/长按手势层
      }
    });
  }

  @Override public int getItemCount() {
    return mPosts.size() + (mShowEndMarker ? 1 : 0);
  }

  public static class EndViewHolder extends RecyclerView.ViewHolder {
    public EndViewHolder(View view) {
      super(view);
    }
  }

  public class ViewHolder extends RecyclerView.ViewHolder {
    public final View mView;
    public final TextView mPostAuthor;
    public final TextView mPostReplyBtn;
    public final TextView mPostIndex;
    public final TextView mPostPublishDate;
    private final LinearLayout mViewGroup;
    public final LinkConsumableTextView mPostContent;
    public Post mPost;

    public ViewHolder(View view) {
      super(view);
      mView = view;
      mPostAuthor = (TextView) view.findViewById(R.id.post_author);
      mPostReplyBtn = (TextView) view.findViewById(R.id.post_reply_btn);
      mPostIndex = (TextView) view.findViewById(R.id.post_index);
      mPostPublishDate = (TextView) view.findViewById(R.id.post_publish_date);
      mViewGroup = (LinearLayout) view.findViewById(R.id.post_content_holder);
      mPostContent = (LinkConsumableTextView) view.findViewById(R.id.post_content);
    }

    @Override public String toString() {
      return mPost.toString();
    }
  }
}
