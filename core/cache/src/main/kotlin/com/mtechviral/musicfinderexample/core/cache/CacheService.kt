package com.mtechviral.musicfinderexample.core.cache

import android.content.Context
import android.util.Log
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.common.UrlSanitizer
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /**
     * 「缓存我喜欢」开关的进程内镜像（默认开启，与历史行为一致）。
     *
     * 设置页写入后立即更新，正在播放页 / 弹窗无需重启即可读到最新值。
     */
    private val _autoCacheLiked = MutableStateFlow(true)
    val autoCacheLiked: StateFlow<Boolean> = _autoCacheLiked.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        _autoCacheLiked.value = AppPreferences.getBoolean(AppPreferences.KEY_AUTO_CACHE_LIKED, true)
    }

    /** 是否开启「缓存我喜欢」（添加歌曲到喜欢时自动缓存到本地） */
    fun isAutoCacheLikedEnabled(): Boolean = _autoCacheLiked.value

    /** 设置「缓存我喜欢」并写入偏好 */
    fun setAutoCacheLiked(enabled: Boolean) {
        _autoCacheLiked.value = enabled
        AppPreferences.putBoolean(AppPreferences.KEY_AUTO_CACHE_LIKED, enabled)
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
     * 检查并执行淘汰（优化建议 05）。
     *
     * - 容量口径与 [getCacheSizeBytes] 一致：包含音频、封面与临时文件；
     * - 先清理下载中断残留的 `.tmp` 孤儿文件；
     * - 再按「最近使用」时间淘汰已登记的音频缓存；仍不足时淘汰最旧的封面缓存；
     * - **删除失败时保留数据库记录并停止淘汰**，不虚报已释放空间。
     *
     * @param requiredBytes 需要腾出的空间（字节）
     */
    suspend fun evictIfNeeded(requiredBytes: Long) {
        val maxSizeBytes = getCacheSizeMB().toLong() * 1024 * 1024
        cleanupTempFiles()
        val currentSize = getCacheSizeBytes()
        if (currentSize + requiredBytes <= maxSizeBytes) return

        var needEvict = (currentSize + requiredBytes) - maxSizeBytes

        // 1) 按「最近使用」时间淘汰音频缓存
        while (needEvict > 0) {
            val oldest = DatabaseHelper.queryOldestCachedSong() ?: break
            val cachedPath = oldest.cachedPath ?: break
            val file = File(cachedPath)
            val fileSize = if (file.exists()) file.length() else 0L
            if (file.exists() && !file.delete()) {
                // 删除失败：保留记录（否则 DB 会指向丢失文件），不再虚报释放
                Log.w(TAG, "缓存文件删除失败，停止淘汰: $cachedPath")
                break
            }
            oldest.id?.let { DatabaseHelper.clearSongCache(it) }
            needEvict -= fileSize
        }

        // 2) 音频仍不足时淘汰封面缓存（封面同样计入容量）
        if (needEvict > 0) {
            evictArtwork(needEvict)
        }
    }

    /** 清理下载中断残留的 `.tmp` 文件（占用容量但不属于任何缓存记录） */
    private suspend fun cleanupTempFiles() {
        withContext(Dispatchers.IO) {
            val dir = getCacheDir()
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".tmp") }
                .forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 按最旧优先淘汰封面缓存文件，返回实际释放的字节数。
     * 删除成功后同步清除引用该文件的数据库封面字段（多首歌可共享同一封面）。
     */
    private suspend fun evictArtwork(requiredBytes: Long): Long {
        var released = 0L
        withContext(Dispatchers.IO) {
            val artworkDir = File(getCacheDir(), ARTWORK_DIR_NAME)
            if (!artworkDir.isDirectory) return@withContext
            val files = artworkDir.listFiles { f -> f.isFile }
                ?.sortedBy { it.lastModified() }
                ?: return@withContext
            for (file in files) {
                if (released >= requiredBytes) break
                val size = file.length()
                if (file.delete()) {
                    released += size
                    DatabaseHelper.clearArtworkCacheByPath(file.absolutePath)
                } else {
                    Log.w(TAG, "封面缓存删除失败，停止淘汰: ${file.absolutePath}")
                    break
                }
            }
        }
        return released
    }

    /**
     * 缓存歌曲到本地，返回缓存后的本地文件路径，失败返回 null。
     * 同一 remoteId 的并发请求复用同一个在途任务，不会重复下载。
     *
     * 优化建议 06：成功、失败与取消都会完成在途任务，
     * 保证所有等待者可靠结束（返回结果或 null 可重试），不会悬挂。
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

        var result: String? = null
        var cancelled: Throwable? = null
        try {
            result = doCacheSong(song)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = e
        } catch (e: Exception) {
            Log.w(TAG, "缓存任务异常: ${e.message}")
        } finally {
            // 任务仍是自己时才移除（防止 ABA）：新任务已被注册则保留
            mutex.withLock {
                if (inFlight[remoteId] === task) inFlight.remove(remoteId)
            }
            // 无论成功/失败/取消都先完成任务，唤醒所有等待者
            task.complete(result)
        }
        // 保留协程取消语义（调用方仍会收到 CancellationException）
        cancelled?.let { throw it }
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

            // 下载歌曲：**流式写入磁盘**，不把整首歌读进内存。
            // 之前用 `ResponseBody.bytes()`，缓存几十 MB 的远程音频会直接
            // java.lang.OutOfMemoryError（真机实测 85MB 文件崩溃在 okio readByteArray）。
            val streamUrl = SubsonicService.getStreamUrl(remoteId)
            if (!downloadToFile(streamUrl, cachePath)) return@withContext null

            // 更新数据库
            song.id?.let { DatabaseHelper.updateSongCache(it, cachePath.absolutePath) }

            cachePath.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 保留协程取消语义（不要吞掉 CancellationException）
            throw e
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

    /**
     * 「喜欢」状态变为喜欢后调用：**远程歌曲自动加入缓存池**（音频 + 封面）。
     *
     * 受设置页「缓存我喜欢」开关控制（默认开启）：关闭时本方法**不做任何事**。
     *
     * - 取消喜欢不会删除已有缓存（用户没说要在取消时删）；
     * - 本地歌曲本来就在磁盘上，直接跳过；
     * - 已缓存且封面也齐的歌曲不再重复下载。
     */
    fun autoCacheLiked(songId: Long) {
        if (!isAutoCacheLikedEnabled()) {
            Log.d(TAG, "「缓存我喜欢」已关闭，跳过自动缓存 songId=$songId")
            return
        }
        scope.launch {
            val song = DatabaseHelper.querySongById(songId) ?: return@launch
            if (!song.isLiked || !song.isRemote) return@launch
            if (song.isCached && !song.cachedArtworkPath.isNullOrEmpty()) return@launch
            Log.d(TAG, "喜欢歌曲自动缓存: ${song.title}")
            startCaching(song) { updated ->
                // 把"已缓存/已缓存封面"合并回曲库快照，列表上的缓存角标立刻生效
                MusicLibrary.updateSong(updated)
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
                // 命中也刷新「最近使用」时间，供封面淘汰按 LRU 进行（优化建议 05）
                cachePath.setLastModified(System.currentTimeMillis())
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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

    /**
     * 删除所有缓存（目录 + 数据库记录）。
     *
     * 优化建议 05：同时重置音频与封面的数据库缓存字段 ——
     * 否则「清空全部缓存 → 重启」后封面字段残留，远程封面不会重新下载。
     */
    suspend fun clearAllCache() {
        withContext(Dispatchers.IO) {
            val dir = getCacheDir()
            if (dir.exists()) dir.deleteRecursively()
            cacheDirRef = null
        }
        DatabaseHelper.clearAllCacheRecords()
    }

    /** 获取所有已缓存歌曲 */
    suspend fun getCachedSongs(): List<Song> = DatabaseHelper.queryCachedSongs()

    /**
     * 流式下载到文件（常量内存占用）。
     *
     * 先写 `.tmp` 再重命名，避免下载中断留下半个"已缓存"文件被当成缓存命中。
     */
    private fun downloadToFile(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    // 日志脱敏：流地址含认证参数（u/s/t），不得进入日志（优化建议 01）
                    Log.w(TAG, "下载失败 ${response.code} ${UrlSanitizer.redact(url)}")
                    false
                } else {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output, DOWNLOAD_BUFFER_SIZE)
                        }
                    }
                    if (tmp.renameTo(target)) {
                        true
                    } else {
                        tmp.delete()
                        false
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载失败 ${UrlSanitizer.redact(url)}: ${e.message}")
            runCatching { tmp.delete() }
            false
        }
    }

    /** app_flutter：与 Flutter 端 getApplicationDocumentsDirectory() 对应 */
    private const val DOCUMENTS_DIR_NAME = "app_flutter"
    private const val CACHE_DIR_NAME = "subsonic_cache"
    private const val ARTWORK_DIR_NAME = "artwork"

    /** 下载缓冲（流式落盘，避免整首歌驻留内存） */
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
}
