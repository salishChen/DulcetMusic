package com.mtechviral.musicfinderexample.core.cache

import android.content.Context
import android.util.Log
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Subsonic 缓存池管理服务。
 *
 * 与原 Flutter 工程 `lib/data/cache_service.dart` 逐方法对应：
 * 本地缓存目录、缓存池大小（默认 2GB，可调 500MB~50GB）、
 * LRU 淘汰、音频与封面缓存、后台缓存、清空缓存。
 *
 * 路径兼容：旧版本 Flutter 的文档目录为
 * `/data/data/<pkg>/app_flutter`，此处沿用同一位置，
 * 使**升级后已下载的缓存文件继续可用**。
 */
object CacheService {

    private const val TAG = "CacheService"

    private const val DEFAULT_CACHE_SIZE_MB = 2048 // 默认 2GB
    private const val MIN_CACHE_SIZE_MB = 500      // 最小 500MB
    private const val MAX_CACHE_SIZE_MB = 51200    // 最大 50GB

    private lateinit var appContext: Context

    /** 后台缓存任务作用域（startCaching 用，不阻塞调用方） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 在途缓存任务注册表（remoteId -> Deferred）：同一首歌并发触发时复用同一任务 */
    private val inFlight = HashMap<String, CompletableDeferred<String?>>()
    private val mutex = Mutex()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var cacheDirRef: File? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 获取缓存目录（不存在则创建） */
    suspend fun getCacheDir(): File = withContext(Dispatchers.IO) {
        cacheDirRef?.let { return@withContext it }
        // 与 Flutter 端 getApplicationDocumentsDirectory() 保持一致：<dataDir>/app_flutter
        val dir = File(File(appContext.dataDir, DOCUMENTS_DIR_NAME), CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        cacheDirRef = dir
        dir
    }

    /** 用户设置的缓存池大小（MB） */
    fun getCacheSizeMB(): Int =
        AppPreferences.getInt(AppPreferences.KEY_CACHE_SIZE_MB, DEFAULT_CACHE_SIZE_MB)

    /** 设置缓存池大小（MB），越界会被裁剪，并立即检查是否需要淘汰 */
    suspend fun setCacheSizeMB(sizeMB: Int) {
        val clamped = sizeMB.coerceIn(MIN_CACHE_SIZE_MB, MAX_CACHE_SIZE_MB)
        AppPreferences.putInt(AppPreferences.KEY_CACHE_SIZE_MB, clamped)
        evictIfNeeded(0)
    }

    /** 当前缓存总大小（字节） */
    suspend fun getCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        val dir = getCacheDir()
        var total = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { total += it.length() }
        total
    }

    /** 当前缓存总大小（MB，向上取整） */
    suspend fun getCacheSizeMBActual(): Int {
        val bytes = getCacheSizeBytes()
        return ((bytes + 1024 * 1024 - 1) / (1024 * 1024)).toInt()
    }

    /**
     * 检查并执行 LRU 淘汰。
     * @param requiredBytes 需要腾出的空间（字节）
     */
    suspend fun evictIfNeeded(requiredBytes: Long) {
        val maxSizeBytes = getCacheSizeMB().toLong() * 1024 * 1024
        val currentSize = getCacheSizeBytes()
        if (currentSize + requiredBytes <= maxSizeBytes) return

        var needEvict = (currentSize + requiredBytes) - maxSizeBytes
        while (needEvict > 0) {
            val oldest = DatabaseHelper.queryOldestCachedSong() ?: break
            val cachedPath = oldest.cachedPath ?: break
            // 删除缓存文件
            val file = File(cachedPath)
            if (file.exists()) {
                val fileSize = file.length()
                if (file.delete()) needEvict -= fileSize else needEvict -= fileSize
            }
            // 清除数据库中的缓存记录
            oldest.id?.let { DatabaseHelper.clearSongCache(it) }
        }
    }

    /**
     * 缓存歌曲到本地，返回缓存后的本地文件路径，失败返回 null。
     * 同一 remoteId 的并发请求复用同一个在途任务，不会重复下载。
     */
    suspend fun cacheSong(song: Song): String? {
        val remoteId = song.remoteId ?: return null

        var deferred: CompletableDeferred<String?>? = null
        var owner = false
        mutex.withLock {
            val existing = inFlight[remoteId]
            if (existing != null) {
                deferred = existing
            } else {
                val created = CompletableDeferred<String?>()
                inFlight[remoteId] = created
                deferred = created
                owner = true
            }
        }
        val task = deferred!!
        if (!owner) return task.await()

        val result = try {
            doCacheSong(song)
        } finally {
            // 任务仍是自己时才移除（防止 ABA）：新任务已被注册则保留
            mutex.withLock {
                if (inFlight[remoteId] === task) inFlight.remove(remoteId)
            }
        }
        task.complete(result)
        return result
    }

