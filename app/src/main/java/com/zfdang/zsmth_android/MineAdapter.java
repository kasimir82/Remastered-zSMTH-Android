package com.zfdang.zsmth_android;

import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.facebook.drawee.view.SimpleDraweeView;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.LoginThrottle;
import com.zfdang.zsmth_android.newsmth.UserStatus;

import java.util.List;

/**
 * 「我的」页面列表适配器。
 */
public class MineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

  public interface OnMineItemClick {
    void onMineItemClick(MineItem item);
  }

  private final List<MineItem> items;
  private final OnMineItemClick listener;

  public MineAdapter(List<MineItem> items, OnMineItemClick listener) {
    this.items = items;
    this.listener = listener;
  }

  @Override public int getItemCount() {
    return items.size();
  }

  @Override public int getItemViewType(int position) {
    return items.get(position).type;
  }

  @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    LayoutInflater inflater = LayoutInflater.from(parent.getContext());
    if (viewType == MineItem.TYPE_USER) {
      return new UserCardHolder(inflater.inflate(R.layout.item_mine_user_card, parent, false));
    }
    if (viewType == MineItem.TYPE_SECTION) {
      return new SectionHolder(inflater.inflate(R.layout.item_mine_section, parent, false));
    }
    if (viewType == MineItem.TYPE_SWITCH) {
      return new SwitchHolder(inflater.inflate(R.layout.item_mine_switch, parent, false));
    }
    return new RowHolder(inflater.inflate(R.layout.item_mine_row, parent, false));
  }

  @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    MineItem item = items.get(position);
    if (holder instanceof UserCardHolder) {
      ((UserCardHolder) holder).bind(item);
    } else if (holder instanceof SectionHolder) {
      ((SectionHolder) holder).bind(item);
    } else if (holder instanceof SwitchHolder) {
      ((SwitchHolder) holder).bind(item);
    } else if (holder instanceof RowHolder) {
      ((RowHolder) holder).bind(item);
    }
  }

  private static String statText(int value) {
    return String.valueOf(value);
  }

  class UserCardHolder extends RecyclerView.ViewHolder {
    final SimpleDraweeView avatar;
    final TextView userName;
    final TextView userHint;
    final TextView loginAction;
    final TextView postValue;
    final TextView scoreValue;
    final TextView loginValue;
    final TextView levelValue;

    UserCardHolder(View itemView) {
      super(itemView);
      avatar = itemView.findViewById(R.id.mine_avatar);
      userName = itemView.findViewById(R.id.mine_user_name);
      userHint = itemView.findViewById(R.id.mine_user_hint);
      loginAction = itemView.findViewById(R.id.mine_login_action);
      postValue = itemView.findViewById(R.id.mine_stat_post_value);
      scoreValue = itemView.findViewById(R.id.mine_stat_score_value);
      loginValue = itemView.findViewById(R.id.mine_stat_login_value);
      levelValue = itemView.findViewById(R.id.mine_stat_level_value);
    }

    void bind(MineItem item) {
      UserStatus user = SMTHApplication.activeUser;
      boolean valid = SMTHApplication.isValidUser();

      if (valid && user != null) {
        userName.setText(user.getId());
        userHint.setText(user.is_online() ? "水木社区 · 在线" : "水木社区 · 已登录");
        loginAction.setText("注销");

        String faceUrl = user.getFace_url();
        if (!TextUtils.isEmpty(faceUrl)) {
          avatar.setImageURI(Uri.parse(faceUrl));
        } else {
          avatar.setImageResource(R.drawable.ic_person_black_48dp);
        }

        postValue.setText(statText(user.getPost_count()));
        scoreValue.setText(statText(user.getScore_user()));
        loginValue.setText(statText(user.getLogin_count()));

        String level = user.getLevel();
        if (TextUtils.isEmpty(level)) {
          level = user.getLife();
        }
        levelValue.setText(TextUtils.isEmpty(level) ? "-" : level);
      } else if (LoginThrottle.isRestricted()) {
        // 登录受限态：显示服务端原因 + 建议等待时间，提醒用户不要反复重试
        String[] hint = LoginThrottle.getRestrictionHint();
        userName.setText("登录受限");
        userName.setTextColor(userName.getContext().getResources()
            .getColor(com.zfdang.zsmth_android.R.color.login_restricted_warn));
        userHint.setText("服务器未能登录：" + hint[0] + "\n" + hint[1]);
        userHint.setMaxLines(3);
        userHint.setTextColor(userHint.getContext().getResources()
            .getColor(com.zfdang.zsmth_android.R.color.login_restricted_warn));
        loginAction.setText("重试");
        avatar.setImageResource(R.drawable.ic_person_black_48dp);
        postValue.setText("-");
        scoreValue.setText("-");
        loginValue.setText("-");
        levelValue.setText("-");
      } else {
        userName.setText("点击登录");
        userName.setTextColor(userName.getContext().getResources()
            .getColor(com.zfdang.zsmth_android.R.color.mine_row_title));
        userHint.setText("登录后可管理收藏与站内信");
        userHint.setMaxLines(2);
        userHint.setTextColor(userHint.getContext().getResources()
            .getColor(com.zfdang.zsmth_android.R.color.mine_row_summary));
        loginAction.setText("登录");
        avatar.setImageResource(R.drawable.ic_person_black_48dp);
        postValue.setText("-");
        scoreValue.setText("-");
        loginValue.setText("-");
        levelValue.setText("-");
      }

      itemView.setOnClickListener(v -> listener.onMineItemClick(MineItem.action("user_card")));
      loginAction.setOnClickListener(v -> listener.onMineItemClick(MineItem.action("user_action")));
    }
  }

  static class SectionHolder extends RecyclerView.ViewHolder {
    final TextView title;

    SectionHolder(View itemView) {
      super(itemView);
      title = itemView.findViewById(R.id.mine_section_title);
    }

    void bind(MineItem item) {
      title.setText(item.title);
    }
  }

  class RowHolder extends RecyclerView.ViewHolder {
    final ImageView icon;
    final TextView title;
    final TextView summary;
    final ImageView chevron;

    RowHolder(View itemView) {
      super(itemView);
      icon = itemView.findViewById(R.id.mine_row_icon);
      title = itemView.findViewById(R.id.mine_row_title);
      summary = itemView.findViewById(R.id.mine_row_summary);
      chevron = itemView.findViewById(R.id.mine_row_chevron);
    }

    void bind(MineItem item) {
      icon.setImageResource(item.iconRes);
      title.setText(item.title);
      if (TextUtils.isEmpty(item.summary)) {
        summary.setVisibility(View.GONE);
      } else {
        summary.setVisibility(View.VISIBLE);
        summary.setText(item.summary);
      }
      itemView.setOnClickListener(v -> listener.onMineItemClick(item));
    }
  }

  class SwitchHolder extends RecyclerView.ViewHolder {
    final ImageView icon;
    final TextView title;
    final TextView summary;
    final SwitchCompat switchWidget;

    SwitchHolder(View itemView) {
      super(itemView);
      icon = itemView.findViewById(R.id.mine_switch_icon);
      title = itemView.findViewById(R.id.mine_switch_title);
      summary = itemView.findViewById(R.id.mine_switch_summary);
      switchWidget = itemView.findViewById(R.id.mine_switch_widget);
    }

    void bind(MineItem item) {
      icon.setImageResource(item.iconRes);
      title.setText(item.title);
      if (TextUtils.isEmpty(item.summary)) {
        summary.setVisibility(View.GONE);
      } else {
        summary.setVisibility(View.VISIBLE);
        summary.setText(item.summary);
      }
      switchWidget.setChecked(item.checked);
      itemView.setOnClickListener(v -> {
        // 先切换显示状态，真实设置在回调中落盘后再整体刷新
        switchWidget.setChecked(!item.checked);
        listener.onMineItemClick(item);
      });
    }
  }
}
