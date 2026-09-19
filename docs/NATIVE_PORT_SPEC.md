# 原生 Android 移植规范（Flutter → Kotlin/Jetpack Compose）

本文件是 `android` 分支原生重写的**唯一契约**。所有模块必须严格按本规范实现，
不得自行发明跨模块 API、不得修改 `core:*` 已冻结的公开签名（如需新增，先在此登记）。

---

## 0. 工程与约定

| 项 | 约定 |
|----|------|
| 语言 / UI | Kotlin + Jetpack Compose（Material3），XML 仅用于桌面小组件与悬浮窗 |
| 包名 | `com.mtechviral.musicfinderexample`（与旧版一致，保证数据库/偏好可平滑升级） |
| 模块 | `:app`、`:core:{model,common,database,network,media,cache,player,designsystem}`、`:feature:{home,songs,albums,artists,playlists,favorites,search,nowplaying,scan,stats,cache,subsonic,settings}` |
| 依赖注入 | 不使用 Hilt/Dagger；全部为 Kotlin `object` 单例，`Application.onCreate` 中初始化 |
| 异步 | Kotlin Coroutines + `StateFlow`；`suspend` 函数内部已切 IO，UI 侧用 `rememberCoroutineScope()` 调用 |
| 状态订阅 | `collectAsStateWithLifecycle()`（`androidx.lifecycle:lifecycle-runtime-compose`） |
| 文案 | 与旧版一致，直接写中文字面量（旧版即为硬编码中文，未使用 i18n） |
| 参考源码 | Flutter 原实现已解包到仓库根目录 **`.flutter_reference/lib/`**（只读参照，勿修改、勿提交） |
| 颜色 | 品牌紫 `0xFF7C4DFF`、品牌青 `0xFF18D2C7`；浅色底 `0xFFF7F7FA`、深色底 `0xFF0E0E14` |

### 页面数据获取统一模式（对应旧版 InheritedWidget + ValueNotifier 模式）

```kotlin
// 曲库（全量歌曲）来自全局 MusicLibrary
val library by MusicLibrary.songs.collectAsStateWithLifecycle()
LaunchedEffect(Unit) { MusicLibrary.reload() }        // 页面首次进入时刷新

// 聚合类数据（专辑/艺术家/歌单/统计）本地状态 + 曲库变化时重查
var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
LaunchedEffect(library) { albums = DatabaseHelper.queryAlbums() }
```

> 旧版每个页面在 `initState` 里查库并 `addListener(songData.notifier)`；
> 上面这段与之一一对应（`library` 变化即等价于 `songData.notifier` 触发）。

---

## 1. `core:model`（已完成，冻结）

```kotlin
package com.mtechviral.musicfinderexample.core.model

data class Song(
  val id: Long? = null, val title: String, val path: String,
  val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
  val trackNumber: Int? = null, val duration: Long? = null,     // ms
  val bitrate: Int? = null, val sampleRate: Int? = null, val bitDepth: Int? = null,
  val size: Long? = null, val format: String? = null, val codec: String? = null,
  val dateAdded: Long? = null, val dateModified: Long? = null,
  val hasArtwork: Boolean = false, val lyrics: String? = null, val source: String? = null,
  val sourceType: String? = null, val remoteId: String? = null, val remoteStreamUrl: String? = null,
  val cachedPath: String? = null, val cacheTimestamp: Long? = null,
  val cachedArtworkPath: String? = null, val coverArtId: String? = null,
  val playCount: Int = 0, val isLiked: Boolean = false, val lastPlayed: Long? = null,
) {
  val identityKey: String; val displayArtist: String; val displayAlbum: String
  val isRemote: Boolean; val isCached: Boolean; val playablePath: String; val durationText: String
  fun mergeCache(cachedPath: String?, cachedArtworkPath: String?): Song
  fun mergeLyrics(newLyrics: String?): Song
  // equals/hashCode 仅比较 path（与旧版一致！）
  companion object { const val SOURCE_TYPE_LOCAL; SOURCE_TYPE_SUBSONIC; SOURCE_MEDIA_LIBRARY }
}

data class Album(title, artist?, coverSongId?, coverSongPath?, coverArtworkPath?, coverArtId?, songCount) { val displayArtist }
data class Artist(name, songCount, albumCount, coverSongPath?, coverArtworkPath?, coverArtId?)
data class Playlist(id?, name, songCount)
data class PlaylistSong(playlistId, songId, position)
data class SubsonicConfig(id?, intranetUrl, publicUrl, username, password, serverName?, isActive)
data class ArtistMeta(id?, name, artistId?, coverArtId?, cachedArtworkPath?, isLiked)
enum class PlayMode(val label: String) { SEQUENTIAL, RANDOM, SINGLE; fun next(): PlayMode }
```

