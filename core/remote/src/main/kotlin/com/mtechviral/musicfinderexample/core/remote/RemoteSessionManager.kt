package com.mtechviral.musicfinderexample.core.remote

import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.media.ExclusionFilter
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.EmbyProvider
import com.mtechviral.musicfinderexample.core.network.RemoteMusicProvider
import com.mtechviral.musicfinderexample.core.network.RemoteLibrary
import com.mtechviral.musicfinderexample.core.network.RemoteRequest
import com.mtechviral.musicfinderexample.core.network.SubsonicProvider
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.network.WebDavProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sole remote connection visible to the app. All public operations reject stale songs. */
object RemoteSessionManager {
    enum class ScanPhase { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    data class ScanState(
        val sourceId: String? = null,
        val phase: ScanPhase = ScanPhase.IDLE,
        val processed: Int = 0,
        val total: Int = 0,
        val imported: Int = 0,
        val error: String? = null,
    )

    private val mutex = Mutex()
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState
    private val _source = MutableStateFlow<RemoteSource?>(null)
    val source: StateFlow<RemoteSource?> = _source
    @Volatile private var provider: RemoteMusicProvider? = null
    @Volatile private var generation = 0L
    private var loaded = false
    private var scanJob: Job? = null

    val isConfigured: Boolean get() = _source.value != null
    val activeSourceId: String? get() = _source.value?.id
    val sessionRevision: Long get() = generation

    private fun newProvider(source: RemoteSource): RemoteMusicProvider = when (source.protocol) {
        RemoteProtocol.SUBSONIC, RemoteProtocol.NAVIDROME -> SubsonicProvider(source)
        RemoteProtocol.WEBDAV -> WebDavProvider(source)
        RemoteProtocol.EMBY -> EmbyProvider(source)
    }

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        val stored = DatabaseHelper.activeRemoteSource()
        val next = stored?.let(::newProvider)
        if (next is SubsonicProvider) next.activate()
        else SubsonicService.deactivate()
        provider = next
        _source.value = stored
        generation++
        loaded = true
    }

    suspend fun test(candidate: RemoteSource): String {
        scanJob?.cancelAndJoin()
        return mutex.withLock { newProvider(candidate).testConnection() }
    }

    suspend fun libraries(candidate: RemoteSource): List<RemoteLibrary> {
        scanJob?.cancelAndJoin()
        return mutex.withLock {
            val temporary = newProvider(candidate)
            temporary.testConnection()
            temporary.libraries()
        }
    }

    /** The scan belongs to the active source, not to the lifetime of the scan screen. */
    suspend fun startScan(): Boolean = mutex.withLock {
        if (scanJob != null) return@withLock false
        val sourceId = activeSourceId ?: throw IllegalStateException("请先配置远程音乐源")
        val task = scanScope.launch(start = CoroutineStart.LAZY) {
            try {
                val imported = scan { processed, total ->
                    _scanState.value = ScanState(sourceId, ScanPhase.RUNNING, processed, total)
                }
                if (activeSourceId != sourceId) throw CancellationException("远程音乐源已切换")
                val last = _scanState.value
                _scanState.value = ScanState(sourceId, ScanPhase.SUCCEEDED,
                    last.processed, last.total, imported)
            } catch (e: CancellationException) {
                _scanState.value = ScanState(sourceId, ScanPhase.CANCELLED)
                throw e
            } catch (e: Exception) {
                _scanState.value = ScanState(sourceId, ScanPhase.FAILED, error = e.message)
            }
        }
        scanJob = task
        task.invokeOnCompletion {
            scanScope.launch {
                mutex.withLock { if (scanJob === task) scanJob = null }
            }
        }
        _scanState.value = ScanState(sourceId, ScanPhase.RUNNING)
        task.start()
        true
    }

    /** Caller must stop old remote playback, scanning and cache work before invoking this. */
    suspend fun activate(candidate: RemoteSource) {
        scanJob?.cancelAndJoin()
        mutex.withLock {
        if (_source.value?.id != candidate.id) SubsonicService.easyTierBaseUrl = null
        val next = newProvider(candidate)
        next.testConnection()
        val verified = if (next is EmbyProvider) candidate.copy(serverIdentity = next.serverIdentity)
            else candidate
        DatabaseHelper.activateRemoteSource(verified)
        if (next is SubsonicProvider) next.activate()
        else SubsonicService.deactivate()
        provider = next
        _source.value = verified
        generation++
        loaded = true
        MusicLibrary.reload()
        }
    }

    suspend fun deactivate() {
        scanJob?.cancelAndJoin()
        mutex.withLock {
        DatabaseHelper.deactivateRemoteSource()
        provider = null
        SubsonicService.deactivate()
        _source.value = null
        generation++
        loaded = true
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

    suspend fun stream(song: Song): RemoteRequest {
        val active = requireCurrent(song)
        val request = active.stream(song)
        if (provider !== active) throw CancellationException("远程音乐源已切换")
        return request
    }

    suspend fun artwork(song: Song): ByteArray? {
        val active = requireCurrent(song)
        val result = active.artwork(song)
        if (provider !== active) throw CancellationException("远程音乐源已切换")
        return result
    }

    suspend fun lyrics(song: Song): String? {
        val active = requireCurrent(song)
        val result = active.lyrics(song)
        if (provider !== active) throw CancellationException("远程音乐源已切换")
        return result
    }

    suspend fun scan(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val job = coroutineContext[Job] ?: error("扫描需要协程")
        val (current, key) = mutex.withLock {
            if (scanJob != null && scanJob !== job) throw IllegalStateException("已有远程扫描任务")
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
