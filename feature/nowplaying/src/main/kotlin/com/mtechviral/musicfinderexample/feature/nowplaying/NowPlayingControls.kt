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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.common.Formatters
import com.mtechviral.musicfinderexample.core.designsystem.component.MpControlButton
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.model.PlayMode
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController

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
 * Dart：`Slider`（白色轨道 / 白 38% 底轨，trackHeight 3，thumb 半径 6）+
 * 下方 `'$_positionText / $_durationText'`。原生按需求把两个时间分别置于左右两侧，
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
        Slider(
            value = displayValue,
            onValueChange = { value ->
                dragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                dragging = false
                onSeek(dragValue.toLong())
            },
            valueRange = 0f..max,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.38f),
            ),
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
        // 悬浮窗歌词：显示且锁定 → 解锁图标；其余 → 音符图标
        IconButton(onClick = onToggleOverlay) {
            Icon(
                imageVector = if (overlayVisible && overlayLocked) {
                    Icons.Filled.LockOpen
                } else {
                    Icons.Filled.MusicNote
                },
                contentDescription = when {
                    overlayVisible && overlayLocked -> "解锁歌词悬浮窗"
                    overlayVisible -> "关闭歌词悬浮窗"
                    else -> "打开歌词悬浮窗"
                },
                tint = Color.White,
                modifier = Modifier.size(FUNCTION_ICON_SIZE),
            )
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