---

## 2. `core:common`（已完成，冻结）

```kotlin
object LrcParser {
  fun parse(raw: String?): List<LrcLine>            // LrcLine(timeMs: Long, text: String)
  fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int
}
object Formatters {
  fun formatSize(bytes: Long): String               // B/KB/MB/GB
  fun formatSizeMB(mb: Int): String
  fun formatDuration(ms: Long?): String             // mm:ss，无效返回 --:--
  fun formatDateTime(ms: Long?): String             // yyyy-MM-dd HH:mm
  fun formatDate(ms: Long?): String
  fun formatPlayCount(count: Int): String
}
object AppPreferences {                              // 兼容读取旧版 flutter.* 键
  const val KEY_THEME_MODE; KEY_CACHE_SIZE_MB; KEY_LAST_PLAYLIST_SONGS; KEY_LAST_PLAYLIST_MODE
  fun init(context); fun getString/getInt/getLong/getBoolean; fun putString/putInt/putLong/putBoolean
}
enum class AppThemeMode(val storageValue: String) { LIGHT, DARK, SYSTEM }
object ThemePreference { val mode: StateFlow<AppThemeMode>; fun load(); fun set(mode: AppThemeMode) }
```

---

## 3. `core:database`（已完成，冻结）

`object DatabaseHelper`：全部为 `suspend` 函数，方法名与旧版 `DatabaseHelper` 一一对应。

```kotlin
fun init(context: Context); fun invalidateCache()

// 歌曲
suspend fun queryAllSongs(): List<Song>                 // 带内存缓存，按 title 排序
suspend fun querySongById(id: Long): Song?
suspend fun querySongByPath(path: String): Song?
suspend fun querySongByRemoteId(remoteId: String): Song?
suspend fun insertSongs(songs: List<Song>, source: String?): Int   // 去重规则同旧版
suspend fun deleteSong(id: Long); suspend fun clearSongs()

// 专辑 / 艺术家
suspend fun queryAlbums(): List<Album>
suspend fun querySongsByAlbum(album: String): List<Song>
suspend fun queryAlbumsByArtist(artist: String): List<Album>
suspend fun queryArtists(): List<Artist>
suspend fun querySongsByArtist(artist: String): List<Song>

// 歌单
suspend fun queryPlaylists(): List<Playlist>
suspend fun createPlaylist(name: String): Long?         // 重名返回 null
suspend fun deletePlaylist(id: Long)
suspend fun querySongsInPlaylist(playlistId: Long): List<Song>
suspend fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean
suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long)
suspend fun findPlaylistByName(name: String): Playlist?

// Subsonic 配置
suspend fun querySubsonicConfig(): SubsonicConfig?
suspend fun saveSubsonicConfig(config: SubsonicConfig)
suspend fun deleteSubsonicConfig(id: Long)

// 远程缓存
suspend fun updateSongCache(songId: Long, cachedPath: String)
suspend fun queryCachedSongs(): List<Song>
suspend fun clearSongCache(songId: Long)
suspend fun queryOldestCachedSong(): Song?
suspend fun updateArtworkCache(songId: Long, artworkPath: String)
suspend fun updateSongLyrics(songId: Long, lyrics: String)
suspend fun querySongsNeedingArtworkCache(): List<Song>

// 统计 / 喜欢（喜欢只针对单曲：不再有 queryLikedAlbums / queryLikedArtists / toggleLikeArtist）
suspend fun incrementPlayCount(songId: Long)
suspend fun queryTopPlayed(limit: Int = 20): List<Song>
suspend fun queryRecentlyPlayed(limit: Int = 50): List<Song>
suspend fun toggleLikeSong(songId: Long)
suspend fun queryLikedSongs(): List<Song>
suspend fun queryLikedSongCount(): Int

// 艺术家元数据（toggleLikeArtist 已随"喜欢只针对单曲"删除；isLiked 列保留但不再读写）
suspend fun upsertArtistMeta(name: String, artistId: String?, coverArtId: String?)
suspend fun upsertArtistMetaBatch(artists: List<Triple<String, String?, String?>>)
suspend fun updateArtistArtworkCache(artistName: String, artworkPath: String)
suspend fun queryArtistMeta(name: String): ArtistMeta?
suspend fun queryAllArtistMeta(): List<ArtistMeta>
```

