/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/scan_page.dart
 * （原实现没有独立路由，由 MPNavScaffold 的 IndexedStack 第 5 页承载；
 *  原生端按规范第 9 节收敛为 Navigation-Compose 的 "scan" 路由）
 */
package com.mtechviral.musicfinderexample.feature.scan

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 扫描音乐一级页面导航图：注册 [AppRoutes.SCAN]。
 *
 * 页面跳转以回调参数驱动（屏幕本身不依赖 NavController，便于 `:app` 的 HomeShell
 * 直接按索引调用）。
 */
fun NavGraphBuilder.scanGraph(navController: NavController) {
    composable(AppRoutes.SCAN) {
        ScanScreen(onOpenSubsonicConfig = { navController.navigate(AppRoutes.SUBSONIC) })
    }
}
