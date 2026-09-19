package com.mtechviral.musicfinderexample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mtechviral.musicfinderexample.core.common.AppRoutes
import com.mtechviral.musicfinderexample.core.common.ThemePreference
import com.mtechviral.musicfinderexample.core.designsystem.component.MiniPlayerBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.YuleMusicTheme
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.feature.albums.albumsGraph
import com.mtechviral.musicfinderexample.feature.albums.AlbumsScreen
import com.mtechviral.musicfinderexample.feature.artists.artistsGraph
import com.mtechviral.musicfinderexample.feature.artists.ArtistsScreen
import com.mtechviral.musicfinderexample.feature.cache.cacheGraph
import com.mtechviral.musicfinderexample.feature.favorites.favoritesGraph
import com.mtechviral.musicfinderexample.feature.favorites.FavoritesScreen
import com.mtechviral.musicfinderexample.feature.home.HomeShell
import com.mtechviral.musicfinderexample.feature.home.LocalSelectPage
import com.mtechviral.musicfinderexample.feature.nowplaying.NowPlayingOverlay
import com.mtechviral.musicfinderexample.feature.playlists.playlistsGraph
import com.mtechviral.musicfinderexample.feature.playlists.PlaylistsScreen
import com.mtechviral.musicfinderexample.feature.scan.scanGraph
import com.mtechviral.musicfinderexample.feature.scan.ScanScreen
import com.mtechviral.musicfinderexample.feature.search.searchGraph
import com.mtechviral.musicfinderexample.feature.settings.SettingsScreen
import com.mtechviral.musicfinderexample.feature.settings.settingsGraph
import com.mtechviral.musicfinderexample.feature.songs.SongsScreen
import com.mtechviral.musicfinderexample.feature.songs.songsGraph
import com.mtechviral.musicfinderexample.feature.stats.StatsScreen
import com.mtechviral.musicfinderexample.feature.stats.statsGraph
import com.mtechviral.musicfinderexample.feature.subsonic.SubsonicConfigScreen
import com.mtechviral.musicfinderexample.feature.subsonic.subsonicGraph

/**
 * 应用根组合。
 *
 * 对应原 Flutter 工程 `lib/main.dart` 的 `MyMaterialApp.build`：
 * - 主题随 `ThemePreference` 的三态切换；
 * - 导航器与底部迷你播放栏竖向拼接：播放栏跨所有路由常驻；
 * - 「正在播放」以覆盖层形式从底部滑出（全局 `NowPlayingUiState.progress` 驱动），
 *   展开时播放栏淡化隐藏（由 `MiniPlayerBar` 内部绑定同一进度实现）。
 *
 * 系统栏（沉浸式）约定：
 * - 顶部只避让状态栏；
 * - 底部不再整体避让手势导航条，而是由常驻播放栏把背景一直铺到屏幕底边
 *   （内容仍留在导航条上方），避免出现「播放栏浮在小黑条上方、下方露一条底色」；
 * - 当前无歌曲（播放栏不渲染）时，导航内容自行避让手势条。
 */
@Composable
fun YuleMusicApp() {
    val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()

    YuleMusicTheme(mode = themeMode) {
        val navController = rememberNavController()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                // 导航区（占满剩余空间；无播放栏时才需要自行避让手势条）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (currentSong == null) Modifier.navigationBarsPadding() else Modifier,
                        ),
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = AppRoutes.HOME,
                    ) {
                        // 一级页面容器：拼接式侧边栏 + 九个一级页面保活切换
                        composable(AppRoutes.HOME) {
                            HomeShell { index ->
                                PrimaryPage(
                                    index = index,
                                    onOpenSearch = { navController.navigate(AppRoutes.SEARCH) },
                                    onOpenAlbum = {
                                        navController.navigate(AppRoutes.albumDetail(it))
                                    },
                                    onOpenArtist = {
                                        navController.navigate(AppRoutes.artistDetail(it))
                                    },
                                    onOpenPlaylist = {
                                        navController.navigate(AppRoutes.playlistDetail(it))
                                    },
                                    onOpenSubsonicConfig = {
                                        navController.navigate(AppRoutes.SUBSONIC)
                                    },
                                    onOpenCacheManage = {
                                        navController.navigate(AppRoutes.CACHE_MANAGE)
                                    },
                                    onOpenLyricsOverlaySettings = {
                                        navController.navigate(AppRoutes.LYRICS_OVERLAY_SETTINGS)
                                    },
                                )
                            }
                        }

                        // 各 feature 模块的导航图（二级页面 + 直接可达的一级页面路由）
                        songsGraph(navController)
                        albumsGraph(navController)
                        artistsGraph(navController)
                        playlistsGraph(navController)
                        favoritesGraph(navController)
                        searchGraph(navController)
                        scanGraph(navController)
                        statsGraph(navController)
                        cacheGraph(navController)
                        subsonicGraph(navController)
                        settingsGraph(navController)
                    }
                }

                // 底部常驻迷你播放栏（播放页展开时自动淡化隐藏）
                MiniPlayerBar(
                    onOpenNowPlaying = { NowPlayingUiState.open() },
                )
            }

            // 「正在播放」覆盖层：由全局进度驱动从底部滑出/收起
            NowPlayingOverlay(
                onClose = { NowPlayingUiState.close() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 一级页面按侧边栏下标分发（顺序与 `kSidebarItems` 完全一致）：
 * 0 歌曲 / 1 专辑 / 2 艺术家 / 3 歌单 / 4 喜欢 / 5 扫描音乐 / 6 远程配置 / 7 统计 / 8 设置
 */
@Composable
private fun PrimaryPage(
    index: Int,
    onOpenSearch: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenSubsonicConfig: () -> Unit,
    onOpenCacheManage: () -> Unit,
    onOpenLyricsOverlaySettings: () -> Unit,
) {
    // CompositionLocal.current 是 @Composable 读取，必须在 composable 函数体内取一次再传给非 @Composable 回调
    val selectPage = LocalSelectPage.current

    when (index) {
        0 -> SongsScreen(
            onOpenNowPlaying = { NowPlayingUiState.open() },
            onOpenSearch = onOpenSearch,
            // 空状态"去扫描"：等价 Dart 的 selectPage(5)
            onOpenScan = { selectPage(5) },
        )

        1 -> AlbumsScreen(onOpenAlbum = onOpenAlbum)
        2 -> ArtistsScreen(onOpenArtist = onOpenArtist)
        3 -> PlaylistsScreen(onOpenPlaylist = onOpenPlaylist)
        4 -> FavoritesScreen()
        5 -> ScanScreen(onOpenSubsonicConfig = onOpenSubsonicConfig)
        6 -> SubsonicConfigScreen()
        7 -> StatsScreen()
        8 -> SettingsScreen(
            onOpenCacheManage = onOpenCacheManage,
            onOpenLyricsOverlaySettings = onOpenLyricsOverlaySettings,
        )
    }
}
