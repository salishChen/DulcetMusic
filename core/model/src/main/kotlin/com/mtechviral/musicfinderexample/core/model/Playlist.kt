package com.mtechviral.musicfinderexample.core.model

/**
 * 歌单实体：映射 playlists 表。
 * 对应原 Flutter 工程 `lib/data/models/playlist.dart`。
 */
data class Playlist(
    val id: Long? = null,
    val name: String,
    /** 歌单内歌曲数量（列表页展示用，查询时 JOIN 统计） */
    val songCount: Int = 0,
)

/**
 * 歌单-歌曲绑定记录：映射 playlist_songs 子表。
 */
data class PlaylistSong(
    val playlistId: Long,
    val songId: Long,
    /** 歌单内排序位置 */
    val position: Int,
)
