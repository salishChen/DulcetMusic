package com.mtechviral.musicfinderexample.core.remote

import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.media.ExclusionFilter
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.EmbyProvider
import com.mtechviral.musicfinderexample.core.network.RemoteMusicProvider
import com.mtechviral.musicfinderexample.core.network.RemoteRequest
import com.mtechviral.musicfinderexample.core.network.SubsonicProvider
import com.mtechviral.musicfinderexample.core.network.WebDavProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sole remote connection visible to the app. All public operations reject stale songs. */
object RemoteSessionManager {
    private val mutex = Mutex()
    private val _source = MutableStateFlow<RemoteSource?>(null)
    val source: StateFlow<RemoteSource?> = _source
    private var provider: RemoteMusicProvider? = null
    private var generation = 0L
    private var scanJob: Job? = null

    val isConfigured: Boolean get() = _source.value != null
    val activeSourceId: String? get() = _source.value?.id

    private fun newProvider(source: RemoteSource): RemoteMusicProvider = when (source.protocol) {
        RemoteProtocol.SUBSONIC, RemoteProtocol.NAVIDROME -> SubsonicProvider(source)
        RemoteProtocol.WEBDAV -> WebDavProvider(source)
        RemoteProtocol.EMBY -> EmbyProvider(source)
    }

    suspend fun load() = mutex.withLock {
        val stored = DatabaseHelper.activeRemoteSource()
        val next = stored?.let(::newProvider)
        if (next is SubsonicProvider) next.activate()
        provider = next
        _source.value = stored
        generation++
    }

    suspend fun test(candidate: RemoteSource): String = mutex.withLock {
        newProvider(candidate).testConnection()
    }

    /** Caller must stop old remote playback, scanning and cache work before invoking this. */
    suspend fun activate(candidate: RemoteSource) {
        scanJob?.cancelAndJoin()
        mutex.withLock {
        val next = newProvider(candidate)
        next.testConnection()
        DatabaseHelper.activateRemoteSource(candidate)
        if (next is SubsonicProvider) next.activate()
        provider = next
        _source.value = candidate
        generation++
        MusicLibrary.reload()
        }
    }

    suspend fun deactivate() {
        scanJob?.cancelAndJoin()
        mutex.withLock {
        DatabaseHelper.deactivateRemoteSource()
        provider = null
        _source.value = null
        generation++
        MusicLibrary.reload()
        }
    }

    private fun requireCurrent(song: Song): RemoteMusicProvider {
        val active = provider ?: throw IllegalStateException("远程音乐源未配置")
        if (song.sourceId == null || song.sourceId != active.source.id) {
            throw IllegalStateException("歌曲不属于当前远程音乐源")
        }
        return active
    }

    suspend fun stream(song: Song): RemoteRequest = requireCurrent(song).stream(song)
    suspend fun artwork(song: Song): ByteArray? = requireCurrent(song).artwork(song)
    suspend fun lyrics(song: Song): String? = requireCurrent(song).lyrics(song)

    suspend fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val job = coroutineContext[Job] ?: error("扫描需要协程")
        val (current, key) = mutex.withLock {
            if (scanJob != null) throw IllegalStateException("已有远程扫描任务")
            val current = provider ?: throw IllegalStateException("请先配置远程音乐源")
            scanJob = job
            current to generation
        }
        try {
            val songs = current.getAllSongs()
            var imported = 0
            var index = 0
            for (batch in songs.chunked(200)) {
                mutex.withLock {
                    if (generation != key) throw CancellationException("远程音乐源已切换")
                    imported += DatabaseHelper.insertSongs(ExclusionFilter.filter(batch), current.source.id)
                }
                index += batch.size
                onProgress(index, songs.size)
            }
            MusicLibrary.reload()
            return imported
        } finally {
            mutex.withLock { if (scanJob === job) scanJob = null }
        }
    }
}