`object MusicLibrary`（等价旧版 `SongData`）：

```kotlin
val songs: StateFlow<List<Song>>
val current: List<Song>
suspend fun load(); suspend fun reload(); fun updateSongs(newSongs: List<Song>); fun updateSong(updated: Song)
val length: Int; val songNumber: Int; val currentIndex: Int
fun setCurrentIndex(index: Int); val nextSong: Song?; val prevSong: Song?; val randomSong: Song?
```

---

## 4. `core:network`（已完成，冻结）

```kotlin
object SubsonicService {
  val isConfigured: Boolean; val currentConfig: SubsonicConfig?
  suspend fun loadConfig(): SubsonicConfig?
  suspend fun saveConfig(newConfig: SubsonicConfig)
  fun resetConnection()
  suspend fun ping(): Boolean
  suspend fun getAllSongs(): List<Song>
  suspend fun getStreamUrl(songId: String): String
  suspend fun getPlaylists(): List<RemotePlaylist>
  suspend fun getPlaylistDetail(playlistId: String): RemotePlaylistDetail
  suspend fun createPlaylist(name: String, songIds: List<String>): String?
  suspend fun syncPlaylistsToLocalStorage(): Int        // 返回同步歌单数
  suspend fun getCoverArt(coverArtId: String): ByteArray?
  suspend fun getLyrics(artist: String, title: String): String?
}
class SubsonicException(message: String) : Exception
// RemotePlaylist(id, name) / RemotePlaylistDetail(id, name, entries: List<RemoteSong>) / RemoteSong(...)
```

---

## 5. `core:media`（已完成，冻结）

```kotlin
object AudioFormats { val extensions: Set<String>; fun isSupported(path): Boolean; fun codecOf(ext): String }
object MetadataParser { fun parse(path: String): Song?; fun readEmbeddedArtwork(path: String): ByteArray? }
typealias ScanProgress = (processed: Int, total: Int, failed: Int) -> Unit
data class ScanResult(val added: Int, val failed: Int, val total: Int)
class MetadataScanService(context: Context, batchSize: Int = 30) {
  suspend fun scanMediaLibrary(onProgress: ScanProgress? = null): ScanResult
  suspend fun scanFolder(folderPath: String, onProgress: ScanProgress? = null): ScanResult
}
object ArtworkCache {
  fun peek(key: String): ByteArray?; fun has(key: String): Boolean
  fun invalidate(key: String); fun invalidateByPathPrefix(path: String); fun clear()
  suspend fun load(path: String, cachedArtworkPath: String? = null): ByteArray?
}
```

---

## 6. `core:cache`（已完成，冻结）

```kotlin
object CacheService {
  fun init(context: Context)
  suspend fun getCacheDir(): File
  fun getCacheSizeMB(): Int
  suspend fun setCacheSizeMB(sizeMB: Int)          // 500 ~ 51200
  suspend fun getCacheSizeBytes(): Long
  suspend fun getCacheSizeMBActual(): Int
  suspend fun evictIfNeeded(requiredBytes: Long)
  suspend fun cacheSong(song: Song): String?
  fun startCaching(song: Song, onCached: ((Song) -> Unit)? = null)
  suspend fun cacheArtwork(song: Song): String?
  suspend fun cacheArtworkBatch(songs: List<Song>): List<Song>
  suspend fun deleteCache(songIds: List<Long>)
  suspend fun clearAllCache()
  suspend fun getCachedSongs(): List<Song>
}
```

---

## 7. `core:player`（已完成，冻结）

