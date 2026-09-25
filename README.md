# 愉乐 · 原生 Android 音乐播放器

本分支（`android`）把 `master` 分支上的 Flutter 音乐播放器**完整重写为原生 Android 应用**
（Kotlin + Jetpack Compose + Media3），不再依赖 Flutter 框架。

- 原 Flutter 实现：`master` 分支（原样保留，未改动）
- 软件功能详情（当前实现的行为说明）：[`doc/软件功能详情.md`](doc/软件功能详情.md)
- 优化建议（代码审查与改进计划）：[`doc/优化建议.md`](doc/优化建议.md)

---

## 功能一览（与 Flutter 版一一对应）

| 分类 | 功能 |
|------|------|
| 曲库 | 扫描安卓媒体库 / 扫描指定文件夹（递归、分批解析元数据）、清空曲库 |
| 浏览 | 歌曲、专辑（含详情）、艺术家（含详情）、歌单（含详情）、全部歌曲搜索 |
| 收藏 | 我喜欢（只支持喜欢单曲）、喜欢状态同步 |
| 播放 | 播放 / 暂停 / 上一首 / 下一首 / 拖动进度 / 静音、列表循环 / 随机 / 单曲循环 |
| 播放页 | 手势展开收起的「正在播放」页、封面/歌词切换、逐行歌词高亮、播放列表抽屉 |
| 通知栏 | MediaStyle 通知（上一曲 / 播放暂停 / 下一曲）+ 自定义「词 / 解锁」悬浮歌词按钮 |
| 悬浮窗 | 全局悬浮歌词窗（可拖动、锁定、8 种配色、字号 10~36、单行/双行） |
| 桌面小组件 | 封面 + 歌名 + 播放/暂停，点击回到应用 |
| 远程 | Subsonic 服务器（内网优先 / 公网回退）、远程曲库导入、远程歌单同步、歌词与封面拉取 |
| 缓存 | 远程音频/封面缓存池、LRU 淘汰、缓存管理页（多选删除 / 清空） |
| 统计 | 最常播放、最近播放 |
| 设置 | 主题三态（浅色 / 深色 / 跟随系统）、缓存池大小、缓存我喜欢（喜欢即自动缓存）、悬浮窗歌词开关 |

---

## 工程结构（按职责详细拆分）

```
app/                          宿主壳层：Application、MainActivity、NavHost、常驻迷你播放栏
core/
  model/                      数据实体：Song / Album / Artist / Playlist / SubsonicConfig / PlayMode ...
  common/                     通用工具：LRC 解析、格式化、偏好设置（兼容旧版 flutter.* 键）、路由契约
  database/                   SQLite（music_player.db v6）：建表/迁移/DAO/门面/曲库快照
  network/                    Subsonic REST 客户端（认证、内网优先、歌单同步、歌词/封面）
  media/                      扫描与元数据：MediaStore/文件夹扫描、MediaMetadataRetriever、内嵌歌词、封面缓存
  cache/                      Subsonic 缓存池：下载、LRU 淘汰、封面缓存
  player/                     Media3 播放服务、播放列表仓库、全局控制器、悬浮歌词服务、桌面小组件
  designsystem/               主题（浅色/深色）与通用组件（封面、歌曲行、顶栏、迷你播放栏、操作弹窗）
feature/
  home/                       拼接式侧边栏外壳 + 九个一级页面容器
  songs/ albums/ artists/     歌曲 / 专辑 / 艺术家（含详情）
  playlists/ favorites/       歌单（含详情）/ 我喜欢
  search/ nowplaying/         全库搜索 / 正在播放覆盖层
  scan/ stats/ cache/         扫描音乐 / 统计 / 缓存管理
  subsonic/ settings/         远程配置 / 设置
```

---

## 构建

要求：JDK 17+、Android SDK（compileSdk 36、build-tools 34+）。

```bash
# 1. 配置 SDK 路径
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 编译 Debug APK
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 单元测试（LRC 解析等）
./gradlew :core:common:test
```

---

## 与旧版的数据兼容

`applicationId` 仍为 `com.mtechviral.musicfinderexample`，且数据库文件名、版本号、
表结构、偏好键名全部与 Flutter 版保持一致，因此**可以直接覆盖安装并复用旧数据**：

| 数据 | 位置 | 兼容策略 |
|------|------|----------|
| 曲库 | `/data/data/<pkg>/databases/music_player.db`（version 6） | 表结构/迁移链完全一致 |
| 远程缓存 | `/data/data/<pkg>/app_flutter/subsonic_cache/` | 沿用同一目录，已下载缓存继续可用 |
| 主题 / 缓存池 / 上次播放列表 | `FlutterSharedPreferences`（`flutter.*` 前缀） | `AppPreferences` 优先读新库，回退读旧键 |
| 悬浮窗设置 / 桌面小组件 | `lyrics_overlay_prefs` / `home_widget` | 偏好文件名与键名不变 |
| 通知渠道 | `com.mtechviral.musicfinderexample.audio` | 沿用同一 channelId |
