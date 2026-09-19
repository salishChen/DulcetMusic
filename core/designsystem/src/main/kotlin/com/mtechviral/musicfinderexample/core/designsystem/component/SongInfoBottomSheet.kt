/*
 * 对应 Dart 原文件：`.flutter_reference/lib/widgets/mp_song_bottom_sheet.dart`
 * （其中"歌曲信息"行组对应同文件的 `_showSongInfoDialog`）
 *
 * 逐条对照 Dart：
 *   卡片 1 「歌曲信息卡」：封面 40×40（圆角 8）+ 歌名 + "艺术家 · 专辑"
 *   卡片 2 「功能卡」（dense ListTile，图标 22dp / 文案 14sp）：
 *     1. 喜欢 / 取消喜欢（Icons.favorite / favorite_border）—— 内部 `DatabaseHelper.toggleLikeSong`
 *     2. 添加到播放队列（Icons.queue_music）—— 内部 `PlaylistRepository.addSong`（队列追加，
 *        与 Dart `audioHandler?.playlistData.addSong(song)` 一致）→ `onPlayNext` 为刷新通知
 *     3. 缓存歌曲（Icons.download，仅"远程且未缓存"）—— 内部 `CacheService.startCaching(song)`
 *     4. 添加到歌单（Icons.playlist_add）→ `onAddToPlaylist`（歌单选择器由调用方弹出）
 *     5. 永久删除（Icons.delete_forever，红 0xFFFF5252）—— 内部 `LibrarySongDeleter.delete`
 *        （删库 + 同步移出播放列表）+ `MusicLibrary.reload()` → `onDelete` 为刷新通知
 *   新增（Dart 用对话框/播放页承载，本弹窗按移植要求内联展示）：
 *     - "歌曲信息"行组：歌名/艺术家/专辑/专辑艺术家/专辑内排序/时长/比特率/采样率/位深/大小/
 *       格式/编码/添加时间/修改时间/路径（用 `Formatters` 格式化，空值显示"未知"）
 *     - "歌词"行组：`LrcParser.parse(song.lyrics)`，无歌词显示"暂无歌词"
 *   Dart 中另有"艺术家/专辑"跳转项与歌单详情页专用的"从本歌单删除"项，
 *   因规范第 8 节冻结签名未提供对应回调，本实现不包含。
 *
 * 回调契约：`onPlayNext` / `onDelete` 仅作"刷新通知"（数据库/队列改动已在本弹窗内完成），
 * 调用方不得重复执行 addSong / deleteSong；`onAddToPlaylist` 为动作回调（弹歌单选择器）。
 */
package com.mtechviral.musicfinderexample.core.designsystem.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.Formatters
import com.mtechviral.musicfinderexample.core.common.LrcParser
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.LibrarySongDeleter
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 删除项红色（Dart: `Color(0xFFFF5252)`） */
private val SheetDangerRed = Color(0xFFFF5252)