```kotlin
object PlaylistRepository {                    // 等价旧版 PlaylistData
  val songs: StateFlow<List<Song>>; val playMode: StateFlow<PlayMode>
  val current: List<Song>; val length: Int
  /** 只回答"有无"；列表允许重复，故不表示"仅一份" */
  fun contains(song): Boolean
  /** 追加到末尾，**不判重**（第十六轮：允许同一首歌重复出现），无返回值 */
  fun addSong(song: Song)
  fun setSongs(songs: List<Song>)
  /** 按 path 移除**全部**同名单曲 */
  fun removeSong(song: Song)
  /** 按**位置**精确移除一项（重复歌曲时只删被点的那一份） */
  fun removeAt(index: Int)
  /** 按 path 批量移出播放列表，返回实际移除数量（第十二轮新增：
   *  曲库删除歌曲时同步清理播放列表，整批只 notify 一次） */
  fun removeSongs(paths: Collection<String>): Int
  /** 更新该 path 的**全部**副本（歌词/封面/喜欢状态，第十六轮起多处一并更新） */
  fun updateSong(updatedSong: Song); fun clear()
  fun togglePlayMode(): PlayMode; fun setPlayMode(mode: PlayMode)
  /** 随机播放入口：把列表打乱后存入并切到 RANDOM，返回实际顺序（第十五轮新增） */
  fun playShuffled(songs: List<Song>): List<Song>
  /** 当前播放曲 path，由 PlayerController 回填；随机重排时用于把当前曲置于首位（第二十二轮） */
  var currentPath: String?
  suspend fun restoreFromPrefs(): Int
}

/**
 * 曲库永久删除的统一入口（第十二轮新增）。
 *
 * 为什么需要：删除（DatabaseHelper）与播放队列（PlaylistRepository）是两个独立结构，
 * 只删库会留下"播放列表里还有一首已不存在的歌"。
 *
 * 语义：逐首 deleteSong 后 `PlaylistRepository.removeSongs(paths)`；
 * 若被删的是当前播放项，由 PlayerController 接续播放下一首（见下）。
 */
object LibrarySongDeleter {
  /** @return 实际发起删除的歌曲数（仅统计已入库、即有 id 的歌曲） */
  suspend fun delete(songs: Collection<Song>): Int
}

object PlayerController {                      // 等价旧版 MpAudioHandler
  fun init(context: Context)
  val currentSong: StateFlow<Song?>
  val isPlaying: StateFlow<Boolean>
  val isMuted: StateFlow<Boolean>
  val duration: StateFlow<Long?>               // ms
  val position: StateFlow<Long>                // ms
  val currentIndex: StateFlow<Int>
  val playMode: StateFlow<PlayMode>
  val connected: StateFlow<Boolean>
  suspend fun playSong(song: Song): Boolean
  suspend fun playSongs(songs: List<Song>, startIndex: Int = 0): Boolean
  /** 按下标播放，重复歌曲时用于精确指定播放哪一份（第十六轮新增） */
  suspend fun playAt(index: Int, openNowPlaying: Boolean = true): Boolean
  /** 「随机播放」：列表本身随机排序后整列播放（第十五轮新增） */
  suspend fun playShuffled(songs: List<Song>, startIndex: Int = 0): Boolean
  suspend fun resumeOrPlay(); suspend fun pause(); suspend fun togglePlayPause()
  fun togglePlayPauseAsync()
  suspend fun skipToNext(); suspend fun skipToPrevious()
  suspend fun seekTo(positionMs: Long)
  suspend fun setMuted(muted: Boolean)
  fun togglePlayMode(): PlayMode; fun setPlayMode(mode: PlayMode)
  suspend fun stopAndClear()
}

object NowPlayingUiState {                     // 等价旧版 nowPlayingOpen + nowPlayingController
  val progress: StateFlow<Float>               // 0 = 收起，1 = 展开
  val isOpen: StateFlow<Boolean>               // progress > 0.5
  val currentProgress: Float
  fun setProgress(value: Float); fun open(); fun close(); fun toggle()
}

object LyricsOverlayManager {                  // 等价旧版 LyricsOverlayManager
  val isVisible: StateFlow<Boolean>; val isLocked: StateFlow<Boolean>; val linesCount: StateFlow<Int>
  fun checkPermission(): Boolean; fun requestPermission()
  fun showOverlay(): Boolean; fun hideOverlay(); fun onNotificationToggle()
  fun toggleLock(); fun getLinesCount(): Int; fun setLinesCount(count: Int)
}
```

`FloatingLyricsService`（悬浮窗服务）、`MusicWidgetProvider` / `MusicWidgetUpdater`（桌面小组件）
已按原 Java 实现 1:1 迁移，UI 层无需直接调用组件类。

---

## 8. `core:designsystem`（本规范冻结的公开 API）

主题：

```kotlin
package com.mtechviral.musicfinderexample.core.designsystem.theme

val BrandPurple: Color      // 0xFF7C4DFF
val BrandCyan: Color        // 0xFF18D2C7
val TextSecondaryLight: Color  // 0xFF8A8A99
val TextSecondaryDark: Color   // 0xFFB3B3C2

@Composable fun YuleMusicTheme(mode: AppThemeMode, content: @Composable () -> Unit)
// 由 :app 顶层调用一次；内部读取 ThemePreference.mode 决定明暗
val MaterialTheme.ytTextSecondary: Color   // 次要文字色（随明暗切换）
```

