package com.mtechviral.musicfinderexample.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 展示格式化工具。
 *
 * 对应原 Flutter 工程中的 `CacheService.formatSize` / `formatSizeMB`
 * 与 `Song.durationText` 等分散的格式化逻辑，此处统一收敛。
 */
object Formatters {

    /** 格式化文件大小：B / KB / MB / GB（与原 CacheService.formatSize 一致） */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    /** 格式化 MB 容量：< 1024MB 显示 MB，否则显示 GB */
    fun formatSizeMB(mb: Int): String =
        if (mb < 1024) "$mb MB"
        else String.format(Locale.US, "%.1f GB", mb / 1024.0)

    /** 时长（毫秒）→ mm:ss，无效值返回 --:-- */
    fun formatDuration(ms: Long?): String {
        if (ms == null || ms <= 0) return "--:--"
        val totalSeconds = ms / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "$m:${s.toString().padStart(2, '0')}"
    }

    /** 时间戳（毫秒）→ yyyy-MM-dd HH:mm */
    fun formatDateTime(ms: Long?): String {
        if (ms == null || ms <= 0) return "--"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(ms))
    }

    /** 时间戳（毫秒）→ yyyy-MM-dd */
    fun formatDate(ms: Long?): String {
        if (ms == null || ms <= 0) return "--"
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return fmt.format(Date(ms))
    }

    /** 播放次数文案 */
    fun formatPlayCount(count: Int): String = "$count 次"
}
