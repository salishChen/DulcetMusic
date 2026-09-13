package com.mtechviral.musicfinderexample.core.media

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * 内嵌歌词读取。
 *
 * 原 Flutter 端使用 `audio_metadata_reader`，可直接取出音频文件内嵌的歌词标签；
 * Android 系统 [android.media.MediaMetadataRetriever] **没有**歌词字段，
 * 因此这里实现一个轻量标签解析器，覆盖最常见的两类容器：
 *
 * - **ID3v2（MP3）**：`USLT`（非同步歌词）帧；以及 `TXXX` 帧中描述为
 *   `LYRICS` / `UNSYNCEDLYRICS` / `LYRIC` 的文本。
 * - **Vorbis Comment（FLAC / OGG / Opus）**：`LYRICS`、`UNSYNCEDLYRICS`、
 *   `LYRIC`、`SYNCLYRICS` 字段。
 *
 * 解析失败或不存在时返回 null（与原实现"没有歌词标签"语义一致）。
 */
object EmbeddedLyricsReader {

    private const val TAG = "EmbeddedLyricsReader"

    private val LYRICS_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "LYRIC", "SYNCLYRICS")

    /** 读取指定音频文件的内嵌歌词，无则返回 null */
    fun read(path: String): String? = try {
        val file = File(path)
        if (!file.exists()) {
            null
        } else {
            when (AudioFormats.extensionNameOf(path)) {
                "mp3" -> readId3v2(file)
                "flac" -> readFlacVorbisComment(file)
                "ogg", "opus" -> readOggVorbisComment(file)
                else -> null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取内嵌歌词失败 $path: ${e.message}")
        null
    }

    // ===================== ID3v2（MP3） =====================

    private fun readId3v2(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.read(header) != 10) return null
            // "ID3"
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return null
            }
            val version = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = synchsafe(header, 6)

            // 扩展头（v2.4/v2.3 均可能带 extended header）
            var offset = 10L
            var limit = 10L + tagSize
            if (flags and 0x40 != 0) {
                val extSizeBytes = ByteArray(4)
                if (raf.read(extSizeBytes) == 4) {
                    val extSize = if (version >= 4) synchsafe(extSizeBytes, 0) else beInt(extSizeBytes, 0)
                    offset += extSize
                }
            }

            while (offset + 10 <= limit) {
                raf.seek(offset)
                val frameHeader = ByteArray(10)
                if (raf.read(frameHeader) != 10) break
                val frameId = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
                if (frameId.isBlank() || frameId[0] == '\u0000') break
                val frameSize = if (version >= 4) {
                    synchsafe(frameHeader, 4)
                } else {
                    beInt(frameHeader, 4)
                }
                if (frameSize <= 0) break

                val body = ByteArray(frameSize)
                raf.seek(offset + 10)
                val read = raf.read(body)
                if (read <= 0) break

                when (frameId) {
                    "USLT" -> decodeUslt(body, version)?.let { return it }
                    "SYLT" -> decodeUslt(body, version)?.let { return it }
                    "TXXX" -> decodeTxxx(body)?.let { return it }
                }
                offset += 10 + frameSize
                // 兼容 v2.3 的 padding / 异常长度
                if (offset <= 10) break
            }
        }
        return null
    }

    /** USLT：encoding(1) + language(3) + descriptor(\0 结尾) + lyrics */
    private fun decodeUslt(body: ByteArray, version: Int): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        val contentStart = if (version >= 4) 4 else 5
        if (body.size <= contentStart) return null
        val text = decodeText(body, contentStart, body.size, encoding)
        return text.trim().takeIf { it.isNotEmpty() }
    }

    /** TXXX：encoding(1) + description(\0 结尾) + value */
    private fun decodeTxxx(body: ByteArray): String? {
        if (body.size < 3) return null
        val encoding = body[0].toInt() and 0xFF
        val terminatorSize = if (encoding == 1 || encoding == 2) 2 else 1
        var descEnd = 1
        while (descEnd + terminatorSize <= body.size) {
            val isTerminator = if (terminatorSize == 2) {
                body[descEnd] == 0.toByte() && body[descEnd + 1] == 0.toByte()
            } else {
                body[descEnd] == 0.toByte()
            }
            if (isTerminator) break
            descEnd++
        }
        if (descEnd + terminatorSize > body.size) return null
        val description = decodeText(body, 1, descEnd, encoding).trim().uppercase()
        if (description !in LYRICS_KEYS) return null
        val valueStart = descEnd + terminatorSize
        if (valueStart >= body.size) return null
        return decodeText(body, valueStart, body.size, encoding).trim().takeIf { it.isNotEmpty() }
    }

    // ===================== Vorbis Comment（FLAC / OGG） =====================

    private fun readFlacVorbisComment(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            if (raf.read(magic) != 4 || String(magic, Charsets.US_ASCII) != "fLaC") return null
            while (true) {
                val blockHeader = ByteArray(4)
                if (raf.read(blockHeader) != 4) return null
                val isLast = (blockHeader[0].toInt() and 0x80) != 0
                val blockType = blockHeader[0].toInt() and 0x7F
                val length = be24(blockHeader, 1)
                if (blockType == 4) { // VORBIS_COMMENT
                    val body = ByteArray(length)
                    if (raf.read(body) != length) return null
                    return parseVorbisComment(body)
                }
                raf.seek(raf.filePointer + length)
                if (isLast) return null
            }
        }
    }

    private fun readOggVorbisComment(file: File): String? {
        RandomAccessFile(file, "r").use { raf ->
            val length = minOf(file.length(), MAX_OGG_SCAN_BYTES).toInt()
            val data = ByteArray(length)
            if (raf.read(data) != length) return null
            val marker = "vorbis".toByteArray(Charsets.US_ASCII)
            val idx = indexOf(data, marker)
            if (idx < 0) return null
            // vorbis 头之后紧跟 comment 头：0x03 + "vorbis"
            var pos = idx + marker.size
            if (pos + 1 >= data.size) return null
            // 跳过 packet type / 可能的 framing
            pos += 1
            if (pos + 7 > data.size) return null
            val vendorLength = leInt(data, pos)
            if (vendorLength < 0 || pos + 4 + vendorLength > data.size) return null
            pos += 4 + vendorLength
            if (pos + 4 > data.size) return null
            val count = leInt(data, pos)
            pos += 4
            if (count <= 0 || count > 100_000) return null
            for (i in 0 until count) {
                if (pos + 4 > data.size) return null
                val len = leInt(data, pos)
                pos += 4
                if (len < 0 || pos + len > data.size) return null
                val entry = String(data, pos, len, Charsets.UTF_8)
                pos += len
                parseCommentEntry(entry)?.let { return it }
            }
        }
        return null
    }

    private fun parseVorbisComment(body: ByteArray): String? {
        var pos = 0
        if (body.size < 8) return null
        val vendorLength = leInt(body, pos)
        pos += 4 + vendorLength
        if (pos + 4 > body.size) return null
        val count = leInt(body, pos)
        pos += 4
        if (count <= 0) return null
        for (i in 0 until count) {
            if (pos + 4 > body.size) return null
            val len = leInt(body, pos)
            pos += 4
            if (len < 0 || pos + len > body.size) return null
            val entry = String(body, pos, len, Charsets.UTF_8)
            pos += len
            parseCommentEntry(entry)?.let { return it }
        }
        return null
    }

    /** "LYRICS=..." -> 歌词值 */
    private fun parseCommentEntry(entry: String): String? {
        val eq = entry.indexOf('=')
        if (eq <= 0) return null
        val key = entry.substring(0, eq).trim().uppercase()
        if (key !in LYRICS_KEYS) return null
        return entry.substring(eq + 1).trim().takeIf { it.isNotEmpty() }
    }

    // ===================== 二进制辅助 =====================

    /** ID3v2 synchsafe 整数（每字节仅 7 位有效） */
    private fun synchsafe(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun be24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)

    private fun decodeText(bytes: ByteArray, from: Int, to: Int, encoding: Int): String {
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(bytes, from, (to - from).coerceAtLeast(0), charset)
            .replace("\u0000", "")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private const val MAX_OGG_SCAN_BYTES = 512 * 1024L
}