通用组件（`com.mtechviral.musicfinderexample.core.designsystem.component`）：

```kotlin
/** 歌曲封面（自动读内嵌封面 / 远程缓存封面，无封面显示紫青渐变占位） */
@Composable fun SongArtwork(
    song: Song, modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp, contentScale: ContentScale = ContentScale.Crop,
)

/** 按路径取封面（专辑/艺术家等无 Song 对象的场景） */
@Composable fun ArtworkImage(
    path: String?, cachedArtworkPath: String? = null,
    songId: Long? = null, coverArtId: String? = null,
    modifier: Modifier = Modifier, cornerRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
)

/** 圆形文字头像（艺术家无封面时的首字占位） */
@Composable fun MpCircleAvatar(text: String, modifier: Modifier = Modifier, size: Dp = 48.dp)

/** 圆形控制按钮（播放页/播放栏的大按钮） */
@Composable fun MpControlButton(
    icon: ImageVector, contentDescription: String?, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Dp = 48.dp, iconSize: Dp = 26.dp,
    enabled: Boolean = true, tint: Color = LocalContentColor.current,
)

/** 歌曲列表行（封面 + 歌名 + 艺术家·专辑 + 时长 + 更多按钮；当前播放行高亮） */
@Composable fun MpSongListItem(
    song: Song, isCurrent: Boolean,
    onClick: () -> Unit, onMoreClick: () -> Unit,
    modifier: Modifier = Modifier, showArtwork: Boolean = true, showDivider: Boolean = true,
    showAddButton: Boolean = true,
    /** 是否显示时长（歌曲页传 false：隐藏时长并把加号右移 20dp；第二十二轮新增） */
    showDuration: Boolean = true,
)

/** 一级页面统一顶栏：左上角目录按钮 + 页面名 + 右侧 actions */
@Composable fun PrimaryAppBar(
    title: String, onMenuClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
)

/** 底部常驻迷你播放栏（隐藏逻辑由调用方控制） */
@Composable fun MiniPlayerBar(
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前歌曲为空时是否自动隐藏 */
    hideWhenEmpty: Boolean = true,
)

/** 实体操作菜单（底部弹出）：歌曲 / 专辑 / 艺术家 三种目标
 *  「喜欢 / 取消喜欢」仅 SongTarget（且仅单首）显示；专辑/艺术家目标不提供该入口 */
@Composable fun EntityActionSheet(
    entity: EntityActionTarget,
    onDismiss: () -> Unit,
    onPlay: (List<Song>) -> Unit = {},
    onPlayNext: (List<Song>) -> Unit = {},
    onAddToPlaylist: (List<Song>) -> Unit = {},
    onToggleLike: (List<Song>) -> Unit = {},
    onDelete: (List<Song>) -> Unit = {},
    /** true（默认）= 弹窗内部先 `LibrarySongDeleter.delete`（deleteSong + 移出播放列表）
     *  + reload 再回调 onDelete；false = 不做库删除，仅回调（歌单详情页"从本歌单移除"用） */
    deleteFromLibrary: Boolean = true,
    /** 删除项文案 */
    deleteLabel: String = "永久删除",
    /** 需要隐藏的菜单项（默认全部显示）。播放页 / 播放列表的「更多」用它去掉
     *  「播放 / 添加到播放队列 / 添加到歌单」。 */
    hiddenItems: Set<EntityActionItem> = emptySet(),
    /** 是否显示标题栏右上角关闭按钮（默认 true）；隐藏后仍可点遮罩/返回键/选项关闭 */
    showHeaderCloseButton: Boolean = true,
)

/** 可单个隐藏的菜单项 */
enum class EntityActionItem { PLAY, ADD_TO_QUEUE, ADD_TO_PLAYLIST }

sealed interface EntityActionTarget {
    data class SongTarget(val songs: List<Song>) : EntityActionTarget
    data class AlbumTarget(val album: Album, val songs: List<Song>) : EntityActionTarget
    data class ArtistTarget(val artist: Artist, val songs: List<Song>) : EntityActionTarget
}

/** 歌曲信息 / 歌词底部弹窗（对应旧版 mp_song_bottom_sheet.dart） */
@Composable fun SongInfoBottomSheet(
    song: Song, onDismiss: () -> Unit,
    onPlayNext: () -> Unit = {}, onAddToPlaylist: () -> Unit = {}, onDelete: () -> Unit = {},
)
```

