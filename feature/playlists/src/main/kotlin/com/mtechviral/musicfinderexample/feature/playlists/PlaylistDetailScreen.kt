/*
 * 对应 Dart 原文件：lib/pages/playlist_detail_page.dart（歌单详情页，二级页面）
 *                 以及 lib/widgets/mp_song_bottom_sheet.dart 中的
 *                 `_showAddToPlaylistDialog()` / `_promptPlaylistName()`（"添加到歌单"弹窗，原生端在歌单模块内实现）
 *
 * 与 Dart 逐条对照：
 *  - AppBar：标题 = 歌单名，centerTitle = true，自带返回（原生显式给 navigationIcon + onBack）
 *  - 进入页面 querySongsInPlaylist(id)；id 无效时直接结束 loading（Dart 中避免永久 loading）
 *  - 空状态文案："歌单为空，去歌曲列表添加吧"
 *  - 点击歌曲：把本歌单整体设为播放列表（PlaylistRepository.setSongs）后播放选中歌曲（PlayerController.playSong）
 *  - 每行的"更多"弹 EntityActionSheet；删除项 = 从本歌单移除（DatabaseHelper.removeSongFromPlaylist），
 *    并同步更新本地列表（Dart `_removeSong`：不重新查询）；
 *    使用规范 §8 新增的 `deleteFromLibrary = false` + `deleteLabel = "从本歌单移除"`，只解绑、不删曲库记录
 *  - "添加到歌单"：列出全部歌单 + "新建歌单"入口；"暂无歌单"占位；
 *    选中后提示"已添加到歌单「x」/歌曲已在歌单「x」中"，新建后提示"已添加到歌单「x」/新建歌单失败（可能重名）"
 *
 * 与 Dart 的差异：
 *  1) Dart 页面构造参数是 Playlist 对象（名称随参数传入）；原生路由只带 id，故额外查一次 queryPlaylists() 回填标题。
 *  2) Dart `MpSongListItem` 的更多弹窗为自定义 mp_song_bottom_sheet；原生统一使用 designsystem 的 EntityActionSheet，
 *     因此弹窗内的操作项集合与 Dart 不完全相同（"从本歌单移除"映射到 onDelete + deleteLabel）。
 *  3) `EntityActionSheet` 的 target 为该行单曲（`SongTarget(listOf(song))`），保证"喜欢/移除"只作用于当前行，
 *     与 Dart 每行弹窗的粒度一致。
 *  4) SnackBar -> Toast；showModalBottomSheet -> ModalBottomSheet；配色/文案保持与 Dart 一致。
 */
package com.mtechviral.musicfinderexample.feature.playlists

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch

/** 歌单详情页：对应 Dart `PlaylistDetailPage`（原生路由只传 playlistId） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(playlistId: Long, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var playlistName by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sheetSong by remember { mutableStateOf<Song?>(null) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    // 对应 Dart initState `_query(id)`；id 缺失时直接结束加载态
    LaunchedEffect(library, playlistId) {
        if (playlistId > 0L) {
            songs = DatabaseHelper.querySongsInPlaylist(playlistId)
        }
        loading = false
    }

    // Dart 由页面参数直接携带歌单名，原生路由只带 id，故查库回填标题
    LaunchedEffect(playlistId) {
        playlistName = DatabaseHelper.queryPlaylists()
            .firstOrNull { it.id == playlistId }?.name ?: ""
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = playlistName.ifEmpty { "歌单" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                songs.isEmpty() -> Text(
                    text = "歌单为空，去歌曲列表添加吧",
                    color = SecondaryGrey,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = songs, key = { it.path }) { song ->
                        MpSongListItem(
                            song = song,
                            isCurrent = currentSong?.path == song.path,
                            onClick = {
                                // Dart：queue = _songs（点击歌曲把本歌单作为播放列表）后播放选中歌曲
                                PlaylistRepository.setSongs(songs)
                                scope.launch { PlayerController.playSong(song) }
                            },
                            onMoreClick = { sheetSong = song },
                        )
                    }
                }
            }
        }
    }

    // ===================== 歌曲"更多"弹窗 =====================

    sheetSong?.let { song ->
        EntityActionSheet(
            entity = EntityActionTarget.SongTarget(listOf(song)),
            onDismiss = { sheetSong = null },
            onPlay = { list -> scope.launch { PlayerController.playSongs(list, 0) } },
            // 契约：onPlayNext = "添加到播放队列"
            onPlayNext = { list -> list.forEach { PlaylistRepository.addSong(it) } },
            // 契约：onAddToPlaylist = "添加到歌单"（由本页弹出歌单选择器）
            onAddToPlaylist = { list -> addToPlaylistSongs = list },
            onToggleLike = {
                // 契约：EntityActionSheet 内部已完成 toggleLikeSong 写库；这里只做刷新
                scope.launch { songs = DatabaseHelper.querySongsInPlaylist(playlistId) }
            },
            onDelete = { list ->
                // 歌单详情页语义 = Dart 弹窗中的"从本歌单删除"（只解绑，不删曲库记录）
                scope.launch {
                    list.forEach { target ->
                        target.id?.let { DatabaseHelper.removeSongFromPlaylist(playlistId, it) }
                    }
                    // Dart `_removeSong`：同步更新本地列表，避免重新查询
                    val paths = list.map { it.path }.toSet()
                    songs = songs.filterNot { it.path in paths }
                }
            },
            // 规范 §8 新增参数：歌单页只解绑，不删除曲库记录，文案"从本歌单移除"
            deleteFromLibrary = false,
            deleteLabel = "从本歌单移除",
        )
    }

    // ===================== 添加到歌单 =====================

    if (addToPlaylistSongs.isNotEmpty()) {
        AddToPlaylistSheet(
            songs = addToPlaylistSongs,
            onDismiss = { addToPlaylistSongs = emptyList() },
            onMessage = { message -> toast(context, message) },
        )
    }
}

/**
 * "添加到歌单"选择器：对应 Dart `_showAddToPlaylistDialog()`
 * （新建歌单入口 + 全部歌单列表 + "暂无歌单"占位；选中后直接写入 playlist_songs）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddToPlaylistSheet(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Dart：先 await queryPlaylists() 再弹窗
    LaunchedEffect(Unit) { playlists = DatabaseHelper.queryPlaylists() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = "添加到歌单",
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
                modifier = Modifier.padding(16.dp),
            )
            // 新建歌单入口（Dart：图标与文字均为品牌青）
            SheetActionRow(
                icon = Icons.Filled.Add,
                title = "新建歌单",
                tint = BrandCyan,
                titleColor = BrandCyan,
                onClick = { showCreateDialog = true },
            )
            if (playlists.isEmpty()) {
                Text(
                    text = "暂无歌单",
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(items = playlists, key = { it.id ?: it.name }) { playlist ->
                        SheetActionRow(
                            icon = Icons.Filled.QueueMusic,
                            title = playlist.name,
                            subtitle = "${playlist.songCount} 首",
                            onClick = {
                                val targetId = playlist.id
                                if (targetId != null) {
                                    scope.launch {
                                        var added = 0
                                        for (song in songs) {
                                            val songId = song.id ?: continue
                                            if (DatabaseHelper.addSongToPlaylist(targetId, songId)) added++
                                        }
                                        onMessage(
                                            if (added > 0) "已添加到歌单「${playlist.name}」"
                                            else "歌曲已在歌单「${playlist.name}」中",
                                        )
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Dart：点"新建歌单"后弹出歌单名输入框，创建成功后直接加入
    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { raw ->
                showCreateDialog = false
                val name = raw.trim()
                if (name.isNotEmpty()) {
                    scope.launch {
                        val playlistId = DatabaseHelper.createPlaylist(name)
                        if (playlistId == null) {
                            onMessage("新建歌单失败（可能重名）")
                        } else {
                            for (song in songs) {
                                val songId = song.id ?: continue
                                DatabaseHelper.addSongToPlaylist(playlistId, songId)
                            }
                            onMessage("已添加到歌单「$name」")
                            onDismiss()
                        }
                    }
                }
            },
        )
    }
}

/** 短提示（Dart SnackBar，duration 1s） */
private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
