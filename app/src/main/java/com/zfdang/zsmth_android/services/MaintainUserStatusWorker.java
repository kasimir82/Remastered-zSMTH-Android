package com.zfdang.zsmth_android.services;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.Settings;
import com.zfdang.zsmth_android.helpers.LoginThrottle;
import com.zfdang.zsmth_android.helpers.SilentLoginHelper;
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import com.zfdang.zsmth_android.newsmth.UserInfo;
import com.zfdang.zsmth_android.newsmth.UserStatus;

import java.util.concurrent.TimeUnit;

import io.reactivex.functions.Function;
import io.reactivex.observers.DisposableObserver;
import io.reactivex.schedulers.Schedulers;

public class MaintainUserStatusWorker extends Worker {

    public static final String REPEAT = "REPEAT";
    private static final String TAG = "MUSWorker";

    public static final String WORKER_ID=MaintainUserStatusWorker.class.getName();

    public MaintainUserStatusWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
//        Log.d(TAG, "doWork");

        // do the job here
        doWorkInternal();

        Data myData = getInputData();
        // schedule the next worker
        boolean repeat = myData.getBoolean(REPEAT, false);
        if (repeat) {
            enqueueNextWorker();
        }

        // return result
        return Result.success();
    }

    private void doWorkInternal() {
        // This describes what will happen when service is triggered
        // process here:
        // 1. get user status
        // 2.1 if it's not guest, go to step 3
        // 2.2 if login-with-guesture-verification is used, go to step 3; otherwise try to login;
        // 2.2.1 if login success, get user status (2.2.1.1) again. go to step 3
        // 2.2.2 if login failed, go to step 3
        // 3. check whether user status == SMTHApplication.activeUser
        // 3.1 if they are same, just return SMTHApplication.activeUser
        // 3.2 if not, get real face URL
        // 4. if user status is a different user, send notification to receiver to update navigationView

        final SMTHHelper helper = SMTHHelper.getInstance();

        //Log.d(TAG, "1.0 get current UserStatus from remote");
        helper.wService.queryActiveUserStatus().map(new Function<UserStatus, UserStatus>() {
            @Override
            public UserStatus apply(UserStatus userStatus) throws Exception {
                // 记录 ajax_session 返回的原始登录态：id / is_login / ajax_code / ajax_msg，
                // 便于定位「有 cookie 但被判游客」以及登录受限(太多窗口/频繁)错误
                Log.d(TAG, "queryActiveUserStatus: " + (userStatus == null ? "null" : userStatus.toString()));
                // 响应异常(非成功码/含受限关键词)落盘；正常 guest 是冷启动常见现象，不记避免刷屏
                if (userStatus != null) {
                  String code = userStatus.getAjax_code();
                  String msg = userStatus.getAjax_msg();
                  if ((code != null && !"0005".equals(code))
                      || com.zfdang.zsmth_android.helpers.LoginThrottle.isTooFrequentDialog(msg)) {
                    com.zfdang.zsmth_android.helpers.LoginFailureLogger.log("status_abnormal",
                        "ajax_code=" + code + " ajax_msg=" + msg + " id=" + userStatus.getId()
                            + " is_login=" + userStatus.is_login());
                  }
                }
                // create a dummy userStatus if it is null
                if (userStatus == null) {
                    userStatus = new UserStatus();
                }

                // set id to guest if it is null
                if (userStatus.getId() == null) {
                    userStatus.setId("guest");
                }

                // cookie 有效时不刷新 lastLoginTime：该字段只记录「真实登录时刻」，
                // 供 cookie 失效后判断能否静默重登。若在这里刷新，会导致失效后
                // 被 30 分钟节流挡住，用户长时间卡在「未登录」。
                return userStatus;
            }
        }).map(new Function<UserStatus, UserStatus>() {
            @Override
            public UserStatus apply(UserStatus userStatus) throws Exception {
                //Log.d(TAG, "3.0 call: " + userStatus.toString());
                if (!TextUtils.equals(userStatus.getId(), "guest")) {
                    // valid user
                    // 注意：ajax_session 返回的 face_url 是**默认头像**(face_default_m.jpg)，
                    // 真实头像只出现在 /nForum/user/query/{id}.json 里。
                    // 所以缓存里即使有值，只要是默认头像也不能复用，必须去远端取真头像。
                    String cachedFace = SMTHApplication.activeUser == null
                        ? null : SMTHApplication.activeUser.getFace_url();
                    boolean cachedUsable = !TextUtils.isEmpty(cachedFace)
                        && !cachedFace.contains("face_default");
                    if (SMTHApplication.activeUser != null
                        && TextUtils.equals(userStatus.getId(), SMTHApplication.activeUser.getId())
                        && cachedUsable) {
                        // current user is already cached in SMTHApplication，且缓存的是真头像 → 复用
                        userStatus.setFace_url(cachedFace);
                    } else {
                        // get correct faceURL
                        // 走到这里有两种情况：缓存为空(静默重登后新建的 activeUser)，
                        // 或缓存的是默认头像(ajax_session 给的那个) → 都去远端取真头像。
                        UserInfo userInfo = helper.wService.queryUserInformation(userStatus.getId()).blockingFirst();
                        if (userInfo != null && !TextUtils.isEmpty(userInfo.getFace_url())) {
                            userStatus.setFace_url(userInfo.getFace_url());
                        }
                        com.zfdang.zsmth_android.helpers.DiagLogger.log("UserInfo", "FACE_URL",
                            com.zfdang.zsmth_android.helpers.DiagLogger.PASS,
                            "id=" + userStatus.getId() + " cached=" + cachedFace
                                + " resolved=" + userStatus.getFace_url());
                    }
                } else {
                    //Log.d(TAG, "call: 3.3 " + "invalid logined user");
                }
                return userStatus;
            }
        }).subscribe(new DisposableObserver<UserStatus>() {
            @Override
            public void onNext(UserStatus userStatus) {
                //Log.d(TAG, "4.0 onNext: " + userStatus.toString());
                // cache user if necessary, so we don't have to query User avatar url again in the future
                boolean updateUserIcon = false;
                if (!TextUtils.equals(userStatus.getId(), SMTHApplication.displayedUserId)) {
                    // active user is null, or active user is different with userstatus, update the icon
                    // Log.d(TAG, "onNext: " + "4.1 cache userStatus as activeUser");
                    updateUserIcon = true;
                }
                // 状态机统一验证：访问NewExpress版面实际验证，结果直接修正三态。
                // 本 onNext 在 IO 线程，用同步版验证（内部有网络请求）。
                com.zfdang.zsmth_android.helpers.LoginState verifiedState =
                    com.zfdang.zsmth_android.helpers.LoginStateManager.refreshStateSync();
                android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG,
                    "Worker.onNext: state=" + verifiedState + " (jsonId=" + userStatus.getId() + ")");
                if (verifiedState != com.zfdang.zsmth_android.helpers.LoginState.LOGGED_IN) {
                    userStatus.setId("guest"); // 走未登录分支，UI 刷新为未登录 + 触发自动重登
                }

                SMTHApplication.activeUser = userStatus;

                String message = "";
                if (SMTHApplication.isValidUser()) {
                    // get message if user is valid user
                    message = getNotificationMessage(SMTHApplication.activeUser);
                } else {
                    // 未登录：先尝试后台静默登录，不立即弹登录页打扰用户。
                    // 成功→静默恢复；需要验证码→通知页面层弹可见 WebView；失败→才提示重新登录。
                    trySilentLogin();
                    // 仍更新头像为游客态（UI 一致性），但去掉自动弹登录的提示
                    // message 留空，由静默登录回调决定后续提示
                }

                UserStatusReceiver receiver = SMTHApplication.mUserStatusReceiver;
                // 每次检查完成都通知 UI 刷新：
                // 登录成功后若 updateUserIcon=false 且无新消息，之前不会发通知，
                // 导致「我的」页顶端账号区域不刷新。这里无条件发送，保证登录态变化时 UI 同步。
                if (receiver != null) {
                    //Log.d(TAG, "4.2 cached user, valid message, valid receiver, send message");
                    Bundle bundle = new Bundle();
                    if (message.length() > 0) {
                        bundle.putString(SMTHApplication.SERVICE_NOTIFICATION_MESSAGE, message);
                    }
                    // Here we call send passing a resultCode and the bundle of extras
                    receiver.send(Activity.RESULT_OK, bundle);
                }
            }

            @Override
            public void onError(Throwable e) {
                Log.e(TAG, "onError: " + Log.getStackTraceString(e));
            }

            @Override
            public void onComplete() {
            }
        });
    }

    /**
     * 后台静默登录：统一走 LoginManager.autoLogin（判断/Toast/全局态同步都在那里）。
     * 这里只保留 Worker 专属的"成功后刷新登录态并通知 UI 更新头像"。
     */
    private void trySilentLogin() {
        android.util.Log.d(com.zfdang.zsmth_android.helpers.LoginThrottle.TAG_DBG, "Worker.trySilentLogin ENTER");
        com.zfdang.zsmth_android.helpers.LoginManager.autoLogin("worker", userId -> {
            Log.i(TAG, "autoLogin success: " + userId + ", refresh status");
            UserStatusReceiver receiver = SMTHApplication.mUserStatusReceiver;
            // 重新拉一次登录态，更新全局 activeUser 与 UI
            // 本回调在主线程，网络请求必须切到 IO 线程
            SMTHHelper.getInstance().wService.queryActiveUserStatus()
                    .subscribeOn(Schedulers.io())
                    .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
                    .subscribe(status -> {
                        if (status != null && status.getId() != null
                                && !"guest".equals(status.getId())) {
                            SMTHApplication.activeUser = status;
                            SMTHApplication.displayedUserId = status.getId();
                            if (receiver != null) {
                                receiver.send(Activity.RESULT_OK, new Bundle());
                            }
                        }
                    }, e -> Log.e(TAG, "refresh status after autoLogin failed", e));
        });
    }

    public String getNotificationMessage(UserStatus userStatus) {
        String message = "";
        Settings settings = Settings.getInstance();
        if (userStatus.hasNewMail() && settings.isNotificationMail()) {
            message = SMTHApplication.NOTIFICATION_NEW_MAIL + "  ";
        }
        if (userStatus.hasNewLike() && settings.isNotificationLike()) {
            message += SMTHApplication.NOTIFICATION_NEW_LIKE + "  ";
        }
        if (userStatus.hasNewAt() && settings.isNotificationAt()) {
            message += SMTHApplication.NOTIFICATION_NEW_AT + "  ";
        }
        if (userStatus.hasNewReply() && settings.isNotificationReply()) {
            message += SMTHApplication.NOTIFICATION_NEW_REPLY + "  ";
        }
        //Log.d(TAG, message);
        return message;
    }

    private void enqueueNextWorker() {
        Data.Builder inputData = new Data.Builder();
        inputData.putBoolean(MaintainUserStatusWorker.REPEAT, true);
        OneTimeWorkRequest userStatusWorkRequest =
                new OneTimeWorkRequest.Builder(MaintainUserStatusWorker.class)
                        .setInitialDelay(SMTHApplication.INTERVAL_TO_CHECK_MESSAGE, TimeUnit.MINUTES)
                        .setInputData(inputData.build())
                        .build();
        WorkManager.getInstance(getApplicationContext()).enqueueUniqueWork(WORKER_ID, ExistingWorkPolicy.KEEP,userStatusWorkRequest);
    }
}
