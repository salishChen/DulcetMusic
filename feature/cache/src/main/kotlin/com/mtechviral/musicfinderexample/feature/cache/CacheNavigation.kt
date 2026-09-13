/*
 * 对应 Dart 原文件：
 *   - lib/pages/cache_manage_page.dart（页面本体）
 *   - lib/pages/settings_page.dart（原「缓存管理」入口的 Navigator.push）
 *
 * 缓存管理页在本模块内只暴露一个导航图扩展函数，供 :app 组装路由。
 */

package com.mtechviral.musicfinderexample.feature.cache

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes

/**
 * 注册缓存管理路由（[AppRoutes.CACHE_MANAGE]）。
 *
 * 二级页面：由「设置」页跳转进入，返回时回到上一页。
 */
fun NavGraphBuilder.cacheGraph(navController: NavController) {
    composable(AppRoutes.CACHE_MANAGE) {
        CacheManageScreen(onBack = { navController.popBackStack() })
    }
}
