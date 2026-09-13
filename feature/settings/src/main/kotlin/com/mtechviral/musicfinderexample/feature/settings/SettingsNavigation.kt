/*
 * 对应 Dart 原文件：
 *   - lib/pages/settings_page.dart（页面本体）
 *   - lib/widgets/mp_nav_scaffold.dart（原 IndexedStack 中「设置」一级页面的注册）
 *
 * 设置页在本模块内只暴露一个导航图扩展函数，供 :app 组装路由。
 */

package com.mtechviral.musicfinderexample.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册设置路由（[AppRoutes.SETTINGS]）与「桌面歌词」二级页。
 *
 * 一级页面：顶栏为 `PrimaryAppBar`；「缓存管理」入口跳转 [AppRoutes.CACHE_MANAGE]，
 * 「桌面歌词」入口跳转 [AppRoutes.LYRICS_OVERLAY_SETTINGS]。
 */
fun NavGraphBuilder.settingsGraph(navController: NavController) {
    composable(AppRoutes.SETTINGS) {
        SettingsScreen(
            onOpenCacheManage = { navController.navigate(AppRoutes.CACHE_MANAGE) },
            onOpenLyricsOverlaySettings = {
                navController.navigate(AppRoutes.LYRICS_OVERLAY_SETTINGS)
            },
        )
    }
    composable(AppRoutes.LYRICS_OVERLAY_SETTINGS) {
        LyricsOverlaySettingsScreen(onBack = { navController.popBackStack() })
    }
}
