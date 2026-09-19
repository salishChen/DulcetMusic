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
| （无对应；Dart 删除时不清理播放列表） | `core/player/.../LibrarySongDeleter.kt`（第十二轮新增，见 §5.13） |
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
| `lib/pages/now_playing.dart` | `feature/nowplaying/.../NowPlayingOverlay.kt`、`NowPlayingControls.kt`、`NowPlayingLyrics.kt`、`NowPlayingPlaylistPage.kt`、`NowPlayingGestures.kt` |
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
| `RANDOM` | 打乱顺序后按新顺序播放 | **列表本身重排**（进入随机时打乱 `PlaylistRepository` 的列表），`shuffleModeEnabled = false`，`repeatMode = REPEAT_MODE_ALL` |
| `SINGLE` | 始终重复当前歌曲 | `repeatMode = REPEAT_MODE_ONE`；手动「上一首/下一首」仍换曲（见 §5.15） |

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
| 侧边栏手势 | Dart 用动态 touchSlop 的自定义识别器；原生用「侧边栏与主页各自挂 draggable + 打开态拦截层关闭（可点击空白关闭）」 | Compose 手势竞技场机制不同，交互等价（见 §5.18） |
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

### 5.8 性能与稳定性（第七轮反馈）

| 现象 | 根因 | 修法 |
|------|------|------|
| 主页右滑拉出侧边栏 / 侧边栏左滑回主页 明显卡顿 | `HomeShell` 在**组合期**读取 `sidebarProgress.value` 来判断 `isOpen`，拖动与补间期间每帧都会重组整页（连同 LazyColumn 的所有可见行） | 改为 `derivedStateOf { progress > 0.5f }`：只有跨过 50% 才重组一次；位移仍由 `graphicsLayer` 延迟读取，跟手不受影响 |
| 同上（第二个来源） | 播放页展开进度用 `collectAsStateWithLifecycle()` 收集，300ms 展开/收起补间期间同样每帧重组整页 | 改为镜像到 `mutableFloatStateOf`，并**只在外层 `graphicsLayer` 里读取**（延迟读取不再触发重组） |
| 歌曲列表上下滚动卡顿 | `ArtworkImage` 在 `produceState` 主体（主线程）里直接 `BitmapFactory.decodeByteArray` 全尺寸解码；每滚进一行卡一次，划出再划回还要重新解码 | ① 读字节与解码全部移到 `Dispatchers.Default`；② 新增**带字节预算（24MB）的 LRU 已解码位图缓存**；③ 列表缩略图传 `maxSizePx`，用 `inSampleSize` 按显示尺寸采样解码（歌曲行 50dp、多选行 40dp、播放列表行 48dp、艺术家 120dp、专辑网格半屏宽等调用点） |
| 缓存远程音频偶发崩溃 | `CacheService.download()` 用 `ResponseBody.bytes()` 把整首歌读进内存，真机缓存 85MB 文件时抛 `java.lang.OutOfMemoryError`（okio readByteArray） | 改为**流式落盘**：先写 `.tmp`（64KB 缓冲循环拷贝）再重命名，内存占用恒定，且下载中断不会留下"半个缓存文件"被当成命中 |

### 5.9 喜欢即自动缓存（第八轮反馈，第九轮改为可选开关）

需求：标记为喜爱的音乐应当自动缓存（离线可听）。

实现：`CacheService.autoCacheLiked(songId)` —— 读取该歌曲，若「已喜欢 + 远程 + 尚未缓存（或封面未缓存）」
就调用现有 `startCaching(song)` 后台下载音频与封面，完成后 `MusicLibrary.updateSong(...)`
把"已缓存"状态合并回曲库快照，列表上的缓存角标随即出现。

> **第九轮变更**：该行为已改为受设置页「缓存我喜欢」开关控制（见 §5.10），**默认开启**以保持历史行为。

接入点（覆盖所有"喜欢"入口，均在 `wasLiked == false` 即"刚刚变为喜欢"时触发）：

| 入口 | 位置 |
|------|------|
| 歌曲操作弹窗 | `EntityActionSheet`（仅 `SongTarget` 显示喜欢项，见 §5.10） |
| 歌曲信息底部弹窗 | `SongInfoBottomSheet` |
| 播放页红心按钮 | `NowPlayingOverlay.toggleLike` |

约定：**取消喜欢不删除已有缓存**（删除交给「缓存管理」页）；本地歌曲本就在磁盘上，直接跳过；
已缓存且封面齐全的歌曲不重复下载。相应提示文案也会带上"将自动缓存"（如「已添加到喜欢，将自动缓存」）。

> 说明：只对"新标记喜欢"的动作生效；本功能上线前就已经喜欢的歌曲不会自动补缓存，
> 需要在「缓存管理」页手动缓存，或后续按需增加"一键缓存全部喜欢歌曲"。

### 5.10 喜欢只针对单曲 + 「缓存我喜欢」开关（第九轮反馈）

需求 1：**喜欢功能只支持喜欢某首歌**，不再支持专辑与艺术家。
需求 2：设置页新增「缓存我喜欢」——开启时，把音乐添加到喜欢里会自动缓存至本地。

#### ① 喜欢收敛为「仅单曲」

原先喜欢有三层语义：歌曲（`songs.isLiked`）、专辑（= 其下每首歌逐首喜欢）、
艺术家（`artists_meta.isLiked` + 其下每首歌逐首喜欢）。现全部收敛为**只写单曲的 `songs.isLiked`**：

| 位置 | 变更 |
|------|------|
| `EntityActionSheet` | 喜欢项由"album/artist/song 均有"改为**仅 `SongTarget(listOf(song))` 显示**；专辑/艺术家目标不再出现该入口。判定条件为 `(entity as? SongTarget)?.songs?.singleOrNull()`，因此多选集合也不会被逐首喜欢 |
| `FavoritesScreen` | 由「音乐 / 专辑 / 艺术家」三 Tab（`TabRow` + `HorizontalPager`）改为**单个喜欢歌曲列表**；随分页器一并移除 `LocalSidebarDrag` nestedScroll 转发（只剩竖向列表，横向右滑由 `HomeShell.draggable` 直接接管） |
| `FavoritesNavigation` / `YuleMusicApp` | 不再向 `FavoritesScreen()` 注入 `onOpenAlbum` / `onOpenArtist`（专辑/艺术家 Tab 已不存在） |
| `DatabaseHelper` | 删除 `queryLikedAlbums()` / `queryLikedArtists()` / `toggleLikeArtist(name)` |
| `AlbumDao` / `ArtistDao` / `ArtistMetaDao` | 删除对应的 `queryLikedAlbums()` / `queryLikedArtists()` / `toggleLikeArtist()` 实现（`artists_meta.isLiked` 列保留，不迁移数据库） |

