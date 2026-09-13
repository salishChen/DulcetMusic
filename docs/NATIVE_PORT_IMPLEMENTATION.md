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
| `lib/pages/now_playing.dart` | `feature/nowplaying/.../NowPlayingOverlay.kt`、`NowPlayingControls.kt`、`NowPlayingLyrics.kt`、`NowPlayingPlaylistPage.kt`、`NowPlayingSheets.kt`、`NowPlayingGestures.kt` |
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
Media3 `MediaSession` 的媒体按键偏好（`setMediaButtonPreferences`，旧的 `setCustomLayout` 同时保留）
复刻了原实现的「词 / 解锁」按钮与三种图标状态（`ic_lyrics_word`、`ic_lyrics_word_active`、
`ic_lyrics_word_locked`，图标本体为「词」字，见 §5.4），点击通过
`MediaSession.Callback.onCustomCommand` 分发到 `LyricsOverlayManager.onNotificationToggle()`。

Media3 1.9 让这个按钮出现在通知栏需要同时满足三件事（缺一个按钮就被静默丢弃）：

1. 按钮必须声明槽位：`CommandButton.Builder().setSlots(CommandButton.SLOT_OVERFLOW)`
   —— `CommandButton.getCustomLayoutFromMediaButtonPreferences` 只保留带槽位的按钮；
2. 自定义命令必须在 `MediaSession.Callback.onConnect` 里通过
   `AcceptedResultBuilder.setAvailableSessionCommands(...)` 声明为该控制器可用；
3. 通知栏按钮取自**已连接控制器**的 `getMediaButtonPreferences()`
   （`MediaNotificationManager.updateNotification`），因此 `onConnect` 里要下发一次，
   状态变化时还要对 `session.connectedControllers` 逐个刷新，图标/文案才会实时更新。

> 注意：媒体通知本身由 `MediaSessionService` 负责，但 Media3 只在会话上存在
> **已连接的 `MediaController`** 时才展示通知（见 §5.4），因此 App 侧必须通过
> `MediaController` 而不是直接持有 ExoPlayer。

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
4. `Song.equals/hashCode` **不覆写**（使用 data class 默认的"全字段比较"）。
   `StateFlow` 赋值时按 `equals` 去重：若像 Dart 那样"只比 `path`"，播放中异步回填的
   歌词/封面/缓存状态（同 `path` 的 Song 副本）会被判定为"值未变化"而不发射，
   表现为**首次播放线上歌曲时歌词永不显示**（退出重启后从库里读到歌词才显示）。
   去重语义（播放列表、曲库列表、`LazyColumn` key）全部由调用处以 `path`/`identityKey` 显式实现。

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

### 5.3 播放页修订（第二轮反馈）

| 需求 | 实现 |
|------|------|
| 播放条样式：轨道高 2px、滑块为直径 4px 小圆球 | 弃用 Material3 `Slider`，改为自绘 `NowPlayingProgressBar`（`NowPlayingControls.kt`）：轨道 **2dp**（底轨白 38% / 已播放段纯白），滑块为直径 **4dp** 的白色圆球、圆心正好贴合两端；触控区仍保留 36dp 便于拖动 |
| 播放列表改为同一页面的上下滑动切换 | 恢复 Dart 的竖向 `PageView`：`NowPlayingOverlay` 用两页滑动容器（第 0 页播放页 / 第 1 页播放列表 `NowPlayingPlaylistPage.kt`），两页共用同一张模糊封面背景；上划进入播放列表、列表滚到顶部后继续下滑返回播放页，右下角「播放列表」按钮同样是翻页而不是弹窗 |
| 首次播放线上音乐无歌词 | 根因见 §5.1 第 4 条：`Song` 原先覆写了"仅按 `path` 比较"的 `equals`，`StateFlow` 去重丢弃了"同 `path` 但歌词已回填"的更新。改为 data class 默认全字段比较后即恢复正常 |
| 歌词页按返回键应退出播放页 | `BackHandler`：播放页（含歌词面板已展开）按返回键**直接退出播放页**（与 Dart `WillPopScope` 一致），不再退回封面视图；播放列表页按返回键翻回播放页 |
| 底部手势导航条（沉浸式） | `MiniPlayerBar` 背景一直铺到屏幕底边（`navigationBarsPadding` 只把内容/手势区留在导航条上方），`:app` 外壳改为 `statusBarsPadding`，并在没有歌曲（播放栏不渲染）时给导航区补 `navigationBarsPadding`，消除"播放栏浮在小黑条上方"的断层 |

