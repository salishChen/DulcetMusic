/*
 * 对应 Dart 原文件：lib/pages/albums_page.dart（AlbumsPage）
 *                  lib/pages/album_detail_page.dart（AlbumDetailPage）
 *
 * 原工程用 `Navigator.push(MaterialPageRoute(...))` 直接构造页面；
 * 原生端收敛为本文件的导航图（见 docs/NATIVE_PORT_SPEC.md 第 9 节路由契约）。
 */
package com.mtechviral.musicfinderexample.feature.albums

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册专辑一级页（[AppRoutes.ALBUMS]）与专辑详情页（[AppRoutes.ALBUM_DETAIL]）。
 *
 * 屏幕本身用「默认参数 + 回调」暴露导航点，因此同一个 [AlbumsScreen] 既能被本导航图
 * 驱动，也能被 `:app` 的侧边栏外壳直接调用。
 */
fun NavGraphBuilder.albumsGraph(navController: NavController) {
    composable(route = AppRoutes.ALBUMS) {
        AlbumsScreen(
            onOpenAlbum = { album ->
                navController.navigate(AppRoutes.albumDetail(album.title, album.artist))
            },
        )
    }

    composable(
        route = AppRoutes.ALBUM_DETAIL,
        arguments = listOf(
            navArgument(AppRoutes.ARG_TITLE) { type = NavType.StringType },
            navArgument(AppRoutes.ARG_ARTIST) { type = NavType.StringType },
        ),
    ) { backStackEntry ->
        // AppRoutes.albumDetail 跳转时已做 Uri.encode，Navigation 读取路径参数时会自动解码，
        // 此处不要再解码一次（否则标题中含 "%xx" 时会被二次解码）
        val albumTitle = backStackEntry.arguments?.getString(AppRoutes.ARG_TITLE).orEmpty()
        val artistArg = backStackEntry.arguments?.getString(AppRoutes.ARG_ARTIST).orEmpty()
        AlbumDetailScreen(
            albumTitle = albumTitle,
            // 优化建议 10：复合键（专辑名 + 专辑艺术家）定位专辑；占位符还原为 null
            albumArtist = artistArg.takeIf { it.isNotEmpty() && it != AppRoutes.UNKNOWN_ARTIST },
            onBack = { navController.popBackStack() },
        )
    }
}