> 上述签名如需扩展，**必须**先更新本规范，再让使用方同步。

---

## 9. 路由契约（`core:common/AppRoutes.kt`，已冻结）

```kotlin
object AppRoutes {
    const val HOME = "home"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val SCAN = "scan"
    const val SUBSONIC = "subsonic"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val CACHE_MANAGE = "cache_manage"

    const val ALBUM_DETAIL = "album_detail/{title}"
    const val ARTIST_DETAIL = "artist_detail/{name}"
    const val PLAYLIST_DETAIL = "playlist_detail/{id}"

    fun albumDetail(title: String): String          // 内部做 Uri.encode
    fun artistDetail(name: String): String
    fun playlistDetail(id: Long): String

    const val ARG_TITLE = "title"
    const val ARG_NAME = "name"
    const val ARG_ID = "id"
}
```

每个 feature 模块**只暴露**一个导航图扩展函数（放在 `<feature>/src/main/kotlin/.../feature/<x>/<X>Navigation.kt`）：

| 模块 | 扩展函数 | 路由 | 屏幕签名 |
|------|----------|------|----------|
| home | 无导航图：仅提供 `HomeShell(content: @Composable (index: Int) -> Unit)`、`Sidebar(...)`、`LocalOpenSidebar` | — | 由 `:app` 组装九个一级页面 |
| songs | `fun NavGraphBuilder.songsGraph(navController: NavController)` | `songs` | `@Composable fun SongsScreen(onOpenNowPlaying: () -> Unit = {}, onOpenSearch: () -> Unit = {})` |
| albums | `fun NavGraphBuilder.albumsGraph(navController: NavController)` | `albums`、`album_detail/{title}` | `AlbumsScreen(onOpenAlbum: (String) -> Unit = {})` / `AlbumDetailScreen(albumTitle: String, onBack: () -> Unit = {})` |
| artists | `fun NavGraphBuilder.artistsGraph(navController: NavController)` | `artists`、`artist_detail/{name}` | `ArtistsScreen(onOpenArtist: (String) -> Unit = {})` / `ArtistDetailScreen(artistName: String, onOpenAlbum: (String) -> Unit = {}, onBack: () -> Unit = {})` |
| playlists | `fun NavGraphBuilder.playlistsGraph(navController: NavController)` | `playlists`、`playlist_detail/{id}` | `PlaylistsScreen(onOpenPlaylist: (Long) -> Unit = {})` / `PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit = {})` |
| favorites | `fun NavGraphBuilder.favoritesGraph(navController: NavController)` | `favorites` | `FavoritesScreen()`（只展示喜欢的歌曲，无专辑/艺术家 Tab） |
| search | `fun NavGraphBuilder.searchGraph(navController: NavController)` | `search` | `SearchScreen(onBack: () -> Unit = {})` |
| scan | `fun NavGraphBuilder.scanGraph(navController: NavController)` | `scan` | `ScanScreen(onOpenSubsonicConfig: () -> Unit = {})` |
| stats | `fun NavGraphBuilder.statsGraph(navController: NavController)` | `stats` | `StatsScreen()` |
| cache | `fun NavGraphBuilder.cacheGraph(navController: NavController)` | `cache_manage` | `CacheManageScreen(onBack: () -> Unit = {})` |
| subsonic | `fun NavGraphBuilder.subsonicGraph(navController: NavController)` | `subsonic` | `SubsonicConfigScreen()` |
| settings | `fun NavGraphBuilder.settingsGraph(navController: NavController)` | `settings` | `SettingsScreen(onOpenCacheManage: () -> Unit = {})` |
| nowplaying | 无导航图（由 `:app` 以覆盖层渲染） | — | `@Composable fun NowPlayingOverlay(onClose: () -> Unit, modifier: Modifier = Modifier)` |

> **一级页面容器**：九个一级页面由 `:app` 通过 `HomeShell { index -> ... }` 的
> slot 按侧边栏下标渲染，**因此屏幕函数必须能在没有 NavController 的上下文里直接调用**，
> 跨页跳转一律使用上表的"默认参数 + 回调"。各 feature 的 `xxxGraph(navController)`
> 仍然保留（图内部用 `navController` 驱动这些回调），保证路由也可直接到达。

