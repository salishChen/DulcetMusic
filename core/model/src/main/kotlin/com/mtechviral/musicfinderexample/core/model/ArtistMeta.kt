package com.mtechviral.musicfinderexample.core.model

/**
 * 艺术家元数据：映射 artists_meta 表。
 *
 * 原 Flutter 端以 `Map<String, dynamic>` 传递该表数据（`queryArtistMeta` /
 * `queryAllArtistMeta`），原生端收敛为强类型实体。
 */
data class ArtistMeta(
    val id: Long? = null,
    val name: String,
    /** Subsonic 服务器上的艺术家 ID */
    val artistId: String? = null,
    /** Subsonic 封面 ID */
    val coverArtId: String? = null,
    /** 本地缓存的封面文件路径 */
    val cachedArtworkPath: String? = null,
    val isLiked: Boolean = false,
)
