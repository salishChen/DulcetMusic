# 原生 Android 移植实现说明

> 本文档说明 `android` 分支如何把 `master` 分支的 Flutter 音乐播放器重写为原生 Android。
> 契约（冻结 API / 路由 / 组件签名）见 [`NATIVE_PORT_SPEC.md`](NATIVE_PORT_SPEC.md)。

---

## 1. 技术栈对照

| 能力 | Flutter 版 | 原生版 |
|------|-----------|--------|
| UI | Flutter Widget + Material3 | Jetpack Compose + Material3 |
| 音频播放 | `audioplayers`（`AudioPlayer`） | Media3 **ExoPlayer** |
| 媒体会话/通知栏 | `audio_service`（`BaseAudioHandler`） | Media3 **`MediaSessionService` + `MediaSession`** |
| 媒体按键 | `AudioService.androidForceEnableMediaButtons()` | `MediaSessionService` 的 `MEDIA_BUTTON` intent-filter（系统自动路由） |
| 数据库 | `sqflite`（`music_player.db` v6） | `SQLiteOpenHelper`（同名、同版本、同 DDL、同迁移链） |
| 偏好设置 | `shared_preferences` | `SharedPreferences` + 兼容读取 `flutter.*` 旧键 |
| HTTP/JSON | `http` + `dart:convert` | OkHttp + `org.json` |
| MD5 | `crypto` 包 | `java.security.MessageDigest` |
| 元数据 | `audio_metadata_reader` | `MediaMetadataRetriever` + 自研 ID3v2/Vorbis 歌词解析 |
| 媒体库枚举 | `on_audio_query` | `MediaStore.Audio` |
| 文件选择 | `file_picker` | SAF（`OpenDocumentTree` + `DocumentFile`） |
| 图片加载 | `Image.memory` + 自研 `ArtworkCache` | Compose `Image` + 同一套 `ArtworkCache`（字节级内存缓存） |
| 桌面小组件 | `home_widget` + 原生 `MusicWidgetProvider` | 原生 `MusicWidgetProvider`（直连播放器，移除轮询） |
| 悬浮窗歌词 | MethodChannel + `FloatingLyricsService` | 原生 `FloatingLyricsService`（Kotlin 重写）+ `LyricsOverlayManager` |
| 状态管理 | `ValueNotifier` + `InheritedWidget` | `StateFlow` + `collectAsStateWithLifecycle` |

---

## 2. 模块依赖关系

```
                    ┌──────────────┐
                    │   :app       │  Application / MainActivity / NavHost / 常驻播放栏
                    └──────┬───────┘
        ┌───────────────┬──┴───────────────┬────────────────┐
        ▼               ▼                  ▼                ▼
  :feature:home   :feature:* ×12    :core:designsystem   :core:player
        │               │                  │                │
        └───────────────┴──────────────────┴────────────────┘
                                       │
        ┌──────────────┬───────────────┼──────────────┬─────────────┐
        ▼              ▼               ▼              ▼             ▼
  :core:cache   :core:network    :core:media   :core:database  :core:common
        └──────────────┴───────────────┴──────────────┴─────────────┘
                                       ▼
                                 :core:model
```

规则：
- `core:*` 之间单向依赖，`core:model` 为最底层（纯 Kotlin，无 Android UI 依赖）。
- `feature:*` 依赖 `core:*` 与 `:feature:home`（仅为取 `LocalOpenSidebar` / `LocalSelectPage`），
  **feature 之间不互相依赖**；跨 feature 跳转通过 `core:common` 的 `AppRoutes` 路由字符串完成。
- `:feature:home` 不依赖任何 feature：九个一级页面由 `:app` 通过 `HomeShell { index -> ... }` 的插槽组装。

---

## 3. 逐文件移植对照表

### 3.1 数据层

