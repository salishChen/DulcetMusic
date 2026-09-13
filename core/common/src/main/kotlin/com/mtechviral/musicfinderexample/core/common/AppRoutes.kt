package com.mtechviral.musicfinderexample.core.common

import android.net.Uri

/**
 * 全局导航路由契约。
 *
 * 原 Flutter 工程使用 `Navigator.push` + 直接构造页面；原生端统一收敛为
 * Navigation-Compose 路由字符串，各 feature 模块通过本契约互相跳转，
 * 避免 feature 之间产生编译期依赖。
 */
object AppRoutes {

    // ---- 一级页面 ----
    const val HOME = "home"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FAVORITES = "favorites"
    const val SCAN = "scan"
    const val SUBSONIC = "subsonic"
    const val STATS = "stats"
    const val SETTINGS = "settings"

    // ---- 二级页面 ----
    const val SEARCH = "search"
    const val CACHE_MANAGE = "cache_manage"
    const val LYRICS_OVERLAY_SETTINGS = "lyrics_overlay_settings"
    const val ALBUM_DETAIL = "album_detail/{title}"
    const val ARTIST_DETAIL = "artist_detail/{name}"
    const val PLAYLIST_DETAIL = "playlist_detail/{id}"

    const val ARG_TITLE = "title"
    const val ARG_NAME = "name"
    const val ARG_ID = "id"

    /** 跳转专辑详情（专辑名可能包含 `/`、`?` 等字符，需编码） */
    fun albumDetail(title: String): String = "album_detail/${Uri.encode(title)}"

    /** 跳转艺术家详情 */
    fun artistDetail(name: String): String = "artist_detail/${Uri.encode(name)}"

    /** 跳转歌单详情 */
    fun playlistDetail(id: Long): String = "playlist_detail/$id"
}