/**
 * 歌曲信息 / 歌词底部弹窗（对应旧版 `showSongBottomSheet` + `_showSongInfoDialog`）。
 *
 * 列表中点击"更多"或长按歌曲时弹出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongInfoBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        SongInfoContent(
            song = song,
            onDismiss = onDismiss,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun SongInfoContent(
    song: Song,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    // 用曲库中的最新对象覆盖传入快照（喜欢/歌词/封面可能已被后台回填）
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val live = remember(song, library) { library.firstOrNull { it.path == song.path } ?: song }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 12.dp),
    ) {
        // ===================== 卡片 1：歌曲信息卡 =====================
        SongHeaderCard(live)

        // ===================== 卡片 2：功能卡 =====================
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 1. 喜欢 / 取消喜欢
                ActionTile(
                    icon = if (live.isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    label = if (live.isLiked) "取消喜欢" else "喜欢",
                ) {
                    val wasLiked = live.isLiked
                    val songId = live.id
                    val autoCache = CacheService.isAutoCacheLikedEnabled()
                    scope.launch {
                        if (songId != null) DatabaseHelper.toggleLikeSong(songId)
                        // 设置「缓存我喜欢」开启时，标记为喜欢后自动缓存（仅远程歌曲）
                        if (!wasLiked && songId != null) CacheService.autoCacheLiked(songId)
                        MusicLibrary.reload()
                        val message = when {
                            wasLiked -> "已取消喜欢"
                            live.isRemote && autoCache -> "已添加到喜欢，将自动缓存"
                            else -> "已添加到喜欢"
                        }
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }
                }

                // 2. 添加到播放队列
                ActionTile(icon = Icons.Filled.QueueMusic, label = "添加到播放队列") {
                    onDismiss()
                    // Dart: audioHandler?.playlistData.addSong(song)
                    // 第十六轮：addSong 不再判重，可重复加入
                    PlaylistRepository.addSong(live)
                    Toast.makeText(appContext, "已添加到播放队列", Toast.LENGTH_SHORT).show()
                    onPlayNext()
                }

                // 3. 缓存歌曲（仅远程未缓存歌曲显示）
                if (live.isRemote && !live.isCached) {
                    ActionTile(icon = Icons.Filled.Download, label = "缓存歌曲") {
                        onDismiss()
                        // Dart: CacheService.instance.startCaching(song)
                        CacheService.startCaching(live)
                        Toast.makeText(
                            appContext,
                            "开始缓存「${live.title}」",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                // 4. 添加到歌单
                ActionTile(icon = Icons.Filled.PlaylistAdd, label = "添加到歌单") {
                    onDismiss()
                    onAddToPlaylist()
                }

                // 5. 永久删除
                ActionTile(
                    icon = Icons.Filled.DeleteForever,
                    label = "永久删除",
                    color = SheetDangerRed,
                ) {
                    val title = live.title
                    scope.launch {
                        // Dart: DatabaseHelper.instance.deleteSong(id) + songData.reload()
                        // 删库 + 同步移出播放列表（若删的是正在播放的歌，会自动接续下一首）
                        LibrarySongDeleter.delete(listOf(live))
                        MusicLibrary.reload()
                        Toast.makeText(
                            appContext,
                            "已删除「$title」",
                            Toast.LENGTH_SHORT,
                        ).show()
                        onDelete()
                        onDismiss()
                    }
                }
            }
        }

        // ===================== 歌曲信息（原 _showSongInfoDialog 行组） =====================
        SectionCard(title = "歌曲信息") {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (row in infoRows(live)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = row.first,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(80.dp),
                        )
                        Text(
                            text = row.second,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ===================== 歌词 =====================
        SectionCard(title = "歌词") {
            val lines = remember(live.lyrics) { LrcParser.parse(live.lyrics) }
            if (lines.isEmpty()) {
                Text(
                    text = "暂无歌词",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (line in lines) {
                        Text(
                            text = line.text,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 卡片 1：封面 40×40（圆角 8）+ 歌名 + "艺术家 · 专辑" */
@Composable
private fun SongHeaderCard(song: Song) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(
                path = song.path,
                cachedArtworkPath = song.cachedArtworkPath,
                songId = song.id,
                coverArtId = song.coverArtId,
                modifier = Modifier.size(40.dp),
                cornerRadius = 8.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = song.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W600,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${song.displayArtist} · ${song.displayAlbum}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 信息 / 歌词分区卡片（浅底卡片 + 分区标题） */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            content()
        }
    }
}

/** 功能卡内的 dense 操作行（Dart `_actionTile`） */
@Composable
private fun ActionTile(
    icon: ImageVector,
    label: String,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val fg = color ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(text = label, color = fg, fontSize = 14.sp)
    }
}

/**
 * 歌曲信息行组（Dart `_showSongInfoDialog` 的 rows，顺序与文案完全一致）。
 * 大小/时间用 [Formatters] 格式化，空值显示"未知"。
 */
private fun infoRows(song: Song): List<Pair<String, String>> = listOf(
    "歌名" to song.title,
    "艺术家" to song.displayArtist,
    "专辑" to song.displayAlbum,
    "专辑艺术家" to (song.albumArtist ?: "未知"),
    "专辑内排序" to (song.trackNumber?.toString() ?: "未知"),
    "时长" to song.durationText,
    "比特率" to (song.bitrate?.let { "${(it / 1000.0).roundToInt()} kbps" } ?: "未知"),
    "采样率" to (song.sampleRate?.let { "$it Hz" } ?: "未知"),
    "位深" to (song.bitDepth?.let { "$it bit" } ?: "未知"),
    "大小" to (song.size?.takeIf { it > 0 }?.let { Formatters.formatSize(it) } ?: "未知"),
    "格式" to (song.format?.uppercase() ?: "未知"),
    "编码" to (song.codec ?: "未知"),
    "添加时间" to formatTimestamp(song.dateAdded),
    "修改时间" to formatTimestamp(song.dateModified),
    "路径" to song.path,
)

/** 时间戳格式化（Dart `fmtTime`：空值显示"未知"） */
private fun formatTimestamp(ms: Long?): String =
    if (ms == null || ms <= 0) "未知" else Formatters.formatDateTime(ms)
