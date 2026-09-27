package com.mtechviral.musicfinderexample.core.common

import java.security.MessageDigest

/**
 * 远程歌曲的稳定资源定位符（优化建议 01）。
 *
 * 数据库与备份中**不再持久化带认证参数（u / s / t）的流地址**，
 * 只保存稳定定位符 `subsonic://<remoteId>@<来源哈希12>`：
 * - `remoteId`：远程服务器上的稳定歌曲 ID；
 * - 来源哈希：按 `source`（如 `subsonic@用户@服务器地址`）散列，
 *   换服务器后定位符自然不同，不会与旧服务器的记录冲突。
 *
 * 播放 / 下载时由网络层按当前配置**临时**生成带认证的流地址。
 */
object RemoteLocator {

    private const val PREFIX = "subsonic://"

    /** 生成远程歌曲定位符（remoteId 缺失时以身份键散列兜底） */
    fun subsonic(remoteId: String?, source: String?, identityKey: String): String {
        val idPart = remoteId?.takeIf { it.isNotEmpty() } ?: "song-${sha256Hex(identityKey).take(12)}"
        return "$PREFIX$idPart@${sha256Hex(source ?: "").take(12)}"
    }

    /** 是否为远程定位符（而非本地文件路径） */
    fun isLocator(path: String): Boolean = path.startsWith(PREFIX)

    /** New protocol-neutral locator. Old subsonic:// paths remain readable. */
    fun forSource(sourceId: String, remoteId: String): String {
        require(sourceId.isNotBlank() && remoteId.isNotBlank())
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(remoteId.toByteArray(Charsets.UTF_8))
        return "remote://$sourceId/$encoded"
    }

    /** SHA-256 十六进制 */
    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
