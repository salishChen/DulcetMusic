/*
 * 对应 Dart 原文件：lib/pages/favorites_page.dart（喜欢页）
 *
 * ============================ 需求变更：喜欢只针对单曲 ============================
 * 原 Dart 版喜欢页为「音乐 / 专辑 / 艺术家」三个 Tab，且「喜欢」可以作用于专辑与艺术家
 * （专辑 = 其下每首歌逐首喜欢；艺术家 = artists_meta.isLiked + 逐首喜欢）。
 * 现按需求收敛为**仅支持喜欢某首歌**：
 *  - 本页只保留「音乐」一个列表，专辑 / 艺术家两个 Tab 及对应查询已移除；
 *  - 操作弹窗（EntityActionSheet）对专辑 / 艺术家目标不再提供「喜欢」入口；
 *  - 数据库侧 queryLikedAlbums / queryLikedArtists / toggleLikeArtist 一并删除。
 *
 * 逐条对照（保留部分）：
 *  - 顶栏：PrimaryAppBar("喜欢")，左上角目录按钮 -> LocalOpenSidebar
 *  - 数据：queryLikedSongs()，加载中显示整页进度圈
 *  - 列表行：封面 + 歌名 + "艺术家 · 专辑"，右侧红心按钮取消喜欢（toggleLikeSong）后立即刷新
 *  - 点击歌曲：PlaylistRepository.setSongs(likedSongs) 后 PlayerController.playSong(song)
 *  - 空状态：图标 + 主文案 + "在播放页或歌曲菜单中点击 ❤ 收藏"
 *  - 进入页面时先做轻量一致性检查：queryLikedSongCount() 与页面内存数量一致则跳过整表刷新
 *
 * 与 Dart 的差异：
 *  1) Dart 用 RefreshIndicator 支持下拉刷新；原生未加，其余刷新时机一致（进入页面 / 曲库变化 / 取消喜欢后）。
 *  2) Dart 点击歌曲后 `openNowPlayingPage(song)`；原生统一为 setSongs + playSong（是否展开播放页由外壳决定）。
 *  3) 主题次要文字色使用 designsystem 的 MaterialTheme.ytTextSecondary（等价 Dart theme.textTheme.bodySmall）。
 *  4) 原先为「三 Tab 分页器」做的侧边栏 nestedScroll 转发（LocalSidebarDrag）已随分页器一并移除：
 *     本页只剩竖向列表，横向右滑直接由 HomeShell 的 draggable 接管，可正常拉出侧边栏。
 */
package com.mtechviral.musicfinderexample.feature.favorites

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/** 喜欢页红心色（Dart Colors.red[400]） */
private val LikeRed = Color(0xFFEF5350)

/** 空状态灰阶（Dart Colors.grey[400] / Colors.grey[500]） */
private val EmptyIconGrey = Color(0xFFBDBDBD)
private val EmptyTextGrey = Color(0xFF9E9E9E)

/**
 * 喜欢页：只展示**喜欢的歌曲**。
 *
 * 需求变更后不再接收 `onOpenAlbum` / `onOpenArtist`（专辑与艺术家 Tab 已移除），
 * 规范签名的 `FavoritesScreen()` 形式保持不变。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen() {
    val scope = rememberCoroutineScope()

    // 对应 Dart 页面 addListener(songData.notifier)：曲库变化时重查喜欢数据
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var likedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }

    suspend fun loadAll() {
        likedSongs = DatabaseHelper.queryLikedSongs()
        loading = false
        loadedOnce = true
    }

    // 进入页面（首次组合）/ 曲库变化：
    // 先用 queryLikedSongCount() 做轻量一致性检查，与页面内存数量一致则跳过整表刷新（Dart 原逻辑）
    LaunchedEffect(library) {
        if (loadedOnce) {
            val count = DatabaseHelper.queryLikedSongCount()
            if (count == likedSongs.size) return@LaunchedEffect
        }
        loadAll()
    }

    // CompositionLocal.current 是 @Composable 读取，必须在 composable 函数体内取一次再传给非 @Composable 回调
    val openSidebar = LocalOpenSidebar.current

    Scaffold(
        topBar = {
            PrimaryAppBar(
                title = "喜欢",
                onMenuClick = openSidebar,
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LikedSongsTab(
                    songs = likedSongs,
                    onUnlike = { song ->
                        scope.launch {
                            song.id?.let { DatabaseHelper.toggleLikeSong(it) }
                            loadAll()
                        }
                    },
                    onClickSong = { song ->
                        PlaylistRepository.setSongs(likedSongs)
                        scope.launch { PlayerController.playSong(song) }
                    },
                )
            }
        }
    }
}

// ===================== 喜欢的音乐 =====================

@Composable
private fun LikedSongsTab(
    songs: List<Song>,
    onUnlike: (Song) -> Unit,
    onClickSong: (Song) -> Unit,
) {
    if (songs.isEmpty()) {
        FavoritesEmptyView(icon = Icons.Filled.FavoriteBorder, text = "还没有喜欢的音乐")
    } else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items = songs, key = { it.path }) { song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClickSong(song) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SongArtwork(
                    song = song,
                    modifier = Modifier.size(50.dp),
                    cornerRadius = 12.dp,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${song.displayArtist} · ${song.displayAlbum}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
                IconButton(onClick = { onUnlike(song) }) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = "取消喜欢",
                        tint = LikeRed,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// ===================== 空状态 =====================

/** 对应 Dart `_emptyView(icon, text)` */
@Composable
private fun FavoritesEmptyView(icon: ImageVector, text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = EmptyIconGrey,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = text, color = EmptyTextGrey, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "在播放页或歌曲菜单中点击 ❤ 收藏", color = EmptyIconGrey, fontSize = 13.sp)
        }
    }
}
