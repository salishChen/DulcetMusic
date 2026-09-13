package com.mtechviral.musicfinderexample.core.model

/**
 * 歌曲实体：映射数据库 songs 表的全部字段。
 *
 * 对应原 Flutter 工程 `lib/data/models/song.dart`。
 * 所有元数据均在扫描时从音频文件中读取，播放时直接使用 [path] 指向的本地文件；
 * Subsonic 远程歌曲的 [path] 存的是流媒体 URL，[cachedPath] 为本地缓存文件。
 */
data class Song(
    val id: Long? = null,

    /** 歌名（无标签时回退为文件名） */
    val title: String,

    /** 文件绝对路径（唯一）；远程歌曲为流媒体 URL */
    val path: String,

    val artist: String? = null,
    val album: String? = null,

    /** 专辑艺术家 */
    val albumArtist: String? = null,

    /** 专辑内排序（音轨号） */
    val trackNumber: Int? = null,

    /** 时长（毫秒） */
    val duration: Long? = null,

    /** 比特率（bps） */
    val bitrate: Int? = null,

    /** 采样率（Hz） */
    val sampleRate: Int? = null,

    /** 位深（bit），解析库不支持时为 null */
    val bitDepth: Int? = null,

    /** 文件大小（字节） */
    val size: Long? = null,

    /** 文件格式（扩展名，如 mp3 / flac） */
    val format: String? = null,

    /** 编码（如 MPEG Layer III / FLAC，取自扩展名推断） */
    val codec: String? = null,

    /** 添加时间（毫秒时间戳，入库时间） */
    val dateAdded: Long? = null,

    /** 文件修改时间（毫秒时间戳） */
    val dateModified: Long? = null,

    /** 是否含内嵌封面 */
    val hasArtwork: Boolean = false,

    /** 内嵌歌词文本（通常为 LRC 格式，含 [mm:ss.xx] 时间戳） */
    val lyrics: String? = null,

    /** 音乐来源标识（媒体库为 'media_library'，文件夹扫描为文件夹路径） */
    val source: String? = null,

    /** 来源类型：'local'（本地）或 'subsonic'（远程） */
    val sourceType: String? = null,

    /** Subsonic 远程歌曲 ID */
    val remoteId: String? = null,

    /** 远程流媒体 URL（未缓存时用于流式播放） */
    val remoteStreamUrl: String? = null,

    /** 本地缓存文件路径（已缓存时非 null） */
    val cachedPath: String? = null,

    /** 缓存时间戳（毫秒，用于 LRU 淘汰） */
    val cacheTimestamp: Long? = null,

    /** 本地缓存的封面文件路径（远程歌曲扫描时下载） */
    val cachedArtworkPath: String? = null,

    /** Subsonic 封面 ID（如 "al-123"，用于按需获取封面） */
    val coverArtId: String? = null,

    /** 播放次数 */
    val playCount: Int = 0,

    /** 是否喜欢 */
    val isLiked: Boolean = false,

    /** 最后播放时间戳（毫秒） */
    val lastPlayed: Long? = null,
) {
    /**
     * 归一化的歌曲身份键（用于跨来源判定"同一首歌"）：标题|艺术家|专辑。
     * 与数据库层 [com.mtechviral.musicfinderexample.core.database.DatabaseHelper.identityKeyOf] 保持一致。
     */
    val identityKey: String
        get() = buildString {
            append(title.trim().lowercase())
            append('|')
            append((artist ?: "").trim().lowercase())
            append('|')
            append((album ?: "").trim().lowercase())
        }

    /** 展示用艺术家（空值回退） */
    val displayArtist: String
        get() = if (artist.isNullOrBlank()) "未知艺术家" else artist

    /** 展示用专辑（空值回退） */
    val displayAlbum: String
        get() = if (album.isNullOrBlank()) "未知专辑" else album

    /** 是否为远程 Subsonic 歌曲 */
    val isRemote: Boolean
        get() = sourceType == SOURCE_TYPE_SUBSONIC

    /** 是否已缓存到本地 */
    val isCached: Boolean
        get() = !cachedPath.isNullOrEmpty()

    /** 获取可播放的路径：已缓存返回本地路径，否则返回 path（远程 URL 交给播放器处理） */
    val playablePath: String
        get() = if (isCached) cachedPath!! else path

    /** 时长格式化 mm:ss */
    val durationText: String
        get() {
            val d = duration ?: return "--:--"
            if (d <= 0) return "--:--"
            val totalSeconds = d / 1000
            val m = totalSeconds / 60
            val s = totalSeconds % 60
            return "$m:${s.toString().padStart(2, '0')}"
        }

    /** 合并"缓存完成"后的字段（对应 Dart 端 copyWith(cachedPath:, cachedArtworkPath:)） */
    fun mergeCache(cachedPath: String?, cachedArtworkPath: String?): Song = copy(
        cachedPath = cachedPath ?: this.cachedPath,
        cachedArtworkPath = cachedArtworkPath ?: this.cachedArtworkPath,
    )

    /** 合并歌词（对应 Dart 端 copyWith(lyrics:)） */
    fun mergeLyrics(newLyrics: String?): Song =
        if (newLyrics.isNullOrEmpty()) this else copy(lyrics = newLyrics)

    /*
     * 相等性刻意使用 data class 的默认实现（全字段比较），不能改成"仅按 path 比较"：
     *
     * 歌词 / 封面 / 缓存状态都是在播放过程中异步回填到同一个 path 的 Song 上
     * （见 PlayerController.mergeCurrentSong），再通过 `StateFlow<Song?>` /
     * `StateFlow<List<Song>>` 推给 UI。StateFlow 赋值时用 `equals` 做去重，
     * 一旦改成"仅比 path"，这些"同 path 不同内容"的更新会被判定为"值未变化"而被
     * 丢弃——表现为首次播放线上歌曲时歌词永远不显示、封面/缓存角标不刷新。
     *
     * 去重语义（播放列表、曲库列表、LazyColumn key）全部在调用处以
     * [path] / [identityKey] 显式比较，不依赖 [equals]。
     */

    companion object {
        const val SOURCE_TYPE_LOCAL = "local"
        const val SOURCE_TYPE_SUBSONIC = "subsonic"

        /** 媒体库扫描来源标识 */
        const val SOURCE_MEDIA_LIBRARY = "media_library"
    }
}
