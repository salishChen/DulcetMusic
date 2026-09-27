package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song

/** Authentication belongs to each request; URLs and headers must never be persisted. */
data class RemoteRequest(val url: String, val headers: Map<String, String> = emptyMap())
data class RemoteLibrary(val id: String, val name: String)

interface RemoteMusicProvider {
    val source: RemoteSource
    suspend fun testConnection(): String
    suspend fun libraries(): List<RemoteLibrary> = emptyList()
    suspend fun getAllSongs(): List<Song>
    suspend fun stream(song: Song): RemoteRequest
    suspend fun artwork(song: Song): ByteArray?
    suspend fun lyrics(song: Song): String?
}
