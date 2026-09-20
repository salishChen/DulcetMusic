/*
 * 对应 Dart 原文件：lib/pages/artists_page.dart（ArtistsPage / _ArtistsPageState）
 *
 * 行为对齐要点：
 * - 数据来源 `DatabaseHelper.queryArtists()`（SQL 层已按艺术家名升序）；
 * - 首次进入 + 曲库变化时重查（Dart 的 `songData.notifier` 监听）；
 * - 空状态文案「暂无艺术家」，加载中显示 CircularProgressIndicator；
 * - 行文案 "N 首歌曲 · M 张专辑"；点击行跳艺术家详情，更多按钮弹出 EntityActionSheet（艺术家）。
 */
package com.mtechviral.musicfinderexample.feature.artists

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/**
 * 艺术家一级页面（Dart `ArtistsPage`）
 *
 * @param onOpenArtist 点击艺术家行时跳转艺术家详情（默认空实现：不跳转，不崩溃）
 */
@Composable
fun ArtistsScreen(onOpenArtist: (String) -> Unit = {}) {
    val openSidebar = LocalOpenSidebar.current

    // 监听曲库变化：扫描完成后自动刷新
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var artists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // EntityActionSheet / 歌单选择器状态
    var actionArtist by remember { mutableStateOf<Artist?>(null) }
    var actionSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var pickingSongs by remember { mutableStateOf<List<Song>?>(null) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(library) {
        artists = DatabaseHelper.queryArtists()
        loading = false
    }

    Scaffold(
        topBar = { PrimaryAppBar(title = "艺术家", onMenuClick = { openSidebar() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                artists.isEmpty() -> Text(
                    text = "暂无艺术家",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.ytTextSecondary,
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(artists, key = { it.name }) { artist ->
                        ArtistRow(
                            artist = artist,
                            onClick = { onOpenArtist(artist.name) },
                            onMoreClick = {
                                scope.launch {
                                    actionSongs = DatabaseHelper.querySongsByArtist(artist.name)
                                    actionArtist = artist
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // 艺术家「更多」：弹窗内部完成喜欢/缓存等写入，这里只负责播放与刷新
    actionArtist?.let { artist ->
        EntityActionSheet(
            entity = EntityActionTarget.ArtistTarget(artist, actionSongs),
            onDismiss = { actionArtist = null },
            onPlay = { songs -> scope.launch { PlayerController.playSongs(songs, 0) } },
            // 队列写入已由弹窗内部完成（契约：onPlayNext 仅作刷新通知）；addSong 第十六轮起不再判重
            onPlayNext = { },
            onAddToPlaylist = { songs -> pickingSongs = songs },
            onToggleLike = {
                // 喜欢状态已在弹窗内部写入，这里只刷新列表
                scope.launch { artists = DatabaseHelper.queryArtists() }
            },
            onDelete = {
                // 删除已由弹窗完成，这里只刷新曲库（library 变化会触发上面重查）
                scope.launch { MusicLibrary.reload() }
            },
            onExcludeArtist = { artist, removedCount ->
                // 第二十七轮需求：排除歌手已由弹窗完成（含删库），这里刷新本页。
                // 直接重查一次，避免依赖 library 变化（删 0 首时其值不变，不会触发重查）。
                scope.launch {
                    artists = DatabaseHelper.queryArtists()
                    if (removedCount == 0) {
                        snackbarHostState.showSnackbar("已排除「$artist」，曲库中没有其音乐")
                    }
                }
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
