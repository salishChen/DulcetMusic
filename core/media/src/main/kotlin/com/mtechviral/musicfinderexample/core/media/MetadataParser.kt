package com.mtechviral.musicfinderexample.core.media

import android.media.MediaMetadataRetriever
import android.util.Log
import com.mtechviral.musicfinderexample.core.model.Song
import java.io.File

/**
 * 单文件音频元数据解析。
 *
 * 原 Flutter 端使用 `audio_metadata_reader`（Dart 实现，可解析 ID3/Vorbis/APE 等标签，
 * 并返回 bitrate / sampleRate / lyrics / 内嵌图片）；原生侧使用系统
 * [MediaMetadataRetriever]，字段语义一一对应：
 *
 * | Dart 字段        | 原生来源                                    |
 * |------------------|---------------------------------------------|
 * | title            | METADATA_KEY_TITLE（空则回退文件名）        |
 * | artist           | METADATA_KEY_ARTIST                         |
 * | album            | METADATA_KEY_ALBUM                          |
 * | albumArtist      | METADATA_KEY_ALBUMARTIST（空则回退 artist） |
 * | trackNumber      | METADATA_KEY_CD_TRACK_NUMBER（"3/12"→3）    |
 * | duration(ms)     | METADATA_KEY_DURATION                       |
 * | bitrate          | METADATA_KEY_BITRATE                        |
 * | sampleRate       | METADATA_KEY_SAMPLERATE                     |
 * | bitDepth         | METADATA_KEY_BITS_PER_SAMPLE                |
 * | hasArtwork       | embeddedPicture != null                     |
 * | lyrics           | [EmbeddedLyricsReader]（ID3v2 USLT/TXXX、Vorbis Comment）|
 *
 * 部分字段（bitrate/sampleRate/bitDepth/lyrics）仅在 Android 11/12+ 提供，
 * 低版本返回 null，与原实现"解析库不支持则存 null"的约定一致。
 */
object MetadataParser {

    private const val TAG = "MetadataParser"

    /** 解析单个音频文件；文件不存在或解析失败返回 null（原实现返回 null 计入 failed） */
    fun parse(path: String): Song? {
        val file = File(path)
        if (!file.exists()) return null
        val statSize = file.length()
        val statModified = file.lastModified()

        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)

            val ext = AudioFormats.extensionNameOf(path)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: file.nameWithoutExtension

            val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()?.takeIf { it.isNotEmpty() }
            val albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.trim()?.takeIf { it.isNotEmpty() }
                ?: artist

            val trackNumber = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.let { parseLeadingInt(it) }

            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()

            val bitrate = extractIntSafe(mmr, MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val sampleRate = extractIntSafe(mmr, MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            val bitDepth = extractIntSafe(mmr, MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)

            val lyrics = EmbeddedLyricsReader.read(path)
                ?.trim()?.takeIf { it.isNotEmpty() }

            val hasArtwork = try {
                mmr.embeddedPicture != null
            } catch (e: Exception) {
                Log.w(TAG, "读取内嵌封面失败 $path: ${e.message}")
                false
            }

            return Song(
                title = title,
                path = path,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                trackNumber = trackNumber,
                duration = duration,
                bitrate = bitrate,
                sampleRate = sampleRate,
                bitDepth = bitDepth,
                size = statSize,
                format = ext,
                codec = AudioFormats.codecOf(ext),
                dateAdded = System.currentTimeMillis(),
                dateModified = statModified,
                hasArtwork = hasArtwork,
                lyrics = lyrics,
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析失败 $path: ${e.message}")
            return null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /** 读取内嵌封面字节（无封面返回 null） */
    fun readEmbeddedArtwork(path: String): ByteArray? {
        if (!File(path).exists()) return null
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(path)
            mmr.embeddedPicture
        } catch (e: Exception) {
            Log.w(TAG, "读取封面失败 $path: ${e.message}")
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    /** "3/12" -> 3；非数字返回 null */
    private fun parseLeadingInt(raw: String): Int? {
        val digits = raw.trim().takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    /** 部分 METADATA_KEY_* 常量在低版本系统上不可用，统一做异常兜底 */
    private fun extractIntSafe(mmr: MediaMetadataRetriever, key: Int): Int? = try {
        mmr.extractMetadata(key)?.trim()?.toIntOrNull()
    } catch (_: Exception) {
        null
    }
}
