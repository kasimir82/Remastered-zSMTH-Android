# RzSMTH-Android

水木社区 BBS 的第三方 Android 客户端，基于 [zfdang/zSMTH-Android](https://github.com/zfdang/zSMTH-Android) 修改而来。

本仓库是个人使用的二次开发版本，应用显示名为 **RzSMTH**，用于和上游版本区分。

- 上游基线：`e696dcb`（2026-10-11，Merge PR #65）
- 分支：`master`
- 许可证：沿用上游（见 [LICENSE.md](LICENSE.md)）

> 注意：包名仍是 `com.zfdang.zsmth_android`，签名沿用仓库内的 `app/zsmth.release.jks`，因此安装时会**覆盖**已安装的上游版本，不会与之共存。如需并存，请自行修改 `applicationId`。

## 仓库说明

为控制仓库体积，本仓库未包含以下内容（均不影响构建与运行）：

- `docs/` 下的历史版本安装包（`*.apk`）与演示视频（`zSMTH.mp4`）
- `app/qqwry/` 下的 IP 库源数据（`qqwry.dat`、`qqwry.db` 副本及转换脚本）

运行所需的 IP 归属地数据库仍保留在 `app/src/main/assets/databases/qqwry.db`。如需上述历史资源，请从上游仓库获取。

## 相对上游的改动

### 1. 登录流程改为桌面版页面，移除腾讯验证码

**问题**

上游的 WebView 登录页是 `https://m.newsmth.net/index`，表单提交前需要人工拖动腾讯验证码（表单含 `randstr`、`ticket` 字段）。自动化填充账号密码后仍卡在验证码环节，无法完成"一键登录"。

**改动**

改为加载桌面版老登录页。该页面由 `index.html` 通过 `document.write` 写出 frameset，真正的表单在子框架里，且**没有任何验证码**：

```
https://www.newsmth.net/index.html                     ← JS 写出 frameset
  └─ frame: https://www.newsmth.net/indexpages/default/  ← <form name="form1">，字段 id / passwd
       └─ POST https://www.newsmth.net/bbslogin1203.php  ← 无验证码
```

| 文件 | 改动 |
|---|---|
| `WebviewLoginActivity.java` | 登录页 URL 由 `m.newsmth.net/index` 改为 `www.newsmth.net/index.html` |
| `WebviewLoginClient.java` | 注入逻辑重写：frameset 顶层文档没有 body，原写法 `document.getElementsByName('id')` 取不到表单，改为轮询 `window.frames['mainFrame'].document`，等子框架就绪后填表并 `form1.submit()` |

**成功判定**

上游靠 URL 跳转到 `https://m.newsmth.net/index?m=...` 判断成功，桌面版不会这样跳。改为每秒轮询 Cookie `main[UTMPUSERID]`，一旦不是 `guest` 即判定成功并关闭登录页（最多 25 秒）。

### 2. 修复登录后仍显示「点击登录」

**问题**

`/nForum/user/ajax_session.json` 用于查询当前登录状态。上游请求时只带了 `X-Requested-With: XMLHttpRequest`，缺少 `Referer`，服务端 WAF 返回 **403**，响应体还伪装成「水木社区-IP已被封禁，因为访问过于频繁」，极易误判为 IP 封禁。

结果是：Cookie 完全有效、账号也确实登录成功，但 `MaintainUserStatusWorker` 每次查询都失败，`SMTHApplication.activeUser` 始终是 guest，侧边栏永远显示「点击登录」，自动重登逻辑也从未被触发过。

**改动**（`newsmth/SMTHWWWService.java`）

```java
@Headers({
    "X-Requested-With: XMLHttpRequest",
    "Referer: https://www.newsmth.net/nForum/"
})
@GET("/nForum/user/ajax_session.json")
Observable<UserStatus> queryActiveUserStatus();
```

经实测：只带 `X-Requested-With` → 403；只带 `Referer` → 404；两者都带 → 200。

### 2.1 修正图片 CDN 域名

上游把帖子附件等图片地址补成 `https://static.newsmth.net/...`（`SMTHHelper.java` 的 `SMTH_IMAGE_PREFIX_CDN`），但该域名已不再解析，导致帖子里的图片全部显示失败：

```
static.newsmth.net    → Could not resolve host / ping: unknown host
www.newsmth.net       → 200 image/jpeg
att.newsmth.net       → 200 image/jpeg
static.mysmth.net     → 200 image/jpeg
```

改为 `https://static.mysmth.net`。该域名是当前站点实际使用的 CDN，帖子附件图片恢复正常。

### 2.2 调整字体档位

字体设置里两档过大的倍率调低，避免长文排版溢出：

| 档位 | 原值 | 新值 |
|---|---|---|
| 特大字体 | 135% | 120% |
| 超大字体 | 155% | 135% |

映射在 `Settings.getFontSizeFloatValue()`，显示文字在 `res/values/arrays.xml`，两处已同步。

### 3. 新增「屏蔽设置」

侧边栏「设置」下方新增入口，进入独立页面管理两类规则：

- **屏蔽用户**：作者名不区分大小写完全匹配
- **屏蔽标题**：标题不区分大小写包含关键词即命中

命中的帖子/主题在加载时直接丢弃，不出现在信息流中。覆盖三处列表：

| 列表 | 改动位置 |
|---|---|
| 十大导读 | `TopicListContent.addHotTopic` |
| 版面主题列表（含搜索结果） | `TopicListContent.addBoardTopic` |
| 帖子楼层 | `PostListContent.addItem` |

三个方法由 `void` 改为返回 `boolean`，调用处按返回值决定是否 `notifyItemInserted`，避免索引错位。

**新增文件**

```
app/src/main/java/com/zfdang/zsmth_android/BlockSettingsActivity.java
app/src/main/java/com/zfdang/zsmth_android/helpers/BlockListManager.java
app/src/main/res/layout/activity_block_settings.xml
app/src/main/res/layout/item_block_entry.xml
app/src/main/res/layout/dialog_add_block.xml
app/src/main/res/menu/block_settings_menu.xml
app/src/main/res/drawable/ic_block_white_48dp.xml
```

**存储格式**

规则保存在 `SharedPreferences`（`ZSMTH_BlockList` / `data`）中，单个 JSON 字符串：

```json
{
  "version": 1,
  "users": ["some_user"],
  "keywords": ["广告", "团购"]
}
```

**导入导出**

- 导出：写入 `Download/zsmth_blocklist_yyyyMMdd_HHmmss.json`
  - Android 10+ 走 `MediaStore.Downloads`，无需权限
  - Android 9 及以下走 `Environment.getExternalStoragePublicDirectory`，需要 `WRITE_EXTERNAL_STORAGE`
- 导入：系统文件选择器（SAF），默认定位到 Download 目录，**合并**而非覆盖，完成后提示新增数量

### 4. 应用改名

| 文件 | 改动 |
|---|---|
| `res/values/strings.xml` | `app_name` → `RzSMTH`，登录页标题同步 |
| `SMTHApplication.java` | `App_Title_Prefix` → `"RzSMTH - "` |
| `app/build.gradle` | 产物名改为 `RzSMTH-{debug,release}-v*.apk` |

### 5. 主界面改为底部四栏导航

上游在主界面右下角放了一个悬浮 `z` 按钮，点开后是一个圆形子菜单，里面才是「十大导读 / 收藏夹 / 所有版面 / 邮箱」四个入口。现在改成底部固定导航栏，一键直达：

| 标签 | 对应页面 |
|---|---|
| 首页 | 十大导读 |
| 版面 | 所有版面 |
| 收藏 | 收藏夹 |
| 邮箱 | 邮箱 |

四个入口与悬浮菜单原本的功能完全一致，只是换成了更直观的呈现方式。选中项带圆角高亮背景，对应颜色资源在 `res/color/bottom_nav_item_color.xml` 和 `res/drawable/bottom_nav_item_background.xml`。

改动文件：`app_bar_main.xml`（移除 FAB，加入 `BottomNavigationView`）、`content_main.xml`（留出底部栏空间）、`MainActivity.java`（接入切换监听，移除旧的 `initCircularActionMenu`）、`res/menu/bottom_nav_menu.xml`（新增）。左侧抽屉菜单保持不变，设置、屏蔽设置、关于等入口仍在其中。

底栏做成了通用组件 `res/layout/bottom_nav_bar.xml`，并在 `SMTHBaseActivity` 中提供统一的 `setupBottomNavigation()`。因此版面、帖子、用户资料、邮件内容、登录、发帖、屏蔽设置、儿童安全等页面也都常驻这一栏。在子页面点击标签会回主页（`FLAG_ACTIVITY_CLEAR_TOP`）并切到对应 fragment。

当前标签 id 记录在 `SMTHApplication.currentTabId`，子页面进入时会恢复这个高亮，所以从「收藏」点进版面、帖子后底栏仍停留在「收藏」，不会跳回最左边。帖子页底部的翻页栏与右侧上下翻页按钮都单独抬高了 `bottom_nav_height`，不会被底栏遮住。

全屏图片查看器是全屏手势场景，未加底栏以免遮挡图片。

## 构建

环境要求与上游一致：

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK Platform | 36 |
| Build-Tools | 36.0.0 |
| Gradle | 8.13（由 wrapper 自动下载） |

```bash
echo 'sdk.dir=/path/to/android-sdk' > local.properties
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/RzSMTH-release.apk`（文件名不带版本号），已使用仓库内 `app/zsmth.release.jks` 签名，可直接安装。

版本号规则（`app/build.gradle`）：每次改版 `versionCode` 加一，当前为 1034；`versionName` 手动递增，当前为 26.11.20，不再随构建日期自动变化。

调试版本：

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/RzSMTH-debug-v*.apk
```

> debug 版 `debuggable=true`，可用 `adb shell run-as com.zfdang.zsmth_android` 读取应用私有目录，便于排查登录态和 Cookie。

## 已知问题

1. **被踢下线后不会自动重登**
   `MaintainUserStatusWorker` 的自动重登需要 `saveInfo && lastLoginSuccess && userOnline` 同时成立，但后两个标记只在「口令登录」分支里被置位（`LoginActivity`），网页登录路径不会设置，因此实际永远不会触发。表现为：后台被网站踢掉后，侧边栏静默变回「点击登录」，需要手动登录一次。

2. **屏蔽规则只在加载时生效**
   已经渲染出来的列表不会因为新增规则而实时消失，需要下拉刷新或重新进入页面。

3. **被屏蔽项没有占位**
   命中的帖子直接从列表移除，不显示「已屏蔽」提示，翻页时可能感觉楼层不连续。

## 上游与致谢

本项目全部基础功能来自 [zfdang/zSMTH-Android](https://github.com/zfdang/zSMTH-Android)，包括 RxJava + Retrofit2 + OkHttp3 的网络层、Fresco 图片加载、Jsoup 页面解析等。感谢原作者 zfdang 及所有贡献者。

上游项目地址：<http://zsmth.zfdang.com/>

## 免责声明

本仓库为个人学习与自用目的的修改版本，与水木社区官方无关。请遵守论坛规则，合理使用。
