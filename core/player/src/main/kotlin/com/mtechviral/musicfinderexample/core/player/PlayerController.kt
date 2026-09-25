package com.mtechviral.musicfinderexample.core.player

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.media.ArtworkCache
import com.mtechviral.musicfinderexample.core.model.PlayMode
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 全局播放控制器（单例）。
 *
 * 对应原 Flutter 工程 `lib/data/audio_handler.dart` 的 `MpAudioHandler`：
 * 统一管理播放/暂停/上下首/进度/静音/播放模式，并维护当前歌曲。
 *
 * 原生端实现说明（与 Dart 端的对应关系）：
 * | Dart (audioplayers + audio_service)        | 原生 (Media3 ExoPlayer + MediaSession)        |
 * |--------------------------------------------|-----------------------------------------------|
 * | `sharedAudioPlayer`                        | [PlaybackService] 内的 ExoPlayer 实例          |
 * | `currentSong` / `isPlaying` ValueNotifier  | 同名 `StateFlow`                              |
 * | `_resolveNext(forward)` 手写下一首          | ExoPlayer 队列 + repeat/shuffle 模式           |
 * | `PlayMode.single` -> repeatMode one        | `Player.REPEAT_MODE_ONE`                      |
 * | `PlayMode.random` -> 打乱顺序               | 列表本身重排（`shuffleModeEnabled = false`）   |
 * | 通知栏/媒体会话                             | `MediaSessionService` + 自定义「歌词」按钮     |
 * | `_publishStateThrottled` 500ms             | 悬浮窗歌词推送同样节流 500ms                   |
 *
 * 播放控制通道：App 侧统一通过 `MediaController`（Media3 官方客户端）下发命令，
 * 不再直接持有服务内的 ExoPlayer 实例——见 [init] 的说明。
 */
object PlayerController {

    private const val TAG = "PlayerController"

    /** 位置推送到悬浮窗歌词的节流间隔（毫秒），与原实现一致 */
    private const val PLATFORM_POS_INTERVAL_MS = 500L

    /** 位置轮询间隔（毫秒），对应 audioplayers 的 onPositionChanged 频率 */
    private const val POSITION_POLL_MS = 200L

    /** 等待服务连接的超时（毫秒） */
    private const val CONNECT_TIMEOUT_MS = 5000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null

    /**
     * 与前台播放服务（MediaSession）连接的客户端控制器。
     *
     * 它实现了 Media3 的 [Player] 接口，App 侧的所有播放控制都通过它下发；
     * 连接成功前为 null（[awaitPlayer] 会等待连接完成）。
     */
    private var controller: MediaController? = null

    /** 进行中的连接任务（避免重复连接） */
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // ===================== 对外状态 =====================

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /** 当前歌曲时长（毫秒），未知时为 null */
    private val _duration = MutableStateFlow<Long?>(null)
    val duration: StateFlow<Long?> = _duration.asStateFlow()

    /** 当前播放位置（毫秒） */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** 当前歌曲在播放列表中的下标（-1 表示无） */
    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    /** 播放模式（代理播放列表仓库） */
    val playMode: StateFlow<PlayMode> get() = PlaylistRepository.playMode

    /** 服务是否已连接 */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var tickerJob: Job? = null
    private var lastPublishedPosMs = -1L

    /**
     * 队列因「当前歌曲被移除」而重建后，需要为接续的歌曲补一次播放收尾。
     *
     * 这类重建在 Media3 中对应的 transition reason 是 `PLAYLIST_CHANGED`，
     * 监听器据此默认跳过 `postPlayWork`；置位后由 [handleCurrentItemChanged] 消费。
     *
     * 置位发生在 `PlaylistRepository.songs` 的收集协程（`scope` = `Dispatchers.Main.immediate`），
     * 消费发生在 Media3 的 `Player.Listener` 回调。两者当前都落在主线程，`@Volatile` 仅作防御。
     */
    @Volatile
    private var postWorkAfterQueueRebuild = false

    // ===================== 初始化 / 连接 =====================

    /**
     * 初始化：连接前台播放服务的 [MediaController]（Media3 官方客户端）。
     *
     * 为什么必须走 MediaController 而不是直接取服务里的 ExoPlayer 实例：
     * Media3 的 `MediaSessionService` 只在「会话上存在已连接的 MediaController」时才
     * 展示媒体通知与媒体控制中心（`MediaNotificationManager.shouldShowNotification`
     * 会检查 `getConnectedControllerForSession(...)` 及其 timeline），
     * 直接操控 ExoPlayer 会导致通知栏完全没有播放控制器。
     *
     * 由 Application.onCreate 调用。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        LyricsOverlayManager.init(app)
        if (controller != null || controllerFuture != null) return
        try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val future = MediaController.Builder(app, token)
                .setListener(controllerListener)
                .buildAsync()
            controllerFuture = future
            future.addListener(
                {
                    try {
                        attachController(future.get())
                    } catch (e: Exception) {
                        Log.e(TAG, "连接播放服务失败", e)
                        controllerFuture = null
                    }
                },
                MoreExecutors.directExecutor(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaController 失败", e)
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(disconnected: MediaController) {
            Log.w(TAG, "播放服务连接已断开")
            disconnected.removeListener(playerListener)
            controller = null
            controllerFuture = null
            _connected.value = false
        }
    }

    /** 连接成功后接管播放控制（等价原 `attachService`） */
    private fun attachController(c: MediaController) {
        controller = c
        c.addListener(playerListener)

        // 播放列表内容变化 -> 同步会话队列
        scope.launch {
            PlaylistRepository.songs.collect { syncQueue(it) }
        }
        // 播放模式变化 -> 同步 repeat / shuffle
        scope.launch {
            PlaylistRepository.playMode.collect { applyPlayMode(it) }
        }

        _connected.value = true
        startTicker()

        // 恢复播放列表（应用启动时序：先连上服务，再恢复上次列表）
        scope.launch {
            if (PlaylistRepository.current.isEmpty()) {
                PlaylistRepository.restoreFromPrefs()
            }
            // 优化建议 09：恢复上次的当前条目（含重复曲的精确下标），
            // 只定位不自动播放；按下播放键从该条目继续
            val restoredIndex = PlaylistRepository.currentIndex
            if (restoredIndex in PlaylistRepository.current.indices) {
                _currentIndex.value = restoredIndex
                _currentSong.value = PlaylistRepository.current[restoredIndex]
            }
            applyPlayMode(PlaylistRepository.playMode.value)
            publishCurrentState()
        }
    }

