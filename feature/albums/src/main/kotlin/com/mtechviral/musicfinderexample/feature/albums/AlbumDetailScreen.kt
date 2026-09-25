/*
 * 对应 Dart 原文件：lib/pages/album_detail_page.dart（AlbumDetailPage / _AlbumDetailPageState）
 *
 * 行为对齐要点：
 * - 数据来源 `DatabaseHelper.querySongsByAlbum(albumTitle)`（SQL 内按 trackNumber 升序）；
 * - 首次进入 + 曲库变化时重查；
 * - 头部信息区：封面、专辑名、艺术家、"N 首歌曲"；
 * - 二级页面顶栏用 TopAppBar + 返回按钮；
 * - 歌曲列表用 MpSongListItem，点击行 = setSongs(本页歌曲) + playSong(该曲)；
 * - 空状态文案「本专辑暂无歌曲」。
 *
 * 相对 Dart 的差异（按移植要求新增）：头部补充「播放全部 / 随机播放 / 更多」操作区。
 */
package com.mtechviral.musicfinderexample.feature.albums

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch

/**
 * 专辑详情页（Dart `AlbumDetailPage`）
 *
 * @param albumArtist 专辑艺术家（优化建议 10：与专辑名组成复合键，
 *   区分不同艺术家的同名专辑）；null 表示未知艺术家
 * @param onBack 顶栏返回按钮（默认空实现：不返回，不崩溃）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumTitle: String,
    albumArtist: String? = null,
    onBack: () -> Unit = {},
) {
    // 监听曲库变化：缓存/扫描完成后自动刷新
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var album by remember { mutableStateOf<Album?>(null) }
    var loading by remember { mutableStateOf(true) }

    // EntityActionSheet / 歌单选择器状态
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var showAlbumActions by remember { mutableStateOf(false) }
    var pickingSongs by remember { mutableStateOf<List<Song>?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(albumTitle, albumArtist, library) {
        songs = DatabaseHelper.querySongsByAlbum(albumTitle, albumArtist)
        album = DatabaseHelper.queryAlbums().firstOrNull {
            it.title == albumTitle && (it.artist?.takeIf { a -> a.isNotEmpty() }) == albumArtist
        }
        loading = false
    }

    // 头部封面：优先用专辑聚合信息（仅含内嵌封面的歌曲才会写入 coverSongPath），回退到首曲
    val coverSong = songs.firstOrNull()
    val coverPath = album?.coverSongPath ?: coverSong?.path
    val coverArtworkPath = album?.coverArtworkPath ?: coverSong?.cachedArtworkPath
    val coverSongId = album?.coverSongId ?: coverSong?.id
    val coverArtId = album?.coverArtId ?: coverSong?.coverArtId
    val artistText = album?.artist?.takeIf { it.isNotBlank() } ?: coverSong?.displayArtist ?: ""
    val albumForActions = album ?: Album(
        title = albumTitle,
        artist = albumArtist ?: coverSong?.artist,
        coverSongId = coverSong?.id,
        coverSongPath = coverSong?.path,
        coverArtworkPath = coverSong?.cachedArtworkPath,
        coverArtId = coverSong?.coverArtId,
        songCount = songs.size,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(albumTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 顶部专辑头图 + 信息 + 操作按钮
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row {
                        ArtworkImage(
                            path = coverPath,
                            cachedArtworkPath = coverArtworkPath,
                            songId = coverSongId,
                            coverArtId = coverArtId,
                            modifier = Modifier.size(110.dp),
                            cornerRadius = 16.dp,
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = albumTitle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.W700),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = artistText,
                                style = TextStyle(color = MaterialTheme.ytTextSecondary),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${songs.size} 首歌曲",
                                style = TextStyle(fontSize = 12.sp, color = MaterialTheme.ytTextSecondary),
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { scope.launch { PlayerController.playSongs(songs, 0) } },
                            enabled = songs.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("播放全部")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                // 随机播放：列表本身随机排序，再按新顺序整列播放
                                scope.launch { PlayerController.playShuffled(songs, 0) }
                            },
                            enabled = songs.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("随机播放")
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { showAlbumActions = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多",
                            )
                        }
                    }
                }

                HorizontalDivider(thickness = 1.dp)

                if (songs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "本专辑暂无歌曲", color = MaterialTheme.ytTextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        items(songs, key = { it.path }) { song ->
                            MpSongListItem(
                                song = song,
                                isCurrent = currentSong?.path == song.path,
                                onClick = {
                                    // 点击整列播放：以本页歌曲列表替换播放列表，从本首开始
                                    scope.launch {
                                        PlaylistRepository.setSongs(songs)
                                        PlayerController.playSong(song)
                                    }
                                },
                                onMoreClick = { pendingSong = song },
                            )
                        }
                    }
                }
            }
        }
    }

    // 单曲「更多」
    pendingSong?.let { song ->
        EntityActionSheet(
            entity = EntityActionTarget.SongTarget(listOf(song)),
            onDismiss = { pendingSong = null },
            onPlay = { list -> scope.launch { PlayerController.playSongs(list, 0) } },
            // 契约：队列写入已在弹窗内部完成，这里只作刷新通知（addSong 第十六轮起不再判重）
            onPlayNext = { },
            onAddToPlaylist = { list -> pickingSongs = list },
            onToggleLike = {
                // 喜欢状态已在弹窗内部写入，这里只刷新本页列表
                scope.launch {
                    songs = DatabaseHelper.querySongsByAlbum(albumTitle, albumArtist)
                }
            },
            onDelete = {
                // 删除已由弹窗完成，这里只刷新曲库（library 变化会触发上面重查）
                scope.launch { MusicLibrary.reload() }
            },
        )
    }

    // 专辑「更多」
    if (showAlbumActions) {
        EntityActionSheet(
            entity = EntityActionTarget.AlbumTarget(albumForActions, songs),
            onDismiss = { showAlbumActions = false },
            onPlay = { list -> scope.launch { PlayerController.playSongs(list, 0) } },
            // 契约：队列写入已在弹窗内部完成，这里只作刷新通知（addSong 第十六轮起不再判重）
            onPlayNext = { },
            onAddToPlaylist = { list -> pickingSongs = list },
            onToggleLike = {
                scope.launch {
                    songs = DatabaseHelper.querySongsByAlbum(albumTitle, albumArtist)
                }
            },
            onDelete = {
                scope.launch { MusicLibrary.reload() }
            },
        )
    }

    // 「添加到歌单」选择器
    pickingSongs?.let { list ->
        AddToPlaylistSheet(
            songs = list,
            onDismiss = { pickingSongs = null },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )
    }
}
