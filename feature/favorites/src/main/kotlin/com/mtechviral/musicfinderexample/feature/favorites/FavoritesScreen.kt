/*
 * 对应 Dart 原文件：lib/pages/favorites_page.dart（喜欢页：音乐 / 专辑 / 艺术家 三个 Tab）
 *
 * 与 Dart 逐条对照：
 *  - 顶栏：PrimaryAppBar("喜欢")，左上角目录按钮 -> LocalOpenSidebar
 *  - 数据：queryLikedSongs() / queryLikedAlbums() / queryLikedArtists()，加载中显示整页进度圈
 *  - Tab：`音乐 (n)` / `专辑 (n)` / `艺术家 (n)`；Dart 用 TabController + TabBarView，原生用 TabRow + HorizontalPager
 *  - 音乐 Tab：封面 + 歌名 + "艺术家 · 专辑"，右侧红心按钮取消喜欢（toggleLikeSong）后立即刷新；
 *    点击歌曲：PlaylistRepository.setSongs(likedSongs) 后 PlayerController.playSong(song)
 *  - 专辑 Tab：两列网格（间距 12、内边距 16、宽高比 0.85），封面 + 专辑名 + "艺术家 · N首"，点击进入专辑详情
 *  - 艺术家 Tab：圆形封面 + 名称 + "N首歌 · N张专辑"，右侧红心取消喜欢（toggleLikeArtist）后立即刷新，点击进入艺术家详情
 *  - 空状态：图标 + 主文案 + "在播放页或歌曲菜单中点击 ❤ 收藏"
 *  - 进入页面时先做轻量一致性检查：queryLikedSongCount() 与页面内存数量一致则跳过整表刷新
 *
 * 与 Dart 的差异：
 *  1) Dart 用 RefreshIndicator 支持下拉刷新；原生未加（规范未列该组件），其余刷新时机一致（进入页面 / 曲库变化 / 取消喜欢后）。
 *  2) Dart 点击歌曲后 `openNowPlayingPage(song)`；原生按规范第 9 节统一为 setSongs + playSong（是否展开播放页由外壳决定）。
 *  3) 主题次要文字色使用 designsystem 的 MaterialTheme.ytTextSecondary（等价 Dart theme.textTheme.bodySmall）。
 */
package com.mtechviral.musicfinderexample.feature.favorites

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import com.mtechviral.musicfinderexample.feature.home.LocalSidebarDrag
import com.mtechviral.musicfinderexample.feature.home.SidebarDragHandle
import kotlinx.coroutines.launch

/** 喜欢页红心色（Dart Colors.red[400]） */
private val LikeRed = Color(0xFFEF5350)

/** 空状态灰阶（Dart Colors.grey[400] / Colors.grey[500]） */
private val EmptyIconGrey = Color(0xFFBDBDBD)
private val EmptyTextGrey = Color(0xFF9E9E9E)

