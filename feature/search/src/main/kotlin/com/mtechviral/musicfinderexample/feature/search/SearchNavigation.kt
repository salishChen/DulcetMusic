/*
 * 对应 Dart 原文件：`.flutter_reference/lib/widgets/music_search_delegate.dart`
 * （旧版由 `MusicSearchDelegate` 内部 `Navigator.push` 跳转专辑/艺术家详情页；
 *  原生端由本文件提供 `navController` 并通过 [LocalSearchNavigator] 注入搜索页，
 *  从而保持规范第 9 节冻结的 `SearchScreen(onBack)` 签名不变）
 */
package com.mtechviral.musicfinderexample.feature.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.mtechviral.musicfinderexample.core.common.AppRoutes
import com.mtechviral.musicfinderexample.core.model.Album

/**
 * 搜索页跳转能力：搜索结果里点击专辑 / 艺术家时跳转到对应详情页
 * （等价 Dart 的 `Navigator.push(AlbumDetailPage/ArtistDetailPage)`）。
 */
class SearchNavigator internal constructor(private val navController: NavController?) {

    /** 优化建议 10：专辑详情按「专辑名 + 专辑艺术家」复合键跳转 */
    fun openAlbum(album: Album) {
        navController?.navigate(AppRoutes.albumDetail(album.title, album.artist, album.sourceId))
    }

    fun openArtist(name: String) {
        navController?.navigate(AppRoutes.artistDetail(name))
    }

    companion object {
        /** 无导航能力（预览 / 独立使用）时的空实现 */
        val NoOp: SearchNavigator = SearchNavigator(null)
    }
}

/** 由 [searchGraph] 注入；默认 [SearchNavigator.NoOp] */
val LocalSearchNavigator = staticCompositionLocalOf { SearchNavigator.NoOp }

/**
 * 搜索页导航图（路由 [AppRoutes.SEARCH]）。
 * 规范第 9 节：二级页面，由调用方（HomeScreen/首页搜索按钮）通过 `navController.navigate(AppRoutes.SEARCH)` 进入。
 */
fun NavGraphBuilder.searchGraph(navController: NavController) {
    composable(AppRoutes.SEARCH) {
        CompositionLocalProvider(
            LocalSearchNavigator provides SearchNavigator(navController),
        ) {
            SearchScreen(onBack = { navController.popBackStack() })
        }
    }
}
