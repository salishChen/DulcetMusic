package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig

class SubsonicProvider(override val source: RemoteSource) : RemoteMusicProvider {
    private fun config() = SubsonicConfig(
        intranetUrl = source.intranetUrl,
        publicUrl = source.publicUrl,
        username = source.username,
        password = source.password,
        serverName = source.displayName,
    )

    override suspend fun testConnection(): String = when (val result = SubsonicService.testConnection(
        config(), includeEasyTier = SubsonicService.currentSourceId == source.id,
    )) {
        is SubsonicService.ConnectionTestResult.Success -> "连接成功"
        is SubsonicService.ConnectionTestResult.AuthFailed -> throw IllegalStateException(result.message)
        is SubsonicService.ConnectionTestResult.InvalidUrl -> throw IllegalArgumentException(result.message)
        is SubsonicService.ConnectionTestResult.Unreachable -> throw IllegalStateException(result.message)
    }

    suspend fun activate() {
        SubsonicService.activate(config())
        SubsonicService.currentSourceId = source.id
    }

    override suspend fun getAllSongs(): List<Song> =
        (if (source.protocol == RemoteProtocol.NAVIDROME) SubsonicService.getAllSongsPaged()
            else SubsonicService.getAllSongs()).map { song ->
            val id = requireNotNull(song.remoteId) { "Subsonic song has no id" }
            song.copy(sourceId = source.id, path = RemoteLocator.forSource(source.id, id))
        }

    override suspend fun stream(song: Song): RemoteRequest =
        RemoteRequest(SubsonicService.getStreamUrl(requireNotNull(song.remoteId)))

    override suspend fun artwork(song: Song): ByteArray? =
        song.coverArtId?.let { SubsonicService.getCoverArt(it) }

    override suspend fun lyrics(song: Song): String? =
        SubsonicService.getLyrics(song.artist ?: "", song.title)
}
