package com.mtechviral.musicfinderexample.core.database

import android.content.ContentValues
import android.database.Cursor
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_BITRATE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_BIT_DEPTH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHED_ARTWORK_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHED_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHE_TIMESTAMP
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CODEC
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_COVER_ART_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_DATE_ADDED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_DATE_MODIFIED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_DURATION
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_FORMAT
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_HAS_ARTWORK
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_IS_LIKED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_LAST_PLAYED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_LYRICS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_PLAY_COUNT
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_REMOTE_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_REMOTE_STREAM_URL
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SAMPLE_RATE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SIZE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE_TYPE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_TITLE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_TRACK_NUMBER
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * Song ↔ SQLite 行映射。
 *
 * 与原 Flutter 端 `Song.fromMap` / `Song.toMap` 保持一一对应，
 * 包括 `hasArtwork` / `isLiked` 以 0/1 存储的约定。
 */
object SongMapper {

    /**
     * 归一化歌曲身份键（与 `Song.identityKey` 保持一致）：
     * 标题|艺术家|专辑，三者均 trim + 转小写。
     */
    fun identityKeyOf(title: String?, artist: String?, album: String?): String =
        "${(title ?: "").trim().lowercase()}|" +
            "${(artist ?: "").trim().lowercase()}|" +
            "${(album ?: "").trim().lowercase()}"

    fun fromCursor(c: Cursor): Song = Song(
        id = c.getLongOrNull(COL_ID),
        title = c.getStringOrNull(COL_TITLE) ?: "未知歌曲",
        path = c.getStringOrNull(COL_PATH) ?: "",
        artist = c.getStringOrNull(COL_ARTIST),
        album = c.getStringOrNull(COL_ALBUM),
        albumArtist = c.getStringOrNull(COL_ALBUM_ARTIST),
        trackNumber = c.getIntOrNull(COL_TRACK_NUMBER),
        duration = c.getLongOrNull(COL_DURATION),
        bitrate = c.getIntOrNull(COL_BITRATE),
        sampleRate = c.getIntOrNull(COL_SAMPLE_RATE),
        bitDepth = c.getIntOrNull(COL_BIT_DEPTH),
        size = c.getLongOrNull(COL_SIZE),
        format = c.getStringOrNull(COL_FORMAT),
        codec = c.getStringOrNull(COL_CODEC),
        dateAdded = c.getLongOrNull(COL_DATE_ADDED),
        dateModified = c.getLongOrNull(COL_DATE_MODIFIED),
        hasArtwork = (c.getIntOrNull(COL_HAS_ARTWORK) ?: 0) == 1,
        lyrics = c.getStringOrNull(COL_LYRICS),
        source = c.getStringOrNull(COL_SOURCE),
        sourceType = c.getStringOrNull(COL_SOURCE_TYPE),
        sourceId = c.getStringOrNull(COL_SOURCE_ID),
        remoteId = c.getStringOrNull(COL_REMOTE_ID),
        remoteStreamUrl = c.getStringOrNull(COL_REMOTE_STREAM_URL),
        cachedPath = c.getStringOrNull(COL_CACHED_PATH),
        cacheTimestamp = c.getLongOrNull(COL_CACHE_TIMESTAMP),
        cachedArtworkPath = c.getStringOrNull(COL_CACHED_ARTWORK_PATH),
        coverArtId = c.getStringOrNull(COL_COVER_ART_ID),
        playCount = c.getIntOrNull(COL_PLAY_COUNT) ?: 0,
        isLiked = (c.getIntOrNull(COL_IS_LIKED) ?: 0) == 1,
        lastPlayed = c.getLongOrNull(COL_LAST_PLAYED),
    )

