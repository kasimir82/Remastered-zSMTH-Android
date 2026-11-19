package com.zfdang.zsmth_android;

import android.app.Activity;

import android.widget.GridLayout;
import android.widget.ImageView;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Point;
import android.view.Display;
import android.view.Gravity;
import android.widget.ImageView;
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
import java.util.ArrayList;
import java.util.List;
import com.bumptech.glide.Glide;

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

  private GridLayout gridLayout;
  private List<ImageView> imgList;

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
      return new EndViewHolder(view);
    }
    View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.post_item, parent, false);
    return new ViewHolder(view);
  }

  /**
   * 结束标记高度 = 视口高 - 最后一楼高：让最后一楼恰好能滚到与标题齐平（顶部对齐视口上沿）。
   * 这样最后一楼顶到标题后，下方正好是「本贴结束」区域；再点下半屏(结束区域内)即返回。
   * 测量需要等布局完成，最多重试几次（不挂 onScrolled，避免改高度反复触发滚动而 ANR）。
   */
  private void adjustEndMarkerHeight(final View endView, final int retryLeft) {
    if (mRecyclerView == null || !mRecyclerView.isAttachedToWindow() || !mShowEndMarker) return;
    RecyclerView.LayoutManager lm = mRecyclerView.getLayoutManager();
    if (!(lm instanceof androidx.recyclerview.widget.LinearLayoutManager)) return;
    int viewport = mRecyclerView.getHeight() - mRecyclerView.getPaddingTop() - mRecyclerView.getPaddingBottom();
    View lastPostView = lm.findViewByPosition(mPosts.size() - 1);
    int lastH = lastPostView == null ? 0 : lastPostView.getHeight();
    if (viewport <= 0 || lastH <= 0) {
      // 还没测好，下一帧再试，最多 5 次
      if (retryLeft > 0) endView.post(() -> adjustEndMarkerHeight(endView, retryLeft - 1));
      return;
    }
    int markerH = Math.max(dpToPx(endView, 72), viewport - lastH);
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

    imgList = new ArrayList<>();

    // http://stackoverflow.com/questions/13438473/clicking-html-link-in-textview-fires-weird-androidruntimeexception
    final LayoutInflater inflater = mListener.getLayoutInflater();
    for (int i = 1; i < contents.size(); i++) {
      ContentSegment content = contents.get(i);

      if (content.getType() == ContentSegment.SEGMENT_IMAGE) {
        // Log.d("CreateView", "Image: " + content.getUrl());

        if(Settings.getInstance().isImageGridMode()){
          // use grid view to replace original imageview
          ImageView image = (ImageView) inflater.inflate(R.layout.post_item_image, viewGroup, false);
          image.setScaleType(ImageView.ScaleType.CENTER_CROP);
          image.setPadding(5, 5, 5, 5);
          Glide.with(mListener).load(content.getUrl()).into(image);
          bindImageClicks(image, post, content);

          // add image to list, instead of adding to viewgroup directly
          imgList.add(image);
        } else {
          // Add the text layout to the parent layout
          // 采用混排模式，文字和图片混在一起
          WrapContentDraweeView image = (WrapContentDraweeView) inflater.inflate(R.layout.post_item_imageview, viewGroup, false);
          image.setImageFromStringURL(content.getUrl());

          bindImageClicks(image, post, content);

          // Add the text view to the parent layout
          viewGroup.addView(image);
        }

      } else if (content.getType() == ContentSegment.SEGMENT_TEXT) {
        // Log.d("CreateView", "Text: " + content.getSpanned().toString());

        LinkConsumableTextView tv = (LinkConsumableTextView) inflater.inflate(R.layout.post_item_content, viewGroup, false);
        bindTextSegment(tv, content, true);

        // Add the text view to the parent layout
        viewGroup.addView(tv);
      }
    }

    if(Settings.getInstance().isImageGridMode()) {
      // 将图片统一以网格形式添加进去
      int index = imgList.size() - imgList.size() > 1 ? 2 : 1;
      if (imgList.size() > 0) {
        int size, width;

        Display display = mListener.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);
        width = point.x;
        size = imgList.size();

        gridLayout = new GridLayout(mListener);
        GridLayout.Spec rowSpec, columnSpec;

        GridLayout.LayoutParams gridParams = null;
        int remainder, i;
        remainder = i = size % 3;
        for (int k = 0; k < remainder; ++k) {
          rowSpec = GridLayout.spec(0);
          columnSpec = GridLayout.spec(k);
          gridParams = new GridLayout.LayoutParams(rowSpec, columnSpec);
          gridParams.setGravity(Gravity.LEFT);
          gridParams.width = width / i;
          gridParams.height = width / 2;
          gridLayout.addView(imgList.get(k), gridParams);
        }
        viewGroup.addView(gridLayout, index++);

        gridLayout = new GridLayout(mListener);
        for (; i < size; ++i) {
          rowSpec = GridLayout.spec((i - remainder) / 3);
          columnSpec = GridLayout.spec((i - remainder) % 3);
          gridParams = new GridLayout.LayoutParams(rowSpec, columnSpec);
          gridParams.width = width / 3;
          gridParams.height = width / 3;
          gridLayout.addView(imgList.get(i), gridParams);
        }
        viewGroup.addView(gridLayout, index);
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

    holder.mPostAuthor.setText(post.getAuthor());
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
    public final TextView mPostIndex;
    public final TextView mPostPublishDate;
    private final LinearLayout mViewGroup;
    public final LinkConsumableTextView mPostContent;
    public Post mPost;

    public ViewHolder(View view) {
      super(view);
      mView = view;
      mPostAuthor = (TextView) view.findViewById(R.id.post_author);
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