| Flutter 原文件 | 原生文件 |
|----------------|----------|
| `lib/data/models/song.dart` | `core/model/.../Song.kt` |
| `lib/data/models/album.dart` | `core/model/.../Album.kt` |
| `lib/data/models/artist.dart` | `core/model/.../Artist.kt` |
| `lib/data/models/playlist.dart` | `core/model/.../Playlist.kt`（含 `PlaylistSong`） |
| `lib/data/models/subsonic_config.dart` | `core/model/.../SubsonicConfig.kt` |
| （`queryArtistMeta` 的 Map 返回值） | `core/model/.../ArtistMeta.kt`（强类型化） |
| `lib/data/playlist_data.dart` 的 `PlayMode` | `core/model/.../PlayMode.kt` |
| `lib/data/database_helper.dart` | `core/database/.../MusicDatabase.kt`（建表/迁移）<br>`core/database/.../DatabaseHelper.kt`（门面 + 内存缓存）<br>`core/database/.../dao/{Song,Album,Artist,Playlist,SubsonicConfig,ArtistMeta}Dao.kt`<br>`core/database/.../SongMapper.kt`、`CursorExt.kt` |
| `lib/data/song_data.dart` | `core/database/.../MusicLibrary.kt` |
| `lib/data/subsonic_service.dart` | `core/network/.../SubsonicService.kt`、`SubsonicModels.kt` |
| `lib/data/metadata_service.dart` | `core/media/.../MetadataScanService.kt`、`MetadataParser.kt`、`AudioFormats.kt` |
| `lib/widgets/mp_artwork.dart` 的 `ArtworkCache` | `core/media/.../ArtworkCache.kt` |
| （内嵌歌词标签解析） | `core/media/.../EmbeddedLyricsReader.kt`（ID3v2 USLT/TXXX、Vorbis Comment） |
| `lib/data/cache_service.dart` | `core/cache/.../CacheService.kt` |
| `lib/utils/lrc.dart` | `core/common/.../LrcParser.kt` |
| `lib/utils/themes.dart` | `core/common/.../ThemePreference.kt` + `core/designsystem/.../theme/{Color,Theme}.kt` |
| `lib/data/audio_player_instance.dart` | 合并进 `core/player/.../PlaybackService.kt`（服务内唯一的 ExoPlayer 实例） |

### 3.2 播放层

| Flutter 原文件 | 原生文件 |
|----------------|----------|
| `lib/data/audio_handler.dart`（`MpAudioHandler`） | `core/player/.../PlayerController.kt` |
| （`audio_service` 的前台服务/通知） | `core/player/.../PlaybackService.kt` |
| `lib/data/playlist_data.dart`（`PlaylistData`） | `core/player/.../PlaylistRepository.kt` |
| `audio_handler.dart` 的 `nowPlayingOpen` / `nowPlayingController` | `core/player/.../NowPlayingUiState.kt` |
| `lib/data/lyrics_overlay_manager.dart` | `core/player/.../LyricsOverlayManager.kt` |
| `android/.../FloatingLyricsService.java` | `core/player/.../FloatingLyricsService.kt`（Kotlin 1:1 重写） |
| `lib/data/widget_service.dart` | `core/player/.../MusicWidgetUpdater.kt` |
| `android/.../MusicWidgetProvider.java` / `MusicWidgetUpdateHelper.java` | `core/player/.../MusicWidgetProvider.kt` |

### 3.3 UI 层

