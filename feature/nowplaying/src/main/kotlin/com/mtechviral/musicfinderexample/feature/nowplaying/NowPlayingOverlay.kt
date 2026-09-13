/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart（正在播放页主体）
 * 以及 .flutter_reference/lib/widgets/mp_mini_player_bar.dart（展开进度 / 收起协议）。
 *
 * 结构对照：
 *  - `nowPlayingSlideRoute`：整页位移 = (1 - progress) * 屏高，透明度按页面实际位移
 *    （40px 行程内由全透明到不透明），progress >= 0.98 时直接落到最终位置；
 *  - `_CloseDragRecognizer`：竖向拖拽收起（见 NowPlayingGestures.kt）；
 *  - `_closeAndPop` / `_popSelf` / `_onProgressStatus`：进度归零（dismissed）后回调 onClose()；
 *  - `_buildMiddleContent` → NowPlayingMiddleContent（NowPlayingLyrics.kt）；
 *  - `build()` 的顶部信息 / 进度条 / 主控 / 底部功能行 → NowPlayingControls.kt；
 *  - `PageView(scrollDirection: vertical)` 的两页 → 这里用 `pageAnim`（Animatable 0..1）
 *    直接驱动两页位移：第 0 页「正在播放」页（上滑进播放列表、下滑收起播放页），
 *    第 1 页播放列表页（NowPlayingPlaylistPage.kt），两页共用同一张模糊封面背景。
 *    不经过 `Pager` 的吸附判定，因此「小幅下滑也完整返回播放页」等交互规则可完全自定义。
 *
 * 动画源约定（集成契约）：300ms 补间由 `NowPlayingUiState.open()/close()` 内部完成
 * （等价 Dart `nowPlayingController.animateTo`），本页**不再**对 progress 做第二次补间，
 * 位移 / 透明度每帧直接读 progress；手势期间用 setProgress 跟手（内部即时赋值并打断补间）。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.LyricsOverlayManager
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 歌词面板的进出补间时长（Dart：`_lyricsController` 300ms） */
private const val LYRICS_ANIM_MS = 300

/** 竖向翻页（播放页 ↔ 播放列表）补间时长（Dart `_pageController` 300ms） */
private const val PAGE_ANIM_MS = 300

/** 上滑翻到播放列表的就近判定比例（超过该比例或在速度上"上滑"即完成翻页） */
private const val PAGE_SWITCH_RATIO = 0.12f

/** 竖向页面位置：0 = 正在播放页，1 = 播放列表页（对应 Dart 的 PageView 两页） */
private const val NOW_PLAYING_PAGE = 0f
private const val PLAYLIST_PAGE = 1f

/** 竖向手势方向锁定阈值（Dart `_CloseDragRecognizer._lockThreshold` = 28.0） */
private val LOCK_THRESHOLD = 28.dp

/** 横向手势锁定阈值（Flutter `kTouchSlop` = 18.0） */
private val HORIZONTAL_LOCK_THRESHOLD = 18.dp

/** 页面透明度满值行程（Dart：pageMoved / 40.0） */
private val FADE_DISTANCE = 40.dp

/** 速度阈值（Dart：velocity > 300 收起、< -300 取消收起） */
private const val FLING_VELOCITY = 300f

/** 歌词面板横滑速度阈值（Dart：vx < -500 打开、> 500 关闭） */
private const val LYRIC_FLING_VELOCITY = 500f

/** 就近判定（Dart：progress < 0.5 收起） */
private const val CLOSE_RATIO = 0.5f

/** 拖拽进度下限（Dart：clamp(0.002, 1.0)，避免拖到 0 立即触发 dismissed） */
private const val MIN_DRAG_PROGRESS = 0.002f

/** 完全展开判定（Dart：p >= 0.98 直接落到最终位置，消除尾部微偏移） */
private const val SNAP_PROGRESS = 0.98f

/** 背景模糊半径（Dart `blurFilter()`；Android 12 以下 Modifier.blur 自动降级为不模糊） */
private val BACKGROUND_BLUR = 48.dp

/** 背景暗化（Dart：Colors.black.withOpacity(0.35)） */
private const val BACKGROUND_SCRIM_ALPHA = 0.35f

/** 覆盖层是否可见的进度阈值 */
private const val VISIBLE_EPSILON = 0.001f

/**
 * 「正在播放」覆盖层（原 Dart `NowPlaying` 页面）。
 *
 * 由 `:app` 常驻渲染（与底部播放栏同层），无当前歌曲
 * （`PlayerController.currentSong == null`）时整层不渲染任何内容。
 *
 * @param onClose 收起补间结束（progress 归零）后的回调（Dart `_popSelf()`）
 */
