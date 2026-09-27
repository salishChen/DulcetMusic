package com.mtechviral.musicfinderexample.core.cache

import java.io.File
import java.io.RandomAccessFile

/**
 * 播放流通写缓存句柄（耗电优化，doc/耗电分析报告.md §2）。
 *
 * 播放器读到的每个字节同时顺序写入 `<key>.partial` 前缀文件；
 * 从资源头顺序读到尾（EOF）后由 [finish] 直接改名为 `<key>.cache` 正式缓存 ——
 * **首次播放与整曲缓存共用同一次网络传输**，不再"边播放边另起一套整曲下载"。
 *
 * 约定：
 * - 一个缓存键同时只有一个写入者（由 [CacheService] 的键申领互斥保证）；
 * - [openOffset] 必须与已有前缀连续（相等续写 / 变小则截断重写），
 *   越过前缀（前跳 seek）时打开失败，保留前缀文件供后台按 Range 补全；
 * - [finish] 未到 EOF 时保留前缀，后台补全任务可从 `partial.length()` 续传。
 */
class CacheTap private constructor(
    private val partial: File,
    /** 落成后的正式缓存文件（`<key>.cache`） */
    val target: File,
    initialOffset: Long,
) {
    private var raf: RandomAccessFile? = RandomAccessFile(partial, "rw").apply {
        setLength(initialOffset)
        seek(initialOffset)
    }
    private var writeOffset = initialOffset

    /** 当前连续写到的文件偏移 */
    val offset: Long get() = writeOffset

    /** 顺序追加一段读到的字节（偏移必须连续，否则返回 false 表示放弃旁路） */
    @Synchronized
    fun append(buffer: ByteArray, bufferOffset: Int, length: Int): Boolean {
        val file = raf ?: return false
        return try {
            file.seek(writeOffset)
            file.write(buffer, bufferOffset, length)
            writeOffset += length
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 结束旁路。
     *
     * @param eof 本次打开是否顺序读到了资源尾（且打开长度无界）。
     * @return true 表示已落为正式缓存文件（[target]）；false 表示只保留了前缀。
     */
    @Synchronized
    fun finish(eof: Boolean): Boolean {
        val file = raf ?: return false
        raf = null
        try {
            file.close()
        } catch (_: Exception) {
            // ignore
        }
        if (!eof || writeOffset <= 0L) return false
        return if (partial.renameTo(target)) {
            true
        } else {
            runCatching { partial.delete() }
            false
        }
    }

    companion object {
        /**
         * 打开旁路写入：从 [position] 继续写。
         *
         * @return null 表示无法顺序旁路（前缀不连续 / 文件不可写），调用方应放弃旁路；
         *   放弃时已有前缀文件保持不动，供后台 Range 补全。
         */
        fun open(partial: File, target: File, position: Long): CacheTap? {
            if (target.exists()) return null
            val prefix = if (partial.exists()) partial.length() else 0L
            // 位置越过前缀：中间有空洞，无法顺序续写
            if (position > prefix) return null
            return try {
                CacheTap(partial, target, position)
            } catch (_: Exception) {
                null
            }
        }
    }
}