| Flutter 原文件 | 原生文件 |
|----------------|----------|
| `lib/main.dart` | `app/.../YuleMusicApplication.kt`、`MainActivity.kt`、`ui/YuleMusicApp.kt` |
| `lib/widgets/mp_nav_scaffold.dart` | `feature/home/.../HomeShell.kt` |
| `lib/widgets/mp_sidebar.dart` | `feature/home/.../Sidebar.kt`（并定义 `LocalOpenSidebar` / `LocalSelectPage`） |
| `lib/widgets/mp_artwork.dart`（组件部分） | `core/designsystem/.../component/Artwork.kt`（`SongArtwork` / `ArtworkImage`） |
| `lib/widgets/mp_song_list_item.dart` | `core/designsystem/.../component/MpSongListItem.kt` |
| `lib/widgets/mp_nav_scaffold.dart` 的 `buildPrimaryAppBar` | `core/designsystem/.../component/PrimaryAppBar.kt` |
| `lib/widgets/mp_control_button.dart` / `mp_circle_avatar.dart` | `core/designsystem/.../component/MpControlButton.kt` / `MpCircleAvatar.kt` |
| `lib/widgets/mp_mini_player_bar.dart` | `core/designsystem/.../component/MiniPlayerBar.kt` |
| `lib/widgets/entity_action_sheet.dart` | `core/designsystem/.../component/EntityActionSheet.kt` |
| `lib/widgets/mp_song_bottom_sheet.dart` | `core/designsystem/.../component/SongInfoBottomSheet.kt` |
| `lib/pages/songs_page.dart` | `feature/songs/.../SongsScreen.kt`、`SongsNavigation.kt` |
| `lib/pages/albums_page.dart` / `album_detail_page.dart` | `feature/albums/.../AlbumsScreen.kt`、`AlbumListItem.kt`、`AlbumDetailScreen.kt`、`AlbumsNavigation.kt` |
| `lib/pages/artists_page.dart` / `artist_detail_page.dart` | `feature/artists/.../ArtistsScreen.kt`、`ArtistListItem.kt`、`ArtistDetailScreen.kt`、`ArtistsNavigation.kt` |
| `lib/pages/playlists_page.dart` | `feature/playlists/.../PlaylistsScreen.kt`、`PlaylistsNavigation.kt` |
| `lib/pages/playlist_detail_page.dart` | `feature/playlists/.../PlaylistDetailScreen.kt` |
| `lib/pages/favorites_page.dart` | `feature/favorites/.../FavoritesScreen.kt`、`FavoritesNavigation.kt` |
| `lib/widgets/music_search_delegate.dart` | `feature/search/.../SearchScreen.kt`、`SearchNavigation.kt` |
| `lib/pages/now_playing.dart` | `feature/nowplaying/.../NowPlayingOverlay.kt`、`NowPlayingControls.kt`、`NowPlayingLyrics.kt`、`NowPlayingPlaylistDrawer.kt`、`NowPlayingSheets.kt`、`NowPlayingGestures.kt` |
| `lib/pages/scan_page.dart` | `feature/scan/.../ScanScreen.kt`、`ScanNavigation.kt` |
| `lib/pages/stats_page.dart` | `feature/stats/.../StatsScreen.kt`、`StatsNavigation.kt` |
| `lib/pages/cache_manage_page.dart` | `feature/cache/.../CacheManageScreen.kt`、`CacheNavigation.kt` |
| `lib/pages/subsonic_config_page.dart` | `feature/subsonic/.../SubsonicConfigScreen.kt`、`SubsonicNavigation.kt` |
| `lib/pages/settings_page.dart` | `feature/settings/.../SettingsScreen.kt`、`SettingsNavigation.kt` |

> `lib/my_app.dart`、`lib/pages/root_page.dart`、`lib/pages/playlist_page.dart`、
> `lib/widgets/mp_drawer.dart`、`lib/widgets/mp_listview.dart` 在 Flutter 版中即为空文件，未移植。

---

## 4. 关键设计决策

### 4.1 播放队列：交给 ExoPlayer 的播放列表
Dart 端 `_resolveNext(forward)` 手写「下一首」并在 `_ensureShuffle()` 中自行打乱顺序。
原生端把 `PlaylistRepository` 的歌曲列表**镜像为 ExoPlayer 的 playlist**，并用官方语义映射播放模式：

| `PlayMode` | Dart 行为 | 原生映射 |
|-----------|----------|---------|
| `SEQUENTIAL` | 顺序播放、越界回到开头 | `repeatMode = REPEAT_MODE_ALL`，`shuffleModeEnabled = false` |
| `RANDOM` | 打乱顺序后按新顺序播放 | `shuffleModeEnabled = true`，`repeatMode = REPEAT_MODE_ALL` |
| `SINGLE` | 始终重复当前歌曲 | `repeatMode = REPEAT_MODE_ONE`；手动「上一首/下一首」仍按 Dart 语义重播当前曲 |

