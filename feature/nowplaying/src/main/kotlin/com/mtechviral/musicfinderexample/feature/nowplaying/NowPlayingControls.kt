/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - 顶部「歌名 / 艺术家」：build() 中 Padding(fromLTRB(20,8,20,0)) + Column(Text/Text)
 *  - 进度条与时间文本：build() 中的 `slider` 与 `Text('$_positionText / $_durationText')`
 *  - 主控三键：`_control(...)`（上一首 39 / 播放暂停 54 / 下一首 39）
 *  - 底部功能行：播放模式 / 悬浮窗歌词 / 喜欢 / 静音 / 播放列表
 *    （Dart 为模式 + Spacer + 悬浮窗 + 静音 + 播放列表；「喜欢」由本次原生移植补充）
 *  - 悬浮窗歌词按钮：_buildLyricsToggle()
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.common.Formatters
import com.mtechviral.musicfinderexample.core.designsystem.component.MpControlButton
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.model.PlayMode
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import kotlin.math.roundToInt

/** 底部功能按钮图标尺寸（Dart：iconSize 34.8） */
private val FUNCTION_ICON_SIZE = 34.8.dp

/**
 * 顶部栏：收起按钮 + 歌名 / 艺术家 + 更多。
 *
 * Dart 此处只有左对齐的歌名与艺术家（20 / 14，白色 / 白 70%）；
 * 收起按钮与「更多」为本页原生移植新增（对应 Dart 播放列表页的 keyboard_arrow_down
 * 与歌曲的 EntityActionSheet 入口）。
 */
@Composable
internal fun NowPlayingTopBar(
    song: Song,
    onCollapse: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "收起",
                tint = Color.White,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song.displayArtist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                ),
            )
        }
        IconButton(onClick = onMore) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
            )
        }
    }
}

/**
 * 进度条 + 时间。
 *
 * 进度条为需求定制的极细样式（见 [NowPlayingProgressBar]：轨道高 2dp、滑块为
 * 直径 4dp 的小圆球）；两个时间分别置于左右两侧。
 * 拖动过程中不回写播放位置，松手才 [onSeek]（避免拖动时被 200ms 的位置轮询打断）。
 */
@Composable
internal fun NowPlayingProgressRow(
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val position by PlayerController.position.collectAsStateWithLifecycle()
    val duration by PlayerController.duration.collectAsStateWithLifecycle()

    val max = (duration ?: 0L).toFloat()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // Dart：max <= 0 时进度条与时间文本整体不渲染
    if (max <= 0f) return

    val displayValue = if (dragging) dragValue else position.toFloat().coerceIn(0f, max)

    Column(modifier = modifier.fillMaxWidth()) {
        NowPlayingProgressBar(
            value = displayValue,
            max = max,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragValue.toLong())
            },
            // 被「下滑收起播放页」手势接管：只撤销拖动态显示，不再 seek，
            // 避免在进度条上起手下滑关闭播放页时误跳播放位置。
            onDragCancelled = { dragging = false },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPosition(displayValue),
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
            )
            Text(
                text = Formatters.formatDuration(duration),
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
            )
        }
    }
}

/** Dart `_fmt`：0:00 起算，非正值显示 0:00（Formatters 对 <=0 返回 --:--） */
private fun formatPosition(value: Float): String {
    val ms = value.toLong()
    return if (ms <= 0L) "0:00" else Formatters.formatDuration(ms)
}

/** 进度条轨道高度（需求 1：2px） */
private val PROGRESS_TRACK_HEIGHT = 2.dp

/** 进度滑块直径（需求 1：直径 4px 的小圆球） */
private val PROGRESS_THUMB_SIZE = 4.dp

/** 进度条触控高度：视觉极细但仍保留可舒适拖动的触控区 */
private val PROGRESS_TOUCH_HEIGHT = 36.dp

/**
 * 极细进度条（需求 1 定制，替代 Material3 `Slider`）。
 *
 * - 轨道高 [PROGRESS_TRACK_HEIGHT]（2dp），底轨白色 38%、已播放段纯白；
 * - 滑块为直径 [PROGRESS_THUMB_SIZE]（4dp）的白色小圆球，圆心可正好贴住两端；
 * - 按下即定位到触点并进入跟手拖动，拖动期间只回调 [onValueChange]，
 *   松手才回调 [onValueChangeFinished]（调用方据此 seek）；
 *   若中途被父级「下滑收起播放页」识别器接管，则改走 [onDragCancelled]（只撤销显示，不 seek）。
 *
 * 手势说明：父级「下滑收起播放页」识别器工作在 Initial pass，一旦它接管并消费事件，
 * 这里通过 `change.isConsumed` 立即停止跟手，避免同时拖动进度条。
 */
