package com.mtechviral.musicfinderexample.core.media

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面字节内存缓存。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_artwork.dart` 中的 `ArtworkCache`：
 * 以「缓存封面文件路径」优先、歌曲文件路径兜底作为 key；
 * 首次读取（磁盘/内嵌图片）后走内存缓存，避免列表滚动时重复解码；
 * 并发请求同一 key 时复用同一个加载任务（原实现用 `_pending.putIfAbsent` 去重）。
 *
 * 注意：与原实现一致，**加载结果为 null 也会写入缓存**，
 * 使后续 `has(key) == true` 而 `peek(key) == null`，UI 直接展示占位图而不反复重试。
 */
object ArtworkCache {

    private const val TAG = "ArtworkCache"

    private val cache = HashMap<String, ByteArray?>()
    private val pending = HashMap<String, CompletableDeferred<ByteArray?>>()
    private val mutex = Mutex()

    /** 同步取缓存（未加载过返回 null） */
    fun peek(key: String): ByteArray? = synchronized(cache) { cache[key] }

    /** 是否已经加载过该 key（含加载结果为 null 的情况） */
    fun has(key: String): Boolean = synchronized(cache) { cache.containsKey(key) }

    /** 清除指定 key 的缓存（封面缓存路径变更时使用） */
    fun invalidate(key: String) {
        synchronized(cache) {
            cache.remove(key)
            pending.remove(key)
        }
    }

    /** 清除指定路径的缓存（远程歌曲封面缓存完成后使用） */
    fun invalidateByPathPrefix(path: String) {
        invalidate(path)
    }

    /** 清空缓存（缓存管理页清空全部缓存后调用） */
    fun clear() {
        synchronized(cache) {
            cache.clear()
            pending.clear()
        }
    }

    /**
     * 异步加载封面字节（自动去重并发请求）。
     *
     * @param path 歌曲文件路径（可用于读取内嵌封面）
     * @param cachedArtworkPath 远程歌曲的本地缓存封面路径（优先使用）
     */
    suspend fun load(path: String, cachedArtworkPath: String? = null): ByteArray? {
        val cacheKey = cachedArtworkPath ?: path

        synchronized(cache) {
            if (cache.containsKey(cacheKey)) return cache[cacheKey]
        }

        var deferred: CompletableDeferred<ByteArray?>? = null
        var owner = false
        mutex.withLock {
            synchronized(cache) {
                if (cache.containsKey(cacheKey)) {
                    deferred = CompletableDeferred(cache[cacheKey])
                    return@withLock
                }
            }
            val existing = pending[cacheKey]
            if (existing != null) {
                deferred = existing
            } else {
                val created = CompletableDeferred<ByteArray?>()
                pending[cacheKey] = created
                deferred = created
                owner = true
            }
        }
        val result = deferred!!
        if (!owner) return result.await()

        // 优化建议 06：成功/失败/取消都要完成在途任务并移除登记，
        // 保证并发等待者不会悬挂（收到 null 后可重试）。
        var bytes: ByteArray? = null
        var cancelled: Throwable? = null
        try {
            bytes = withContext(Dispatchers.IO) { readBytes(path, cachedArtworkPath) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = e
        } catch (e: Exception) {
            Log.w(TAG, "加载封面异常 $path: ${e.message}")
        } finally {
            if (cancelled == null) {
                // 只有真正读完才写缓存（含结果为 null 的情况）；
                // 取消不写缓存，避免把「没读过」误记成「读过且没有封面」
                synchronized(cache) { cache[cacheKey] = bytes }
            }
            mutex.withLock { pending.remove(cacheKey) }
            result.complete(bytes)
        }
        cancelled?.let { throw it }
        return bytes
    }

    /** 真正读取字节：优先缓存封面文件，其次音频文件内嵌封面 */
    private fun readBytes(path: String, cachedArtworkPath: String?): ByteArray? = try {
        var bytes: ByteArray? = null
        if (!cachedArtworkPath.isNullOrEmpty()) {
            val file = File(cachedArtworkPath)
            if (file.exists()) {
                bytes = file.readBytes()
            }
        }
        if (bytes == null && path.isNotEmpty()) {
            bytes = MetadataParser.readEmbeddedArtwork(path)
        }
        bytes
    } catch (e: Exception) {
        Log.w(TAG, "读取封面失败 $path: ${e.message}")
        null
    }
}
