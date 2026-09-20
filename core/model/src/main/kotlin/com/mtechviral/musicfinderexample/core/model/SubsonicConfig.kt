package com.mtechviral.musicfinderexample.core.model

/**
 * Subsonic 服务器配置实体。
 *
 * 单条配置记录，包含内网和公网两个 URL（未填写的地址为空串，两者填其一即可）；
 * 两者都填写时优先使用内网连接，内网不可达时自动回退公网。
 *
 * 对应原 Flutter 工程 `lib/data/models/subsonic_config.dart`。
 */
data class SubsonicConfig(
    val id: Long? = null,
    val intranetUrl: String = "",
    val publicUrl: String = "",
    val username: String,
    val password: String,
    val serverName: String? = null,
    val isActive: Boolean = true,
)
