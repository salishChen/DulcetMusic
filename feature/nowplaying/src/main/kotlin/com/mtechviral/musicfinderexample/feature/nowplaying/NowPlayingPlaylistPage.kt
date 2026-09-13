/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_buildPlaylistPage`：播放列表页，与「正在播放」页同处竖向 PageView 的第 1 页
 *    （上划进入、下滑返回，共享同一张模糊封面背景）
 *  - `_currentSongCard`：顶部「正在播放」当前歌曲卡片
 *  - 列表项对应 `_buildPlaylistPage` 中的 ListTile（封面 48 圆角 6、歌名、艺术家、
 *    当前行 equalizer、已缓存 offline_pin）
 *
 * 与 Dart 的实现差异：Dart 用左滑 Dismissible 删除队列中的歌曲，原生沿用
 * 「更多」菜单（EntityActionSheet.onDelete = 从播放列表移除）替代。
 *
 * 与上一版原生实现的差异：原先是从底部弹出的 Material 抽屉（自带 surface 底色），
 * 现改回 Dart 的「同一页面的第 1 页」——背景透明（透出共享的模糊封面）、
 * 文字恒为白色，仅额外叠一层暗色蒙版保证可读性。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.model.Song

/** 播放列表页额外叠加的暗色蒙版（共享背景已有一层 35% 黑，这里再加深以保证白字可读） */
private const val PLAYLIST_SCRIM_ALPHA = 0.35f

/** 播放列表行封面尺寸/圆角（Dart：48 / 6） */
private val ROW_ARTWORK_SIZE = 48.dp
private val ROW_ARTWORK_CORNER = 6.dp

/**
 * 播放列表页（竖向 PageView 第 1 页）。
 *
 * @param songs 播放列表内容（PlaylistRepository.songs）
 * @param currentSong 当前播放歌曲（用于高亮当前行与顶部卡片）
 * @param listState 列表滚动状态（父级持有；列表滚到顶部后继续下滑由竖向 PageView
 *   接管并翻回播放页）
 */
@Composable
internal fun NowPlayingPlaylistPage(
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = PLAYLIST_SCRIM_ALPHA))
            .statusBarsPadding(),
    ) {
        // 标题栏：收起 / 「播放列表 + 数量」 / 清空
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
                    tint = Color.White,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "播放列表",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "共 ${songs.size} 首",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    ),
                )
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = "清空播放列表",
                    tint = Color.White,
                )
            }
        }

        CurrentSongCard(
            song = currentSong,
            isPlaying = isPlaying,
            onClick = onCollapse,
        )
        HorizontalDivider(
            thickness = 1.dp,
            color = Color.White.copy(alpha = 0.24f),
        )

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "播放列表为空",
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp),
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
                    PlaylistSongRow(
                        song = item,
                        isCurrent = item.path == currentSong.path,
                        onClick = { onPlaySong(item) },
                        onMoreClick = { onMoreSong(item) },
                    )
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
            modifier = Modifier.size(ROW_ARTWORK_SIZE),
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "正在播放",
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Text(
                text = song.displayArtist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
            )
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Equalizer else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
        )
    }
}

/** 播放列表行（Dart ListTile）：封面 48 圆角 6 / 歌名（当前行加粗）/ 艺术家 / 缓存标识 / 更多 */
@Composable
private fun PlaylistSongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongArtwork(
                song = song,
                modifier = Modifier.size(ROW_ARTWORK_SIZE),
                cornerRadius = ROW_ARTWORK_CORNER,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
                Text(
                    text = song.displayArtist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
                )
            }
            // 已缓存的远程歌曲显示缓存标识（Dart：offline_pin）
            if (song.isRemote && song.isCached) {
                Icon(
                    imageVector = Icons.Filled.OfflinePin,
                    contentDescription = "已缓存",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            // 当前播放行（Dart：equalizer）
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.Equalizer,
                    contentDescription = "正在播放",
                    tint = Color.White,
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = Color.White,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 16.dp),
            thickness = 0.6.dp,
            color = Color.White.copy(alpha = 0.12f),
        )
    }
}
