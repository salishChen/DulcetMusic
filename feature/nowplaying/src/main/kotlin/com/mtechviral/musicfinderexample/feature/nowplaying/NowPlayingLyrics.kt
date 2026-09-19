/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_buildMiddleContent`：封面 + 三行迷你歌词 ↔ 详细歌词（左右滑动切换）
 *  - `_miniLyrics`：最近 3 行歌词（中间为当前行，高亮）
 *  - `_buildLyricsPage`：详细歌词（56 行高、点击跳转、当前行高亮）
 *  - `_updateActiveLine` / `_scrollToActive`：当前行计算与自动居中滚动
 *  - `AlbumUI`（lib/widgets/mp_album_ui.dart）：封面 side = min(375, 屏宽 - 48)，圆角 12
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.common.LrcLine
import com.mtechviral.musicfinderexample.core.common.LrcParser
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController

/** 歌词面板打开时自动把当前行滚到视口上方约 3 行处（Dart: activeLine*56 - 200） */
private const val LYRIC_CENTER_OFFSET = 3

/** 迷你歌词单行高度（Dart: lineHeight = 24.0） */
private val MINI_LINE_HEIGHT = 24.dp

/** 详细歌词单行高度（Dart: Container(height: 56.0)） */
private val LYRIC_LINE_HEIGHT = 56.dp

/**
 * 详细歌词面板的纵向内缩量（第十三轮反馈）。
 *
 * 顶部下移 [LYRICS_PANE_TOP_INSET]、底部上移 [LYRICS_PANE_BOTTOM_INSET]，
 * 面板纵向整体缩小 40 = 10 + 30。
 */
private val LYRICS_PANE_TOP_INSET = 10.dp
private val LYRICS_PANE_BOTTOM_INSET = 30.dp

/** 封面圆角（Dart: BorderRadius.circular(12.0)） */
private val ARTWORK_CORNER = 12.dp

/**
 * 中间内容区：封面 + 迷你歌词 ↔ 详细歌词。
 *
 * [lyricsProgress] 返回歌词面板进度（0 = 封面视图，1 = 详细歌词视图）。
 * 这里刻意用「取值函数」而不是 `Animatable<Float, AnimationVector1D>`：
 *  - `Animatable` 是两参数泛型类（`Animatable<T, V : AnimationVector>`），
 *    在跨文件签名里写 `Animatable<Float>` 无法编译；
 *  - 取值函数只在 `graphicsLayer` 绘制阶段被调用（延迟读取 Snapshot State），
 *    动画期间不会触发重组。
 * [lyricsOpen] 为同一状态的布尔镜像，用于触发「自动滚动到当前行」。
 */
@Composable
internal fun NowPlayingMiddleContent(
    song: Song,
    lyricsProgress: () -> Float,
    lyricsOpen: Boolean,
    lyricsListState: LazyListState,
    onOpenLyrics: () -> Unit,
    onCloseLyrics: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val position by PlayerController.position.collectAsStateWithLifecycle()
    val lines = remember(song.path, song.lyrics) { LrcParser.parse(song.lyrics) }
    val activeIndex = remember(lines, position) { LrcParser.activeIndex(lines, position) }

    // 歌词面板展开后，当前行变化时自动滚动居中（Dart: _scrollToActive）
    LaunchedEffect(lines, lyricsOpen, activeIndex) {
        if (lyricsOpen && lines.isNotEmpty() && activeIndex >= 0) {
            lyricsListState.animateScrollToItem((activeIndex - LYRIC_CENTER_OFFSET).coerceAtLeast(0))
        }
    }

    Box(modifier = modifier.clipToBounds()) {
        // 封面 + 三行迷你歌词：左滑移出并淡出
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val t = lyricsProgress()
                    translationX = -size.width * t
                    alpha = (1f - t).coerceIn(0f, 1f)
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(5f),
                contentAlignment = Alignment.Center,
            ) {
                NowPlayingArtwork(
                    song = song,
                    screenWidthDp = configuration.screenWidthDp.dp,
                    onTap = onOpenLyrics,
                )
            }
            MiniLyrics(lines = lines, activeIndex = activeIndex, onTap = onOpenLyrics)
            Spacer(Modifier.weight(1f))
        }

        // 详细歌词：从右侧滑入
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = size.width * (1f - lyricsProgress()) },
        ) {
            NowPlayingLyricsPane(
                lines = lines,
                activeIndex = activeIndex,
                listState = lyricsListState,
                onSeek = onSeek,
                onBackgroundTap = onCloseLyrics,
            )
        }
    }
}

/** 大封面：点击切换「封面 / 歌词」视图（对应 Dart 中点击迷你歌词 / 左滑封面） */
@Composable
private fun NowPlayingArtwork(
    song: Song,
    screenWidthDp: Dp,
    onTap: () -> Unit,
) {
    // Dart AlbumUI：side = min(375, MediaQuery.width - 48)
    val side = minOf(375.dp, screenWidthDp - 48.dp)
    Box(
        modifier = Modifier
            .size(side)
            .shadow(5.dp, RoundedCornerShape(ARTWORK_CORNER))
            .clip(RoundedCornerShape(ARTWORK_CORNER))
            .clickable(onClick = onTap),
    ) {
        SongArtwork(
            song = song,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = ARTWORK_CORNER,
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * 最近 3 行歌词（上一行 / 当前行 / 下一行），点击打开详细歌词。
 * Dart `_miniLyrics`：无歌词时固定 3 行高度并居中显示「暂无歌词」。
 */
@Composable
private fun MiniLyrics(
    lines: List<LrcLine>,
    activeIndex: Int,
    onTap: () -> Unit,
) {
    if (lines.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MINI_LINE_HEIGHT * 3)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "暂无歌词",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.54f),
                    fontSize = 14.sp,
                ),
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
        return
    }

    val active = if (activeIndex < 0) 0 else activeIndex
    Column(modifier = Modifier.clickable(onClick = onTap)) {
        MiniLyricLine(line = lines.getOrNull(active - 1), isActive = false)
        MiniLyricLine(line = lines.getOrNull(active), isActive = true)
        MiniLyricLine(line = lines.getOrNull(active + 1), isActive = false)
    }
}

/** 迷你歌词单行：当前行 16sp 白 600，其余 13sp 白 45% */
@Composable
private fun MiniLyricLine(line: LrcLine?, isActive: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(MINI_LINE_HEIGHT),
        contentAlignment = Alignment.Center,
    ) {
        if (line != null) {
            Text(
                text = line.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.45f),
                    fontSize = if (isActive) 16.sp else 13.sp,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

/**
 * 详细歌词面板（Dart `_buildLyricsPage`）：
 * 空歌词显示「暂无歌词」；每行 56 高、点击跳转到该行时间（仅当有任一时间戳）；
 * 当前行 17sp 白色加粗，其余 15sp 白 45%。点击空白处返回封面视图。
 *
 * 面板自身的纵向位置由 inset 控制，见 [LYRICS_PANE_TOP_INSET] / [LYRICS_PANE_BOTTOM_INSET]：
 * 外层留满屏仅作「点击空白返回封面」的手势区，歌词内容纵向内缩。
 */
@Composable
private fun NowPlayingLyricsPane(
    lines: List<LrcLine>,
    activeIndex: Int,
    listState: LazyListState,
    onSeek: (Long) -> Unit,
    onBackgroundTap: () -> Unit,
) {
    val hasTimed = lines.any { it.timeMs > 0 }

    // 外层保持满屏，仅用于「点击空白返回封面」的手势判定；
    // 歌词内容自身纵向内缩（顶部下移 10、底部上移 30，整体缩小 40）。
    val contentInset = Modifier.padding(
        top = LYRICS_PANE_TOP_INSET,
        bottom = LYRICS_PANE_BOTTOM_INSET,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onBackgroundTap() } },
    ) {
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().then(contentInset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无歌词",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 15.sp,
                    ),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().then(contentInset),
                contentPadding = PaddingValues(vertical = 40.dp),
            ) {
                itemsIndexed(
                    items = lines,
                    key = { index, line -> "$index-${line.timeMs}" },
                ) { index, line ->
                    val active = index == activeIndex
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(LYRIC_LINE_HEIGHT)
                            .clickable(enabled = hasTimed) { onSeek(line.timeMs) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = line.text,
                            textAlign = TextAlign.Center,
                            style = TextStyle(
                                color = if (active) Color.White else Color.White.copy(alpha = 0.45f),
                                fontSize = if (active) 17.sp else 15.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
        }
    }
}
