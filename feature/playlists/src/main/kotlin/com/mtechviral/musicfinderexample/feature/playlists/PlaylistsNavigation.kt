/*
 * 对应 Dart 原文件：lib/pages/playlists_page.dart、lib/pages/playlist_detail_page.dart
 * （Dart 中用 Navigator.push(MaterialPageRoute(...)) 直接构造页面，原生端改为 Navigation-Compose 路由注册）
 *
 * 本文件只暴露一个导航图扩展函数：
 *   - AppRoutes.PLAYLISTS       -> PlaylistsScreen()
 *   - AppRoutes.PLAYLIST_DETAIL -> PlaylistDetailScreen(playlistId)
 */
package com.mtechviral.musicfinderexample.feature.playlists

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册歌单模块路由（一级页 + 二级详情页）。
 *
 * 屏幕函数带默认参数（规范表格中的 `PlaylistsScreen()` / `PlaylistDetailScreen(playlistId)` 调用形式仍然成立），
 * 导航回调由本扩展函数注入，feature 之间不产生编译期依赖。
 */
fun NavGraphBuilder.playlistsGraph(navController: NavController) {
    composable(route = AppRoutes.PLAYLISTS) {
        PlaylistsScreen(
            onOpenPlaylist = { playlistId -> navController.navigate(AppRoutes.playlistDetail(playlistId)) },
        )
    }

    composable(
        route = AppRoutes.PLAYLIST_DETAIL,
        arguments = listOf(navArgument(AppRoutes.ARG_ID) { type = NavType.LongType }),
    ) { backStackEntry ->
        val playlistId = backStackEntry.arguments?.getLong(AppRoutes.ARG_ID, 0L) ?: 0L
        PlaylistDetailScreen(
            playlistId = playlistId,
            onBack = { navController.popBackStack() },
        )
    }
}