/** 喜欢页：对应 Dart `FavoritesPage` */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FavoritesScreen(
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // 对应 Dart 页面 addListener(songData.notifier)：曲库变化时重查喜欢数据
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var likedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var likedAlbums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var likedArtists by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 3 })

    suspend fun loadAll() {
        likedSongs = DatabaseHelper.queryLikedSongs()
        likedAlbums = DatabaseHelper.queryLikedAlbums()
        likedArtists = DatabaseHelper.queryLikedArtists()
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

    val tabTitles = listOf(
        "音乐 (${likedSongs.size})",
        "专辑 (${likedAlbums.size})",
        "艺术家 (${likedArtists.size})",
    )

    // CompositionLocal.current 是 @Composable 读取，必须在 composable 函数体内取一次再传给非 @Composable 回调
    val openSidebar = LocalOpenSidebar.current
    // 侧边栏跟手拖拽接口：用于把「分页器滚不动」的剩余横向位移转交给主页（见下方 nestedScroll）
    val sidebarDrag = LocalSidebarDrag.current
    val sidebarNestedScroll = remember(sidebarDrag) { sidebarNestedScrollConnection(sidebarDrag) }

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
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = pagerState.currentPage) {
                        tabTitles.forEachIndexed { index, title ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            // 第一个 Tab 向右滑（分页器无法再往左翻）时，把剩余横向位移交给主页：
                            // 表现为"在第一个 Tab 右滑即可拉出侧边栏"（需求 4）。
                            // 其余情况（第 2/3 个 Tab 向右、第 1/2 个 Tab 向左）位移会被分页器消费。
                            .nestedScroll(sidebarNestedScroll),
                    ) { page ->
                        when (page) {
                            0 -> LikedSongsTab(
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

                            1 -> LikedAlbumsTab(
                                albums = likedAlbums,
                                onOpenAlbum = onOpenAlbum,
                            )

                            else -> LikedArtistsTab(
                                artists = likedArtists,
                                onOpenArtist = onOpenArtist,
                                onUnlike = { artist ->
                                    scope.launch {
                                        DatabaseHelper.toggleLikeArtist(artist.name)
                                        loadAll()
                                    }
                                },
                            )
                        }
                    }
                }
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

// ===================== 喜欢的专辑 =====================

@Composable
private fun LikedAlbumsTab(
    albums: List<Album>,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        FavoritesEmptyView(icon = Icons.Filled.Album, text = "还没有喜欢的专辑")
    } else LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = albums, key = { it.title }) { album ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 等价 Dart SliverGridDelegateWithFixedCrossAxisCount(childAspectRatio: 0.85)
                    .aspectRatio(0.85f)
                    .clickable { onOpenAlbum(album.title) },
            ) {
                ArtworkImage(
                    path = album.coverSongPath,
                    cachedArtworkPath = album.coverArtworkPath,
                    songId = album.coverSongId,
                    coverArtId = album.coverArtId,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    cornerRadius = 12.dp,
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = album.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W500,
                )
                Text(
                    text = "${album.displayArtist} · ${album.songCount}首",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
            }
        }
    }
}

// ===================== 喜欢的艺术家 =====================

@Composable
private fun LikedArtistsTab(
    artists: List<Artist>,
    onOpenArtist: (String) -> Unit,
    onUnlike: (Artist) -> Unit,
) {
    if (artists.isEmpty()) {
        FavoritesEmptyView(icon = Icons.Outlined.PeopleOutline, text = "还没有喜欢的艺术家")
    } else LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items = artists, key = { it.name }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenArtist(artist.name) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dart：ClipOval + MpArtwork（艺术家无歌曲 id，按 coverArtId 取远程封面）
                ArtworkImage(
                    path = artist.coverSongPath,
                    cachedArtworkPath = artist.coverArtworkPath,
                    coverArtId = artist.coverArtId,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                    cornerRadius = 24.dp,
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.W500,
                    )
                    Text(
                        text = "${artist.songCount}首歌 · ${artist.albumCount}张专辑",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
                IconButton(onClick = { onUnlike(artist) }) {
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

// ===================== 侧边栏手势转发 =====================

/**
 * 把「喜欢」页 Tab 分页器滚到边界后**未被消费**的横向位移/速度转交给主页侧边栏。
 *
 * 为什么需要：第一个 Tab 向右滑时 `HorizontalPager` 已无处可翻，但它仍会把手势消费掉
 * （overscroll），主页的 `draggable` 因此永远收不到事件 —— 表现为"第一个 Tab 右滑拉不出
 * 侧边栏"。这里通过 nestedScroll 的 postScroll / postFling（分页器消费后剩余的位移）
 * 转发即可：分页器真正能翻页时剩余量为 0，不会误触侧边栏。
 */
private fun sidebarNestedScrollConnection(handle: SidebarDragHandle): NestedScrollConnection =
    object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (available.x != 0f) handle.dragBy(available.x)
            return Offset.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (available.x != 0f) handle.settle(available.x)
            return Velocity.Zero
        }
    }
