package com.mtechviral.musicfinderexample.core.media

/**
 * 支持的音频扩展名与编码推断。
 *
 * 对应原 Flutter 工程 `lib/data/metadata_service.dart` 中的 `kAudioExtensions`
 * 与 `_codecOf`。
 */
object AudioFormats {

    /** 支持的音频扩展名（小写，含点） */
    val extensions: Set<String> = setOf(
        ".mp3", ".m4a", ".mp4", ".flac", ".ogg", ".opus", ".wav", ".aiff",
        ".aifc", ".ape",
    )

    /** 是否支持的音频文件 */
    fun isSupported(path: String): Boolean = extensionOf(path) in extensions

    /** 取小写扩展名（含点），无扩展名返回空串 */
    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot).lowercase()
    }

    /** 取不带点的扩展名 */
    fun extensionNameOf(path: String): String = extensionOf(path).removePrefix(".")

    /** 根据扩展名推断编码（与原 `_codecOf` 完全一致） */
    fun codecOf(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "MPEG Layer III"
        "flac" -> "FLAC"
        "m4a", "mp4" -> "AAC/ALAC"
        "ogg" -> "Vorbis"
        "opus" -> "Opus"
        "wav" -> "PCM"
        "aiff", "aifc" -> "AIFF"
        "ape" -> "APE"
        else -> ext.uppercase()
    }
}
