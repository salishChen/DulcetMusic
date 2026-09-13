/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/songs_page.dart
 * （原实现没有独立路由，由 MPNavScaffold 的 IndexedStack 第 0 页承载并内部 push 搜索页；
 *  原生端按规范第 9 节收敛为 Navigation-Compose 的 "songs" 路由）
 */
package com.mtechviral.musicfinderexample.feature.songs

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState

/**
 * 歌曲一级页面导航图：注册 [AppRoutes.SONGS]。
 *
 * 页面跳转全部通过 [SongsScreen] 的回调参数驱动（屏幕本身不依赖 NavController，
 * 以便 `:app` 的 HomeShell 直接按索引调用）。
 */
fun NavGraphBuilder.songsGraph(navController: NavController) {
    composable(AppRoutes.SONGS) {
        SongsScreen(
            // 等价 Dart 播放页滑出（nowPlayingController 展开）
            onOpenNowPlaying = { NowPlayingUiState.open() },
            onOpenSearch = { navController.navigate(AppRoutes.SEARCH) },
            onOpenScan = { navController.navigate(AppRoutes.SCAN) },
        )
    }
}