好处：通知栏/蓝牙/线控的上一首·下一首由 Media3 原生接管，无需拦截播放器命令。
队列同步采用「相同则跳过 / 尾部追加 / 尾部截断 / 结构变化时重建并保持当前曲与进度」的增量策略，
避免歌词与封面回填时打断播放。

### 4.2 播放收尾与去重
Dart 的 `_playSeq` 令牌用于防止快速切歌时的异步竞态；原生端改用「媒体项过渡事件」统一驱动收尾
（累计播放次数、预缓存下一首、拉取远程歌词、刷新小组件/悬浮窗），并以 `path` 比对去重，
避免 `playSong` 自己触发的 seek 造成重复计数。单曲循环自动重播通过
`onPositionDiscontinuity(AUTO_TRANSITION, 同一下标)` 识别。

### 4.3 位置更新
Dart 依赖 `audioplayers` 的 `onPositionChanged`（约 200ms）并把平台推送节流到 500ms。
原生端用 200ms 轮询协程更新 `PlayerController.position`，悬浮窗歌词推送同样节流 500ms
（`PLATFORM_POS_INTERVAL_MS`），与原实现一致。

### 4.4 通知栏「歌词」按钮
Media3 `MediaSession` 的 `setCustomLayout(List<CommandButton>)` 复刻了原实现的
「词 / 解锁」按钮与三种图标状态（`ic_lyrics`、`ic_lyrics_active`、`ic_lyrics_locked`），
点击通过 `MediaSession.Callback.onCustomCommand` 分发到 `LyricsOverlayManager.onNotificationToggle()`。

### 4.5 悬浮窗歌词
原 Java 服务整体 Kotlin 化，**偏好键名、广播协议、布局 id、定时器与交互全部保持不变**，
因此旧版本写入的悬浮窗设置（颜色/字号/位置/行数/锁定）可直接复用。
区别仅在于：原生端与 `LyricsOverlayManager` 同进程，直接读写同一 `SharedPreferences`
文件与广播，不再需要 MethodChannel。

### 4.6 桌面小组件
原实现「小组件点击 → 写 `pending_play_pause` 偏好 → 唤起 Activity → Flutter 每 500ms 轮询」的链路，
在原生端简化为「小组件广播 → 直接调用 `PlayerController.togglePlayPauseAsync()`」，
偏好键名 `home_widget.{song_title,is_playing,artwork_path}` 保持不变。

### 4.7 一级页面容器
`HomeShell` 复刻 `MPNavScaffold` 的「拼接式侧边栏」：侧边栏与主页在同一条画布上整体平移，
宽度为屏幕 50%；主页随「正在播放」进度后半段上移 120dp 并淡出。
页面保活用 `rememberSaveableStateHolder`（等价 `IndexedStack` + 滚动位置保留）。

---

## 5. 与 Flutter 版的差异汇总

### 5.1 数据/存储
1. 数据库文件名、版本号、表结构与迁移链完全一致；`PRAGMA foreign_keys = ON` 同样启用。
2. Subsonic 缓存目录固定为 `<dataDir>/app_flutter/subsonic_cache`（与 Flutter 的
   `getApplicationDocumentsDirectory()` 一致），升级后已下载缓存可继续使用。
3. `AppPreferences` 读取时优先新库、回退 `FlutterSharedPreferences` 的 `flutter.*` 键，
   主题/缓存池/上次播放列表可无缝延续。
4. `Song.equals/hashCode` **只比较 `path`**（与 Dart 一致），播放列表去重与当前行高亮依赖该语义。