> 数据库结构**未改动**：`songs.isLiked` 与 `artists_meta.isLiked` 列都保留。
> 「喜欢」页只查 `songs.isLiked = 1`，因此旧版本遗留的"喜欢专辑/艺术家"数据不会再显示，
> 但降级回旧版本时那些数据仍在（无破坏性迁移）。

#### ② 设置页「缓存我喜欢」

新增偏好键 `auto_cache_liked`（`AppPreferences.KEY_AUTO_CACHE_LIKED`，旧版本无此键 → 默认 `true`）。

| 层 | 实现 |
|----|------|
| `CacheService` | 持有 `MutableStateFlow<Boolean> autoCacheLiked`（默认 true），`init()` 时从偏好恢复；提供 `isAutoCacheLikedEnabled()` / `setAutoCacheLiked(enabled)`（写状态 + 落偏好） |
| 行为开关 | `autoCacheLiked(songId)` **入口即判断**：关闭时直接 `return`（打日志），不做任何下载 |
| `SettingsScreen` | 「缓存池大小」下方新增开关行（`Icons.Filled.Download` + 标题「缓存我喜欢」+ 副标题「把音乐添加到喜欢时，自动缓存到本地，离线也能播放」），用 `collectAsStateWithLifecycle` 订阅 `CacheService.autoCacheLiked`，切换即时生效 |
| 提示文案 | 三个喜欢入口的 Toast 在开关**关闭时不再声称"将自动缓存"**（`live.isRemote && autoCache` 才提示） |

### 5.11 播放页 / 播放列表的「更多」弹窗收敛（第十轮反馈）

需求：**播放列表每行三个点**与**播放页右上角三个点**弹出的菜单里，去掉「播放 / 添加到播放队列 /
添加到歌单」，并移除弹窗右上角的关闭按钮。**仅限这两个入口**，其他页面（歌曲页 / 专辑 / 艺术家 /
歌单详情）的同一弹窗保持完整。

关键点：这两个入口**共用同一个 `EntityActionSheet` 实例**（`NowPlayingOverlay` 中的
`actionSheetSong` 状态：播放页顶栏 `onMore` 与播放列表行 `onMoreSong` 都写它），
因此只需改这一处调用即可同时覆盖两个入口。

> 后续变更（第二十轮，见 §5.21）：播放列表每行的「更多」已改为**减号按钮**
> （直接移出队列、不再弹窗），因此该 `EntityActionSheet` 现在**只由播放页顶栏打开**；
> 其「从播放列表移除」按 `currentIndex` 删除当前曲。本节的参数约定本身仍然有效。

实现（`core:designsystem` 的 `EntityActionSheet` 增加两个**默认保持原行为**的参数，避免影响其他调用方）：

| 参数 | 作用 |
|------|------|
| `hiddenItems: Set<EntityActionItem>` | 按项隐藏菜单。新增枚举 `EntityActionItem { PLAY, ADD_TO_QUEUE, ADD_TO_PLAYLIST }`；播放页传三项全隐藏 |
| `showHeaderCloseButton: Boolean = true` | 为 false 时不渲染标题栏右上角关闭按钮（仍可点遮罩 / 返回键 / 点任一选项关闭） |

播放页调用点相应地不再传 `onPlay` / `onPlayNext` / `onAddToPlaylist`（已无对应菜单项），
弹窗最终只剩：**喜欢 / 取消喜欢**、**缓存歌曲**（仅远程未缓存时）、**从播放列表移除**。

随之清理的死代码：`NowPlayingOverlay` 不再需要「添加到歌单」选择器状态 `addToPlaylistSongs`；
`feature/nowplaying/.../NowPlayingSheets.kt`（仅含 `NowPlayingAddToPlaylistDialog`）已无调用方，整文件删除。

> 注：其他页面仍有「添加到歌单」入口，用的是各 feature 自己的 `AddToPlaylistSheet` / 对话框，
> 与本次删除的 `NowPlayingSheets.kt` 无关（后者是播放覆盖层专用、无 Scaffold 环境的 Toast 版）。

### 5.12 底部迷你播放栏文字颜色随深色模式切换（第十一轮反馈）

**现象**：深色模式下底部播放栏（`MiniPlayerBar`）内的歌曲名仍是黑色，落在深色栏体上难以辨认；
副标题（艺术家名）正常。

**根因**：Compose 的 `MaterialTheme.typography.titleMedium` **只含字号/字重等度量信息，不含颜色**
（`TextStyle.color` 为 `Unspecified`）。此时 `Text` 会回退到 `LocalContentColor.current`，
而该 CompositionLocal 的**默认值是 `Color.Black`**；只有 `Surface`/`Scaffold` 等组件才会把它
设为对应的 `contentColor`。本栏位于 `YuleMusicApp` 的 `Box`/`Column` 之下，
**没有任何 `Surface` 祖先**，因此永远拿不到主题文字色 —— 与深色模式无关，浅色下恰好也黑字而"看起来正常"。
副标题之所以正常，是因为它显式写了 `color = secondaryText`（`onSurfaceVariant`）。

> 对照 Dart：Flutter 的 `Text` 颜色由 `DefaultTextStyle` 提供，其 `titleMedium` 在
> `ThemeData.textTheme` 中自带 `bodyColor`，所以 Dart 版无需写法也正常 —— 这是两端的默认值语义差异。

**修复**（`core/designsystem/.../component/MiniPlayerBar.kt`）：新增
`val titleText = MaterialTheme.colorScheme.onSurface`，并显式赋给栏内两处原本无色的标题
（有歌曲时的 `track.title`、空态的「愉乐」占位标题）。栏内 4 个 `Text` 现均带显式主题色
（`onSurface` / `onSurfaceVariant` / `primary`），不再依赖 `LocalContentColor` 的隐式回退。



### 5.13 删除正在播放的歌曲时同步播放列表并接续播放（第十二轮反馈）

**需求**：正在播放某首歌时，若在"歌曲"页面把它删除，播放列表里也应删掉这首歌，
并播放下一首。