补充：播放页收起时会重置内部位置（歌词面板回到封面视图、竖向翻页回到第 0 页），
等价 Dart 中"每次打开都新建播放页"的状态语义。

### 5.4 播放页 / 侧边栏 / 通知栏修订（第三轮反馈）

| 需求 | 实现 |
|------|------|
| 播放页 ↔ 播放列表切换不跟手、上滑有延迟 | 见 §5.3 的竖向 `PageView` 改为 `pageAnim`（`Animatable` 0..1）直接驱动两页位移：两页始终参与组合（不再出现"开始滑动才组合播放列表"的首帧卡顿），手势跟手 1:1，并在**接管瞬间补发锁定前累积的行程**（`NowPlayingGestures` 死区补偿），消除 28px 空行程 |
| 方向锁定：左划进歌词的途中下滑会退出播放页 | 横竖两个识别器共用一个 `DragAxisLock`，并要求「本轴位移 ≥ 另一轴 × 1.25」才接管（留出余量）。一旦某个轴向接管，另一轴向在本次手势内不再抢占 |
| 侧边栏右滑卡在中间、松手不再收尾 | 根因：拖动状态里用 `!isOpen`（进度 > 50%）做守卫，越过一半后 delta 被丢弃，`onDragStopped` 也直接 `return`。改为「拖动中持续跟手 + 松手按趋势结算」：`velocity > 220px/s`，或速度很小时进度 > 32%，即完整展开 |
| 「喜欢」页第一个 Tab 右滑打不开侧边栏 | `HorizontalPager` 即使无处可翻也会消费手势（overscroll），主页 `draggable` 收不到事件。新增 `LocalSidebarDrag`（`SidebarDragHandle`）+ `nestedScroll` 转发：把分页器**消费后剩余**的横向位移/速度交给主页；分页器能真正翻页时剩余量为 0，不会误触 |
| 通知栏没有播放控制器 | 根因：Media3 的 `MediaNotificationManager.shouldShowNotification` 要求会话上存在**已连接的 `MediaController`**，而之前 App 直接持有服务内的 ExoPlayer 实例、从未建立控制器连接，于是通知与系统媒体控制中心都不会出现。现改为 `PlayerController` 通过 `MediaController`（`SessionToken` + `buildAsync`）下发全部播放命令，服务侧不再把 ExoPlayer 暴露给 App |
| 悬浮歌词图标应为「词」字 | 通知栏自定义按钮改用 `ic_lyrics_word{,_active,_locked}.xml`（由系统字体「词」的字形轮廓生成的 VectorDrawable，生成脚本 `tools/gen_word_icon.ps1`），保留对勾 / 小锁角标；播放页底部按钮同样改为「词」字（锁定时带小锁角标） |
| 悬浮歌词不实时刷新、停在第一句 | 服务原先只依赖 `ACTION_UPDATE_STATE` 广播同步位置；广播一旦未送达，位置就永远停在展开悬浮窗那一刻。现增加**轮询兜底**：200ms 定时器每次都从 SharedPreferences 同步歌词/位置/播放状态。另：暂停/继续时也推送播放状态，避免暂停后仍按"播放中"外推位置 |
| 播放列表小幅下滑不返回播放页 | 翻页收尾规则自定义：从播放列表下滑时，只要手势成立（方向锁 + 28dp）就**完整翻回播放页**，不再按幅度吸附回播放列表；上滑进播放列表仍保留就近/速度判定（超过 12% 或带向上速度即完成） |

### 5.5 通知栏「词」按钮（第四轮反馈）

需求：在通知栏媒体控制器里增加一个控制桌面（悬浮）歌词显示的按钮。

现象：通知栏只有「上一曲 / 暂停 / 下一曲」三个按钮，看不到歌词开关。用 `javap` 反编译
Media3 1.9.4 定位到三个必要条件，缺任何一个按钮都会被**静默丢弃**：

