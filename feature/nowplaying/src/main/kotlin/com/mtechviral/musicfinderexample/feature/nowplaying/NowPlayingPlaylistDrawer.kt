/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_buildPlaylistPage`：播放列表页（标题栏「播放列表」/ 收起 / 清空、当前歌曲卡片、歌曲列表）
 *  - `_currentSongCard`：顶部「正在播放」当前歌曲卡片
 *  - 列表项对应 `_buildPlaylistPage` 中的 ListTile（封面 48 圆角 6、歌名、艺术家、
 *    当前行图标 equalizer）；删除由「更多」菜单（EntityActionSheet.onDelete）完成，
 *    Dart 中的左滑 Dismissible 未移植（见汇报）。
 *
 * 与 Dart 的差异：Dart 把播放列表作为竖向 PageView 的第 1 页（全屏、白色文字浮在模糊封面上），
 * 原生移植改为底部滑出的抽屉（Material3 surface 配色），因此列表项改用 designsystem 的
 * MpSongListItem（保证明暗主题下均可读）。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 播放列表抽屉。
 *
 * @param songs 播放列表内容（PlaylistRepository.songs）
 * @param currentSong 当前播放歌曲（用于高亮当前行与顶部卡片）
 * @param listState 列表滚动状态（父级持有，用于「列表已在顶部再下滑 → 收起抽屉」判定）
 */
@Composable
internal fun NowPlayingPlaylistDrawer(
    songs: List<Song>,
    currentSong: Song,
    isPlaying: Boolean,
    listState: LazyListState,
    onCollapse: () -> Unit,
    onClear: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onMoreSong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏：收起 / 标题 + 数量 / 清空
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onCollapse) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "收起",
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "播放列表",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    )
                    Text(
                        text = "共 ${songs.size} 首",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "清空播放列表",
                    )
                }
            }
            CurrentSongCard(
                song = currentSong,
                isPlaying = isPlaying,
                onClick = onCollapse,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "播放列表为空",
                        style = TextStyle(
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding(),
                ) {
                    items(items = songs, key = { it.path }) { item ->
                        MpSongListItem(
                            song = item,
                            isCurrent = item.path == currentSong.path,
                            onClick = { onPlaySong(item) },
                            onMoreClick = { onMoreSong(item) },
                            // 已在播放列表中，无需「加入播放列表」按钮
                            showAddButton = false,
                        )
                    }
                }
            }
        }
    }
}

/** Dart `_currentSongCard`：封面 48 + 「正在播放」+ 歌名 + 艺术家 + 播放状态图标 */
@Composable
private fun CurrentSongCard(
    song: Song,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArtwork(
            song = song,
            modifier = Modifier.size(48.dp),
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "正在播放",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            Text(
                text = song.displayArtist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Equalizer else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
