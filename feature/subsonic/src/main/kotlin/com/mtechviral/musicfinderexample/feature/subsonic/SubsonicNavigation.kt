/*
 * 对应 Dart 原文件：
 *   - lib/pages/subsonic_config_page.dart（页面本体）
 *   - lib/widgets/mp_nav_scaffold.dart（原 IndexedStack 中「远程配置」一级页面的注册）
 *
 * 远程配置页在本模块内只暴露一个导航图扩展函数，供 :app 组装路由。
 */

package com.mtechviral.musicfinderexample.feature.subsonic

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册远程配置路由（[AppRoutes.SUBSONIC]）。
 *
 * 一级页面：顶栏为 `PrimaryAppBar`，目录按钮由 home 模块的 LocalOpenSidebar 提供。
 */
fun NavGraphBuilder.subsonicGraph(navController: NavController) {
    composable(AppRoutes.SUBSONIC) {
        SubsonicConfigScreen()
    }
}