| 条件 | 依据 | 修法 |
|------|------|------|
| 按钮必须声明槽位 | `CommandButton.getCustomLayoutFromMediaButtonPreferences` 只保留带 `slots` 的按钮（无槽位直接跳过） | `CommandButton.Builder().setSlots(CommandButton.SLOT_OVERFLOW)` |
| 自定义命令必须声明为可用 | 会话按"该控制器可用命令"过滤媒体按键偏好 | `MediaSession.Callback.onConnect` 返回 `AcceptedResultBuilder(session).setAvailableSessionCommands(DEFAULT_SESSION_COMMANDS + toggle_lyrics)` |
| 通知栏按钮取自**已连接控制器**的偏好 | `MediaNotificationManager.updateNotification` 取 `getConnectedControllerForSession(session).getMediaButtonPreferences()` | `onConnect` 里 `setMediaButtonPreferences(...)` 下发一次；`refreshLyricsButton()` 再对 `session.connectedControllers` 逐个刷新，图标/文案随悬浮歌词状态实时变化 |

真机实测（小米 14 / Android 16）：通知栏媒体控制器由 3 个动作变为 4 个 ——
`上一曲 / 暂停 / 下一曲 / 解锁`（悬浮歌词处于"已开启已锁定"时显示「解锁」，否则显示「词」）；
`dumpsys media_session` 同时可见 `custom actions=[Action:mName='解锁, mIcon=...]`。

### 5.6 桌面歌词：居中 / 锁定语义 / 独立设置页（第五轮反馈）

| 需求 | 实现 |
|------|------|
| 桌面歌词应居中，现在偏右 | 根因：偏好里 `pos_x` 是被历史版本写入的越界值（818，而 1200px 屏 + 1080px 窗宽时合法范围仅 0~120），窗口于是被推到屏幕右侧。现在创建窗口时校验坐标：**放不下整个窗口就恢复水平居中**；拖动时把坐标夹在屏幕内（拖不出去）；设置页另加「重新居中」一键摆正 |
| 锁定后点击歌词不应弹出配置框 | 恢复原语义：`ACTION_UP` 只有在 `!isLocked` 时才 `toggleSettings()`（锁定即不响应点击） |
| 锁定后点「词」按钮先解锁、再点才关闭 | 该状态机本来就由 `LyricsOverlayManager.onNotificationToggle()` 实现并同时用于播放页按钮与通知栏按钮：`未显示 → 显示`、`已显示且锁定 → 解锁`、`已显示未锁定 → 关闭`。本轮只是把"锁定时点击悬浮窗弹面板"这条意外通路去掉，使解锁入口唯一、行为可预期 |
| 设置页新增「桌面歌词」页 | 新增二级页 `LyricsOverlaySettingsScreen`（路由 `AppRoutes.LYRICS_OVERLAY_SETTINGS`，设置页入口「桌面歌词」）：实时预览 + 显示开关 + 字号(10~36) + 字体粗细(细/常规/粗) + 文字颜色(8 预设) + 行数(单行/双行) + 位置锁定 + 重新居中 |

配套改动：字重偏好新增 `font_weight`（默认粗体，保持原观感），悬浮窗 `applyLyricsStyle()` 按
`细 = sans-serif-light / 常规 = sans-serif / 粗 = sans-serif bold` 设置 `Typeface`；
设置页与服务共用 `LyricsOverlayManager` 的颜色/字号/粗细/行数/锁定状态，任一侧修改都会写偏好 +
发 `ACTION_UPDATE_STATE`，**正在运行的悬浮窗立即生效**；悬浮窗口内的面板改为「拖动字号实时预览、
松手落库」，颜色/锁定改动即时同步回设置页。

### 5.7 桌面歌词：解锁通道与触摸穿透（第六轮反馈）