    private suspend fun awaitPlayer(): Player? {
        controller?.let { return it }
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { _connected.filter { it }.first() }
        return controller
    }

    // ===================== 播放控制 =====================

    /**
     * 播放指定歌曲（按 path 定位到队列中的**第一份**后播放）。
     *
     * 支持三种播放源（与原实现一致）：
     * 1. 已缓存的远程歌曲 -> 本地文件播放
     * 2. 未缓存的远程歌曲 -> 流式播放 + 后台缓存
     * 3. 本地歌曲 -> 本地文件播放
     *
     * 注意（第十六轮）：队列允许同一首歌重复出现，本方法只按 [Song] 定位，
     * 因此**永远命中第一份**。要播放"指定那一行"请用 [playAt]。
     *
     * @param openNowPlaying 是否同时在 UI 上展开「正在播放」页。
     *   与原 Flutter 端一致：从任意列表点歌都会打开播放页（`openNowPlayingPage`），
     *   而通知栏/小组件/自动续播等场景不打开。
     */
    suspend fun playSong(song: Song, openNowPlaying: Boolean = true): Boolean =
        playAt(PlaylistRepository.current.indexOfFirst { it.path == song.path }, openNowPlaying)

    /**
     * 按**队列下标**播放（第十六轮新增）。
     *
     * 为什么需要：同一首歌可在播放列表内出现多次，只给 [Song] 无法区分是哪一份
     * （`indexOfFirst` 永远命中第一份）。播放列表页每行点击必须用本方法，
     * 才能播放"被点的那一行"。
     *
     * @param index 队列下标；越界时返回 false（不播放）
     */
    suspend fun playAt(index: Int, openNowPlaying: Boolean = true): Boolean {
        val songs = PlaylistRepository.current
        if (index < 0 || index >= songs.size) return false
        val song = songs[index]

        val p = awaitPlayer() ?: return false

        var playable = resolveCachedState(song)

        _currentSong.value = playable
        _currentIndex.value = index
        _duration.value = null
        _position.value = 0L
        lastPublishedPosMs = 0L
        publishCurrentState()

        try {
            syncQueue(songs)
            val uri = resolvePlayableUri(playable)
            // 先把通知栏封面文件准备好（仅 IO，不触碰播放器），再构建媒体项
            val artUri = prepareArtworkUri(playable)
            replaceItemIfNeeded(p, index, playable, uri, artUri)
            p.seekTo(index, 0L)
            p.prepare()
            p.play()
            _isPlaying.value = true

            if (playable.isRemote && !playable.isCached) {
                // 后台缓存音频 + 封面
                CacheService.startCaching(playable) { updated ->
                    scope.launch { mergeCurrentSong(updated, mergeArtwork = true, mergeLyrics = true) }
                }
            } else if (playable.isRemote && playable.cachedArtworkPath == null &&
                playable.coverArtId != null && playable.id != null
            ) {
                // 音频已缓存但封面可能未缓存：仅补充封面缓存
                CacheService.startCaching(playable) { updated ->
                    scope.launch { mergeCurrentSong(updated, mergeArtwork = true) }
                }
            }

            postPlayWork(playable)
            if (openNowPlaying) NowPlayingUiState.open()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "播放失败: ${e.message}")
            // 连接可能已失效（内网/公网切换）：重置，下次播放重新解析
            SubsonicService.resetConnection()
            _isPlaying.value = false
            _currentSong.value = null
            publishCurrentState()
            return false
        }
    }

    /**
     * 整列播放：替换播放列表并从指定下标开始。
     *
     * 注意（第二十二轮需求 4）：随机模式下 [PlaylistRepository.setSongs] 会**重排队列**，
     * 因此不能把 [startIndex] 直接当作"入列后"的下标（会播成另一首）。
     *
     * 优化建议 09：目标条目按 **(path, 同名第几份)** 精确定位 ——
     * 同一首歌在列表出现两次时，指定第二份不会错播到第一份；
     * 队列被重排（随机）后按"同名第几份"在新队列中定位对应条目。
     */
    suspend fun playSongs(songs: List<Song>, startIndex: Int = 0): Boolean {
        if (songs.isEmpty()) return false
        val idx = startIndex.coerceIn(0, songs.size - 1)
        val target = songs[idx]
        // 目标是同 path 条目中的第几份（从 0 计）
        val occurrence = songs.take(idx).count { it.path == target.path }

        PlaylistRepository.setSongs(songs)

        val queue = PlaylistRepository.current
        var seen = 0
        var targetIndex = -1
        for ((i, s) in queue.withIndex()) {
            if (s.path == target.path) {
                if (seen == occurrence) {
                    targetIndex = i
                    break
                }
                seen++
            }
        }
        if (targetIndex < 0) targetIndex = queue.indexOfFirst { it.path == target.path }
        return playAt(targetIndex)
    }

    /**
     * 「随机播放」：把 [songs] 随机排序后作为播放列表并从头播放。
     *
     * 关键点：**随机化的是播放列表本身**，之后按列表顺序顺序播放
     * （需求：进入随机模式时随机排序当前歌曲列表，然后顺序播放该列表）。
     * 因此这里不能再依赖 ExoPlayer 的 `shuffleModeEnabled`——那不会改变列表
     * 顺序，界面上看到的列表与实际播放顺序会对不上。
     *
     * 排序由 [PlaylistRepository.playShuffled] 完成并返回实际顺序，
     * 这里直接取该顺序的第 [startIndex] 首开始播放，避免二次打乱。
     */
    suspend fun playShuffled(songs: List<Song>, startIndex: Int = 0): Boolean {
        if (songs.isEmpty()) return false
        val shuffled = PlaylistRepository.playShuffled(songs)
        // 同上：用下标定位，避免重复歌曲时命中错误的那一份
        return playAt(startIndex.coerceIn(0, shuffled.size - 1))
    }

    /**
     * 「播放选中队列」（第二十六轮需求 2）：把 [songs] 插入到**当前播放歌曲之后**并立即播放第一首。
     *
     * 与 [playSongs] 的区别：不替换整个播放列表，而是在当前曲之后插入，
     * 保留原有队列与之后要播的内容（需求：不是插入到列表底部，而是插入到当前播放音乐之后）。
     *
     * 队列为空（或没有当前曲）时退化为"以 [songs] 建列表并从头播放"，
     * 否则会把歌插到队尾却不播放，与"播放选中队列"的字面预期不符。
     *
     * 插入后第一首的位置 = 原当前曲下标 + 1，因此用 [playAt] 按下标定位播放，
     * 天然正确处理"同一首歌有多份"的情况。
     */
    suspend fun playAfterCurrent(songs: List<Song>): Boolean {
        if (songs.isEmpty()) return false

        val queueWasEmpty = PlaylistRepository.current.isEmpty()
        if (queueWasEmpty) {
            // 没有当前播放曲可插入其后：等价于整列播放
            return playSongs(songs, 0)
        }

        val currentIndexBefore = _currentIndex.value
        val inserted = PlaylistRepository.insertAfterCurrent(songs)
        if (inserted <= 0) return false

        // 插入位置紧随当前曲；同步队列（syncQueue）走"中途插入"增量分支，
        // 当前曲不受影响、继续播放，这里再把播放指针移到新插入的第一首。
        return playAt(currentIndexBefore + 1)
    }

    /** 恢复播放（暂停态）或重播当前歌曲；无当前歌曲则空操作 */
    suspend fun resumeOrPlay() {
        val song = _currentSong.value ?: return
        // 占位曲目（队列已清空）没有可播放内容，且 _currentIndex 为 -1：
        // 直接返回，避免掉进下面的 playAt(-1) 分支
        if (song.isPlaceholder) return
        val p = awaitPlayer() ?: return
        when {
            // 按下标恢复：同一首歌有多份时，应重播"当前那一份"而不是第一份
            p.playbackState == Player.STATE_IDLE || p.mediaItemCount == 0 ->
                playAt(_currentIndex.value, openNowPlaying = false)
            p.isPlaying -> Unit
            else -> {
                p.play()
                _isPlaying.value = true
            }
        }
        publishCurrentState()
    }

    suspend fun pause() {
        val p = awaitPlayer()
        p?.pause()
        _isPlaying.value = false
        publishCurrentState()
    }

    suspend fun togglePlayPause() {
        if (_isPlaying.value) pause() else resumeOrPlay()
    }

    /** 供小组件等非挂起场景调用 */
    fun togglePlayPauseAsync() {
        scope.launch { togglePlayPause() }
    }

    suspend fun skipToNext() {
        val p = awaitPlayer() ?: return
        if (PlaylistRepository.current.isEmpty()) return
        p.seekTo(resolveManualSkipIndex(p, forward = true), 0L)
        p.play()
        _position.value = 0L
    }

    suspend fun skipToPrevious() {
        val p = awaitPlayer() ?: return
        if (PlaylistRepository.current.isEmpty()) return
        p.seekTo(resolveManualSkipIndex(p, forward = false), 0L)
        p.play()
        _position.value = 0L
    }

    /**
     * 手动点按「上一曲 / 下一曲」时的目标下标。
     *
     * 关键点：**单曲循环（`REPEAT_MODE_ONE`）只应作用于"播完自动续播"，
     * 手动切歌仍应切到相邻曲目**。Media3 已经内建了这个语义 ——
     * `BasePlayer.getNextMediaItemIndex()` 走的是
     * `getRepeatModeForNavigation()`（把 `REPEAT_MODE_ONE` 视作 `REPEAT_MODE_OFF`），
     * 因此 `nextMediaItemIndex` / `previousMediaItemIndex` 在单曲循环下**本来就会换曲**。
     *
     * 原先这里额外写了一个 `PlayMode.SINGLE` 分支去 `seekTo(当前下标, 0)`，
     * 那才是"点上一曲/下一曲只重播本曲"的真正原因（该分支源于对 Dart
     * `_resolveNext(single)` 的字面直译，但 Dart 那套是"自动续播"与"手动切歌"
     * 共用同一个函数，原生端两者已由 ExoPlayer 分开处理）。
     *
     * 边界：单曲循环下导航按 `REPEAT_MODE_OFF` 计算，走到队尾/队首时
     * `nextMediaItemIndex` 会返回 [C.INDEX_UNSET]（此处不会换曲）。为保持
     * 与"列表循环"一致的按键手感，此时环绕到队首/队尾；队列只有一首歌时
     * 环绕即重播本曲（与 Dart 单曲分支的效果一致）。
     */
    private fun resolveManualSkipIndex(p: Player, forward: Boolean): Int {
        val target = if (forward) p.nextMediaItemIndex else p.previousMediaItemIndex
        if (target != C.INDEX_UNSET) return target
        // 兜底：环绕到首/尾。仅单曲循环会走到这里
        // （列表循环 / 随机为 REPEAT_MODE_ALL，Media3 自身就会环绕）
        if (p.mediaItemCount <= 0) return C.INDEX_UNSET
        return if (forward) 0 else p.mediaItemCount - 1
    }

    suspend fun seekTo(positionMs: Long) {
        val p = awaitPlayer() ?: return
        p.seekTo(positionMs.coerceAtLeast(0L))
        _position.value = positionMs.coerceAtLeast(0L)
        lastPublishedPosMs = _position.value
        updateFloatingLyrics()
    }

    suspend fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        awaitPlayer()?.volume = if (muted) 0f else 1f
    }

    /** 切换播放模式（顺序 / 随机 / 单曲） */
    fun togglePlayMode(): PlayMode = PlaylistRepository.togglePlayMode()

    fun setPlayMode(mode: PlayMode) = PlaylistRepository.setPlayMode(mode)

    /** 停止播放并清空播放列表（用于"清空播放列表"按钮） */
    suspend fun stopAndClear() {
        val p = awaitPlayer()
        p?.stop()
        p?.clearMediaItems()
        _isPlaying.value = false
        // 第二十二轮需求 5：清空后**不置空** currentSong，而是放入占位曲目，
        // 使底部播放栏与播放页保持可见（歌名「愉乐~愉悦~」/ 歌手「Hi~」）。
        _currentSong.value = Song.placeholder
        _currentIndex.value = -1
        _duration.value = null
        _position.value = 0L
        PlaylistRepository.clear()
        publishCurrentState()
    }

    /**
     * 播放列表中的当前歌曲被移除后调用。
     *
     * 第二十二轮需求 5：队列**已空**时改为放入占位曲目（保留播放栏与播放页）；
     * 队列仍有歌曲（只是当前项没了）时保持原有的"清空当前歌曲"语义。
     */
    private fun onCurrentSongRemoved() {
        _currentSong.value = if (PlaylistRepository.length == 0) Song.placeholder else null
        _currentIndex.value = -1
        _duration.value = null
        _position.value = 0L
        publishCurrentState()
    }

    // ===================== 队列同步 =====================

    /**
     * 把播放列表镜像到 ExoPlayer 队列。
     *
     * 采用增量更新（追加 / 截断）以保持当前播放项与进度不中断；
     * 结构发生其它变化时重建队列，并尽量保持当前歌曲与播放位置。
     *
     * 特例——**当前歌曲被移出播放列表**（如从曲库中永久删除）：
     * 若新列表只是旧列表的子序列（纯移除），则接续播放原本紧随其后的那首，
     * 而不是停在队首并清空当前歌曲；其余情况（切换到另一张列表）保持原行为，
     * 由调用方的 `playSong(...)` 接管。
     */
    private fun syncQueue(songs: List<Song>) {
        val p = controller ?: return
        val existingIds = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        val newIds = songs.map { it.path }
        if (existingIds == newIds) return

        if (songs.isEmpty()) {
            p.clearMediaItems()
            // 队列已空：当前歌曲状态必须一并清空，否则播放栏会继续展示一首已不存在的歌
            onCurrentSongRemoved()
            return
        }

        val wasPlaying = p.playWhenReady
        // 当前播放项以**位置**定位：第十六轮起同一首歌可在列表中重复出现，
        // mediaId 不再唯一，因此不能用 `newIds.indexOf(mediaId)` 去找"当前项"。
        val currentIndex = p.currentMediaItemIndex
        // 纯移除（新列表更短且是旧列表的子序列）。允许重复 —— 用贪心匹配求"存活下标"，
        // 而不是旧的 `id !in newIds`（重复项会被误判成"应当保留"，导致删不掉）。
        val keepIndices = if (newIds.size < existingIds.size) {
            greedyKeepIndices(newIds, existingIds)
        } else {
            null
        }
        // 纯重排：数量一致且**多重集**相同（同一首歌的重复份数也一致），仅顺序不同。
        // 要求当前项有效 —— 否则没有"要保持不动的那一项"，交给 else 分支重建即可。
        val pureReorder = currentIndex >= 0 &&
            newIds.size == existingIds.size &&
            existingIds.sorted() == newIds.sorted()

        // 中途插入（第二十六轮需求 2）：队列变长、旧列表是新列表的子序列（无删除/重排），
        // 且**所有新增项都排在当前播放项之后**（因此当前项下标不变、播放不受影响）。
        // 新增项还必须是连续的一段，才能用一次 `addMediaItems(index, items)` 增量添加。
        val insertIndices: List<Int>? = if (
            newIds.size > existingIds.size && currentIndex >= 0 && existingIds.isNotEmpty()
        ) {
            val keep = greedyKeepIndices(existingIds, newIds)
            if (keep == null || keep.size != existingIds.size) {
                null
            } else {
                val inserted = newIds.indices.filter { it !in keep.toHashSet() }
                when {
                    inserted.isEmpty() -> null
                    // 插入全在当前项之后 → 当前项下标保持 currentIndex 不变
                    inserted.first() <= currentIndex -> null
                    // 必须是连续的一段，否则一次 addMediaItems 无法表达
                    inserted != (inserted.first()..inserted.last()).toList() -> null
                    else -> inserted
                }
            }
        } else {
            null
        }

        when {
            existingIds.isEmpty() -> {
                p.setMediaItems(songs.map { buildMediaItem(it) })
                p.prepare()
                p.pause()
            }

            // 尾部追加（当前项必然存活）
            newIds.size > existingIds.size &&
                existingIds == newIds.subList(0, existingIds.size) -> {
                p.addMediaItems(songs.drop(existingIds.size).map { buildMediaItem(it) })
            }

            // 中途插入（第二十六轮需求 2「播放选中队列」）：在**当前播放曲之后**插入若干首。
            // 判定：旧列表是新的子序列，且插入位置都在当前播放项之后 ——
            // 这样当前项下标不变，可以走 `addMediaItems(index, ...)` 增量添加，
            // 避免落入下面的 else 分支整队重建（重建会打断播放并丢失精确进度）。
            insertIndices != null -> {
                val newItems = insertIndices.map { songs[it] }
                p.addMediaItems(insertIndices.first(), newItems.map { buildMediaItem(it) })
            }

            // 纯移除：按位置精确删除即可。当前项之前若有项被删，其下标会前移，需一并同步
            keepIndices != null -> {
                val removed = existingIds.indices.filter { it !in keepIndices.toHashSet() }
                if (currentIndex !in removed) {
                    // 从后往前删，保证下标不失效
                    for (i in removed.asReversed()) {
                        p.removeMediaItem(i)
                    }
                    if (currentIndex >= 0) _currentIndex.value = p.currentMediaItemIndex
                } else {
                    // 被移除的正是当前播放项：接续播放原本紧随其后的那首
                    // （需求：删除正在播放的歌曲后继续播放下一首）。
                    // 按**位置**计算接续项，同一首歌有多份时也能唯一确定。
                    val successor = successorIndexAfterRemoval(keepIndices, removed, currentIndex)
                    // 这类重建的 transition reason 是 PLAYLIST_CHANGED，监听器默认不补播放收尾
                    // （累计播放次数 / 预缓存下一首 / 拉歌词），这里先置位、由监听器消费。
                    // 仅当原本确有当前项时才会产生 transition，否则置位将无人消费。
                    if (currentIndex >= 0) postWorkAfterQueueRebuild = true
                    p.setMediaItems(songs.map { buildMediaItem(it) }, successor, 0L)
                    // 原本在播放则继续播放下一首；原本暂停则保持暂停（删除不应擅自开始播放）
                    p.playWhenReady = wasPlaying
                    p.prepare()
                }
            }

            // 纯重排（同一批歌曲换了顺序，如"进入随机播放"）：队列顺序必须真正下发，
            // 否则随机后的播放顺序与界面显示的列表顺序不一致。当前歌曲与进度保持不变。
            pureReorder -> {
                val newIndex = occurrenceMappedIndex(existingIds, newIds, currentIndex)
                val resumePosition = p.currentPosition
                p.setMediaItems(songs.map { buildMediaItem(it) }, newIndex, resumePosition)
                p.playWhenReady = wasPlaying
                p.prepare()
                // 重排后当前歌曲仍在队列中、其 window uid 未变 —— Media3 的
                // evaluateMediaItemTransitionReason 只在 window uid 变化时才回调
                // onMediaItemTransition，所以这里必须自己同步下标（_currentIndex
                // 还驱动 precacheNext 的"下一首"计算，不同步会预缓存错歌）。
                _currentIndex.value = newIndex
            }

            else -> {
                val newIndex = occurrenceMappedIndex(existingIds, newIds, currentIndex)
                val resumePosition = p.currentPosition
                p.setMediaItems(
                    songs.map { buildMediaItem(it) },
                    if (newIndex >= 0) newIndex else 0,
                    if (newIndex >= 0) resumePosition else 0L,
                )
                p.playWhenReady = wasPlaying && newIndex >= 0
                p.prepare()
                if (newIndex < 0) {
                    // 当前歌曲已被移除：与 Dart 端一致，不中断已开始的播放，
                    // 但清空"当前歌曲"展示状态
                    onCurrentSongRemoved()
                }
            }
        }
    }

    /**
     * 当前播放项在**新列表**中的下标。
     *
     * 第十六轮起同一首歌可在队列内重复出现，mediaId 不再唯一，
     * 因此不能再用 `newIds.indexOf(mediaId)`；这里改按**出现次数**定位：
     * 先算出当前项是 [oldIds] 中该 id 的第几份（occurrence），
     * 再在 [newIds] 中找同样的第几份。
     *
     * @return 新下标；当前项在新列表中已不存在（或 [oldIndex] 无效）时返回 -1。
     */
    private fun occurrenceMappedIndex(
        oldIds: List<String>,
        newIds: List<String>,
        oldIndex: Int,
    ): Int {
        val id = oldIds.getOrNull(oldIndex) ?: return -1
        val occurrence = oldIds.take(oldIndex + 1).count { it == id } - 1
        var seen = -1
        for (i in newIds.indices) {
            if (newIds[i] == id) {
                seen++
                if (seen == occurrence) return i
            }
        }
        return -1
    }

    /**
     * 贪心匹配：返回"把 [target] 按顺序匹配到 [full] 上"所得的 [full] 下标列表；
     * 若 [target] 不是 [full] 的子序列则返回 null。
     *
     * 与旧的 `isSubsequence` 不同之处：这里返回**具体下标**，因而能正确处理
     * "同一首歌在队列里出现多次"的情况。旧实现用 `id !in newIds` 反推被删项，
     * 遇到重复项会把两份都当成"应当保留"，结果一份也删不掉。
     */
    private fun greedyKeepIndices(target: List<String>, full: List<String>): List<Int>? {
        val keep = ArrayList<Int>(target.size)
        var i = 0
        for (t in target) {
            while (i < full.size && full[i] != t) i++
            if (i >= full.size) return null
            keep.add(i)
            i++
        }
        return keep
    }

    /**
     * 计算「当前播放项被移出队列」后应接续播放的**新列表下标**。
     *
     * @param keep 存活的旧下标（升序）
     * @param removed 被删的旧下标
     * @param currentIndex 当前播放项在旧列表中的下标
     *
     * 优先接续紧随其后的首个存活项；若其后已无存活项（被删的是最后一首），
     * 则绕回列表开头（等价列表循环的自然行为）。全程按位置计算，
     * 因此同一首歌有多份时也能唯一确定接续项。
     */
    private fun successorIndexAfterRemoval(
        keep: List<Int>,
        removed: List<Int>,
        currentIndex: Int,
    ): Int {
        val removedSet = removed.toHashSet()
        val oldSize = keep.size + removed.size
        for (old in (currentIndex + 1) until oldSize) {
            if (old !in removedSet) return keep.indexOf(old)
        }
        return 0
    }

    private fun buildMediaItem(
        song: Song,
        uriOverride: String? = null,
        artworkUriOverride: android.net.Uri? = null,
    ): MediaItem {
        val uri = uriOverride ?: song.playablePath
        // 封面地址使用确定性文件路径：即便文件此刻尚未写好，后续（预缓存）补齐后
        // 通知栏再次刷新即可拿到封面；不依赖播放中替换媒体项（那会打断播放）。
        val artworkUri = artworkUriOverride ?: artworkFile(song)?.toUri()
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.album ?: "")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                if (artworkUri != null) setArtworkUri(artworkUri)
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(song.path)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /** 仅当队列中该项的 URI / 封面与期望不一致时才替换（避免无谓重载） */
    private fun replaceItemIfNeeded(
        p: Player,
        index: Int,
        song: Song,
        uri: String,
        artworkUri: android.net.Uri?,
    ) {
        if (index < 0 || index >= p.mediaItemCount) return
        val existing = p.getMediaItemAt(index)
        // MediaController 收到的 MediaItem 可能不含 localConfiguration（URI 经 bundle 传输），
        // 此时只以 mediaId 判定「同一首歌」，避免每次播放都替换媒体项而打断播放
        val existingUri = existing.localConfiguration?.uri?.toString()
        val sameUri = existingUri == null || existingUri == uri
        val sameArt = artworkUri == null || existing.mediaMetadata.artworkUri == artworkUri
        if (existing.mediaId == song.path && sameUri && sameArt) return
        p.replaceMediaItem(index, buildMediaItem(song, uri, artworkUri))
    }

    /**
     * 播放模式的 repeat / shuffle 映射。
     *
     * 注意（第十五轮调整）：**随机模式不再依赖播放器的 shuffle**。
     * 需求是"进入随机播放时随机排序当前歌曲列表，然后顺序播放该列表"，
     * 即随机化发生在**列表本身**（由 `PlaylistRepository` 重排，见
     * `setPlayMode` / `playShuffled`），之后按列表顺序顺序播放即可。
     * 若仍打开 `shuffleModeEnabled`，播放器会在已打乱的列表上**再乱序一次**，
     * 与界面显示的列表顺序不一致，因此这里统一关闭 shuffle。
     */
    private fun applyPlayMode(mode: PlayMode) {
        val p = controller ?: return
        when (mode) {
            PlayMode.SEQUENTIAL -> {
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_ALL
            }

            // 列表本身已被随机重排，按顺序播放即可（列表循环）
            PlayMode.RANDOM -> {
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_ALL
            }

            PlayMode.SINGLE -> {
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_ONE
            }
        }
    }

    // ===================== 播放收尾（统计 / 预缓存 / 歌词） =====================

    private fun postPlayWork(song: Song) {
        // 递增播放次数（后台执行，不阻塞播放）
        song.id?.let { id -> ioScope.launch { DatabaseHelper.incrementPlayCount(id) } }
        // 预缓存下一首
        precacheNext()
        // 后台获取歌词（仅当歌曲尚无歌词时）
        if (song.isRemote && song.lyrics.isNullOrEmpty()) {
            fetchLyricsInBackground(song)
        }
    }

    /** 预缓存队列中的下一首远程歌曲 */
    private fun precacheNext() {
        val songs = PlaylistRepository.current
        if (songs.isEmpty()) return
        val idx = _currentIndex.value
        if (idx < 0) return
        val nextIdx = (idx + 1) % songs.size
        val nextSong = songs[nextIdx]
        if (nextSong.isRemote && !nextSong.isCached) {
            CacheService.startCaching(nextSong)
        }
        // 提前准备下一首的通知栏封面
        ensureArtworkFile(nextSong)
    }

    /**
     * 后台获取远程歌曲歌词。
     *
     * 注意：歌词获取仅依赖 artist 与 title，不依赖 song.id；
     * 初次从在线列表播放的歌曲尚未入库（id 为 null）时同样可获取。
     */
    private fun fetchLyricsInBackground(song: Song) {
        val artist = song.artist
        if (artist.isNullOrBlank() || song.title.isBlank()) return
        ioScope.launch {
            try {
                val lyrics = SubsonicService.getLyrics(artist, song.title)
                if (!lyrics.isNullOrEmpty()) {
                    song.id?.let { DatabaseHelper.updateSongLyrics(it, lyrics) }
                    val cur = _currentSong.value
                    val base = if (cur != null && cur.path == song.path) cur else song
                    val updated = base.copy(lyrics = lyrics)
                    if (cur != null && cur.path == song.path) {
                        _currentSong.value = updated
                    }
                    mergeCurrentSong(updated, mergeArtwork = false, mergeLyrics = true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "歌词获取异常: ${e.message}")
            }
        }
    }

    /** 将异步更新的 Song 字段合并到当前歌曲，避免互相覆盖 */
    private suspend fun mergeCurrentSong(
        updated: Song,
        mergeArtwork: Boolean = false,
        mergeLyrics: Boolean = false,
    ) {
        val cur = _currentSong.value
        if (cur == null || cur.path != updated.path) {
            // 正在播放另一首歌：仍更新播放列表，使缓存状态反映到列表 UI
            PlaylistRepository.updateSong(updated)
            MusicLibrary.updateSong(updated)
            return
        }
        var merged = cur
        if (mergeArtwork) {
            merged = merged.copy(
                cachedPath = updated.cachedPath ?: merged.cachedPath,
                cachedArtworkPath = updated.cachedArtworkPath ?: merged.cachedArtworkPath,
            )
        }
        if (mergeLyrics && !updated.lyrics.isNullOrEmpty()) {
            merged = merged.copy(lyrics = updated.lyrics)
        }
        _currentSong.value = merged
        val newArtworkPath = updated.cachedArtworkPath
        if (mergeArtwork && newArtworkPath != null && newArtworkPath != cur.cachedArtworkPath) {
            ArtworkCache.invalidate(cur.path)
            ArtworkCache.invalidateByPathPrefix(newArtworkPath)
            ensureArtworkFile(merged)
        }
        PlaylistRepository.updateSong(merged)
        MusicLibrary.updateSong(merged)
        updateWidget()
        updateFloatingLyrics()
    }

    // ===================== 播放器监听 =====================

    private val playerListener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            publishCurrentState()
            // 暂停/继续/播完都要把最新播放状态同步给悬浮窗歌词，
            // 否则服务侧会一直按"播放中"外推位置，歌词行会自己往前走
            updateFloatingLyrics()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _duration.value = currentDurationOrNull()
            } else if (playbackState == Player.STATE_ENDED) {
                _isPlaying.value = false
                publishCurrentState()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val allowPostWork = reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
            handleCurrentItemChanged(allowPostWork)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            _position.value = newPosition.positionMs.coerceAtLeast(0L)
            // 单曲循环自动重播：同一首歌重新开始，同样要累计播放次数并刷新歌词
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                oldPosition.mediaItemIndex == newPosition.mediaItemIndex
            ) {
                val song = _currentSong.value
                if (song != null) {
                    _position.value = 0L
                    postPlayWork(song)
                }
            }
            updateFloatingLyrics()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "播放失败: ${error.message}")
            // 连接可能已失效（内网/公网切换）：重置，下次播放重新解析
            SubsonicService.resetConnection()
            _isPlaying.value = false
            publishCurrentState()
        }
    }

    /**
     * ExoPlayer 当前项变化（自动切换 / 手动上下首 / 队列重建）后同步状态。
     *
     * @param allowPostWork 是否执行"播放收尾"（累计播放次数 / 预缓存 / 拉歌词）。
     *   playSong 自己已经做过收尾，因此由它触发的 SEEK 过渡通过 path 相同被去重。
     */
    private fun handleCurrentItemChanged(allowPostWork: Boolean) {
        val p = controller ?: return
        // 队列因"当前歌曲被移除"重建：此时 reason 为 PLAYLIST_CHANGED，
        // 但接续的歌曲是首次播放，仍需补做收尾（只用一次）
        val postWork = allowPostWork || postWorkAfterQueueRebuild
        postWorkAfterQueueRebuild = false
        val songs = PlaylistRepository.current
        val idx = p.currentMediaItemIndex
        _currentIndex.value = idx
        val song = songs.getOrNull(idx) ?: return
        val prev = _currentSong.value
        if (prev?.path == song.path) {
            _duration.value = currentDurationOrNull()
            _position.value = p.currentPosition
            return
        }
        val resolved0 = song
        scope.launch {
            val resolved = resolveCachedState(resolved0)
            _currentSong.value = resolved
            _duration.value = currentDurationOrNull()
            _position.value = p.currentPosition.coerceAtLeast(0L)
            ensureArtworkFile(resolved)
            publishCurrentState()
            if (postWork) postPlayWork(resolved)
        }
    }

    // ===================== 位置轮询 =====================

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)
                val p = controller ?: continue
                val pos = p.currentPosition.coerceAtLeast(0L)
                _position.value = pos
                if (_isPlaying.value) {
                    _duration.value = currentDurationOrNull()
                    // 节流推送悬浮窗歌词
                    if (lastPublishedPosMs < 0 ||
                        kotlin.math.abs(pos - lastPublishedPosMs) >= PLATFORM_POS_INTERVAL_MS
                    ) {
                        lastPublishedPosMs = pos
                        updateFloatingLyrics()
                    }
                }
            }
        }
    }

    private fun currentDurationOrNull(): Long? {
        val d = controller?.duration ?: return null
        return if (d == androidx.media3.common.C.TIME_UNSET || d <= 0) null else d
    }

    // ===================== 通知栏 / 悬浮窗 / 小组件 =====================

    /** 更新悬浮窗歌词内容（将原始歌词与播放位置交给服务自行同步） */
    private fun updateFloatingLyrics() {
        if (!LyricsOverlayManager.isVisible.value) return
        LyricsOverlayManager.updateLyrics(
            lyrics = _currentSong.value?.lyrics,
            positionMs = _position.value,
            isPlaying = _isPlaying.value,
        )
    }

    private fun publishCurrentState() {
        // 第二十二轮需求 4：把当前播放曲回填给队列仓库，
        // 供"进入随机模式时把当前曲钉在首位"使用。
        // 放在这里是因为所有改变当前歌曲的路径（playAt / 自动续播 / 停止清空）
        // 最后都会走 publishCurrentState()，单点同步不会漏。
        // 占位曲目不是真实歌曲（其 path 是伪标识），不能写进队列仓库。
        // 优化建议 09：同时回填当前下标（重复曲以条目下标定位）。
        val cur = _currentSong.value
        if (cur == null || cur.isPlaceholder) {
            PlaylistRepository.currentPath = null
            PlaylistRepository.currentIndex = -1
        } else {
            PlaylistRepository.currentPath = cur.path
            PlaylistRepository.currentIndex = _currentIndex.value
        }
        updateWidget()
    }

    private fun updateWidget() {
        val ctx = appContext ?: return
        MusicWidgetUpdater.updateFromSong(ctx, _currentSong.value, _isPlaying.value)
    }

    // ===================== 封面文件（供通知栏使用） =====================

    /**
     * 通知栏封面文件路径（按歌曲路径哈希命名，确定性可预测）。
     *
     * 与原实现一致：把内嵌/缓存的封面字节写入磁盘文件，并把 `file://` 地址
     * 写入 MediaItem 的 `artworkUri`，由 Media3 的通知栏自行加载。
     */
    private fun artworkFile(song: Song): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "artwork").apply { if (!exists()) mkdirs() }
        return File(dir, "art_${song.path.hashCode()}.img")
    }

    /**
     * 确保封面文件就绪并返回其 `file://` 地址（在开始播放前调用）。
     *
     * 注意：**不访问 ExoPlayer**（ExoPlayer 要求单线程访问，只能在其构造线程使用），
     * 仅做"读取封面字节 + 落盘"的 IO 工作。
     */
    private suspend fun prepareArtworkUri(song: Song): android.net.Uri? {
        val file = artworkFile(song) ?: return null
        if (file.exists() && file.length() > 0) return file.toUri()
        return try {
            val bytes = ArtworkCache.load(song.path, song.cachedArtworkPath) ?: return null
            if (bytes.isEmpty()) return null
            withContextIo { file.writeBytes(bytes) }
            file.toUri()
        } catch (e: Exception) {
            Log.w(TAG, "准备通知栏封面失败: ${e.message}")
            null
        }
    }

    /** 后台预热某首歌的通知栏封面文件（只做 IO，同样不访问播放器） */
    private fun ensureArtworkFile(song: Song) {
        ioScope.launch {
            try {
                val file = artworkFile(song) ?: return@launch
                if (file.exists() && file.length() > 0) return@launch
                val bytes = ArtworkCache.load(song.path, song.cachedArtworkPath) ?: return@launch
                if (bytes.isNotEmpty()) file.writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "预热通知栏封面失败: ${e.message}")
            }
        }
    }

    private fun File.toUri(): android.net.Uri = android.net.Uri.fromFile(this)

    /** 解析歌曲的真实播放地址（远程歌曲重新解析内网/公网） */
    private suspend fun resolvePlayableUri(song: Song): String {
        val cachedPath = song.cachedPath
        if (song.isCached && cachedPath != null && File(cachedPath).exists()) {
            // 播放命中缓存：刷新「最近使用」时间，使淘汰接近严格 LRU（优化建议 05）
            song.id?.let { DatabaseHelper.touchSongCache(it) }
            return cachedPath
        }
        val remoteId = song.remoteId
        if (song.isRemote && remoteId != null) {
            return try {
                SubsonicService.getStreamUrl(remoteId)
            } catch (e: Exception) {
                Log.w(TAG, "解析远程地址失败，回退原始地址: ${e.message}")
                song.path
            }
        }
        return song.path
    }

    /** 远程歌曲：先尝试从数据库确认是否已缓存，避免重复触发缓存下载 */
    private suspend fun resolveCachedState(song: Song): Song {
        val remoteId = song.remoteId
        if (!song.isRemote || song.isCached || remoteId == null) return song
        val dbSong = DatabaseHelper.querySongByRemoteId(remoteId) ?: return song
        val cached = dbSong.cachedPath
        if (dbSong.isCached && cached != null && withContextIo { File(cached).exists() }) {
            // 命中缓存同样刷新「最近使用」时间（优化建议 05）
            song.id?.let { DatabaseHelper.touchSongCache(it) }
            return song.copy(cachedPath = cached)
        }
        return song
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
