package com.mtechviral.musicfinderexample.core.model

/**
 * 艺术家聚合实体：从 songs 表 GROUP BY artist 查询构造，不单独入库。
 *
 * 对应原 Flutter 工程 `lib/data/models/artist.dart`。
 */
data class Artist(
    val name: String,
    val songCount: Int = 0,
    val albumCount: Int = 0,

    /** 用于取封面的歌曲路径（任一歌曲） */
    val coverSongPath: String? = null,

    /** 缓存的封面路径 */
    val coverArtworkPath: String? = null,

    /** 远程歌曲的 coverArtId（用于按需从 Subsonic 缓存封面） */
    val coverArtId: String? = null,
)
