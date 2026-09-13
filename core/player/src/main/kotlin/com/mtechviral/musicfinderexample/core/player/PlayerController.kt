package com.mtechviral.musicfinderexample.core.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
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
 * | `PlayMode.random` -> 打乱顺序               | `shuffleModeEnabled = true`                   |
 * | 通知栏/媒体会话                             | `MediaSessionService` + 自定义「歌词」按钮     |
 * | `_publishStateThrottled` 500ms             | 悬浮窗歌词推送同样节流 500ms                   |
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
    private var bound = false

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

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

    // ===================== 初始化 / 连接 =====================

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) = Unit
        override fun onBindingDied(name: ComponentName?) {
            bound = false
        }
    }

    /**
     * 初始化：绑定前台播放服务。
     * 由 Application.onCreate 调用；服务创建后会回调 [attachService]。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (bound) return
        bound = try {
            app.bindService(
                Intent(app, PlaybackService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (e: Exception) {
            Log.e(TAG, "绑定播放服务失败", e)
            false
        }
        LyricsOverlayManager.init(app)
    }

    /** 由 [PlaybackService] 在 onCreate 中调用 */
    fun attachService(
        service: PlaybackService,
        exoPlayer: ExoPlayer,
        mediaSession: MediaSession,
    ) {
        this.player = exoPlayer
        this.session = mediaSession
        appContext = service.applicationContext

        exoPlayer.addListener(playerListener)

        // 播放列表内容变化 -> 同步 ExoPlayer 队列
        scope.launch {
            PlaylistRepository.songs.collect { syncQueue(it) }
        }
        // 播放模式变化 -> 同步 repeat / shuffle
        scope.launch {
            PlaylistRepository.playMode.collect { applyPlayMode(it) }
        }
        // 悬浮窗可见性/锁定状态变化 -> 刷新通知栏按钮图标
        scope.launch {
            LyricsOverlayManager.isVisible.collect { refreshLyricsButton() }
        }
        scope.launch {
            LyricsOverlayManager.isLocked.collect { refreshLyricsButton() }
        }

        _connected.value = true
        startTicker()

        // 恢复播放列表（应用启动时序：先绑定服务，再恢复上次列表）
        scope.launch {
            if (PlaylistRepository.current.isEmpty()) {
                PlaylistRepository.restoreFromPrefs()
            }
            applyPlayMode(PlaylistRepository.playMode.value)
            publishCurrentState()
        }
    }

    /** 由 [PlaybackService] 在 onDestroy 中调用 */
    fun detachService() {
        tickerJob?.cancel()
        tickerJob = null
        player?.removeListener(playerListener)
        player = null
        session = null
        _connected.value = false
    }

    private suspend fun awaitPlayer(): ExoPlayer? {
        player?.let { return it }
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { _connected.filter { it }.first() }
        return player
    }

    // ===================== 播放控制 =====================

    /**
     * 播放指定歌曲（重新定位到列表中的索引后播放）。
     *
     * 支持三种播放源（与原实现一致）：
     * 1. 已缓存的远程歌曲 -> 本地文件播放
     * 2. 未缓存的远程歌曲 -> 流式播放 + 后台缓存
     * 3. 本地歌曲 -> 本地文件播放
     *
     * @param openNowPlaying 是否同时在 UI 上展开「正在播放」页。
     *   与原 Flutter 端一致：从任意列表点歌都会打开播放页（`openNowPlayingPage`），
     *   而通知栏/小组件/自动续播等场景不打开。
     */
    suspend fun playSong(song: Song, openNowPlaying: Boolean = true): Boolean {
        val songs = PlaylistRepository.current
        val idx = songs.indexOfFirst { it.path == song.path }
        if (idx < 0) return false

        val p = awaitPlayer() ?: return false

        var playable = resolveCachedState(song)

        _currentSong.value = playable
        _currentIndex.value = idx
        _duration.value = null
        _position.value = 0L
        lastPublishedPosMs = 0L
        publishCurrentState()

        try {
            syncQueue(songs)
            val uri = resolvePlayableUri(playable)
            replaceItemIfNeeded(p, idx, playable, uri)
            ensureArtworkFile(playable)
            p.seekTo(idx, 0L)
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

    /** 整列播放：替换播放列表并从指定下标开始 */
    suspend fun playSongs(songs: List<Song>, startIndex: Int = 0): Boolean {
        if (songs.isEmpty()) return false
        PlaylistRepository.setSongs(songs)
        val target = songs.getOrNull(startIndex.coerceIn(0, songs.size - 1)) ?: return false
        return playSong(target)
    }

    /** 恢复播放（暂停态）或重播当前歌曲；无当前歌曲则空操作 */
    suspend fun resumeOrPlay() {
        val song = _currentSong.value ?: return
        val p = awaitPlayer() ?: return
        when {
            p.playbackState == Player.STATE_IDLE || p.mediaItemCount == 0 ->
                playSong(song, openNowPlaying = false)
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
        val songs = PlaylistRepository.current
        if (songs.isEmpty()) return
        if (PlaylistRepository.playMode.value == PlayMode.SINGLE && _currentIndex.value >= 0) {
            // 单曲循环：原 `_resolveNext(single)` 返回当前歌曲本身
            p.seekTo(_currentIndex.value, 0L)
            p.play()
            _position.value = 0L
            return
        }
        p.seekToNextMediaItem()
        p.play()
    }

    suspend fun skipToPrevious() {
        val p = awaitPlayer() ?: return
        val songs = PlaylistRepository.current
        if (songs.isEmpty()) return
        if (PlaylistRepository.playMode.value == PlayMode.SINGLE && _currentIndex.value >= 0) {
            p.seekTo(_currentIndex.value, 0L)
            p.play()
            _position.value = 0L
            return
        }
        p.seekToPreviousMediaItem()
        p.play()
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
        _currentSong.value = null
        _currentIndex.value = -1
        _duration.value = null
        _position.value = 0L
        PlaylistRepository.clear()
        publishCurrentState()
    }

    /** 播放列表中的当前歌曲被移除后调用：清空当前歌曲状态 */
    private fun onCurrentSongRemoved() {
        _currentSong.value = null
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
     */
    private fun syncQueue(songs: List<Song>) {
        val p = player ?: return
        val existingIds = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }
        val newIds = songs.map { it.path }
        if (existingIds == newIds) return

        if (songs.isEmpty()) {
            p.clearMediaItems()
            return
        }

        when {
            existingIds.isEmpty() -> {
                p.setMediaItems(songs.map { buildMediaItem(it) })
                p.prepare()
                p.pause()
            }

            newIds.size > existingIds.size && existingIds == newIds.subList(0, existingIds.size) -> {
                // 尾部追加
                p.addMediaItems(songs.drop(existingIds.size).map { buildMediaItem(it) })
            }

            existingIds.size > newIds.size && newIds == existingIds.subList(0, newIds.size) -> {
                // 尾部截断
                p.removeMediaItems(newIds.size, existingIds.size)
            }

            else -> {
                val currentMediaId = p.currentMediaItem?.mediaId
                val newIndex = newIds.indexOf(currentMediaId)
                val resumePosition = p.currentPosition
                val wasPlaying = p.playWhenReady
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

    private fun buildMediaItem(song: Song, uriOverride: String? = null): MediaItem {
        val uri = uriOverride ?: song.playablePath
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.displayArtist)
            .setAlbumTitle(song.album ?: "")
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                artworkFileFor(song)?.let { setArtworkUri(it.toUri()) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(song.path)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /** 仅当队列中该项的 URI 与期望不一致时才替换（避免无谓重载） */
    private fun replaceItemIfNeeded(
        p: ExoPlayer,
        index: Int,
        song: Song,
        uri: String,
    ) {
        if (index < 0 || index >= p.mediaItemCount) return
        val existing = p.getMediaItemAt(index)
        if (existing.mediaId == song.path && existing.localConfiguration?.uri.toString() == uri) return
        p.replaceMediaItem(index, buildMediaItem(song, uri))
    }

    /** 播放模式的 repeat / shuffle 映射（与 Dart 端发布的通知栏状态一致） */
    private fun applyPlayMode(mode: PlayMode) {
        val p = player ?: return
        when (mode) {
            PlayMode.SEQUENTIAL -> {
                p.shuffleModeEnabled = false
                p.repeatMode = Player.REPEAT_MODE_ALL
            }

            PlayMode.RANDOM -> {
                p.shuffleModeEnabled = true
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
        val p = player ?: return
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
            if (allowPostWork) postPlayWork(resolved)
        }
    }

    // ===================== 位置轮询 =====================

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                delay(POSITION_POLL_MS)
                val p = player ?: continue
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
        val d = player?.duration ?: return null
        return if (d == androidx.media3.common.C.TIME_UNSET || d <= 0) null else d
    }

    // ===================== 通知栏 / 悬浮窗 / 小组件 =====================

    /** 刷新通知栏「歌词」按钮图标与文案 */
    private fun refreshLyricsButton() {
        val ctx = appContext ?: return
        val s = session ?: return
        s.setCustomLayout(
            PlaybackService.buildLyricsButtons(
                ctx,
                LyricsOverlayManager.isVisible.value,
                LyricsOverlayManager.isLocked.value,
            ),
        )
    }

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
        updateWidget()
    }

    private fun updateWidget() {
        val ctx = appContext ?: return
        MusicWidgetUpdater.updateFromSong(ctx, _currentSong.value, _isPlaying.value)
    }

    // ===================== 封面文件（供通知栏使用） =====================

    /**
     * 通知栏封面：把内嵌/缓存的封面字节写入磁盘文件，
     * 并把 `file://` 地址写入 MediaItem 的 artworkUri（与原实现一致）。
     */
    private fun artworkFileFor(song: Song): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "artwork").apply { if (!exists()) mkdirs() }
        val file = File(dir, "art_${song.path.hashCode()}.img")
        return if (file.exists() && file.length() > 0) file else null
    }

    /** 异步确保某首歌的通知栏封面文件已就绪 */
    private fun ensureArtworkFile(song: Song) {
        val ctx = appContext ?: return
        ioScope.launch {
            try {
                val dir = File(ctx.cacheDir, "artwork")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "art_${song.path.hashCode()}.img")
                if (file.exists() && file.length() > 0) return@launch
                val bytes = ArtworkCache.load(song.path, song.cachedArtworkPath) ?: return@launch
                if (bytes.isNotEmpty()) {
                    file.writeBytes(bytes)
                    // 正在播放的歌曲：刷新 MediaItem 的封面地址，让通知栏立即拿到封面
                    val p = player ?: return@launch
                    val idx = _currentIndex.value
                    if (idx in 0 until p.mediaItemCount && _currentSong.value?.path == song.path) {
                        val item = p.getMediaItemAt(idx)
                        val newMetadata = item.mediaMetadata.buildUpon()
                            .setArtworkUri(file.toUri())
                            .build()
                        // 仅在未携带封面时替换，避免打断正在进行的播放
                        if (item.mediaMetadata.artworkUri == null) {
                            p.replaceMediaItem(idx, item.buildUpon().setMediaMetadata(newMetadata).build())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "准备通知栏封面失败: ${e.message}")
            }
        }
    }

    private fun File.toUri(): android.net.Uri = android.net.Uri.fromFile(this)

    /** 解析歌曲的真实播放地址（远程歌曲重新解析内网/公网） */
    private suspend fun resolvePlayableUri(song: Song): String {
        val cachedPath = song.cachedPath
        if (song.isCached && cachedPath != null && File(cachedPath).exists()) {
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
            return song.copy(cachedPath = cached)
        }
        return song
    }

    private suspend fun <T> withContextIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }
}