@Composable
fun NowPlayingOverlay(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()
    val progress by NowPlayingUiState.progress.collectAsStateWithLifecycle()

    val song = currentSong
    if (song == null) {
        // 无当前歌曲：整层不渲染任何内容。
        // 兜底：若进度仍处于展开态（队列被清空 / 当前歌曲被移除），把进度归零，
        // 否则底部播放栏会因 progress 恒为 1 而一直保持隐藏。
        // 以「可见性布尔量」为 key，保证每次进入展开态只触发一次 close()。
        val showing = progress > VISIBLE_EPSILON
        LaunchedEffect(showing) {
            if (showing) NowPlayingUiState.close()
        }
        return
    }
    NowPlayingContent(
        song = song,
        progress = progress,
        onClose = onClose,
        modifier = modifier,
    )
}

@Composable
private fun NowPlayingContent(
    song: Song,
    progress: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val fadeDistancePx = with(density) { FADE_DISTANCE.toPx() }
    val lockThresholdPx = with(density) { LOCK_THRESHOLD.toPx() }
    val horizontalLockPx = with(density) { HORIZONTAL_LOCK_THRESHOLD.toPx() }

    // ---------- 订阅状态 ----------
    val playlistSongs by PlaylistRepository.songs.collectAsStateWithLifecycle()
    val playMode by PlayerController.playMode.collectAsStateWithLifecycle()
    val isPlaying by PlayerController.isPlaying.collectAsStateWithLifecycle()
    val isMuted by PlayerController.isMuted.collectAsStateWithLifecycle()
    val overlayVisible by LyricsOverlayManager.isVisible.collectAsStateWithLifecycle()
    val overlayLocked by LyricsOverlayManager.isLocked.collectAsStateWithLifecycle()

    // ---------- 可见性 / 收起完成检测 ----------
    val pageVisible = progress > VISIBLE_EPSILON
    // 曾经展开过（Dart `_popped` 语义的镜像）：进度归零即收起补间结束 → onClose（_popSelf）
    var everVisible by remember { mutableStateOf(false) }

    // ---------- 封面 / 歌词面板进度（Dart _lyricsController） ----------
    val lyricsAnim = remember { Animatable(0f) }
    var lyricsOpen by remember { mutableStateOf(false) }
    val lyricsListState = rememberLazyListState()

    // ---------- 竖向翻页：0 = 播放页 / 1 = 播放列表（Dart 的竖向 PageView） ----------
    // 用 Animatable 直接驱动两页的位移，手指跟手 1:1（不再经过 Pager 的吸附判定，
    // 因此「小幅下滑也应返回播放页」这类操作逻辑可以完全自定义）。
    val pageAnim = remember { Animatable(NOW_PLAYING_PAGE) }
    // 横竖手势共用的方向锁（需求：方向锁定，互不抢占）
    val axisLock = remember { DragAxisLock() }
    // 本次竖向手势的上下文（canStart 判定后写入，供 onDrag / onEnd 使用）
    val dragContext = remember { PageDragContext() }
    val playlistListState = rememberLazyListState()

    LaunchedEffect(pageVisible) {
        if (pageVisible) {
            everVisible = true
        } else if (everVisible) {
            everVisible = false
            // 收起后重置内部位置（等价 Dart 每次打开都新建播放页）：
            // 下次展开回到「封面视图」的第 0 页，而不是停在歌词页 / 播放列表页
            lyricsOpen = false
            lyricsAnim.snapTo(0f)
            pageAnim.snapTo(NOW_PLAYING_PAGE)
            onClose()
        }
    }

    // ---------- 弹窗 ----------
    var actionSheetSong by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }

    // 喜欢状态本地镜像：DatabaseHelper 写库后 PlayerController.currentSong 不会立即刷新
    var likedOverride by remember(song.path) { mutableStateOf(song.isLiked) }

    // ---------- 动作 ----------
    /** 收起播放页：由 NowPlayingUiState 内部 300ms 补间收尾（Dart _closeAndPop） */
    val requestClose: () -> Unit = { NowPlayingUiState.close() }

    /** 打开歌词面板（Dart _openLyrics） */
    val openLyrics: () -> Unit = {
        lyricsOpen = true
        scope.launch {
            lyricsAnim.animateTo(1f, tween(LYRICS_ANIM_MS, easing = FastOutSlowInEasing))
        }
    }

    /** 关闭歌词面板（Dart _closeLyrics） */
    val closeLyrics: () -> Unit = {
        lyricsOpen = false
        scope.launch {
            lyricsAnim.animateTo(0f, tween(LYRICS_ANIM_MS, easing = FastOutSlowInEasing))
        }
    }

    /** 打开播放列表：竖向翻到第 1 页（Dart `_goToPlaylist`） */
    val openPlaylist: () -> Unit = {
        scope.launch {
            pageAnim.animateTo(PLAYLIST_PAGE, tween(PAGE_ANIM_MS, easing = FastOutSlowInEasing))
        }
    }

    /** 翻回播放页：竖向翻到第 0 页（Dart `_goToNowPlaying`） */
    val showNowPlaying: () -> Unit = {
        scope.launch {
            pageAnim.animateTo(NOW_PLAYING_PAGE, tween(PAGE_ANIM_MS, easing = FastOutSlowInEasing))
        }
    }

    /** 清空播放列表（Dart: audioHandler.stopAndClear() 后收起页面） */
    val clearPlaylist: () -> Unit = {
        scope.launch {
            PlayerController.stopAndClear()
            // stopAndClear 会把 currentSong 置空 → 本层立即不再渲染，故需同步收起进度
            NowPlayingUiState.setProgress(0f)
        }
    }

    /** 本页「喜欢」按钮：直接写库并刷新展示（弹窗内部已写库，不能走这里） */
    val toggleLike: () -> Unit = {
        val songId = song.id
        if (songId != null) {
            val next = !likedOverride
            likedOverride = next
            scope.launch {
                DatabaseHelper.toggleLikeSong(songId)
                MusicLibrary.reload()
                val fresh = DatabaseHelper.querySongById(songId)
                if (fresh != null) {
                    likedOverride = fresh.isLiked
                    PlaylistRepository.updateSong(fresh)
                } else {
                    PlaylistRepository.updateSong(song.copy(isLiked = next))
                }
            }
        }
    }

    /** EntityActionSheet 内部已完成 toggleLikeSong：这里只做展示刷新，绝不能再取反 */
    val refreshLikeState: (Song) -> Unit = { target ->
        if (target.path == song.path) likedOverride = !likedOverride
        scope.launch {
            MusicLibrary.reload()
            val songId = target.id
            if (songId != null) {
                val fresh = DatabaseHelper.querySongById(songId)
                if (fresh != null) {
                    if (fresh.path == song.path) likedOverride = fresh.isLiked
                    PlaylistRepository.updateSong(fresh)
                }
            }
        }
    }

    BackHandler(enabled = pageVisible) {
        if (pageAnim.value > 0.5f) {
            // 在播放列表页：返回键翻回播放页（与下滑手势一致）
            showNowPlaying()
        } else {
            // 播放页（含歌词面板已展开的情况）：返回键直接退出播放页（需求 4），
            // 而不是退回封面视图；收起后进度归零会重置内部位置。
            requestClose()
        }
    }

    // ---------- 手势回调（SideEffect 刷新闭包，不重启手势会话） ----------
    val pageDrag = remember { DragCallbacks() }
    val lyricDrag = remember { DragCallbacks() }

    SideEffect {
        pageDrag.canStart = { total, other ->
            dragContext.reset()
            // 需求：方向锁定——竖向须明显占优，否则交还横向（歌词）手势
            val dominant = abs(total) >= abs(other) * AXIS_DOMINANCE
            when {
                !dominant || NowPlayingUiState.currentProgress <= VISIBLE_EPSILON -> false
                pageAnim.value > 0.5f -> {
                    // 播放列表页：仅「列表已到顶 + 下滑」才接管；
                    // 一旦接管，松手必定完整翻回播放页（需求 8）
                    if (total > 0f && !playlistListState.canScrollBackward) {
                        dragContext.fromPlaylist = true
                        true
                    } else {
                        false
                    }
                }
                total > 0f -> {
                    // 播放页下滑收起：歌词列表可上滑时不抢占（内容不在顶部 → 交还列表）
                    dragContext.toPlaylist = false
                    !(lyricsOpen && lyricsListState.canScrollBackward)
                }
                else -> {
                    // 播放页上滑进播放列表：歌词列表还能继续下滚时交还列表
                    dragContext.toPlaylist = true
                    !(lyricsOpen && lyricsListState.canScrollForward)
                }
            }
        }
        pageDrag.onDrag = { dy ->
            if (dragContext.fromPlaylist || dragContext.toPlaylist) {
                // 播放页 ↔ 播放列表：跟手 1:1 移动页面位置
                val next = (pageAnim.value - dy / screenHeightPx).coerceIn(0f, 1f)
                scope.launch { pageAnim.snapTo(next) }
            } else {
                // 下滑收起播放页：跟手修改展开进度（内部会打断正在进行的补间）
                val next = (NowPlayingUiState.currentProgress - dy / screenHeightPx)
                    .coerceIn(MIN_DRAG_PROGRESS, 1f)
                NowPlayingUiState.setProgress(next)
            }
        }
        pageDrag.onEnd = { velocity ->
            when {
                // 需求 8：从播放列表下滑，只要手势成立就完整翻回播放页（不再看幅度）
                dragContext.fromPlaylist -> showNowPlaying()
                dragContext.toPlaylist ->
                    if (velocity < -FLING_VELOCITY || pageAnim.value > PAGE_SWITCH_RATIO) {
                        openPlaylist()
                    } else {
                        showNowPlaying()
                    }
                // 下滑收起播放页：Dart `onFinish`，速度优先，其次就近判定（0.5）
                velocity > FLING_VELOCITY -> NowPlayingUiState.close()
                velocity < -FLING_VELOCITY -> NowPlayingUiState.open()
                NowPlayingUiState.currentProgress < CLOSE_RATIO -> NowPlayingUiState.close()
                else -> NowPlayingUiState.open()
            }
        }
    }

    SideEffect {
        lyricDrag.canStart = { total, other ->
            // 需求：方向锁定——横向须明显占优才接管「封面 ↔ 歌词」拖拽
            abs(total) >= abs(other) * AXIS_DOMINANCE
        }
        lyricDrag.onDrag = { dx ->
            // Dart：_lyricsController.value -= details.delta.dx / screenWidth
            val next = (lyricsAnim.value - dx / screenWidthPx).coerceIn(0f, 1f)
            scope.launch { lyricsAnim.snapTo(next) }
        }
        lyricDrag.onEnd = { velocity ->
            val open = when {
                velocity < -LYRIC_FLING_VELOCITY -> true
                velocity > LYRIC_FLING_VELOCITY -> false
                else -> lyricsAnim.value > CLOSE_RATIO
            }
            if (open) openLyrics() else closeLyrics()
        }
    }

    // ---------- 布局 ----------
    // 覆盖层收起（progress == 0）时不挂手势识别器：Compose 的命中测试只认
    // 「是否有 pointerInput 节点命中」，空的 fillMaxSize Box 不会拦截下层；
    // 若始终挂着 pointerInput，收起的覆盖层会挡掉 :app 下层所有点击。
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .then(
                if (pageVisible) {
                    Modifier.nowPlayingVerticalDrag(
                        lockThresholdPx = lockThresholdPx,
                        callbacks = pageDrag,
                        axisLock = axisLock,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        // 滑出页面：背景 + 主内容整体随进度位移并淡入淡出（每帧直接读 progress）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset {
                    val y = if (progress >= SNAP_PROGRESS) {
                        0f
                    } else {
                        (1f - progress) * screenHeightPx
                    }
                    IntOffset(0, y.roundToInt())
                }
                .graphicsLayer {
                    alpha = ((screenHeightPx * progress) / fadeDistancePx).coerceIn(0f, 1f)
                },
        ) {
            // 共享背景：模糊封面 + 暗化（Dart MpArtwork + blurFilter + 黑 35%）
            SongArtwork(
                song = song,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(BACKGROUND_BLUR),
                cornerRadius = 0.dp,
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = BACKGROUND_SCRIM_ALPHA)),
            )

            // 竖向翻页：第 0 页「播放页」/ 第 1 页「播放列表」
            // （Dart `PageView(scrollDirection: Axis.vertical)`，两页共用上面这张模糊封面）
            // 用 pageAnim 直接驱动两页位移：手指跟手 1:1，且翻页收尾规则完全自定义。

            // 第 0 页：正在播放页（上滑让位给播放列表）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (-pageAnim.value * screenHeightPx).roundToInt()) },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    NowPlayingTopBar(
                        song = song,
                        onCollapse = requestClose,
                        onMore = { actionSheetSong = song },
                    )
                    NowPlayingMiddleContent(
                        song = song,
                        // 延迟读取 Animatable 的值：仅在 graphicsLayer 绘制阶段消费，动画期间不重组
                        lyricsProgress = { lyricsAnim.value },
                        lyricsOpen = lyricsOpen,
                        lyricsListState = lyricsListState,
                        onOpenLyrics = openLyrics,
                        onCloseLyrics = closeLyrics,
                        onSeek = { positionMs -> scope.launch { PlayerController.seekTo(positionMs) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nowPlayingHorizontalDrag(
                                slopPx = horizontalLockPx,
                                callbacks = lyricDrag,
                                axisLock = axisLock,
                            ),
                    )
                    // 播放条 + 主控整体上移 30px（Dart：Transform.translate(0, -30)）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-30).dp),
                    ) {
                        NowPlayingProgressRow(
                            onSeek = { positionMs ->
                                scope.launch { PlayerController.seekTo(positionMs) }
                            },
                        )
                        NowPlayingControlRow(
                            isPlaying = isPlaying,
                            onPrevious = { scope.launch { PlayerController.skipToPrevious() } },
                            onPlayPause = {
                                scope.launch {
                                    if (isPlaying) PlayerController.pause() else PlayerController.resumeOrPlay()
                                }
                            },
                            onNext = { scope.launch { PlayerController.skipToNext() } },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    NowPlayingBottomBar(
                        playMode = playMode,
                        isLiked = likedOverride,
                        likeEnabled = song.id != null,
                        isMuted = isMuted,
                        overlayVisible = overlayVisible,
                        overlayLocked = overlayLocked,
                        onTogglePlayMode = { PlayerController.togglePlayMode() },
                        onToggleLike = toggleLike,
                        onToggleOverlay = { LyricsOverlayManager.onNotificationToggle() },
                        onToggleMute = { scope.launch { PlayerController.setMuted(!isMuted) } },
                        onOpenPlaylist = openPlaylist,
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                    )
                }
            }

            // 第 1 页：播放列表（自下方滑入；完全移出屏幕时不参与命中测试）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, ((1f - pageAnim.value) * screenHeightPx).roundToInt()) },
            ) {
                NowPlayingPlaylistPage(
                    songs = playlistSongs,
                    currentSong = song,
                    isPlaying = isPlaying,
                    listState = playlistListState,
                    onCollapse = showNowPlaying,
                    onClear = clearPlaylist,
                    onPlaySong = { target ->
                        scope.launch { PlayerController.playSong(target) }
                        // Dart：点歌后翻回播放页（`_goToNowPlaying`）
                        showNowPlaying()
                    },
                    onMoreSong = { target -> actionSheetSong = target },
                )
            }
        }
    }

    // ---------- 弹窗 ----------
    val sheetSong = actionSheetSong
    if (sheetSong != null) {
        EntityActionSheet(
            entity = EntityActionTarget.SongTarget(listOf(sheetSong)),
            onDismiss = { actionSheetSong = null },
            onPlay = { songs ->
                actionSheetSong = null
                scope.launch { PlayerController.playSongs(songs, 0) }
            },
            // 契约：onPlayNext = 「添加到播放队列」
            // （弹窗内部已逐首 PlaylistRepository.addSong，此处按 path 去重为幂等兜底）
            onPlayNext = { songs ->
                actionSheetSong = null
                songs.forEach { PlaylistRepository.addSong(it) }
            },
            // 契约：onAddToPlaylist = 「添加到歌单」，由本页弹出歌单选择器
            onAddToPlaylist = { songs ->
                actionSheetSong = null
                addToPlaylistSongs = songs
            },
            // 契约：弹窗内部已完成 toggleLikeSong，这里只能刷新展示
            onToggleLike = { songs -> songs.firstOrNull()?.let(refreshLikeState) },
            // 本页语境下的「删除」= 从播放队列移除（Dart 播放列表 Dismissible 的等价语义）；
            // 必须传 deleteFromLibrary = false，否则弹窗会 DatabaseHelper.deleteSong 永久删库
            onDelete = { songs ->
                actionSheetSong = null
                songs.forEach { PlaylistRepository.removeSong(it) }
            },
            deleteFromLibrary = false,
            deleteLabel = "从播放列表移除",
        )
    }

    val songsToAdd = addToPlaylistSongs
    if (songsToAdd != null) {
        NowPlayingAddToPlaylistDialog(
            songs = songsToAdd,
            onDismiss = { addToPlaylistSongs = null },
        )
    }
}

/**
 * 一次竖向手势的上下文。
 *
 * 手势开始时由 `canStart` 写入（跨重组稳定持有，不能用普通局部变量），
 * `onDrag` / `onEnd` 据此决定作用于「翻页」还是「收起播放页」。
 */
private class PageDragContext {
    /** 手势起于播放列表页（下滑返回播放页） */
    var fromPlaylist: Boolean = false

    /** 手势是播放页上滑（切到播放列表） */
    var toPlaylist: Boolean = false

    fun reset() {
        fromPlaylist = false
        toPlaylist = false
    }
}
