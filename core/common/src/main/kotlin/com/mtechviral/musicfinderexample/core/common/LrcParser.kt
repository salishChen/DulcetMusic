package com.mtechviral.musicfinderexample.core.common

/**
 * 单句歌词（含时间轴）。
 * 对应原 Flutter 工程 `lib/utils/lrc.dart` 中的 `LrcLine`。
 */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * LRC 歌词解析器。
 *
 * 与原 Flutter 端 `parseLrc` 保持完全一致的行为：
 * - 支持 `[mm:ss.xx]` / `[mm:ss.xxx]` 时间戳，一行可含多个时间戳；
 * - 无时间戳的纯文本行按 timeMs = 0 返回，供整体展示；
 * - 有时间戳但文本为空的行丢弃；
 * - 结果按时间升序排序。
 */
object LrcParser {

    private val TIME_TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(raw: String?): List<LrcLine> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<LrcLine>()
        for (line in raw.split('\n')) {
            val matches = TIME_TAG.findAll(line).toList()
            val text = TIME_TAG.replace(line, "").trim()
            if (matches.isEmpty()) {
                if (text.isNotEmpty()) out.add(LrcLine(0L, text))
                continue
            }
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val msStr = m.groupValues[3].ifEmpty { "0" }
                val ms = msStr.padEnd(3, '0').substring(0, 3).toInt()
                out.add(LrcLine(min * 60_000L + sec * 1000L + ms, text))
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    /**
     * 找到当前播放位置对应的歌词行下标（最后一个 timeMs <= positionMs 的行）。
     * 与悬浮窗歌词服务中的查找逻辑一致，返回 -1 表示尚未到第一句。
     */
    fun activeIndex(lines: List<LrcLine>, positionMs: Long): Int {
        for (i in lines.indices.reversed()) {
            if (lines[i].timeMs <= positionMs) return i
        }
        return -1
    }
}