    /**
     * 「扫描元数据」列集合（优化建议 03）：重扫合并已有歌曲时使用。
     *
     * 与 [toContentValues] 的区别：不写主键、不写用户状态
     * （喜欢 / 播放次数 / 最后播放 / 音频与封面缓存），歌词仅在新值非空时覆盖，
     * 也不改首次入库时间（dateAdded）—— 从而保留用户数据与歌单绑定。
     */
    fun metadataContentValues(song: Song, source: String?): ContentValues =
        ContentValues().apply {
            put(COL_TITLE, song.title)
            put(COL_PATH, song.path)
            put(COL_ARTIST, song.artist)
            put(COL_ALBUM, song.album)
            put(COL_ALBUM_ARTIST, song.albumArtist)
            put(COL_TRACK_NUMBER, song.trackNumber)
            put(COL_DURATION, song.duration)
            put(COL_BITRATE, song.bitrate)
            put(COL_SAMPLE_RATE, song.sampleRate)
            put(COL_BIT_DEPTH, song.bitDepth)
            put(COL_SIZE, song.size)
            put(COL_FORMAT, song.format)
            put(COL_CODEC, song.codec)
            put(COL_DATE_MODIFIED, song.dateModified)
            put(COL_HAS_ARTWORK, if (song.hasArtwork) 1 else 0)
            put(COL_SOURCE, source)
            put(COL_SOURCE_TYPE, song.sourceType)
            put(COL_SOURCE_ID, song.sourceId)
            put(COL_REMOTE_ID, song.remoteId)
            put(COL_REMOTE_STREAM_URL, song.remoteStreamUrl)
            put(COL_COVER_ART_ID, song.coverArtId)
            // 歌词：仅当新扫描结果非空时覆盖，保留已获取的歌词
            if (song.lyrics != null) put(COL_LYRICS, song.lyrics)
        }

    /**
     * @param includeId 是否写入主键（新增时为 true，按 id 更新时须为 false，
     *                  对应 Dart 端 `updateMap..remove('id')`）
     */
    fun toContentValues(song: Song, includeId: Boolean = true): ContentValues =
        ContentValues().apply {
            if (includeId && song.id != null) put(COL_ID, song.id)
            put(COL_TITLE, song.title)
            put(COL_PATH, song.path)
            put(COL_ARTIST, song.artist)
            put(COL_ALBUM, song.album)
            put(COL_ALBUM_ARTIST, song.albumArtist)
            put(COL_TRACK_NUMBER, song.trackNumber)
            put(COL_DURATION, song.duration)
            put(COL_BITRATE, song.bitrate)
            put(COL_SAMPLE_RATE, song.sampleRate)
            put(COL_BIT_DEPTH, song.bitDepth)
            put(COL_SIZE, song.size)
            put(COL_FORMAT, song.format)
            put(COL_CODEC, song.codec)
            put(COL_DATE_ADDED, song.dateAdded)
            put(COL_DATE_MODIFIED, song.dateModified)
            put(COL_HAS_ARTWORK, if (song.hasArtwork) 1 else 0)
            put(COL_LYRICS, song.lyrics)
            put(COL_SOURCE, song.source)
            put(COL_SOURCE_TYPE, song.sourceType)
            put(COL_SOURCE_ID, song.sourceId)
            put(COL_REMOTE_ID, song.remoteId)
            put(COL_REMOTE_STREAM_URL, song.remoteStreamUrl)
            put(COL_CACHED_PATH, song.cachedPath)
            put(COL_CACHE_TIMESTAMP, song.cacheTimestamp)
            put(COL_CACHED_ARTWORK_PATH, song.cachedArtworkPath)
            put(COL_COVER_ART_ID, song.coverArtId)
            put(COL_PLAY_COUNT, song.playCount)
            put(COL_IS_LIKED, if (song.isLiked) 1 else 0)
            put(COL_LAST_PLAYED, song.lastPlayed)
        }

    // ---- Cursor 取值辅助：区分 NULL 与 0 ----

    private fun Cursor.getLongOrNull(name: String): Long? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getLong(i)
    }

    private fun Cursor.getIntOrNull(name: String): Int? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getInt(i)
    }

    private fun Cursor.getStringOrNull(name: String): String? {
        val i = getColumnIndex(name)
        return if (i < 0 || isNull(i)) null else getString(i)
    }
}