**为什么原来做不到**：删除与播放队列是两套独立结构 —— `DatabaseHelper`（曲库）与
`PlaylistRepository`（播放列表，再镜像到 ExoPlayer 队列）。原来的删除路径只写曲库，
播放列表里会留下一首**已不存在**的歌；而且 `PlayerController.syncQueue` 的收尾分支
一旦发现"当前媒体项不在新列表里"，只调 `onCurrentSongRemoved()` 清空展示状态，
并把播放位置重置到队首 `0`，不会接续播放。

> 对照 Dart：`songs_page.dart::_deleteSelected` 与 `mp_song_bottom_sheet.dart` 的「永久删除」
> 同样只 `deleteSong` + `reload()`，**不碰播放列表**（`playlist_data.dart` 的 `removeSong`
> 只由播放列表自身的 `Dismissible` / 菜单触发）。因此本条是**按需求刻意超出 Dart 参考实现**的改进。

**实现**：

1. `PlaylistRepository.removeSongs(paths: Collection<String>): Int`（新增，`removeSong`
   改为委托它）—— 按 `path` 批量移出，整批只 `notify` 一次，避免逐首移除导致队列被反复重建。
2. `LibrarySongDeleter`（新增，`core:player`）—— 曲库永久删除的唯一入口：
   逐首 `DatabaseHelper.deleteSong(id)`，随后 `PlaylistRepository.removeSongs(paths)`。
   队列侧的接续播放交给既有的 `PlaylistRepository.songs → PlayerController.syncQueue` 通道，
   调用方无需直接操作 ExoPlayer。
3. `PlayerController.syncQueue` 重构 —— 新增"纯移除"判定
   （`newIds.size < existingIds.size && isSubsequence(newIds, existingIds)`）：
   - 当前项**存活**：改用 `removeMediaItem(i)` 从后往前精确删除被移除项。
     播放与进度完全不受影响（原实现走 `setMediaItems` 整队重建，会打断播放）。
   - 当前项**被移除**：由 `successorIndexAfterRemoval` 算出**原本紧随其后的那首**
     （其后已无存活项则绕回列表开头），`setMediaItems(songs, successor, 0L)` 后
     恢复 `playWhenReady = wasPlaying` —— 原本在播放就接续播下一首，原本暂停则保持暂停
     （删除不应擅自开始播放）。
   - 队列清空分支补 `onCurrentSongRemoved()`：否则播放栏会继续展示一首已不存在的歌。
4. 队列重建会触发 `MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED`，监听器据此跳过
   `postPlayWork`（累计播放次数 / 预缓存下一首 / 拉歌词）。接续的歌曲是首次播放，
   故新增 `postWorkAfterQueueRebuild` 标志，在重建前置位、由 `handleCurrentItemChanged` 消费一次。
5. 接线上：`EntityActionSheet`（`deleteFromLibrary = true` 分支）、`SongInfoBottomSheet`
   的「永久删除」、`SongsScreen` 的批量删除确认框，全部改调 `LibrarySongDeleter.delete(...)`。
   公开签名均未变化（`docs/NATIVE_PORT_SPEC.md` 只登记了新增的 `removeSongs` / `LibrarySongDeleter`）。

**行为覆盖**：删当前播放项（接续下一首 / 删的是最后一首则绕回开头）、删非当前项
（仅移出，播放不受影响）、批量删除同时命中多项、删空整个列表（清空当前歌曲状态）。
`ScanScreen` 的「清空曲库」原本就是 `clearSongs()` + `PlayerController.stopAndClear()`，无需改动。

### 5.14 详细歌词面板纵向内缩（第十三轮反馈）

**需求**：播放页左滑进入的歌词展示区，顶部下移 10px、底部上移 30px，纵向整体缩小 40px。

**实现**（`feature/nowplaying/.../NowPlayingLyrics.kt`）：新增两个常量
`LYRICS_PANE_TOP_INSET = 10.dp` / `LYRICS_PANE_BOTTOM_INSET = 30.dp`，在
`NowPlayingLyricsPane` 内以 `Modifier.padding(top, bottom)` 施加到**歌词内容**
（`LazyColumn` 与空态的「暂无歌词」）。

**为什么加在内容而不是外层 `Box`**：外层保留 `fillMaxSize()`，因为它的
`pointerInput { detectTapGestures { onBackgroundTap() } }` 承担「点击空白返回封面视图」。
若把 padding 加在外层，顶部 10px / 底部 30px 将变成点击死区，与既有交互不符。

**与 Dart 的差异**：Dart `_buildLyricsPage`（`now_playing.dart` 第 900-945 行）的纵向留白
来自「`Expanded` 内的 `ListView` + 底部固定 `SizedBox(height: 50.0)`」——
即只有底部 50px，**顶部无额外留白**。本轮按反馈改为「顶部 10 + 底部 30」，
是刻意的观感调整，非 1:1 复刻。

### 5.15 单曲循环下手动切歌应换曲（第十四轮反馈）

**现象**：选择「单曲循环」后，点上一曲 / 下一曲都是重播本曲。

**根因**：`PlayerController.skipToNext()` / `skipToPrevious()` 里各写了一个
`PlayMode.SINGLE` 分支，直接 `p.seekTo(_currentIndex.value, 0L)` 重播当前曲。

该分支是对 Dart `_resolveNext(single)` 的**字面直译，但迁移前提已变**：Dart 里
「播完自动续播」（`_onComplete`）与「手动切歌」（`skipToNext`）**共用同一个
`_resolveNext`**，所以 Dart 的单曲分支同时管住了两者。原生端这两条路径是分开的 ——
自动续播由 ExoPlayer 的 `REPEAT_MODE_ONE` 负责，手动切歌是独立方法，于是那个分支
变成了纯粹的副作用。

**Media3 语义确认**（读 `media3-common` 1.9.4 源码）：
`BasePlayer.getNextMediaItemIndex()` 走 `getRepeatModeForNavigation()`，
而该方法在 `repeatMode == REPEAT_MODE_ONE` 时**返回 `REPEAT_MODE_OFF`**（`BasePlayer:400-403`）。
即 **Media3 本来就认为"单曲循环只约束自动续播，手动切歌照常换曲"**。
`seekToNextMediaItem()` 同理（`seekToNextMediaItemInternal`）。

> 顺带确认：通知栏 / 耳机按键走 `MediaSession` → `PlayerWrapper.seekToNextMediaItem()`
> （`MediaSessionLegacyStub:655-660`），从不经过本类的方法，
> 所以**那条路径原本就是对的**，此前的 bug 只影响 App 内点按。

