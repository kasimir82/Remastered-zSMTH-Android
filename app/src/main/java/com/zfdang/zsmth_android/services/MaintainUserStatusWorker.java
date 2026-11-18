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
import com.zfdang.zsmth_android.newsmth.SMTHHelper;
import com.zfdang.zsmth_android.newsmth.UserInfo;
import com.zfdang.zsmth_android.newsmth.UserStatus;

import java.util.concurrent.TimeUnit;

import io.reactivex.functions.Function;
import io.reactivex.observers.DisposableObserver;

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
//                Log.d(TAG, "2.0 " + userStatus.toString());
                // create a dummy userStatus if it is null
                if (userStatus == null) {
                    userStatus = new UserStatus();
                }

                // set id to guest if it is null
                if (userStatus.getId() == null) {
                    userStatus.setId("guest");
                }

                // 后台只检测登录状态，不再用 nForum API 自动登录：
                // 水木对纯密码登录要求验证码（实测返回验证码错误），后台无法过验证码。
                // cookie 失效时由页面层（PostListActivity 等）拉起 WebView 登录页让用户过验证。
                if (!TextUtils.equals(userStatus.getId(), "guest")) {
                    // 已登录：cookie 有效，刷新登录时间
                    LoginThrottle.onLoginSuccess();
                }
                return userStatus;
            }
        }).map(new Function<UserStatus, UserStatus>() {
            @Override
            public UserStatus apply(UserStatus userStatus) throws Exception {
                //Log.d(TAG, "3.0 call: " + userStatus.toString());
                if (!TextUtils.equals(userStatus.getId(), "guest")) {
                    // valid user
                    if (SMTHApplication.activeUser != null && TextUtils.equals(userStatus.getId(), SMTHApplication.activeUser.getId())) {
                        // current user is already cached in SMTHApplication
                        //Log.d(TAG, "call: " + "3.1 New user is the same with cached user, copy faceURL from local");
                        userStatus.setFace_url(SMTHApplication.activeUser.getFace_url());
                    } else {
                        // get correct faceURL
                        //Log.d(TAG, "call: " + "3.2 New user is different with cached user, get real face URL from remote");
                        UserInfo userInfo = helper.wService.queryUserInformation(userStatus.getId()).blockingFirst();
                        if (userInfo != null) {
                            userStatus.setFace_url(userInfo.getFace_url());
                        }
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
                SMTHApplication.activeUser = userStatus;

                String message = "";
                if (SMTHApplication.isValidUser()) {
                    // get message if user is valid user
                    message = getNotificationMessage(SMTHApplication.activeUser);
                } else if (updateUserIcon == true) {
                    // not a valid user, but updateUserIcon is true, means login status has lost
                    message = SMTHApplication.NOTIFICATION_LOGIN_LOST;
                }

                UserStatusReceiver receiver = SMTHApplication.mUserStatusReceiver;
                // send notification: 1. new message 2. new activeUser to update Sidebar status
                if ((updateUserIcon || message.length() > 0) && receiver != null) {
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
