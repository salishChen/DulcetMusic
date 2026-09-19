/*
 * 对应 Dart 原文件：lib/pages/artist_detail_page.dart（ArtistDetailPage / _ArtistDetailPageState）
 *
 * 行为对齐要点：
 * - 数据来源 `querySongsByArtist(artistName)` + `queryAlbumsByArtist(artistName)`；
 *   另外读取 `queryArtistMeta(artistName)` 用于远程艺术家封面 coverArtId
 *   （Dart 由 `MpArtwork(coverArtId:)` 隐式触发 CacheService，这里用 ArtworkImage(coverArtId=) 等价）；
 * - 无歌曲且无专辑时空状态文案「该艺术家暂无歌曲」；
 * - 「专辑」区横向滚动（高 172、间距 12、左右 padding 16），点击跳专辑详情；
 * - 「单曲（N）」区用 MpSongListItem，点击行 = setSongs(本页歌曲) + playSong(该曲)；
 * - 二级页面顶栏用 TopAppBar + 返回按钮。
 *
 * 相对 Dart 的差异（按移植要求新增）：头部信息区（封面/名称/歌曲数·专辑数）与
 * 「播放全部 / 随机播放 / 更多」操作区。
 */
package com.mtechviral.musicfinderexample.feature.artists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.MpCircleAvatar
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.ArtistMeta
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch

/**
 * 艺术家详情页（Dart `ArtistDetailPage`）
 *
 * @param onOpenAlbum 点击专辑卡片时跳转专辑详情（默认空实现：不跳转，不崩溃）
 * @param onBack 顶栏返回按钮（默认空实现：不返回，不崩溃）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistName: String,
    onOpenAlbum: (String) -> Unit = {},
    onBack: () -> Unit = {},
) {
    // 监听曲库变化：扫描/缓存完成后自动刷新
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()

    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var artist by remember { mutableStateOf<Artist?>(null) }
    var artistMeta by remember { mutableStateOf<ArtistMeta?>(null) }
    var loading by remember { mutableStateOf(true) }

    // EntityActionSheet / 歌单选择器状态
    var pendingSong by remember { mutableStateOf<Song?>(null) }
    var showArtistActions by remember { mutableStateOf(false) }
    var pickingSongs by remember { mutableStateOf<List<Song>?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(artistName, library) {
        songs = DatabaseHelper.querySongsByArtist(artistName)
        albums = DatabaseHelper.queryAlbumsByArtist(artistName)
        artist = DatabaseHelper.queryArtists().firstOrNull { it.name == artistName }
        // 远程封面：artistMeta.coverArtId 交给 ArtworkImage 触发按需缓存
        artistMeta = DatabaseHelper.queryArtistMeta(artistName)
        loading = false
    }

    val firstSong = songs.firstOrNull()
    val artistEntity = artist
    val artistForActions = artistEntity ?: Artist(
        name = artistName,
        songCount = songs.size,
        albumCount = albums.size,
        coverSongPath = firstSong?.path,
        coverArtworkPath = firstSong?.cachedArtworkPath,
        coverArtId = firstSong?.coverArtId,
    )
    val coverArtId = artistMeta?.coverArtId ?: artist?.coverArtId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(artistName) },
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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                // 头部信息区
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (artistEntity != null) {
                            ArtistAvatar(
                                artist = artistEntity,
                                size = 110.dp,
                                coverArtId = coverArtId,
                            )
                        } else {
                            MpCircleAvatar(text = artistName, size = 110.dp)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artistName,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.W700),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "${songs.size} 首歌曲 · ${albums.size} 张专辑",
                                style = TextStyle(fontSize = 12.sp, color = MaterialTheme.ytTextSecondary),
                            )
                        }
                    }
                }

                // 播放全部 / 随机播放 / 更多
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                        IconButton(onClick = { showArtistActions = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多",
                            )
                        }
                    }
                }

                item { HorizontalDivider(thickness = 1.dp) }

                if (songs.isEmpty() && albums.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "该艺术家暂无歌曲", color = MaterialTheme.ytTextSecondary)
                        }
                    }
                } else {
                    // 专辑区（横向滚动）
                    if (albums.isNotEmpty()) {
                        item { DetailSectionTitle(text = "专辑") }
                        item {
                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(172.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(albums, key = { it.title }) { album ->
                                    ArtistAlbumCard(
                                        album = album,
                                        onClick = { onOpenAlbum(album.title) },
                                    )
                                }
                            }
                        }
                    }

                    // 单曲区
                    item { DetailSectionTitle(text = "单曲（${songs.size}）") }
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
                    item { Spacer(Modifier.height(24.dp)) }
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
                scope.launch { songs = DatabaseHelper.querySongsByArtist(artistName) }
            },
            onDelete = {
                // 删除已由弹窗完成，这里只刷新曲库（library 变化会触发上面重查）
                scope.launch { MusicLibrary.reload() }
            },
        )
    }

    // 艺术家「更多」
    if (showArtistActions) {
        EntityActionSheet(
            entity = EntityActionTarget.ArtistTarget(artistForActions, songs),
            onDismiss = { showArtistActions = false },
            onPlay = { list -> scope.launch { PlayerController.playSongs(list, 0) } },
            // 契约：队列写入已在弹窗内部完成，这里只作刷新通知（addSong 第十六轮起不再判重）
            onPlayNext = { },
            onAddToPlaylist = { list -> pickingSongs = list },
            onToggleLike = {
                scope.launch {
                    artist = DatabaseHelper.queryArtists().firstOrNull { it.name == artistName }
                    artistMeta = DatabaseHelper.queryArtistMeta(artistName)
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

/** 区块标题（Dart：fontSize 16 / w600，padding 16,16,16,8） */
@Composable
private fun DetailSectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600),
    )
}