**修复**（`core/player/.../PlayerController.kt`）：删除两个 SINGLE 分支，
改为统一走新增的 `resolveManualSkipIndex(p, forward)`：

1. 取 `p.nextMediaItemIndex` / `p.previousMediaItemIndex`（Media3 已按上述语义算好）；
2. 若为 `C.INDEX_UNSET`（单曲循环下到队列首/尾时会发生，因为导航按 `REPEAT_MODE_OFF`
   计算，不再环绕），**环绕到另一端**（`0` / `mediaItemCount - 1`），
   与「列表循环」的按键手感保持一致；队列只有一首歌时即重播本曲
   （与 Dart 单曲分支的效果一致，覆盖"只有一首歌"的既有预期）。
3. 再 `p.seekTo(target, 0L)` + `p.play()`，并同步 `_position = 0`。

`PlayMode` / `PlaylistRepository` 公开签名无变化，仅行为修正。

---

### 5.16 随机播放应重排歌曲列表本身（第十五轮反馈）

**需求**：进入随机播放模式时，应当随机排序**当前的歌曲列表**，然后顺序播放随机后的列表。

**原实现**：`applyPlayMode(RANDOM)` 只设置 `shuffleModeEnabled = true`。
这**不会改变 `PlaylistRepository.songs` 的顺序**——ExoPlayer 内部维护一条 shuffle
顺序来取歌，但列表本身原封不动。于是"随机"既看不出排成了什么顺序，界面上的播放列表
顺序也与实际播放顺序不一致。

**Dart 对照**：Dart 的 `_ensureShuffle()`（`audio_handler.dart:415`）也是把当前曲放队首、
其余打乱，但它**只写私有的 `_shuffleOrder` 下标序列，从不改 `playlistData.songs`**
（`_resolveNext` 的 random 分支按下标 `_shuffleOrder[_shufflePos]` 取歌，
`audio_handler.dart:461-467`）。因此本轮是**按要求刻意偏离 Dart**：真正重排列表本身。

**修复**：

1. `PlaylistRepository.setPlayMode(mode)`：切入 `RANDOM` 且列表多于一首时，
   直接 `notify(_songs.value.shuffled())` —— 列表顺序即播放顺序。
   仅从**非随机**切入随机时才打乱（避免随机态内重复点按把列表反复打乱）。
2. 新增 `PlaylistRepository.playShuffled(songs): List<Song>`：「随机播放」按钮的入口，
   一次 `notify` 完成"切到 RANDOM + 存入打乱后的列表"，并返回实际顺序。
   若按旧写法「先 `setPlayMode` 再 `setSongs`」两步走，会用旧列表和新列表各重建一次
   播放队列，白白多打断一次正在播放的音频。
3. 新增 `PlayerController.playShuffled(songs, startIndex = 0)`：取 `playShuffled`
   返回的顺序从第 0 首开始播，**避免二次打乱**（旧代码是
   `setPlayMode(RANDOM)` + `playSongs(songs.shuffled(), 0)`，模式与列表各自打乱一次）。
4. `applyPlayMode(RANDOM)`：`shuffleModeEnabled = **false**`，仍 `REPEAT_MODE_ALL`。
   列表已被重排，若再打开播放器 shuffle，会在打乱后的列表上**再乱序一次**，
   与界面显示的顺序不一致。
5. `syncQueue` 新增**纯重排**分支：同一批歌曲、数量一致、仅顺序不同时，
   用 `setMediaItems(items, newIndex, resumePosition)` 把新顺序真正下发，
   保持当前曲与播放进度，并**显式同步 `_currentIndex`**。
   原因（读 `media3-exoplayer` 1.9.4 源码）：`ExoPlayerImpl` 的
   `evaluateMediaItemTransitionReason`（`ExoPlayerImpl.java:2509-2545`）只有在
   **window uid 变化**时才判定为 transition；重排后当前曲的 uid 未变，
   `onMediaItemTransition` 不保证回调，若不自己同步下标，`_currentIndex` 会指向
   随机前的旧位置，而它正是 `precacheNext()` 计算"下一首"的依据（会预缓存错歌）。
6. 三个「随机播放」按钮（歌曲页 / 专辑详情 / 艺术家详情）改为调用
   `PlayerController.playShuffled(...)`，并移除各自的 `.shuffled()` 与
   `setPlayMode(PlayMode.RANDOM)` 调用。
7. 刻意**不**把打乱放进 `setSongs(...)`：该方法还服务于「播放全部」「点击某首歌」
   等显式选列流程，那些场景用户期望按看到的顺序入列；随机化只发生在
   「进入随机模式」与「随机播放按钮」两个明确入口。

签名变更已先更新 `docs/NATIVE_PORT_SPEC.md` §7（新增 `playShuffled` 两处）。

---

### 5.17 播放列表允许同一首歌重复出现（第十六轮反馈）

**需求**：向播放列表添加歌曲时**不检测**列表内是否已存在该歌曲，即同一首歌可以出现多次。

**为什么这不是一行改动**：`song.path` 此前在整个队列逻辑里充当**唯一键**，
共有五处依赖"path 唯一"这一前提；取消判重后每一处都会出错：

1. **`PlaylistRepository.addSong`**（`playlist_data.dart:70-75` 的对应实现）：
   原先 `if (contains(song)) return false` 直接拒绝重复。现改为无条件追加，
   并**去掉 Boolean 返回值** —— "是否新增"这个语义已不存在，
   调用方的 `if (addSong(...)) added++` 与"该歌曲已在播放列表中"提示一并删除。
2. **`syncQueue` 的移除识别**：原用 `existingIds.filter { it !in newIds }` 反推被删项。
   重复项会被 `in` 判定为"应当保留"，导致**一份也删不掉**（如 `[A,A] → [A]` 会误判为无变化）。
   改为 `greedyKeepIndices(target, full)` 贪心匹配求"存活下标"，
   再按 `existingIds.indices - keep` 求被删下标。
3. **`syncQueue` 的纯重排判定**：原用 `newIds.toSet() == existingIds.toSet()`。
   多重集不同但集合相同的改动（如 `[A,A,B] → [A,B,B]`）会被误判成"纯重排"而不重建队列。
   改为 `existingIds.sorted() == newIds.sorted()`（排序后比较，等价于多重集相等）。