    private suspend fun doCacheSong(song: Song): String? = withContext(Dispatchers.IO) {
        val remoteId = song.remoteId ?: return@withContext null
        try {
            val dir = getCacheDir()
            val cachePath = File(dir, "$remoteId.cache")

            // 检查是否已缓存
            if (cachePath.exists()) {
                song.id?.let { DatabaseHelper.updateSongCache(it, cachePath.absolutePath) }
                return@withContext cachePath.absolutePath
            }

            // 检查缓存池空间
            evictIfNeeded(song.size ?: 0L)

            // 下载歌曲
            val streamUrl = SubsonicService.getStreamUrl(remoteId)
            val bytes = download(streamUrl) ?: return@withContext null

            // 写入缓存文件
            cachePath.writeBytes(bytes)

            // 更新数据库
            song.id?.let { DatabaseHelper.updateSongCache(it, cachePath.absolutePath) }

            cachePath.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "缓存歌曲失败: ${e.message}")
            null
        }
    }

    /**
     * 后台缓存歌曲（不阻塞播放）：同时缓存音频和封面，完成后通过 [onCached] 回调。
     */
    fun startCaching(song: Song, onCached: ((Song) -> Unit)? = null) {
        scope.launch {
            // 缓存音频
            val cachedPath = cacheSong(song)
            // 缓存封面
            var artworkPath: String? = null
            if (song.coverArtId != null && song.id != null) {
                artworkPath = cacheArtwork(song)
            }
            // 回调通知：合并更新后的 Song 对象
            if (onCached != null && (cachedPath != null || artworkPath != null)) {
                onCached(song.mergeCache(cachedPath, artworkPath))
            }
        }
    }

    /** 缓存歌曲封面到本地，返回缓存后的文件路径，失败返回 null */
    suspend fun cacheArtwork(song: Song): String? = withContext(Dispatchers.IO) {
        val coverArtId = song.coverArtId ?: return@withContext null

        try {
            val dir = getCacheDir()
            val artworkDir = File(dir, ARTWORK_DIR_NAME)
            if (!artworkDir.exists()) artworkDir.mkdirs()
            val cachePath = File(artworkDir, "$coverArtId.jpg")

            // 检查是否已缓存
            if (cachePath.exists()) {
                song.id?.let { DatabaseHelper.updateArtworkCache(it, cachePath.absolutePath) }
                return@withContext cachePath.absolutePath
            }

            // 下载封面
            val bytes = SubsonicService.getCoverArt(coverArtId)
            if (bytes == null || bytes.isEmpty()) return@withContext null

            // 写入缓存文件
            cachePath.writeBytes(bytes)

            // 更新数据库（song.id 非 null 时才写库）
            song.id?.let { DatabaseHelper.updateArtworkCache(it, cachePath.absolutePath) }

            cachePath.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "缓存封面失败: ${e.message}")
            null
        }
    }

    /** 批量缓存歌曲封面（扫描后 / 启动补缓存时调用） */
    suspend fun cacheArtworkBatch(songs: List<Song>): List<Song> {
        val updated = ArrayList<Song>()
        for (song in songs) {
            if (song.coverArtId != null && song.id != null) {
                val path = cacheArtwork(song)
                if (path != null) updated.add(song.copy(cachedArtworkPath = path))
            }
        }
        return updated
    }

    /** 删除指定歌曲的缓存（音频 + 数据库记录） */
    suspend fun deleteCache(songIds: List<Long>) {
        for (songId in songIds) {
            val song = DatabaseHelper.querySongById(songId) ?: continue
            val cachedPath = song.cachedPath ?: continue
            withContext(Dispatchers.IO) {
                val file = File(cachedPath)
                if (file.exists()) file.delete()
            }
            DatabaseHelper.clearSongCache(songId)
        }
    }

    /** 删除所有缓存（目录 + 数据库记录） */
    suspend fun clearAllCache() {
        withContext(Dispatchers.IO) {
            val dir = getCacheDir()
            if (dir.exists()) dir.deleteRecursively()
            cacheDirRef = null
        }
        val cachedSongs = DatabaseHelper.queryCachedSongs()
        for (song in cachedSongs) {
            song.id?.let { DatabaseHelper.clearSongCache(it) }
        }
    }

    /** 获取所有已缓存歌曲 */
    suspend fun getCachedSongs(): List<Song> = DatabaseHelper.queryCachedSongs()

    private fun download(url: String): ByteArray? = try {
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    } catch (e: Exception) {
        Log.w(TAG, "下载失败 $url: ${e.message}")
        null
    }

    /** app_flutter：与 Flutter 端 getApplicationDocumentsDirectory() 对应 */
    private const val DOCUMENTS_DIR_NAME = "app_flutter"
    private const val CACHE_DIR_NAME = "subsonic_cache"
    private const val ARTWORK_DIR_NAME = "artwork"
}