@Composable
private fun NowPlayingProgressBar(
    value: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onDragCancelled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { PROGRESS_THUMB_SIZE.toPx() / 2f }
    // 已播放段实际可滑动的轨道长度（扣除左右各一个滑块半径）
    var trackWidthPx by remember { mutableStateOf(0) }
    val fraction = if (max <= 0f) 0f else (value / max).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_TOUCH_HEIGHT)
            .pointerInput(max) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    fun valueAt(x: Float): Float {
                        if (trackWidthPx <= 0) return 0f
                        val f = ((x - thumbRadiusPx) / trackWidthPx).coerceIn(0f, 1f)
                        return f * max
                    }

                    down.consume()
                    onValueChange(valueAt(down.position.x))

                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 手势已被父级（播放页下滑收起）接管：立即停止跟手
                        if (change.isConsumed) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        onValueChange(valueAt(change.position.x))
                        change.consume()
                    }
                    if (cancelled) onDragCancelled() else onValueChangeFinished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道（左右各留出一个滑块半径，使滑块圆心正好落在两端）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PROGRESS_THUMB_SIZE / 2)
                .height(PROGRESS_TRACK_HEIGHT)
                .onSizeChanged { trackWidthPx = it.width }
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.38f)),
        ) {
            // 已播放段
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .background(Color.White),
            )
        }
        // 滑块：直径 4dp 的白色小圆球
        Box(
            modifier = Modifier
                .offset { IntOffset((trackWidthPx * fraction).roundToInt(), 0) }
                .size(PROGRESS_THUMB_SIZE)
                .background(Color.White, CircleShape),
        )
    }
}

/**
 * 主控三键：上一首 / 播放暂停 / 下一首。
 * Dart：iconSize 39 / 54 / 39（原尺寸的 75%），白色。
 */
@Composable
internal fun NowPlayingControlRow(
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MpControlButton(
            icon = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            onClick = onPrevious,
            size = 48.dp,
            iconSize = 39.dp,
            tint = Color.White,
        )
        MpControlButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            onClick = onPlayPause,
            size = 64.dp,
            iconSize = 54.dp,
            tint = Color.White,
        )
        MpControlButton(
            icon = Icons.Filled.SkipNext,
            contentDescription = "下一首",
            onClick = onNext,
            size = 48.dp,
            iconSize = 39.dp,
            tint = Color.White,
        )
    }
}

/**
 * 底部功能行：播放模式 / 喜欢 / 悬浮窗歌词 / 静音 / 播放列表。
 *
 * Dart 顺序为「模式 + Spacer + 悬浮窗 + 静音 + 播放列表」（padding fromLTRB(20,0,12,2)），
 * 本页在 Spacer 之后新增「喜欢」。
 */
@Composable
internal fun NowPlayingBottomBar(
    playMode: PlayMode,
    isLiked: Boolean,
    likeEnabled: Boolean,
    isMuted: Boolean,
    overlayVisible: Boolean,
    overlayLocked: Boolean,
    onTogglePlayMode: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenPlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 播放模式：图标随 playMode 变化，tooltip 用 PlayMode.label
        IconButton(onClick = onTogglePlayMode) {
            Icon(
                imageVector = playModeIcon(playMode),
                contentDescription = playMode.label,
                tint = Color.White,
                modifier = Modifier.size(FUNCTION_ICON_SIZE),
            )
        }
        Spacer(Modifier.weight(1f))
        // 喜欢（直接写库并刷新展示）
        IconButton(onClick = onToggleLike, enabled = likeEnabled) {
            Icon(
                imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isLiked) "取消喜欢" else "喜欢",
                tint = if (isLiked) BrandPurple else Color.White,
                modifier = Modifier.size(FUNCTION_ICON_SIZE),
            )
        }
        // 悬浮窗歌词：图标为「词」字（需求），已开启且锁定时右下角加小锁角标
        IconButton(
            onClick = onToggleOverlay,
            modifier = Modifier.semantics {
                contentDescription = when {
                    overlayVisible && overlayLocked -> "解锁歌词悬浮窗"
                    overlayVisible -> "关闭歌词悬浮窗"
                    else -> "打开歌词悬浮窗"
                }
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "词",
                    color = Color.White,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (overlayVisible && overlayLocked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(13.dp),
                    )
                }
            }
        }
        // 静音
        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (isMuted) "取消静音" else "静音",
                tint = Color.White,
                modifier = Modifier.size(FUNCTION_ICON_SIZE),
            )
        }
        // 播放列表
        IconButton(onClick = onOpenPlaylist) {
            Icon(
                imageVector = Icons.Filled.QueueMusic,
                contentDescription = "播放列表",
                tint = Color.White,
                modifier = Modifier.size(FUNCTION_ICON_SIZE),
            )
        }
    }
}

/** Dart `PlayMode.icon`：顺序 → repeat，随机 → shuffle，单曲 → repeat_one */
private fun playModeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.Filled.Repeat
    PlayMode.RANDOM -> Icons.Filled.Shuffle
    PlayMode.SINGLE -> Icons.Filled.RepeatOne
}