4. **当前播放项的定位**：原用 `newIds.indexOf(currentMediaId)`。
   mediaId 不再唯一，`indexOf` 只会命中第一份。新增
   `occurrenceMappedIndex(oldIds, newIds, oldIndex)`：先算出当前项是旧列表中该 id
   的第几份（occurrence），再在新列表里找同样的第几份；被删则返回 -1。
   删除后的接续项 `successorIndexAfterRemoval(keep, removed, currentIndex)`
   同样改为按**位置**计算。
5. **播放列表页 UI**（`NowPlayingPlaylistPage.kt`）：
   - `items(key = { it.path })` 遇到重复 key 会让 `LazyColumn` 直接抛
     `IllegalArgumentException: Key ... was already used`，改为
     `itemsIndexed(key = { index, item -> "$index-${item.path}" })`；
   - `isCurrent = item.path == currentSong.path` 会把**所有**同名副本一起点亮，
     改为比下标：`index == currentIndex`（新增订阅 `PlayerController.currentIndex`）；
   - 行点击由 `onPlaySong(song)` 改为 `onPlayIndex(index)` —— 见第 6 条；
   - 每行「从播放列表移除」由 `removeSong(song)`（按 path 全删）改为
     `removeAt(index)`（只删被点的那一份），因此弹窗状态新增 `actionSheetIndex`。

6. **新增 `PlayerController.playAt(index, openNowPlaying)`**：`playSong(song)` 内部是
   `indexOfFirst { it.path == song.path }`，重复歌曲时永远命中第一份。
   播放列表页点第 N 行必须能播第 N 份，因此新增按下标的入口；
   `playSong(song)` 改为 `playAt(indexOfFirst { ... })` 的薄封装（行为不变）。
   连带把两个内部调用点也改为按下标：`playSongs` / `playShuffled` 原本
   `playSong(songs[startIndex])`，在重复列表上会启动错误的副本，现直接 `playAt(startIndex)`；
   `resumeOrPlay` 由 `playSong(song)` 改为 `playAt(_currentIndex.value)`。

7. **`PlaylistRepository.updateSong`**：原先只替换 `indexOfFirst` 命中的第一份，
   第 2 份会残留旧的歌词 / 喜欢 / 缓存状态。改为把该 path 的**全部副本**一并替换。

8. **`removeSong` / `removeSongs` 语义保留**：仍是"按 path 删除全部同名副本"。
   这对曲库删除（`LibrarySongDeleter`）是正确语义 —— 歌都没了，队列里哪一份都该清掉。

**顺带修掉一个真实回归**：`EntityActionSheet`（第 341 行）与 `SongInfoBottomSheet`
（第 172 行）**内部自己就执行了 `addSong`**，随后才回调 `onPlayNext`；而 7 个页面
（专辑/艺术家列表与详情、歌单详情等）在 `onPlayNext` 里**又 addSong 了一次**。
判重存在时这是无害的空操作，取消判重后会**真的入列两份**。
已把这 7 处 `onPlayNext` 改为空实现（`SongsScreen` 本就是这个写法），
并在 `docs/NATIVE_PORT_SPEC.md` 的回调契约里写明"不得再 addSong"。

签名变更已先更新 `docs/NATIVE_PORT_SPEC.md` §7（`addSong` 去返回值、
新增 `playAt`、`removeAt` / `updateSong` / `contains` 的语义注释）与「播放列表行为约定」。

---

### 5.18 侧边栏内左划也应能收起侧边栏（第十七轮反馈）

**现象**：侧边栏展开后，在**侧边栏区域内**左划没有任何反应；只有在右侧主页区域左划
才能收起侧边栏（`HomeShell.kt` 里那个"打开态拦截层"）。

**根因**：手势处理全部挂在**主页 Box** 上：

```kotlin
Box(                                    // ← 主页，注意它的 translationX
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { translationX = sidebarWidthPx * sidebarProgress.value }
        .draggable(...)                 // 打开/收起都靠它
) {
    ...
    if (isOpen) { Box(Modifier.fillMaxSize().draggable(...).pointerInput { ... }) }  // 拦截层
}
```

侧边栏展开时这块主页 Box 被 `translationX` 整体推到**右半屏**，所以它（连同内部的
拦截层）只覆盖屏幕右半边；`Sidebar` 自己所在的左半边**没有任何手势节点** ——
手指落在侧边栏上时，事件压根到不了那个 `draggable`，左划自然毫无反应。

**修复**：给 `Sidebar` 自身的 modifier 补上同款横向 `draggable`
（`Orientation.Horizontal` + `dragSidebar` + `settleSidebar`）：

```kotlin
Sidebar(
    currentIndex = pageIndex,
    onSelect = { ... },
    modifier = Modifier
        .width(sidebarWidth).fillMaxHeight()
        .graphicsLayer { translationX = sidebarWidthPx * (sidebarProgress.value - 1f) }
        .draggable(
            orientation = Orientation.Horizontal,
            state = rememberDraggableState { delta -> dragSidebar(delta) },
            onDragStopped = { velocity -> settleSidebar(velocity) },
        ),
)
```

左右两半现在用**同一套** `dragSidebar` / `settleSidebar`，因此侧边栏内左划与
「歌曲页面内左划」的判定完全一致（同一速度阈值 `SIDEBAR_SETTLE_VELOCITY`、
同一就近比例 `SIDEBAR_OPEN_RATIO`）：左划即收回，右划回展开态，快速轻扫按速度结算。

**不影响的交互**：
- 侧边栏内的 `LazyColumn` 纵向滚动 —— `Orientation.Horizontal` 的 `draggable`
  不参与纵向手势竞争；
- 侧边栏条目点击 —— `draggable` 不消费点击（只有超过 touchSlop 的横向拖动才接管）。

签名无变化（仅给既有 `Sidebar` 调用点追加 modifier），故 `docs/NATIVE_PORT_SPEC.md` §7 无需改动。

---

### 5.19 去掉歌曲页的「播放全部」按钮（第十八轮反馈）

**需求**：歌曲页顶部操作条不再显示「播放全部」。

**改动**（`feature/songs/.../SongsScreen.kt`）：

- `SongsHeader` 去掉 `onPlayAll` 参数与对应的 `TextButton`（含 `Icons.Filled.PlayArrow`），
  该行现在只剩「共 N 首」+「随机播放」；调用点同步移除 `onPlayAll` lambda。
- 随之删除已不再使用的 `import ...icons.filled.PlayArrow`。

