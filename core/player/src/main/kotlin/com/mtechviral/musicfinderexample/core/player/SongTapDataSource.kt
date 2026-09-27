package com.mtechviral.musicfinderexample.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.cache.CacheTap
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 旁路歌曲登记表：把解析出的 [Song]（含会话修订号）挂到稳定缓存键上，
 * 供 [SongTapDataSource] 在真正打开 HTTP 流时取用。
 *
 * 数据源链路里 [RemotePlaybackDataSource] 的解析回调先于上游 `open` 执行，
 * 因此键在打开前一定已登记；流关闭后即移除（下次打开会重新登记）。
 */
internal object SongTapRegistry {
    data class Ref(val song: Song, val revision: Long)

    private val refs = java.util.concurrent.ConcurrentHashMap<String, Ref>()

    fun bind(key: String, ref: Ref) {
        refs[key] = ref
    }

    fun resolve(key: String): Ref? = refs[key]

    fun unbind(key: String) {
        refs.remove(key)
    }
}

/**
 * 播放流旁路缓存数据源（耗电优化，doc/耗电分析报告.md §2）。
 *
 * 播放器读到的每个字节同时顺序写入缓存前缀文件（[CacheTap]），
 * 从资源头顺序读到尾后直接落为正式缓存 —— **首次播放与整曲缓存共用同一次
 * 网络传输**，消除"边播边另起一套整曲下载"的重复传输。
 *
 * 仅对解析阶段挂了 [DataSpec.key] 的远程音频生效；本地文件 / 已缓存歌曲直接透传。
 * 前跳（位置越过前缀）会放弃旁路、保留前缀文件，由后台按 Range 补全。
 */
internal class SongTapDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = SongTapDataSource(upstreamFactory.createDataSource())
    }

    private var tap: CacheTap? = null
    private var tapRef: SongTapRegistry.Ref? = null
    private var tapKey: String? = null
    private var boundedOpen = false
    private var reachedEof = false

    /** 隧道传输租约（实际经 EasyTier 本地转发时持有；报告 §4.3 的连接租约） */
    private var tunnelLease: AutoCloseable? = null

    override fun open(dataSpec: DataSpec): Long {
        val key = dataSpec.key
        val ref = key?.let { SongTapRegistry.resolve(it) }
        val bytes = upstream.open(dataSpec)
        reachedEof = false
        boundedOpen = dataSpec.length != C.LENGTH_UNSET.toLong()
        // 实际传输走 EasyTier 转发才持有租约：直连/本地文件不保活组网引擎
        val resolvedUrl = upstream.uri?.toString()
        if (resolvedUrl != null && EasyTierEngine.isTunnelUrl(resolvedUrl)) {
            tunnelLease = EasyTierEngine.acquireTunnelLease()
        }
        if (ref != null && key != null && !boundedOpen) {
            // 有界读（长度截断）的 EOF 不等于资源结尾，不旁路以免误判完整
            val opened = CacheService.openTap(ref.song, dataSpec.position)
            if (opened != null) {
                tap = opened
                tapRef = ref
                tapKey = key
            }
        }
        return bytes
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = upstream.read(buffer, offset, length)
        if (count > 0) {
            val current = tap
            if (current != null && !current.append(buffer, offset, count)) {
                // 无法继续顺序旁路：立即放弃并保留前缀
                finishTap()
            }
        } else if (count == C.RESULT_END_OF_INPUT) {
            reachedEof = true
        }
        return count
    }

    override fun close() {
        try {
            upstream.close()
        } finally {
            finishTap()
            tunnelLease?.close()
            tunnelLease = null
        }
    }

    private fun finishTap() {
        val current = tap ?: return
        tap = null
        val ref = tapRef ?: return
        tapRef = null
        val key = tapKey
        tapKey = null
        if (key != null) SongTapRegistry.unbind(key)
        CacheService.closeTap(ref.song, ref.revision, current, reachedEof && !boundedOpen)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)
}
