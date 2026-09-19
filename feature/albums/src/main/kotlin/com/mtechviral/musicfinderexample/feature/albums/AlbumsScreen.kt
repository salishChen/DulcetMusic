/*
 * 对应 Dart 原文件：lib/pages/albums_page.dart（AlbumsPage / _AlbumsPageState）
 *
 * 行为对齐要点：
 * - 数据来源 `DatabaseHelper.queryAlbums()`（SQL 层已按专辑名升序）；
 * - 首次进入 + 曲库变化时重查（Dart 的 `songData.notifier` 监听）；
 * - 空状态文案「暂无专辑」，加载中显示 CircularProgressIndicator；
 * - 网格 2 列、间距 12、childAspectRatio 0.78、外层 padding 12（与 Dart 常量一致）；
 * - 点击卡片跳专辑详情，点击/长按三点按钮弹出 EntityActionSheet（专辑）。
 */
package com.mtechviral.musicfinderexample.feature.albums

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/**
 * 专辑一级页面（Dart `AlbumsPage`）
 *
 * @param onOpenAlbum 点击专辑卡片时跳转专辑详情（默认空实现：不跳转，不崩溃）
 */
@Composable
fun AlbumsScreen(onOpenAlbum: (String) -> Unit = {}) {
    val openSidebar = LocalOpenSidebar.current

    // 监听曲库变化：扫描完成后自动刷新（等价 Dart 的 songData.notifier.addListener）
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // EntityActionSheet 状态
    var actionAlbum by remember { mutableStateOf<Album?>(null) }
    var actionSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    // "添加到歌单"选择器状态
    var pickingSongs by remember { mutableStateOf<List<Song>?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(library) {
        albums = DatabaseHelper.queryAlbums()
        loading = false
    }

    Scaffold(
        topBar = { PrimaryAppBar(title = "专辑", onMenuClick = { openSidebar() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                albums.isEmpty() -> Text(
                    text = "暂无专辑",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ytTextSecondary,
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(albums, key = { it.title }) { album ->
                        AlbumGridCard(
                            album = album,
                            onClick = { onOpenAlbum(album.title) },
                            onMoreClick = {
                                scope.launch {
                                    actionSongs = DatabaseHelper.querySongsByAlbum(album.title)
                                    actionAlbum = album
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 专辑「更多」：弹窗内部完成喜欢/缓存等写入，这里只负责播放与刷新
    actionAlbum?.let { album ->
        EntityActionSheet(
            entity = EntityActionTarget.AlbumTarget(album, actionSongs),
            onDismiss = { actionAlbum = null },
            // 整列播放（对应 Dart setSongs + 从首曲起播）
            onPlay = { songs -> scope.launch { PlayerController.playSongs(songs, 0) } },
            // "添加到播放队列"：队列写入已在弹窗内部完成（PlaylistRepository.addSong 追加；
            // 该 API 无"插入下一首"变体，与 Dart 一致）。契约：onPlayNext 仅作刷新通知，
            // 此处不可再 addSong —— 第十六轮起 addSong 取消判重，重复调用会真的加入两份。
            onPlayNext = { },
            onAddToPlaylist = { songs -> pickingSongs = songs },
            onToggleLike = {
                // 喜欢状态已在弹窗内部写入，这里只刷新列表
                scope.launch { albums = DatabaseHelper.queryAlbums() }
            },
            onDelete = {
                // 删除已由弹窗完成，这里只刷新曲库（library 变化会触发上面重查）
                scope.launch { MusicLibrary.reload() }
            },
        )
    }

    // 「添加到歌单」选择器
    pickingSongs?.let { songs ->
        AddToPlaylistSheet(
            songs = songs,
            onDismiss = { pickingSongs = null },
            onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
        )
    }
}