**影响面**：仅歌曲页。专辑详情、艺术家详情、歌单详情与 `EntityActionSheet` 的
「播放全部」不受影响（`docs/NATIVE_PORT_SPEC.md` 第 505 行那条"点击专辑/艺术家/歌单/喜欢的
播放全部"仍然有效）。整列播放也仍可由**点击任意歌曲**触达 —— 行点击会
`setSongs(sortedSongs)` 整列后从该曲起播。

**未改动**：多选态本就不显示该操作条（`if (!selectionMode)`），故多选流程无影响。
纯 UI 删除，无签名变化，`docs/NATIVE_PORT_SPEC.md` §7 无需改动。

---

### 5.20 去掉歌曲页的「随机播放」按钮（第十九轮反馈）

**需求**：歌曲页顶部操作条不再显示「随机播放」（接续第十八轮去掉「播放全部」）。

**改动**（`feature/songs/.../SongsScreen.kt`）：

- `SongsHeader` 去掉 `onShuffle` 参数与对应的 `TextButton`（含 `Icons.Filled.Shuffle`）。
  该条现在只剩「共 N 首」一行文本，因此顺带简化实现：原先的
  `Row`（`verticalAlignment` / `horizontalArrangement`）+ `Spacer(weight(1f))` 右侧留白
  已无意义，改为单个带内边距的 `Text`；调用点同步移除 `onShuffle` lambda。
- 删除已不再使用的 `import ...icons.filled.Shuffle`。
- 文件头注释与函数 KDoc 同步更新。

**影响面**：仅歌曲页顶部的入口。专辑详情、艺术家详情、歌单详情的「随机播放」按钮不受影响
（它们各自调用 `PlayerController.playShuffled`，本轮未改动）。
`PlayerController.playShuffled` / `PlaylistRepository.playShuffled` 仍被上述三个页面使用，
**未成为死代码**。

歌曲页本身仍可进入随机模式：播放页底部的播放模式按钮循环
（列表循环 → 随机播放 → 单曲循环）里选中「随机播放」时，
`PlaylistRepository.setPlayMode(RANDOM)` 会把当前列表重排（第十五轮行为），
效果与按该按钮一致。

纯 UI 删除，无签名变化，`docs/NATIVE_PORT_SPEC.md` §7 无需改动。

---

### 5.21 播放列表页：定位当前曲 / 高亮框 / 去分割线 / 行内减号（第二十轮反馈）

需求共五项，集中在「从歌曲页上划进入的播放列表页」：

| # | 需求 | 实现 |
|---|------|------|
| 1 | 上划进入播放列表时**定位到当前播放的歌曲** | `NowPlayingOverlay.openPlaylist` 在翻页补间**结束后** `playlistListState.scrollToItem(currentIndex)` |
| 2 | 当前播放曲用**20px 圆角 + 半透明灰框**标出 | 行外层 `Modifier.background(CURRENT_ROW_HIGHLIGHT, RoundedCornerShape(20.dp))`，色值 `#888888` @ 28% |
| 3 | 取消列表行间**分割线** | 队列内不画任何分割线（第二十一轮进一步明确为：整页只留一条，见 §5.22） |
| 4 | 顶部当前播放区去掉「正在播放」四字与右侧按钮 | `CurrentSongCard` 去掉那行 `Text("正在播放")` 与右侧 `Equalizer`/`PlayArrow` 图标，并移除随之无用的 `isPlaying` 参数 |
| 5 | 队列行右侧三个点 → **减号**，功能为从当前播放队列删掉该曲 | 图标换 `Icons.Filled.Remove`，回调改为 `onRemoveIndex(index)`，直接 `PlaylistRepository.removeAt(index)` |

**几个实现细节值得记录**：

- **定位用 `scrollToItem`（瞬时）而非 `animateScrollToItem`**：上划本身已有一段翻页补间，
  再叠加列表滚动动画会变成两段动画接力、观感拖沓。定位在 `animateTo` **之后**执行，
  避免与翻页抢帧；下标在滚动那一刻才读 `PlayerController.currentIndex.value`
  （不提前捕获），因此补间期间若已切歌，仍会定位到正确的当前曲。
- **高亮框与封面左右对齐**：高亮框若直接铺满整行会贴屏幕边缘，但内部封面必须仍与顶部卡片
  对齐于 16dp。做法是外层 `padding(horizontal = 8.dp)` 承载圆角框、内层再补
  `start = 16.dp - 8.dp`，两者相加正好 16dp（用 `CURRENT_ROW_HORIZONTAL_INSET` 常量表达）。
- **减号按钮按 `index` 删除，而不是按 `Song`**：第十六轮起播放列表允许同一首歌重复出现，
  `removeAt(index)` 才能只删被点的那一份（`removeSong(song)` 会按 `path` 删掉全部同名副本）。
  若删的正是当前播放项，`PlayerController.syncQueue` 会接续播放下一首（第十二轮行为）。
- **弹窗状态的连带清理**：原先播放列表行与播放页顶栏**共用** `EntityActionSheet`，
  因此第十六轮为「删被点的那一份」额外记录了 `actionSheetIndex`。行内改减号后该状态
  已无来源，故一并删除；弹窗现在只由播放页顶栏打开，其「从播放列表移除」直接按
  `currentIndex` 删除（§5.11 的弹窗收敛约定本身不变）。
- 同时删除了不再使用的 `isPlaying` 参数与 `HorizontalDivider` / `MoreVert` / `Equalizer` /
  `PlayArrow` / `Modifier.height` 等 import，避免未使用告警。

纯 UI 与内部签名调整（`NowPlayingPlaylistPage` 为 `internal`），
`docs/NATIVE_PORT_SPEC.md` §7 无需改动。

---

### 5.22 播放列表页按设计图重排（第二十一轮反馈）

参照用户提供的设计图，在 §5.21 的基础上调整（**保留** 20px 圆角灰框高亮）：

| 项 | 变更 |
|----|------|
| 顶部提示 | 新增居中一行小字「此处向下轻扫以返回播放界面」 |
| 当前播放区 | 封面 48dp → **64dp**；副标题改为「**艺术家 - 专辑**」（原仅艺术家）；标题 15sp → 18sp |
| 表头 | 原「收起 chevron + 播放列表/共 N 首 + 清空图标」→「**`[位置] / [总数]`**」左 ·「**播放队列**」中 ·「**清除**」右 |
| 队列行 | **去掉左侧封面**；歌名 15sp → 16sp；副标题改为「艺术家 - 专辑」；右端保留减号按钮 |
| 分割线 | 整页**只在「当前播放区」与「播放队列」之间**保留一条；队列内部无分割线 |
| 底部 | 新增居中胶囊按钮「**随机播放模式**」 |

