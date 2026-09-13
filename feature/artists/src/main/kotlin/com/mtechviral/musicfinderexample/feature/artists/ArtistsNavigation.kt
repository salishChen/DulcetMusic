/*
 * 对应 Dart 原文件：lib/pages/artists_page.dart（ArtistsPage）
 *                  lib/pages/artist_detail_page.dart（ArtistDetailPage）
 *
 * 原工程用 `Navigator.push(MaterialPageRoute(...))` 直接构造页面；
 * 原生端收敛为本文件的导航图（见 docs/NATIVE_PORT_SPEC.md 第 9 节路由契约）。
 */
package com.mtechviral.musicfinderexample.feature.artists

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册艺术家一级页（[AppRoutes.ARTISTS]）与艺术家详情页（[AppRoutes.ARTIST_DETAIL]）。
 *
 * 屏幕本身用「默认参数 + 回调」暴露导航点，因此同一个 [ArtistsScreen] 既能被本导航图
 * 驱动，也能被 `:app` 的侧边栏外壳直接调用。
 */
fun NavGraphBuilder.artistsGraph(navController: NavController) {
    composable(route = AppRoutes.ARTISTS) {
        ArtistsScreen(
            onOpenArtist = { name -> navController.navigate(AppRoutes.artistDetail(name)) },
        )
    }

    composable(
        route = AppRoutes.ARTIST_DETAIL,
        arguments = listOf(navArgument(AppRoutes.ARG_NAME) { type = NavType.StringType }),
    ) { backStackEntry ->
        // AppRoutes.artistDetail 跳转时已做 Uri.encode，Navigation 读取路径参数时会自动解码，
        // 此处不要再解码一次（否则名称中含 "%xx" 时会被二次解码）
        val artistName = backStackEntry.arguments?.getString(AppRoutes.ARG_NAME).orEmpty()
        ArtistDetailScreen(
            artistName = artistName,
            // 专辑详情路由由 feature:albums 的 albumsGraph 注册，同一个 NavHost 内按路由字符串跳转
            onOpenAlbum = { title -> navController.navigate(AppRoutes.albumDetail(title)) },
            onBack = { navController.popBackStack() },
        )
    }
}