| 需求 | 实现 |
|------|------|
| 「词」按钮只能控制显示/隐藏，控制不了解锁 | 根因：服务与设置页/通知栏在**同一个进程**，但 `sendBroadcast(隐式 Intent)` 在真机上送不到动态注册的接收者（logcat 里一条 `Broadcast received` 都没有）。于是 `toggleLock()` 只改了管理器内存状态，服务里的 `isLocked` 仍是 true、窗口依旧锁着；下一次点「词」时管理器认为"已解锁"→ 直接走关闭分支，表现为"只能显隐"。修法：新增同进程可靠通道 —— `LyricsOverlayManager.settingsRevision`（StateFlow 计数），所有设置 setter（含 `setLocked`/`toggleLock`）都会 bump；服务 `observeSettings()` collect 后重新 `loadSettings()` 并 `applyLyricsStyle / applyLinesCount / applyLockState`。广播仅作兜底，且 `toggleLock()` 现在直接写偏好（不再依赖服务写） |
| 锁定后不应阻挡点击事件 | `applyLockState()` 同步切换窗口标志：锁定 → `FLAG_NOT_TOUCHABLE`（触摸穿透到下层应用），解锁 → 去掉该标志；窗口创建时就按当前锁定态设置标志（此时还没 `addView`，不能用 `updateViewLayout`）。因此锁定后既不响应点击/拖动，也不会挡住播放页或列表的点击 |

至此「词」按钮的完整语义（播放页底部按钮与通知栏按钮共用同一实现）：
`未显示 → 显示`、`已显示且锁定 → 解锁（窗口恢复可拖动）`、`已显示未锁定 → 关闭`。



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
- 第二轮反馈修订（播放条样式 / 竖向翻页播放列表 / 歌词回填 / 返回键 / 沉浸式导航条，见 §5.3）后
  重新执行 `./gradlew :app:assembleDebug :core:common:testDebugUnitTest` → **BUILD SUCCESSFUL**，
  产物 `app-debug.apk`（约 22.6 MB）。
- 第三轮反馈修订（跟手翻页 / 方向锁定 / 侧边栏收尾 / 喜欢页右滑 / 通知栏播放控制器 /
  「词」图标 / 悬浮歌词实时刷新 / 小幅下滑返回，见 §5.4）后再次执行
  `./gradlew :app:compileDebugKotlin`、`:app:assembleDebug`、`:core:common:testDebugUnitTest`
  → 均 **BUILD SUCCESSFUL**，产物 `app-debug.apk`（约 24.4 MB，
  含新生成的「词」字形 VectorDrawable）。
- 第四轮反馈修订（通知栏「词」按钮，见 §5.5）后 `./gradlew :app:assembleDebug`
  → **BUILD SUCCESSFUL** 并真机复测。
- 第五轮反馈修订（桌面歌词居中 / 锁定语义 / 「桌面歌词」设置页，见 §5.6）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest` → **BUILD SUCCESSFUL**；
  真机确认悬浮窗窗口帧为 `(60,37)(1080xwrap)`（1200px 屏左右各留 60px，水平居中），
  新设置页正常渲染并正确回显已存偏好（颜色 / 字号 19 / 双行 / 锁定）。

### 6.1.1 真机复测（小米 14 / 23127PN0CC / Android 16）

| 验证项 | 结果 |
|--------|------|
| MediaController 通道 | logcat 出现 `MediaController: Init`、`ExoPlayerImpl: Init`、`MediaSessionImpl: Init`（同进程 pid） |
| 播放是否正常 | `dumpsys media_session`：先 `BUFFERING/READY`→`PLAYING(3)`，`position` 由 0 持续推进到 152596ms；说明改走 `MediaController` 后播放控制完全正常 |
| 媒体通知 | 首次出现通知渠道 `com.mtechviral.musicfinderexample.audio`（音乐播放），通知 `category=transport`、`flags=FOREGROUND_SERVICE`、`template=MediaStyle` |
| 通知按钮 | 修复前 `actions=3`（上一曲/暂停/下一曲）；修复后 `actions=4`，第 4 个为「解锁」（悬浮歌词"已开启已锁定"时的文案），`dumpsys media_session` 同步可见 `custom actions=[Action:mName='解锁, ...]` |
| 音频焦点 | 播放时 `AudioFocus stack` 顶部为本应用（`gain: GAIN, loss: none`），从哔哩哔哩接管焦点 |

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