**几个实现要点**：

- **只有一条分割线的落点**：`Column` 内顺序为 提示 → 当前播放区 → 表头 → `HorizontalDivider`
  → `LazyColumn` → 胶囊按钮。把分割线放在「表头之后、列表之前」，
  既分隔了两大区块，又保证列表**内部**没有任何分割线（`PlaylistSongRow` 里不再画）。
- **左右对齐**：行去掉封面后，文字左边距由 `ROW_TEXT_START =
  ROW_HORIZONTAL_PADDING - ROW_HORIZONTAL_INSET`（16 - 8 = 8dp）表达 ——
  外层 8dp 让圆角高亮框不贴屏幕边缘，内层再补 8dp，**文字与顶部当前播放区对齐于 16dp**。
- **`[位置] / [总数]` 的 "-1" 处理**：`currentIndex` 为 -1（尚无当前项）时退化为只显示总数，
  避免出现 `0 / N` 这种误导性文案。
- **胶囊按钮用 `Surface(onClick, shape = CircleShape)`**：全圆角由 `CircleShape` 给出，
  半透明深色底（黑 45%）保证压在上层模糊封面上仍可读；`navigationBarsPadding` 挂在按钮上，
  使其不被手势导航条遮挡。
- **胶囊的点击语义**：`PlayerController.setPlayMode(PlayMode.RANDOM)`。第十五轮起
  进入随机模式会**重排当前列表**（列表顺序即播放顺序），因此无需另传一份打乱的列表；
  点击后播放列表页的显示顺序会随 `PlaylistRepository.songs` 一起变化。
- 随之清理：`KeyboardArrowDown` / `DeleteSweep` 图标、`CircleShape` 之外的旧常量
  （`ROW_ARTWORK_SIZE` / `CURRENT_ROW_HORIZONTAL_INSET`）与不再使用的 import。

`NowPlayingPlaylistPage` 为 `internal` 且 `onShuffle` 为新增参数，
`docs/NATIVE_PORT_SPEC.md` §7 无需改动。

---

### 5.23 播放列表页交互与「随机播放/空队列」语义（第二十二轮反馈）

本轮共 7 项需求，其中 5 项在播放列表页、1 项在 `core:player`、1 项跨「播放栏 + 播放页」。

| # | 需求 | 实现 |
|---|------|------|
| 1 | 播放列表**顶部当前播放区**下滑可返回播放页 | 把「提示 + 当前播放卡片 + 表头」包进一个带 `draggable(Orientation.Vertical)` 的 `Column`，松手时位移 > 60dp 或向下速度 > 300dp/s 即 `onSwipeDown()`（= `showNowPlaying`） |
| 2 | 当前播放行高亮框**上下各缩短 5px** | 高亮框从"行外层背景"改为**叠加层** `Box(matchParentSize).padding(vertical = 5.dp)`，因此框体变矮但**行高与行距不变** |
| 3 | 底部改为**左侧**的模式胶囊，点击循环切换，且与播放页模式按钮**状态绑定** | 文案/图标取自 `PlayController.playMode`，点击 `PlayerController.togglePlayMode()`；两处共用同一 `StateFlow`，天然联动 |
| 4 | 入列记住原始顺序；随机时记住原始顺序再随机（当前曲置首）；切回顺序还原；再切随机**重新**随机 | `PlaylistRepository` 新增 `originalOrder` 快照 + `currentPath`；见下方详解 |
| 5 | 清空队列后播放栏**不消失**，显示占位内容 | `Song.placeholder`（`isPlaceholder`）+ `stopAndClear()`/`onCurrentSongRemoved()` 落占位；见下方详解 |
| 6 | 队列每行**左右各加 5px** padding | `ROW_HORIZONTAL_EXTRA_PADDING = 5.dp`，叠在 `ROW_TEXT_START` / 行尾 padding 上 |
| 7 | 高亮框圆角 **15px**；播放队列**整体**左右各收窄 5px | `CURRENT_ROW_CORNER = 15.dp`；`QUEUE_HORIZONTAL_INSET = 5.dp` 加在 `LazyColumn`（与空态）容器上，使**每一行连同高亮框**一起内缩 |
| 8 | 队列行**左侧** padding 改为 **15px** | `ROW_TEXT_START = 15.dp`（与队列整体收窄 5px 叠加，文字距屏幕左缘实际 20px） |
| 9 | 歌曲页每行去掉**音乐时长**，加号按钮**右移 20px** | `MpSongListItem` 新增 `showDuration`（默认 true）；歌曲页传 `false`，此时加号加 `Modifier.offset(x = 20.dp)` |
| 10 | 主页底部播放栏**顶部多出一小块遮挡内容** | 去掉 `MiniPlayerBar` 的 `shadow(elevation = 6.dp)`：阴影绘制在组件边界**之外**，会在栏体上方形成一条灰带压住最后一行歌曲；栏体本身已有不透明底色 + 1px 顶部描边，分隔足够 |

> 需求 9 的实现取舍：`MpSongListItem` 是**跨页面共用**组件（专辑/艺术家/歌单/搜索/缓存管理
> 等都在用），而需求只针对歌曲页，因此用**新增可选参数**（`showDuration`）而非直接删掉时长，
> 其余页面行为完全不变。加号位移用 `offset` 做纯视觉平移，不改按钮尺寸与触摸区。

**需求 4 的实现要点（`PlaylistRepository`）**：

- 新增 `originalOrder`（入列时记住的顺序）与 `currentPath`（当前播放曲，由
  `PlayerController.publishCurrentState()` 单点回填）。
- `setSongs` / `addSong` / `playShuffled` / `restoreFromPrefs` 都会同步 `originalOrder`，
  且增删改（`removeAt` / `removeSongs` / `updateSong` / `clear`）**同时**作用于两个列表，
  保证二者始终是同一多重集（否则切回顺序模式会凭空多出/缺少歌曲）。
- `setPlayMode(RANDOM)` → `notify(shuffledKeepingCurrentFirst(_songs))`：
  **当前播放曲放第一位**，其余随机。
