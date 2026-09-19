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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
 * 当前播放行的高亮框（第二十轮需求）：20px 圆角 + 半透明灰底。
 * 取代原先"仅靠 equalizer 图标"的提示方式，整行一眼可辨。
 */
private val CURRENT_ROW_CORNER = 20.dp
private val CURRENT_ROW_HIGHLIGHT = Color(0xFF888888).copy(alpha = 0.28f)

/** 高亮框相对屏幕左右各留出的空白，使框体不贴边（封面仍与顶部卡片对齐于 16dp） */
private val CURRENT_ROW_HORIZONTAL_INSET = 8.dp

/**
 * 播放列表页（竖向 PageView 第 1 页）。
 *
 * @param songs 播放列表内容（PlaylistRepository.songs）
 * @param currentIndex 当前播放项在 [songs] 中的**下标**（用于高亮当前行）
 * @param currentSong 当前播放歌曲（顶部当前歌曲卡片）
 * @param listState 列表滚动状态（父级持有；列表滚到顶部后继续下滑由竖向 PageView
 *   接管并翻回播放页）
 *
 * 注意（第十六轮）：同一首歌可在播放列表内出现多次，因此**不能**再用 `song.path`
 * 作为列表 key（重复 key 会让 LazyColumn 抛 "Key was already used"），
 * 也不能用 `item.path == currentSong.path` 判断当前行（会把所有同名单曲都点亮）。
 * 统一改为按**下标**定位：key 用下标，高亮比下标，点击/移除回调也传下标。
 *
 * 第二十轮调整（按需求）：
 * - 上划进入本页时**自动滚动定位到当前播放的歌曲**（由调用方触发，见 `scrollToCurrent`）；
 * - 当前播放行改用**20px 圆角 + 半透明灰底**的整行高亮框；
 * - 取消每行之间的分割线；
 * - 顶部当前播放卡片去掉「正在播放」文案与右侧播放状态图标；
 * - 队列每行的「更多」按钮改为**减号按钮**，功能是直接从当前播放队列删掉该曲。
 */
@Composable
internal fun NowPlayingPlaylistPage(
    songs: List<Song>,
    currentIndex: Int,
    currentSong: Song,
    listState: LazyListState,
    onCollapse: () -> Unit,
    onClear: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
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
            onClick = onCollapse,
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
                itemsIndexed(items = songs, key = { index, item -> "$index-${item.path}" }) { index, item ->
                    PlaylistSongRow(
                        song = item,
                        isCurrent = index == currentIndex,
                        onClick = { onPlayIndex(index) },
                        onRemoveClick = { onRemoveIndex(index) },
                    )
                }
            }
        }
    }
}

/**
 * 顶部当前歌曲卡片（Dart `_currentSongCard` 的简化版）。
 *
 * 第二十轮调整（按需求）：去掉「正在播放」标题文字与右侧播放状态图标，
 * 只留封面 + 歌名 + 艺术家；点击仍翻回播放页。
 */
@Composable
private fun CurrentSongCard(
    song: Song,
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
            maxSizePx = with(LocalDensity.current) { ROW_ARTWORK_SIZE.roundToPx() },
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
    }
}

/**
 * 播放列表行：封面 48 圆角 6 / 歌名 / 艺术家 / 缓存标识 / 减号按钮。
 *
 * 第二十轮调整（按需求）：
 * - 当前播放行用 **20px 圆角 + 半透明灰底** 的整行高亮框（取代原先仅靠 equalizer 图标）；
 * - **取消行间分割线**；
 * - 右侧「更多」按钮改为**减号按钮**，点击即从当前播放队列删除该曲。
 */
@Composable
private fun PlaylistSongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    // 外层承载"整行高亮框"：左右各留 8dp 使圆角框不贴屏幕边缘，
    // 内层 padding 再补足到 16dp，保证封面与顶部卡片、与其它行**左右对齐**。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CURRENT_ROW_HORIZONTAL_INSET, vertical = 2.dp)
            .then(
                if (isCurrent) {
                    Modifier.background(CURRENT_ROW_HIGHLIGHT, RoundedCornerShape(CURRENT_ROW_CORNER))
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    start = 16.dp - CURRENT_ROW_HORIZONTAL_INSET,
                    end = 4.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SongArtwork(
                song = song,
                modifier = Modifier.size(ROW_ARTWORK_SIZE),
                cornerRadius = ROW_ARTWORK_CORNER,
                maxSizePx = with(LocalDensity.current) { ROW_ARTWORK_SIZE.roundToPx() },
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
                Spacer(Modifier.width(4.dp))
            }
            // 减号按钮：从当前播放队列中删掉该歌曲（第二十轮，取代原「更多」菜单）
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "从播放队列移除",
                    tint = Color.White,
                )
            }
        }
    }
}
