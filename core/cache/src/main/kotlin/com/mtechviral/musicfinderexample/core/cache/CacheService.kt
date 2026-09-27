package com.mtechviral.musicfinderexample.core.cache

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.common.UrlSanitizer
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.media.MetadataParser
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.network.RemoteRequest
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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

    // ===================== 耗电优化（doc/耗电分析报告.md §2） =====================

    /**
     * 音频缓存键的写入者申领表（key -> 写入者类型，[CLAIM_TAP] / [CLAIM_DOWNLOAD]）。
     *
     * 播放流旁路（[CacheTap]）与后台下载对**同一首歌**互斥：
     * 同一内容同时只有一个写入者，首次播放不再出现"边播边另起一套整曲下载"。
     */
    private val claimedKeys = HashMap<String, String>()

    /** 在途下载的 Call（按缓存键）：取消任务时联动 `Call.cancel()`，立刻中断阻塞读 */
    private val activeCalls = HashMap<String, Call>()

    /** 推测性缓存任务（补全已播歌曲前缀 / 预取下一首）：全局**单任务**，随切歌取消 */
    private val speculativeLock = Any()
    private var speculativeJob: Job? = null
    private var speculativeKey: String? = null
    private var speculativePriority = 0

    /** 封面在途任务（`revision|sourceId|coverArtId` -> Deferred）：并发触发复用同一下载 */
    private val artworkInFlight = HashMap<String, CompletableDeferred<String?>>()

    /** 封面失败退避（任务键 -> (失败次数, 上次失败时间)）：失败歌曲不再每次启动全量重试 */
    private val artworkFailures = HashMap<String, Pair<Int, Long>>()

    /**
     * 歌曲缓存完成通知（旁路落盘等路径；由 PlayerController 注册以合并 UI 状态）。
     * `startCaching` 的显式回调不受影响。
     */
    @Volatile
    var onSongCached: ((Song) -> Unit)? = null

    /**
     * 播放流旁路结束但**未落成完整缓存**（保留了可续传前缀）时的通知
     * （由 PlayerController 注册，转入补全任务）。
     *
     * 在写入者申领**释放之后**才触发 —— 避免"切歌时补全任务先于流关闭启动、
     * 被互斥挡掉后永远丢失"的竞态（表现为播放过的歌不缓存）。
     */
    @Volatile
    var onTapClosedIncomplete: ((Song) -> Unit)? = null

    /** Called before switching the only active remote source. */
    fun cancelPending() {
        requestSpeculativeCache(null, "切换远程源")
        httpClient.dispatcher.cancelAll()
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        synchronized(activeCalls) { activeCalls.values.forEach { runCatching { it.cancel() } }; activeCalls.clear() }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
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
        loadArtworkFailures()
    }

    /** 是否开启「缓存我喜欢」（添加歌曲到喜欢时自动缓存到本地） */
    fun isAutoCacheLikedEnabled(): Boolean = _autoCacheLiked.value

    /** 设置「缓存我喜欢」并写入偏好 */
    fun setAutoCacheLiked(enabled: Boolean) {
        _autoCacheLiked.value = enabled
        AppPreferences.putBoolean(AppPreferences.KEY_AUTO_CACHE_LIKED, enabled)
    }

    /** 获取缓存目录（不存在则创建） */
    suspend fun getCacheDir(): File = withContext(Dispatchers.IO) { resolveCacheDir() }

    /** 阻塞版缓存目录解析（数据源旁路写入等无法挂起的调用点使用） */
    internal fun resolveCacheDir(): File {
        cacheDirRef?.let { return it }
        // 与 Flutter 端 getApplicationDocumentsDirectory() 保持一致：<dataDir>/app_flutter
        val dir = File(File(appContext.dataDir, DOCUMENTS_DIR_NAME), CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        cacheDirRef = dir
        return dir
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

    /** 清理下载中断残留的 `.tmp` 文件与陈旧 `.partial` 前缀（占用容量但不属于任何缓存记录） */
    private suspend fun cleanupTempFiles() {
        withContext(Dispatchers.IO) {
            val dir = getCacheDir()
            val now = System.currentTimeMillis()
            val staleBefore = now - TimeUnit.HOURS.toMillis(6)
            val stalePrefixBefore = now - TimeUnit.HOURS.toMillis(24)
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".tmp") && it.lastModified() < staleBefore }
                .forEach { runCatching { it.delete() } }
            // 前缀文件是可续传的中间状态（旁路/断点下载），只清理超过 24 小时无人续写的
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(PARTIAL_FILE_SUFFIX) && it.lastModified() < stalePrefixBefore }
                .forEach {
                    val key = it.name.removeSuffix(PARTIAL_FILE_SUFFIX)
                    val busy = synchronized(claimedKeys) { key in claimedKeys }
                    if (!busy) runCatching { it.delete() }
                }
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
     *
     * 耗电优化（报告 §2）：与**播放流旁路**按缓存键互斥 —— 同一首歌正在
     * 边播边写缓存时本方法直接返回 null，不再并行第二套整曲下载。
     */
    suspend fun cacheSong(song: Song): String? {
        val remoteId = song.remoteId ?: return null
        val sourceId = song.sourceId ?: return null
        if (RemoteSessionManager.activeSourceId != sourceId) return null
        val revision = RemoteSessionManager.sessionRevision
        val taskKey = "$revision|$sourceId|$remoteId"
        val cacheKey = cacheKeyOf(song) ?: return null
        // 任务取消要能中断下载循环（不再只依赖下载完成后的检查）
        val taskJob = currentCoroutineContext()[Job]

        var deferred: CompletableDeferred<String?>? = null
        var owner = false
        mutex.withLock {
            val existing = inFlight[taskKey]
            if (existing != null) {
                deferred = existing
            } else {
                val created = CompletableDeferred<String?>()
                inFlight[taskKey] = created
                deferred = created
                owner = true
            }
        }
        val task = deferred!!
        if (!owner) return task.await()

        // 写入者互斥：若正被**播放流旁路**占用（切歌竞态：流通常在几秒内关闭），
        // 短暂等待释放后接管；被其它下载占用则直接让路（在途去重已保证不重复）。
        var claimed = false
        var attempts = 0
        while (attempts < CLAIM_WAIT_ATTEMPTS) {
            attempts++
            if (claimKey(cacheKey, CLAIM_DOWNLOAD)) {
                claimed = true
                break
            }
            if (claimHolder(cacheKey) != CLAIM_TAP) break
            delay(CLAIM_WAIT_INTERVAL_MS)
        }
        if (!claimed) {
            // 播放流旁路或另一下载正在写同一首歌：不产生第二套传输
            mutex.withLock { if (inFlight[taskKey] === task) inFlight.remove(taskKey) }
            task.complete(null)
            Log.d(TAG, "跳过缓存（已有写入者）: ${song.title}")
            return null
        }

        var result: String? = null
        var cancelled: Throwable? = null
        try {
            result = doCacheSong(song, revision, cacheKey, taskJob)
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = e
        } catch (e: Exception) {
            Log.w(TAG, "缓存任务异常: ${e.message}")
        } finally {
            releaseKey(cacheKey)
            unregisterCall(cacheKey)
            // 任务仍是自己时才移除（防止 ABA）：新任务已被注册则保留
            mutex.withLock {
                if (inFlight[taskKey] === task) inFlight.remove(taskKey)
            }
            // 无论成功/失败/取消都先完成任务，唤醒所有等待者
            task.complete(result)
        }
        // 保留协程取消语义（调用方仍会收到 CancellationException）
        cancelled?.let { throw it }
        return result
    }

    private suspend fun doCacheSong(
        song: Song,
        revision: Long,
        cacheKey: String,
        taskJob: Job?,
    ): String? = withContext(Dispatchers.IO) {
        val remoteId = song.remoteId ?: return@withContext null
        val sourceId = song.sourceId ?: return@withContext null
        if (RemoteSessionManager.activeSourceId != sourceId ||
            RemoteSessionManager.sessionRevision != revision) return@withContext null
        try {
            val dir = getCacheDir()
            val cachePath = File(dir, "$cacheKey$CACHE_FILE_SUFFIX")

            // 检查是否已缓存
            if (cachePath.exists()) {
                if (RemoteSessionManager.sessionRevision != revision) return@withContext null
                song.id?.let { DatabaseHelper.updateSongCache(it, cachePath.absolutePath) }
                return@withContext cachePath.absolutePath
            }

            // 检查缓存池空间
            evictIfNeeded(song.size ?: 0L)

            // 下载歌曲：流式落盘、可从 `.partial` 前缀续传、每块检查取消信号
            val request = RemoteSessionManager.stream(song)
            if (!downloadToCacheFile(request, cacheKey, {
                    taskJob?.isActive != false &&
                        RemoteSessionManager.activeSourceId == sourceId &&
                        RemoteSessionManager.sessionRevision == revision
                }) { registerCall(cacheKey, it) }
            ) return@withContext null
            if (RemoteSessionManager.activeSourceId != sourceId ||
                RemoteSessionManager.sessionRevision != revision) {
                return@withContext null
            }

            // 更新数据库 + WebDAV 标签补全
            finalizeCachedSong(song, revision, cachePath)
            cachePath.absolutePath
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 保留协程取消语义（不要吞掉 CancellationException）
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "缓存歌曲失败: ${e.message}")
            null
        }
    }

    /** 下载/旁路完成后的统一收尾：数据库登记 + WebDAV 标签补全 */
    private suspend fun finalizeCachedSong(song: Song, revision: Long, cachePath: File) {
        if (RemoteSessionManager.activeSourceId != song.sourceId ||
            RemoteSessionManager.sessionRevision != revision) return
        song.id?.let { DatabaseHelper.updateSongCache(it, cachePath.absolutePath) }
        if (song.sourceType == Song.SOURCE_TYPE_WEBDAV && song.id != null) {
            try {
                enrichCachedWebDavSong(song, cachePath, revision)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "WebDAV 标签补全失败: ${e.message}")
            }
        }
    }

    private suspend fun enrichCachedWebDavSong(song: Song, cachePath: File, revision: Long) {
        val sourceId = song.sourceId ?: return
        val remoteId = song.remoteId ?: return
        val songId = song.id ?: return
        val parsed = MetadataParser.parse(cachePath.absolutePath) ?: return
        fun stillCurrent() = RemoteSessionManager.activeSourceId == sourceId &&
            RemoteSessionManager.sessionRevision == revision
        if (!stillCurrent()) return
        val updated = DatabaseHelper.enrichWebDavSong(songId, sourceId, remoteId,
            parsed, cachePath.nameWithoutExtension)
        if (parsed.hasArtwork && stillCurrent()) {
            val bytes = MetadataParser.readEmbeddedArtwork(cachePath.absolutePath)
            if (bytes != null && bytes.size <= 4_000_000 && stillCurrent()) {
                val artworkDir = File(getCacheDir(), ARTWORK_DIR_NAME)
                artworkDir.mkdirs()
                val target = File(artworkDir,
                    "${RemoteLocator.sha256Hex("$sourceId|$remoteId|embedded")}.img")
                target.writeBytes(bytes)
                if (stillCurrent()) DatabaseHelper.updateArtworkCache(songId, target.absolutePath)
            }
        }
        if (updated && stillCurrent()) MusicLibrary.reload()
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
                val latest = song.id?.let { DatabaseHelper.querySongById(it) } ?: song
                onCached(latest.mergeCache(cachedPath, artworkPath))
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

    // ===================== 播放流旁路缓存（报告 §2：合并传输） =====================

    /** 歌曲的稳定缓存键（源 + 远程 id 的哈希；与历史缓存文件命名一致） */
    fun cacheKeyOf(song: Song): String? {
        val remoteId = song.remoteId ?: return null
        val sourceId = song.sourceId ?: return null
        return RemoteLocator.sha256Hex("$sourceId|$remoteId")
    }

    /**
     * 播放流开始旁路写缓存。
     *
     * 与后台下载按缓存键互斥（[claimKey]）：已有写入者或无法顺序续写时返回 null，
     * 调用方放弃旁路直接透传（绝不产生同内容双传输/文件竞争）。
     */
    fun openTap(song: Song, position: Long): CacheTap? {
        val key = cacheKeyOf(song) ?: return null
        if (!claimKey(key, CLAIM_TAP)) return null
        val dir = resolveCacheDir()
        val tap = CacheTap.open(
            File(dir, "$key$PARTIAL_FILE_SUFFIX"),
            File(dir, "$key$CACHE_FILE_SUFFIX"),
            position,
        )
        if (tap == null) {
            releaseKey(key)
            return null
        }
        return tap
    }

    /**
     * 播放流旁路结束。
     *
     * 顺序读完整个资源（[eof]）时前缀直接落为正式缓存 —— 首次播放即完成整曲缓存，
     * **零额外网络传输**；中途切歌/前跳则保留前缀并经 [onTapClosedIncomplete]
     * 转入补全任务按 Range 续传（在写入者释放后触发，无互斥竞态）。
     */
    fun closeTap(song: Song, revision: Long, tap: CacheTap, eof: Boolean) {
        val key = cacheKeyOf(song)
        val completed = tap.finish(eof)
        if (key != null) releaseKey(key)
        if (!completed) {
            if (hasPartialCache(song)) onTapClosedIncomplete?.invoke(song)
            return
        }
        val target = tap.target
        scope.launch {
            try {
                finalizeCachedSong(song, revision, target)
                val latest = song.id?.let { DatabaseHelper.querySongById(it) } ?: song
                onSongCached?.invoke(latest.mergeCache(target.absolutePath, null))
            } catch (e: Exception) {
                Log.w(TAG, "旁路缓存收尾失败: ${e.message}")
            }
        }
    }

    /** 是否存在未完成的旁路前缀（供"补全刚播过歌曲"的调度决策） */
    fun hasPartialCache(song: Song): Boolean {
        val key = cacheKeyOf(song) ?: return false
        val dir = runCatching { resolveCacheDir() }.getOrNull() ?: return false
        return File(dir, "$key$PARTIAL_FILE_SUFFIX").let { it.exists() && it.length() > 0 } &&
            !File(dir, "$key$CACHE_FILE_SUFFIX").exists()
    }

    // ===================== 推测性缓存调度（报告 §2.3） =====================

    /** 推测性任务优先级：预取（下一首 / 补封面等纯推测） */
    const val PRIORITY_PREFETCH = 1

    /** 推测性任务优先级：补全已播放内容（只差尾部，价值更高） */
    const val PRIORITY_COMPLETION = 2

    /**
     * 请求一次推测性缓存（补全刚播过歌曲的前缀 / 预取下一首）。
     *
     * 全局只保留**一个**任务：目标变化时旧任务立即取消
     * （协程取消 + 联动 `Call.cancel()`），连续点播不会堆积后台下载。
     *
     * 优先级调度：高优先级（补全已播）可打断低优先级（预取）；
     * 正在执行高优先级任务时，低优先级请求直接让路（预取丢了就丢了）。
     * [song] 为 null 表示无条件取消当前任务。
     */
    fun requestSpeculativeCache(
        song: Song?,
        reason: String,
        priority: Int = PRIORITY_PREFETCH,
    ) {
        val key = song?.let { cacheKeyOf(it) }
        synchronized(speculativeLock) {
            if (song == null || key == null) {
                speculativeJob?.cancel()
                speculativeKey?.let { cancelCall(it) }
                speculativeJob = null
                speculativeKey = null
                speculativePriority = 0
                return
            }
            if (key == speculativeKey && speculativeJob?.isActive == true) return
            // 旧任务优先级更高（如正在补全已播歌曲）：预取请求让路
            if (speculativeJob?.isActive == true && speculativePriority > priority) return
            speculativeJob?.cancel()
            speculativeKey?.let { cancelCall(it) }
            speculativeKey = key
            speculativePriority = priority
            Log.d(TAG, "推测性缓存[$reason]: ${song.title}")
            speculativeJob = scope.launch { cacheSong(song) }
        }
    }

    /**
     * 推测性下载的电量/网络约束（报告 §2.3.3）。
     *
     * - [completionDownloadAllowed]：补全**已经播放过**的内容 —— 低电量时暂停；
     * - [prefetchDownloadAllowed]：纯预取（下一首 / 批量补封面）—— 另需"非计费网络或充电中"。
     */
    fun completionDownloadAllowed(): Boolean {
        val battery = batteryStatus() ?: return true
        return battery.charging || !battery.low
    }

    fun prefetchDownloadAllowed(): Boolean {
        if (!completionDownloadAllowed()) return false
        val ctx = appContext ?: return false
        val metered = runCatching {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        }.getOrDefault(false)
        return !metered || batteryStatus()?.charging == true
    }

    private data class BatteryStatus(val charging: Boolean, val low: Boolean)

    private fun batteryStatus(): BatteryStatus? = runCatching {
        val ctx = appContext ?: return null
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (scale > 0 && level >= 0) level * 100 / scale else -1
        BatteryStatus(charging = charging, low = pct in 0..15)
    }.getOrNull()

    // ===================== 写入者互斥 / Call 登记 =====================

    private fun claimKey(key: String, owner: String): Boolean =
        synchronized(claimedKeys) { if (claimedKeys.containsKey(key)) false else { claimedKeys[key] = owner; true } }

    private fun claimHolder(key: String): String? = synchronized(claimedKeys) { claimedKeys[key] }

    private fun releaseKey(key: String) {
        synchronized(claimedKeys) { claimedKeys.remove(key) }
    }

    private fun registerCall(key: String, call: Call) {
        synchronized(activeCalls) { activeCalls[key] = call }
    }

    private fun unregisterCall(key: String) {
        synchronized(activeCalls) { activeCalls.remove(key) }
    }

    /** 取消某键的在途下载：联动 `Call.cancel()`，立刻中断阻塞中的读 */
    private fun cancelCall(key: String) {
        synchronized(activeCalls) { activeCalls.remove(key) }?.let { runCatching { it.cancel() } }
    }

    /** 后台缓存封面（不触碰音频），完成后回调合并更新的 Song */
    fun cacheArtworkAsync(song: Song, onCached: ((Song) -> Unit)? = null) {
        scope.launch {
            val artworkPath =
                if (song.coverArtId != null && song.id != null) cacheArtwork(song) else null
            if (onCached != null && artworkPath != null) {
                val latest = song.id?.let { DatabaseHelper.querySongById(it) } ?: song
                onCached(latest.mergeCache(null, artworkPath))
            }
        }
    }

    /** 缓存歌曲封面到本地，返回缓存后的文件路径，失败返回 null */
    suspend fun cacheArtwork(song: Song): String? {
        val coverArtId = song.coverArtId ?: return null
        val sourceId = song.sourceId ?: return null
        if (RemoteSessionManager.activeSourceId != sourceId) return null
        val revision = RemoteSessionManager.sessionRevision
        val taskKey = "$revision|$sourceId|$coverArtId"

        // 失败退避：退避窗口内不再重试（失败歌曲不再每次启动全量重试，报告 §6）
        if (artworkBackoffActive(taskKey)) return null

        // 在途去重：启动补缓存与播放页补封面并发时复用同一下载
        var deferred: CompletableDeferred<String?>? = null
        var owner = false
        mutex.withLock {
            val existing = artworkInFlight[taskKey]
            if (existing != null) {
                deferred = existing
            } else {
                val created = CompletableDeferred<String?>()
                artworkInFlight[taskKey] = created
                deferred = created
                owner = true
            }
        }
        val task = deferred!!
        if (!owner) return task.await()

        var result: String? = null
        try {
            result = doCacheArtwork(song, revision, taskKey)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "缓存封面任务异常: ${e.message}")
        } finally {
            mutex.withLock { if (artworkInFlight[taskKey] === task) artworkInFlight.remove(taskKey) }
            task.complete(result)
        }
        return result
    }

    private suspend fun doCacheArtwork(song: Song, revision: Long, taskKey: String): String? =
        withContext(Dispatchers.IO) {
            val coverArtId = song.coverArtId ?: return@withContext null
            val sourceId = song.sourceId ?: return@withContext null
            try {
                val dir = getCacheDir()
                val artworkDir = File(dir, ARTWORK_DIR_NAME)
                if (!artworkDir.exists()) artworkDir.mkdirs()
                val cachePath =
                    File(artworkDir, "${RemoteLocator.sha256Hex("$sourceId|$coverArtId")}.jpg")

                // 检查是否已缓存
                if (cachePath.exists()) {
                    if (RemoteSessionManager.sessionRevision != revision) return@withContext null
                    // 命中也刷新「最近使用」时间，供封面淘汰按 LRU 进行（优化建议 05）
                    cachePath.setLastModified(System.currentTimeMillis())
                    song.id?.let { DatabaseHelper.updateArtworkCache(it, cachePath.absolutePath) }
                    clearArtworkFailure(taskKey)
                    return@withContext cachePath.absolutePath
                }

                // 下载封面
                val bytes = RemoteSessionManager.artwork(song)
                if (bytes == null || bytes.isEmpty()) {
                    recordArtworkFailure(taskKey)
                    return@withContext null
                }
                if (RemoteSessionManager.activeSourceId != sourceId ||
                    RemoteSessionManager.sessionRevision != revision) return@withContext null

                // 写入缓存文件
                cachePath.writeBytes(bytes)

                // 更新数据库（song.id 非 null 时才写库）
                song.id?.let { DatabaseHelper.updateArtworkCache(it, cachePath.absolutePath) }
                clearArtworkFailure(taskKey)
                cachePath.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "缓存封面失败: ${e.message}")
                recordArtworkFailure(taskKey)
                null
            }
        }

    /** 封面批量补缓存（启动补缓存用；耗电优化，报告 §6） */
    suspend fun cacheArtworkBatch(
        songs: List<Song>,
        maxItems: Int = ARTWORK_BATCH_BUDGET,
    ): List<Song> {
        // 约束：低电量或（计费网络且未充电）不做推测性批量下载
        if (!prefetchDownloadAllowed()) {
            Log.d(TAG, "跳过封面批量补缓存（低电量或计费网络未充电）")
            return emptyList()
        }
        val updated = ArrayList<Song>()
        val seen = HashSet<String>()
        for (song in songs) {
            if (updated.size >= maxItems) break
            val coverArtId = song.coverArtId ?: continue
            if (song.id == null) continue
            val sourceId = song.sourceId ?: continue
            // 按 (数据源, 封面 ID) 去重：同一封面只下载一次
            if (!seen.add("$sourceId|$coverArtId")) continue
            val path = cacheArtwork(song)
            if (path != null) updated.add(song.copy(cachedArtworkPath = path))
        }
        return updated
    }

    // ===================== 封面失败退避（报告 §6） =====================

    private fun artworkBackoffActive(taskKey: String): Boolean {
        synchronized(artworkFailures) {
            val (count, at) = artworkFailures[taskKey] ?: return false
            val shift = count.coerceIn(0, 5)
            val backoff = (ARTWORK_BACKOFF_BASE_MS shl shift).coerceAtMost(ARTWORK_BACKOFF_MAX_MS)
            return System.currentTimeMillis() - at < backoff
        }
    }

    private fun recordArtworkFailure(taskKey: String) {
        synchronized(artworkFailures) {
            val count = artworkFailures[taskKey]?.first ?: 0
            artworkFailures[taskKey] = (count + 1) to System.currentTimeMillis()
            // 防止无界增长：只保留最近 500 条
            while (artworkFailures.size > 500) {
                val oldest = artworkFailures.minByOrNull { it.value.second }?.key ?: break
                artworkFailures.remove(oldest)
            }
            persistArtworkFailures()
        }
    }

    private fun clearArtworkFailure(taskKey: String) {
        synchronized(artworkFailures) {
            if (artworkFailures.remove(taskKey) != null) persistArtworkFailures()
        }
    }

    private fun persistArtworkFailures() {
        val serialized = synchronized(artworkFailures) {
            artworkFailures.entries.joinToString("\n") { (key, value) -> "$key\t${value.first}\t${value.second}" }
        }
        AppPreferences.putString(KEY_ARTWORK_FAILURES, serialized)
    }

    private fun loadArtworkFailures() {
        val raw = AppPreferences.getString(KEY_ARTWORK_FAILURES, "") ?: return
        synchronized(artworkFailures) {
            artworkFailures.clear()
            for (line in raw.split('\n')) {
                val parts = line.split('\t')
                if (parts.size != 3) continue
                val count = parts[1].toIntOrNull() ?: continue
                val at = parts[2].toLongOrNull() ?: continue
                artworkFailures[parts[0]] = count to at
            }
        }
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
     * 流式下载到缓存文件（常量内存、可续传、可随时取消）。
     *
     * - 复用 `<key>.partial` 前缀（播放流旁路 / 上次中断留下）：存在前缀时以
     *   `Range: bytes=N-` 续传（206 追加；200 表示服务器不支持续传，重头下载）；
     * - 每个下载块检查 [isCurrent]，失效立即停止并 `Call.cancel()` ——
     *   切歌后旧任务不再把剩余整首下完；
     * - 全部字节落盘后改名为 `<key>.cache`，避免半文件被当成缓存命中。
     *
     * @param onCallStarted 下载 Call 建立时回调（登记以便取消任务时联动 `Call.cancel()`）。
     * @return true 表示目标缓存文件已完整落盘。
     */
    private fun downloadToCacheFile(
        request: RemoteRequest,
        key: String,
        isCurrent: () -> Boolean,
        onCallStarted: ((Call) -> Unit)? = null,
    ): Boolean {
        val dir = resolveCacheDir()
        val target = File(dir, "$key$CACHE_FILE_SUFFIX")
        if (target.exists()) return true
        val partial = File(dir, "$key$PARTIAL_FILE_SUFFIX")
        val offset = if (partial.exists()) partial.length() else 0L
        return try {
            val httpRequest = Request.Builder().url(request.url).get().apply {
                request.headers.forEach { (name, value) -> header(name, value) }
                if (offset > 0) header("Range", "bytes=$offset-")
            }.build()
            val call = httpClient.newCall(httpRequest)
            onCallStarted?.invoke(call)
            call.execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    // 日志脱敏：流地址含认证参数（u/s/t），不得进入日志（优化建议 01）
                    Log.w(TAG, "下载失败 ${response.code} ${UrlSanitizer.redact(request.url)}")
                    return@use false
                }
                // 服务器不支持续传（请求了 Range 却回 200）：丢弃前缀重头下载
                val append = offset > 0 && response.code == 206
                if (offset > 0 && !append) runCatching { partial.delete() }

                var aborted = false
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    val input = body.byteStream()
                    while (true) {
                        // 取消信号联动：立即停止，不再多下一个字节
                        if (!isCurrent()) {
                            aborted = true
                            call.cancel()
                            break
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
                if (aborted || !isCurrent()) {
                    // 取消：保留前缀供下次续传（陈旧前缀由 cleanupTempFiles 清理）
                    false
                } else if (partial.renameTo(target)) {
                    true
                } else {
                    Log.w(TAG, "缓存文件改名失败: ${target.name}")
                    runCatching { partial.delete() }
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "下载失败 ${UrlSanitizer.redact(request.url)}: ${e.message}")
            false
        }
    }

    /** app_flutter：与 Flutter 端 getApplicationDocumentsDirectory() 对应 */
    private const val DOCUMENTS_DIR_NAME = "app_flutter"
    private const val CACHE_DIR_NAME = "subsonic_cache"
    private const val ARTWORK_DIR_NAME = "artwork"

    /** 正式音频缓存 / 可续传前缀文件后缀（同键同目录，落盘即改名） */
    private const val CACHE_FILE_SUFFIX = ".cache"
    private const val PARTIAL_FILE_SUFFIX = ".partial"

    /** 写入者类型：播放流旁路 / 后台下载 */
    private const val CLAIM_TAP = "tap"
    private const val CLAIM_DOWNLOAD = "download"

    /** 下载等待播放流旁路释放写入者的轮询参数（切歌竞态兜底） */
    private const val CLAIM_WAIT_ATTEMPTS = 40
    private const val CLAIM_WAIT_INTERVAL_MS = 500L

    /** 下载缓冲（流式落盘，避免整首歌驻留内存） */
    private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024

    /** 启动补封面的单次预算（大曲库跨启动渐进补全，报告 §6） */
    private const val ARTWORK_BATCH_BUDGET = 200

    /** 封面失败退避：30 分钟起，按失败次数翻倍，上限 24 小时 */
    private const val ARTWORK_BACKOFF_BASE_MS = 30L * 60 * 1000
    private const val ARTWORK_BACKOFF_MAX_MS = 24L * 60 * 60 * 1000

    /** 封面失败记录的持久化键 */
    private const val KEY_ARTWORK_FAILURES = "artwork_failures"
}