**一级页面**（歌曲/专辑/艺术家/歌单/喜欢/扫描音乐/远程配置/统计/设置）的顶栏统一使用
`PrimaryAppBar(title = "...", onMenuClick = { onOpenSidebar() })`，由 `HomeScreen` 传入
`onOpenSidebar`。二级页面（详情/搜索/缓存管理）使用 `TopAppBar` + 返回键（`navigationIcon`）。

> ⚠️ **CompositionLocal 使用注意**：`CompositionLocal.current` 是 `@Composable @ReadOnlyComposable`
> 读取，**不能在非 @Composable 的 lambda（如 `onClick = { ... }`）里读取**，否则报
> `@Composable invocations can only happen from the context of a @Composable function`。
> 正确写法是在 composable 函数体内先取一次再传：
> ```kotlin
> val openSidebar = LocalOpenSidebar.current        // composable 函数体内
> ...
> PrimaryAppBar(title = "歌曲", onMenuClick = openSidebar)
> ```

### 播放列表行为约定（所有页面统一）

- 点击列表中的歌曲：`PlaylistRepository.setSongs(list)` 后 `PlayerController.playSong(song)`；
  **播放列表页的每行点击**改用 `PlayerController.playAt(index)`（同一首歌可重复，按下标才精确）。
- 点击专辑/艺术家/歌单/喜欢的"播放全部"：`PlayerController.playSongs(songs, 0)`。
- 点击"随机播放"：`PlayerController.playShuffled(songs, 0)` —— 把歌曲列表**本身**
  随机排序后按下标 0 开始播放（第十五轮修正，见下）。
- 当前播放行高亮：普通列表用 `PlayerController.currentSong` 比较 `song.path`；
  **播放列表页**改用 `PlayerController.currentIndex` 比较**下标**（第十六轮起列表允许重复，
  按 path 比较会把同名副本全部点亮）。
- **播放列表允许同一首歌出现多次**（第十六轮）：
  `PlaylistRepository.addSong(song)` **不再判重**，重复调用即重复入列
  （需求：向播放列表添加歌曲时不检测列表内是否已存在）。
  由此带来的一组约束：
  - `addSong` 不再返回 Boolean（旧返回值"是否新增"已无意义），提示文案不再有
    "该歌曲已在播放列表中"分支；
  - **`EntityActionSheet` / `SongInfoBottomSheet` 内部已执行 `addSong`**，
    因此调用方的 `onPlayNext` 只作刷新通知，**不得再 `addSong`**（否则重复入列两份）；
  - 播放列表页删除一行用 `removeAt(index)`（按位置），**不能**用 `removeSong(song)`
    （按 path 会删掉全部同名副本）；
  - `updateSong(updated)` 会更新该 path 的**全部副本**，避免第 2 份残留旧的
    歌词 / 喜欢 / 缓存状态；
  - 收藏/曲库删除走 `removeSongs(paths)`，仍是"删掉该歌的全部副本"，语义正确；
  - 与 Dart 差异：Dart `addSong` 会先 `contains` 判重、已存在则返回 false 不入列
    （`playlist_data.dart:70-75`）；此处按需求刻意取消判重。
- **随机模式：随机化的是列表本身**（第十五轮修正，第二十二轮补充顺序记忆）：
  `PlayMode.RANDOM` 不再映射到 `shuffleModeEnabled = true`，而是把当前播放列表
  **真正重排**（`PlaylistRepository.setPlayMode(RANDOM)` 在切入随机时打乱列表；
  「随机播放」按钮走 `playShuffled`，一次通知完成"打乱 + 切模式"），之后按列表顺序
  顺序播放。这样界面上的列表顺序与实际播放顺序一致；若仍打开播放器内部 shuffle，
  播放器会在已打乱的列表上再乱序一次，与界面显示对不上。
  注意与 Dart 的差异：Dart 只维护私有 `_shuffleOrder` 下标序列，从不改动
  `playlistData.songs`，界面列表顺序不变；此处按需求刻意改为重排列表本身。
- **原始顺序记忆与还原**（第二十二轮）：
  `PlaylistRepository` 另行保存一份入列时的原始顺序 `originalOrder`：
  - 切入 `RANDOM`：`originalOrder` 保持不动，`songs` 重排为
    「**当前播放曲置于首位** + 其余随机」（当前曲由 `PlayerController` 回填 `currentPath`）；
  - 切回 `SEQUENTIAL`：`songs` 还原为 `originalOrder`；
  - **每次**切入 `RANDOM` 都重新随机（不复用上次结果）；
  - `SINGLE` 不改动列表顺序；
  - 增删改（`addSong`/`setSongs`/`removeAt`/`removeSongs`/`updateSong`/`clear`/`restoreFromPrefs`）
    同时作用于两个列表，保证二者始终是同一多重集。
