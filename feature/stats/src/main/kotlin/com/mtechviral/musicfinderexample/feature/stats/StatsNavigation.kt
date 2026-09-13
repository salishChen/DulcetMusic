/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/stats_page.dart
 * （原实现没有独立路由，由 MPNavScaffold 的 IndexedStack 第 7 页承载；
 *  原生端按规范第 9 节收敛为 Navigation-Compose 的 "stats" 路由）
 */
package com.mtechviral.musicfinderexample.feature.stats

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 统计一级页面导航图：注册 [AppRoutes.STATS]。
 *
 * 页面内不需要跳转，无回调参数（屏幕同样可被 `:app` 的 HomeShell 直接调用）。
 */
fun NavGraphBuilder.statsGraph(navController: NavController) {
    composable(AppRoutes.STATS) {
        StatsScreen()
    }
}