- `setPlayMode(SEQUENTIAL)` → `notify(originalOrder)`：还原入列顺序。
- **每次**切入随机都重新打乱（不缓存上次的随机结果），因此"再切随机 = 重新随机"。
- `playShuffled`（「随机播放」按钮）刻意用**整体随机**而非"当前曲置首"：
  调用方随后从第 0 首开始播放，若把上一队列的当前曲钉在首位，会导致
  "点随机播放却总先放刚才那首"。
- **顺带修掉一个真实 bug**：`playSongs(songs, startIndex)` 原先在 `setSongs` **之后**
  用 `playAt(startIndex)`；需求 4 让 `setSongs` 在随机模式下会重排队列，
  于是 `startIndex` 会指向另一首歌。改为先从**入参列表**取出目标曲再 `playSong(target)`。

**需求 5 的实现要点（空队列占位）**：

- `Song.placeholder`：`title = "愉乐~愉悦~"`、`artist = "Hi~"`、`album = null`，
  `path` 为伪标识 `PLACEHOLDER_PATH`，并以 `Song.isPlaceholder` 判定。
- `stopAndClear()` 与 `onCurrentSongRemoved()`（队列已空时）把 `currentSong` 置为该占位曲，
  因此底部播放栏与播放页**都不再消失**；仅当队列非空却找不到当前项时仍保持 `null`。
- 逐项落实需求：
  - 底部播放栏：歌名「愉乐~愉悦~」/ 歌手「Hi~」；**禁止左右滑动切歌**
    （水平手势被消费且不位移），但**上划仍可进入播放列表**；
  - 播放页顶栏：同样显示占位歌名/歌手，隐藏「更多」按钮；
  - 封面区与迷你歌词区**留空**（不画渐变占位图）；
  - 右滑进入的详细歌词页仍显示「暂无歌词」（沿用既有无歌词分支）；
  - 进度条与时间：左右都显示 `--:--`，且进度条不可拖动；
  - 上一曲/下一曲点击**无效果**（回调置空；`skipToNext/Previous` 本就对空队列早返回）；
  - 播放页背景改为**跟随主题**的纯色（占位态没有封面可模糊）。
- `publishCurrentState()` 不把占位曲的伪 path 写进 `PlaylistRepository.currentPath`。

**真机验证（小米 14 / 23127PN0CC）**：冷启动无崩溃；播放列表页可见
「此处向下轻扫以返回播放界面」、`1 / 2174` ·「播放队列」·「清除」表头、
无封面的队列行与行尾减号按钮、底部左侧「随机播放模式」胶囊
（`vision_ground` 实测胶囊位于 x 146–599 / 屏宽 1200，确为左侧而非居中）；
清空队列后底部播放栏保留并显示「愉乐~愉悦~ / Hi~」。

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
- 第十二轮反馈修订（删除正在播放的歌曲时同步播放列表并接续播放，见 §5.13）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest --rerun-tasks`
  → **BUILD SUCCESSFUL**（`core:player` / `core:designsystem` / `feature:songs` 强制重编）。
- 第十三轮反馈修订（详细歌词面板纵向内缩 40px，见 §5.14）后
  `./gradlew :feature:nowplaying:compileDebugKotlin :app:assembleDebug :core:common:testDebugUnitTest --rerun-tasks`
  → **BUILD SUCCESSFUL**。
- 第十四轮反馈修订（单曲循环下手动切歌应换曲，见 §5.15）后
  `./gradlew :core:player:compileDebugKotlin --rerun-tasks` → **BUILD SUCCESSFUL**。
- 第十五轮反馈修订（随机播放改为随机排序歌曲列表本身，见 §5.16）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest --rerun-tasks`
  → **BUILD SUCCESSFUL**（`core:player` / `feature:songs` / `feature:albums` /
  `feature:artists` 强制重编），LrcParser 测试 8/8 通过。
- 第十六轮反馈修订（播放列表允许同一首歌重复出现，见 §5.17）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest --rerun-tasks`
  → **BUILD SUCCESSFUL**（`core:player` / `core:designsystem` / `feature:nowplaying` /
  `feature:songs` / `feature:albums` / `feature:artists` / `feature:playlists` 强制重编），
  LrcParser 测试 8/8 通过。
- 第十七轮反馈修订（侧边栏内左划收起侧边栏，见 §5.18）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest`
  → **BUILD SUCCESSFUL**（`feature:home` 重编），LrcParser 测试 8/8 通过。
- 第十八轮反馈修订（去掉歌曲页「播放全部」按钮，见 §5.19）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest`
  → **BUILD SUCCESSFUL**（`feature:songs` 重编），LrcParser 测试 8/8 通过。
- 第十九轮反馈修订（去掉歌曲页「随机播放」按钮，见 §5.20）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest`
  → **BUILD SUCCESSFUL**（`feature:songs` 重编），LrcParser 测试 8/8 通过。
- 第二十轮反馈修订（播放列表页定位当前曲 / 高亮框 / 去分割线 / 卡片与行按钮调整，见 §5.21）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest`
  → **BUILD SUCCESSFUL**（`feature:nowplaying` 重编），LrcParser 测试 8/8 通过。
- 第二十一轮反馈修订（播放列表页按设计图重排：轻扫提示 / 大封面 / 位置·播放队列·清除表头 /
  去行封面 / 单条分割线 / 底部随机播放胶囊，见 §5.22）后
  `./gradlew :app:assembleDebug :core:common:testDebugUnitTest --rerun-tasks`
  → **BUILD SUCCESSFUL**（`feature:nowplaying` 强制重编），LrcParser 测试 8/8 通过。
- 第二十二轮反馈修订（播放列表页 7 项交互调整 + 随机播放顺序记忆 + 空队列占位，见 §5.23）后
  `./gradlew :app:assembleDebug :app:assembleRelease :core:common:testDebugUnitTest`
  → **BUILD SUCCESSFUL**（`core:model` / `core:player` / `core:designsystem` /
  `feature:nowplaying` / `app` 重编），LrcParser 测试 8/8 通过；
  正式版 `app-release.apk`（15.28 MB）已通过 `adb install -r` 覆盖安装到小米 14
  （签名一致，曲库与偏好保留），冷启动无崩溃。
- 第二十二轮追加修订（队列行左侧 padding 15px / 高亮框圆角 15px / 队列整体收窄 5px /
  歌曲页去时长且加号右移 20px / 修复播放栏顶部投影遮挡，见 §5.23 需求 8~10）后
  `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**。

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