- **空队列占位**（第二十二轮）：
  清空播放队列后 `PlayerController.currentSong` **不置空**，而是置为
  `Song.placeholder`（`title = "愉乐~愉悦~"`、`artist = "Hi~"`、`album = null`，
  `isPlaceholder == true`）。因此底部播放栏与「正在播放」页保持可见：
  封面区与迷你歌词区留空、右滑的详细歌词页显示「暂无歌词」、
  时间显示 `--:--`、进度条不可拖动、上一曲/下一曲点击无效果、
  播放栏禁止左右滑动切歌（但上划仍可进入播放列表）。
- **单曲循环下手动切歌仍换曲**（第十四轮修正）：
  `PlayMode.SINGLE` 只映射到 ExoPlayer 的 `REPEAT_MODE_ONE`，即**只影响"播完自动续播"**；
  `skipToNext()` / `skipToPrevious()`（播放页与迷你播放栏的上一曲/下一曲，以及通知栏
  与耳机按键经由 MediaSession 的同类命令）在单曲循环下**仍然切换到相邻曲目**。
  Media3 已内建该语义：`getNextMediaItemIndex()` / `getPreviousMediaItemIndex()` 使用
  `getRepeatModeForNavigation()`（把 `REPEAT_MODE_ONE` 视作 `REPEAT_MODE_OFF`）。
  单曲循环下走到队列首/尾时，`PlayerController` 会环绕到另一端（仅一首歌时即重播本曲）。
- 所有"更多"入口弹出 `EntityActionSheet`。
- **删除正在播放的歌曲**（第十二轮新增，Dart 无此行为）：从曲库永久删除时，
  `LibrarySongDeleter.delete(songs)` 会同步把歌曲移出 `PlaylistRepository`，
  若被删的正是当前播放项，`PlayerController` 接续播放**原本紧随其后的那首**
  （其后已无存活项则绕回列表开头）；原本暂停则保持暂停。
  队列更新走 `PlaylistRepository.songs` → `PlayerController.syncQueue` 的既有通道，
  调用方无需手动操作 ExoPlayer 队列。

### `EntityActionSheet` 回调契约（重要）

弹窗**内部**已完成数据库写入（song/album：对每首歌 `DatabaseHelper.toggleLikeSong`；
artist：`toggleLikeArtist` + 该艺术家每首歌 `toggleLikeSong`），随后才回调
`onToggleLike(songs)`。因此调用方的 `onToggleLike` **只做刷新**
（`MusicLibrary.reload()` / 重查列表 / 更新本地 state），**不得**再次调用
`toggleLikeSong` / `toggleLikeArtist`，否则会二次取反。

回调真实语义：`onPlayNext` = "添加到播放队列"（`PlaylistRepository.addSong` 追加，
**写入已在弹窗内部完成**，调用方只作刷新通知，不得再 `addSong` —— 第十六轮起 addSong
不再判重，重复调用会入列两份）；
`onAddToPlaylist` = "添加到歌单"（调用方弹歌单选择器，内部 `addSongToPlaylist`）；
`onDelete(songs)` 之后同样只做刷新。

`deleteFromLibrary = true` 时弹窗内部执行 `LibrarySongDeleter.delete(songs)`
（= `deleteSong` + 移出播放列表）；`false` 时不写库，删除项文案取 `deleteLabel`
且始终显示（歌单详情页"从本歌单移除"）。

---

## 10. 验收要求（每个 feature 模块必须满足）

1. 只使用本规范列出的 API；不得修改 `core:*`、`designsystem`、其它 feature 的文件。
2. 编译通过（`./gradlew :feature:<x>:assembleDebug`），不出现未使用 import 造成的告警错误。
3. 页面行为、文案、排序、空状态提示与原 Dart 页面一致（逐条对照参考文件）。
4. 所有列表使用 `LazyColumn` + `key = { it.path }`（歌单用 `tag` 或下标）。
5. 长耗时操作（扫描、缓存、同步）使用 `rememberCoroutineScope().launch { ... }` + 进度状态。
6. 权限（读媒体库、悬浮窗、通知）按旧版时机申请（扫描页申请媒体权限等）。
