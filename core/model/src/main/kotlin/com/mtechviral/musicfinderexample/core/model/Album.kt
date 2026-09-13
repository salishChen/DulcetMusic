package com.mtechviral.musicfinderexample.core.model

/**
 * 专辑聚合实体：从 songs 表 GROUP BY album 查询构造，不单独入库。
 *
 * 对应原 Flutter 工程 `lib/data/models/album.dart`。
 */
data class Album(
    /** 专辑名 */
    val title: String,

    /** 专辑艺术家（优先 albumArtist，回退 artist） */
    val artist: String? = null,

    /** 用于取封面的歌曲 id（专辑内任一含封面歌曲） */
    val coverSongId: Long? = null,

    /** 用于取封面的歌曲路径 */
    val coverSongPath: String? = null,

    /** 缓存的封面路径 */
    val coverArtworkPath: String? = null,

    /** 远程歌曲的 coverArtId（用于按需从 Subsonic 缓存封面） */
    val coverArtId: String? = null,

    /** 专辑内歌曲数 */
    val songCount: Int = 0,
) {
    /** 展示用艺术家（空值回退） */
    val displayArtist: String
        get() = if (artist.isNullOrBlank()) "未知艺术家" else artist
}
