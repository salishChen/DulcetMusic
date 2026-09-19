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
 * 屏幕函数保持规范签名 `FavoritesScreen()` 可调用。
 * 需求变更后本页只展示喜欢的歌曲，专辑 / 艺术家 Tab 已移除，
 * 因此不再需要向屏幕注入 `onOpenAlbum` / `onOpenArtist`（保留 [navController] 参数以符合导航图约定）。
 */
fun NavGraphBuilder.favoritesGraph(@Suppress("UNUSED_PARAMETER") navController: NavController) {
    composable(route = AppRoutes.FAVORITES) {
        FavoritesScreen()
    }
}