### 5.2 功能实现差异（有意为之）
| 项 | 差异 | 原因 |
|----|------|------|
| 元数据解析 | `MediaMetadataRetriever` 取代 `audio_metadata_reader`；位深/比特率/采样率在 Android 11 以下可能为 null | 系统 API 能力边界；与原实现"解析库不支持则存 null"一致 |
| 内嵌歌词 | 自研 `EmbeddedLyricsReader`（ID3v2 USLT/TXXX、Vorbis Comment） | 系统 API 无歌词字段 |
| 侧边栏手势 | Dart 用动态 touchSlop 的自定义识别器；原生用「父级 draggable 打开 + 打开态拦截层关闭（可点击空白关闭）」 | Compose 手势竞技场机制不同，交互等价 |
| 列表反馈 | `SnackBar` → `Toast`（部分页面仍用 SnackbarHost） | 无需 Scaffold 宿主，跨页统一 |
| 歌曲行「加入播放列表」 | 同一 Toast 文案 | 语义一致 |
| 远程封面 | 通知栏封面改用 `MediaItem.artworkUri` 指向缓存文件 | Media3 通知栏取图机制 |
| 小组件播放/暂停 | 不再唤起 Activity | 原生同进程直连播放器 |
| 搜索页 | 新增命中关键词高亮与当前播放行高亮 | 体验增强 |
| 详情页 | 专辑/艺术家详情新增头部信息区与操作按钮 | 一级页面无独立顶栏入口时的可用性补充 |
| 主题次要文字色 | 统一 `MaterialTheme.ytTextSecondary` | 深色下可读性 |

---

## 6. 构建与验证

```bash
export JAVA_HOME=<JDK 17+>
echo "sdk.dir=<Android SDK>" > local.properties

./gradlew :core:common:testDebugUnitTest   # 单元测试（LRC 解析）
./gradlew :app:assembleDebug               # 编译全部模块并产出 APK
```

### 6.1 编译与单元测试

- 工具链：`AGP 8.11.1` / `Kotlin 2.2.20` / `compileSdk 36` / `targetSdk 36` / `minSdk 24` /
  `Gradle 8.14` / `JDK 21` / `Compose BOM 2025.10.01` / `Media3 1.9.4`。
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**，产物 `app/build/outputs/apk/debug/app-debug.apk`（约 23.7 MB）。
- `./gradlew :core:common:testDebugUnitTest` → **8 项 LrcParser 测试全部通过**。

### 6.2 模拟器冒烟测试（Pixel_9 / android-37 / x86_64）

`adb install` + `am start` 后逐项验证（`uiautomator dump` + `logcat` + `dumpsys`）：

| 验证项 | 结果 |
|--------|------|
| 冷启动 | 进程存活、`MainActivity` 为 `topResumedActivity`、无 `FATAL EXCEPTION` |
| 数据库兼容 | 直接读到旧版遗留曲库（13 首中文歌曲），说明 `music_player.db` v6 结构兼容 |
| 歌曲页渲染 | 标题「歌曲」、操作条「播放全部 / 随机播放」、歌曲行（歌名 + 艺术家 + 时长）、顶栏按钮（打开侧边栏 / 搜索 / 排序 / 多选）全部出现 |
| 侧边栏 | 点击目录按钮后展开，出现「愉乐 + 歌曲 / 专辑 / 艺术家 / 歌单 / 喜欢 / 扫描音乐 / 远程配置 / 统计 / 设置」九个入口 |
| 播放 | 点歌后 `MediaSession` `BUFFERING → PLAYING`，`position` 持续推进（17ms → 3050ms → 6057ms），播放页覆盖层显示歌名/艺术家/`暂无歌词`/`0:04` / `3:36` |
| 通知渠道 | `NotificationChannel{mId='com.mtechviral.musicfinderexample.audio', mName=音乐播放}`，与旧版一致 |
| 媒体按键会话 | 系统日志 `Media button session is changed to com.mtechviral.musicfinderexample/...` |

冒烟测试发现并修复的缺陷：

1. **跨线程访问 ExoPlayer**：`ensureArtworkFile()` 在 IO 线程调用 `replaceMediaItem()`，触发
   `Player is accessed on the wrong thread`（ExoPlayer 单线程约束）。
   已改为「封面文件只做 IO」+「MediaItem 的 `artworkUri` 在构建时按确定性路径写入」，
   既不跨线程，也不会因替换媒体项打断正在播放的音频。
2. Android 13+ 前台通知不可见：`MainActivity` 首次进入时申请 `POST_NOTIFICATIONS`
   （旧版由 `audio_service` 隐式依赖，未显式申请）。
