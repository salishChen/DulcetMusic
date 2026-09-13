/*
 * 对应 Dart 原文件：lib/pages/favorites_page.dart（喜欢页路由注册）
 * （Dart 中该页面由侧边栏直接构造，原生端统一走 AppRoutes.FAVORITES）
 */
package com.mtechviral.musicfinderexample.feature.favorites

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册"喜欢"页路由。
 *
 * 屏幕函数保持规范签名 `FavoritesScreen()` 可调用，导航回调由本扩展函数注入
 * （Dart 中喜欢页点击专辑/艺术家会 push 详情页，见 `_buildLikedAlbums` / `_buildLikedArtists`）。
 */
fun NavGraphBuilder.favoritesGraph(navController: NavController) {
    composable(route = AppRoutes.FAVORITES) {
        FavoritesScreen(
            onOpenAlbum = { albumTitle -> navController.navigate(AppRoutes.albumDetail(albumTitle)) },
            onOpenArtist = { artistName -> navController.navigate(AppRoutes.artistDetail(artistName)) },
        )
    }
}
