package com.zfdang.zsmth_android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;

import android.os.Handler;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.zfdang.SMTHApplication;
import com.zfdang.zsmth_android.helpers.LoginThrottle;

/**
 * A login screen that offers login to newsmth forum
 */
public class LoginActivity extends SMTHBaseActivity implements OnClickListener {

  private EditText m_userNameEditText;
  private EditText m_passwordEditText;
  private CheckBox mSaveInfo;

  // these 3 parameters are used by webviewlogin only
  static final int LOGIN_ACTIVITY_REQUEST_CODE = 9528;  // The request code
  static final String USERNAME = "USERNAME";
  static final String PASSWORD = "PASSWORD";
  /** WebView 登录失败时带回的错误类型，第一页据此决定是否清密码 */
  static final String EXTRA_LOGIN_ERROR_TYPE = "login_error_type";
  static final String ERR_TYPE_PASSWORD = "password"; // 明确密码错误
  static final String ERR_TYPE_OTHER = "other";       // 其它失败(账号过多/超时/验证码等)

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_login);
        setupBottomNavigation();
    setSupportActionBar(findViewById(R.id.login_toolbar));

    // 处于登录频繁冷却期：提示并不再显示可提交的登录表单
    if (LoginThrottle.inCooldown()) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("登录受限")
                .setMessage(LoginThrottle.getCooldownMessage())
                .setPositiveButton("知道了", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    // these two variables should be loaded from preference
    Settings setting = Settings.getInstance();
    String username = setting.getUsername();
    String password = setting.getPassword();
    boolean saveinfo = setting.isSaveInfo();

    m_userNameEditText = (EditText) findViewById(R.id.username_edit);
    m_userNameEditText.setText(username);
    m_passwordEditText = (EditText) findViewById(R.id.password_edit);
    m_passwordEditText.setText(password);

    if (username == null || username.isEmpty()) {
      new Handler().postDelayed(() -> {
        m_userNameEditText.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(m_userNameEditText, InputMethodManager.SHOW_IMPLICIT);
      }, 300);
    }


    mSaveInfo = (CheckBox) findViewById(R.id.save_info);
    mSaveInfo.setChecked(saveinfo);

    TextView registerLink = (TextView) findViewById(R.id.register_link);
    registerLink.setMovementMethod(LinkMovementMethod.getInstance());

    TextView asmHelpLink = (TextView) findViewById(R.id.asm_help_link);
    asmHelpLink.setMovementMethod(LinkMovementMethod.getInstance());

    Button ubutton = (Button) findViewById(R.id.signin_button);
    ubutton.setOnClickListener(this);

    // enable back button in the title barT
    ActionBar bar = getSupportActionBar();
    if (bar != null) {
      bar.setDisplayHomeAsUpEnabled(true);
    }
  }

  @Override public void onClick(View view) {
    if (view.getId() == R.id.signin_button) {
      // login with provided username and password
      String username = m_userNameEditText.getText().toString();
      String password = m_passwordEditText.getText().toString();

      View focusView = null;

      // Check for inputted username & password
      if (!isInputValueValid(username)) {
        focusView = m_userNameEditText;
      } else if (!isInputValueValid(password)) {
        focusView = m_passwordEditText;
      }

      if (focusView != null) {
        // There was an error; don't attempt login and focus the first field with an alert
        focusView.requestFocus();
        Toast.makeText(SMTHApplication.getAppContext(), "请输入用户名/密码！", Toast.LENGTH_SHORT).show();
      } else {
        // 统一走 WebView 登录：https://www.newsmth.net/index.html 填表提交。
        // 水木对 nForum API 的纯密码登录要求验证码（实测返回"验证码错误"），
        // 已不再使用 attemptLoginFromWWW / wService.login()。
        boolean saveinfo = mSaveInfo.isChecked();
        Settings.getInstance().setSaveInfo(saveinfo);
        if (saveinfo) {
          Settings.getInstance().setUsername(username);
          Settings.getInstance().setPassword(password);
        } else {
          Settings.getInstance().setUsername("");
          Settings.getInstance().setPassword("");
        }
        Settings.getInstance().setUserOnline(true);

        Intent intent = new Intent(this, WebviewLoginActivity.class);
        intent.putExtra(USERNAME, username);
        intent.putExtra(PASSWORD, password);
        startActivityForResult(intent, LOGIN_ACTIVITY_REQUEST_CODE);
      }
    }
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
//    Log.d(TAG, "receive login result" + requestCode);
    if (requestCode == LOGIN_ACTIVITY_REQUEST_CODE) {
//      Log.d(TAG, "receive login result");
      if (resultCode == RESULT_OK) {
        Toast.makeText(getApplicationContext(), "登录成功!", Toast.LENGTH_SHORT).show();
        // WebView 登录成功也记录时间，避免 Worker 紧接着再登录一次
        LoginThrottle.onLoginSuccess();
        Settings.getInstance().setLastLoginSuccess(true);
        Intent resultIntent = new Intent();
        setResult(Activity.RESULT_OK, resultIntent);
        finish();
      } else {
        // 只有明确的「密码错误」才清空密码框让用户重输；
        // 其它失败(账号过多/踢出/超时/验证码)保留密码，避免误删。
        String errType = data == null ? null : data.getStringExtra(EXTRA_LOGIN_ERROR_TYPE);
        if (ERR_TYPE_PASSWORD.equals(errType) && m_passwordEditText != null) {
          m_passwordEditText.setText("");
          m_passwordEditText.requestFocus();
        }
      }
    }
  }

  // check input value
  // not null, with size > 0
  private boolean isInputValueValid(String value) {
    if(value == null) return false;
    return value.length() > 0;
  }

  @Override public void onStart() {
    super.onStart();
  }

  @Override public void onStop() {
    super.onStop();
  }

  @Override public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case android.R.id.home:
        onBackPressed();
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }
}

