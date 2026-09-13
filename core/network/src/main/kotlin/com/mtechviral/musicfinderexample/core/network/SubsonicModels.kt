package com.mtechviral.musicfinderexample.core.network

/**
 * Subsonic 相关数据传输对象。
 *
 * 原 Flutter 端直接以 `Map<String, dynamic>` 传递 JSON，原生端收敛为强类型，
 * 但字段名与解析规则（duration 以秒传输、track 可能缺省等）保持完全一致。
 */

/** 远程歌单摘要（getPlaylists 返回项） */
data class RemotePlaylist(
    val id: String,
    val name: String,
)

/** 远程歌单详情（getPlaylist 返回） */
data class RemotePlaylistDetail(
    val id: String,
    val name: String,
    val entries: List<RemoteSong>,
)

/** 远程歌曲（getAlbum 的 song 项 / getPlaylist 的 entry 项） */
data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val size: Long?,
    val suffix: String?,
    val contentType: String?,
    val bitRate: Int?,
    val track: Int?,
    val coverArt: String?,
)

/** Subsonic API 异常（含错误码与消息，对应 Dart 端抛出的 `Subsonic 错误 (code): message`） */
class SubsonicException(message: String) : Exception(message)
