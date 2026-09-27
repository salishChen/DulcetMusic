package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_BITRATE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_BIT_DEPTH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHED_ARTWORK_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHED_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_CACHE_TIMESTAMP
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_COVER_ART_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_DURATION
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_IS_LIKED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_LAST_PLAYED
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_LYRICS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_PATH
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_PLAY_COUNT
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_HAS_ARTWORK
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_REMOTE_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SAMPLE_RATE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE_TYPE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_SOURCE_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_TITLE
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_TRACK_NUMBER
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SONGS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.VIEW_VISIBLE_SONGS
import com.mtechviral.musicfinderexample.core.database.SongMapper
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 播放概览聚合结果（统计页顶部卡片，优化建议 07）。
 */
data class PlayOverview(
    /** 全库播放总次数（SUM(playCount)） */
    val totalPlayCount: Long,
    /** 已听歌曲数（playCount > 0） */
    val playedSongCount: Int,
    /** 时间窗口内播放过的歌曲数（lastPlayed >= since） */
    val recentPlayCount: Int,
)

/**
 * songs 表数据访问对象。
 *
 * 逐条对应原 Flutter 工程 `DatabaseHelper` 中「歌曲」「远程歌曲缓存」「播放统计」
 * 「喜欢功能」四个分区的全部查询。
 */
class SongDao(private val musicDatabase: MusicDatabase) {

    private val db: SQLiteDatabase get() = musicDatabase.writableDatabase

    /** 查询全部歌曲（按标题排序，忽略大小写） */
    fun queryAllSongs(): List<Song> =
        db.query(VIEW_VISIBLE_SONGS, null, null, null, null, null, "title COLLATE NOCASE ASC")
            .use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 按 id 查询单曲 */
    fun querySongById(id: Long): Song? =
        db.query(TABLE_SONGS, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) SongMapper.fromCursor(c) else null }

    /** 按 path 查询单曲（用于播放列表持久化恢复） */
    fun querySongByPath(path: String): Song? =
        db.query(VIEW_VISIBLE_SONGS, null, "$COL_PATH = ?", arrayOf(path), null, null, null)
            .use { c -> if (c.moveToFirst()) SongMapper.fromCursor(c) else null }

    /** 按远程 id 查询单曲 */
    fun querySongByRemoteId(remoteId: String): Song? {
        val sourceId = db.rawQuery("SELECT activeSourceId FROM remote_state WHERE singletonId = 1", null)
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return null
        return querySongByRemoteId(sourceId, remoteId)
    }

    fun querySongByRemoteId(sourceId: String, remoteId: String): Song? =
        db.query(TABLE_SONGS, null, "$COL_SOURCE_ID = ? AND $COL_REMOTE_ID = ?", arrayOf(sourceId, remoteId), null, null, "1")
            .use { c -> if (c.moveToFirst()) SongMapper.fromCursor(c) else null }

    /**
     * 事务批量插入/更新歌曲，返回实际新增/更新的数量。
     *
     * 匹配与合并规则（优化建议 03：「扫描元数据」与「用户状态」分开处理）：
     * - 匹配优先级：
     *   1) 远程歌曲按 (remoteId + 来源) 严格匹配；旧库中来源为 `subsonic` 的行
     *      在身份键一致时兼容匹配（升级后迁移为按服务器区分的来源标识）；
     *   2) 本地歌曲按 path 匹配（同路径改标签重扫 → 更新原行元数据）；
     *   3) 兜底按身份键（标题|艺术家|专辑）匹配（文件移动/改名、跨来源同曲）。
     * - 命中已有行时**只更新扫描元数据**（标题/路径/时长/码率/来源等），
     *   保留主键与用户状态（喜欢、播放次数、最后播放、歌词、音频与封面缓存），
     *   歌单绑定（playlist_songs 外键）因此不会丢失；新扫描到的歌词非空时才覆盖歌词。
     * - 未命中时插入新行；**不再对 songs 表使用 REPLACE**
     *   （path 为 UNIQUE，REPLACE 会删除旧行并级联解除歌单绑定）。
     */
    fun insertSongs(songs: List<Song>, source: String?): Int {
        if (songs.isEmpty()) return 0

        val index = matchIndex()
        var affected = 0
        db.beginTransaction()
        try {
            for (song in songs) {
                val hit = index.findMatch(song, source)
                if (hit != null) {
                    // 同一首歌：更新元数据，保留用户状态与主键
                    db.update(
                        TABLE_SONGS,
                        SongMapper.metadataContentValues(song, source),
                        "$COL_ID = ?",
                        arrayOf(hit.id.toString()),
                    )
                    index.onUpdated(hit, song, source)
                    affected++
                } else {
                    val rowId = db.insert(TABLE_SONGS, null, valuesOf(song, source))
                    if (rowId != -1L) {
                        index.onInserted(rowId, song, source)
                        affected++
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return affected
    }

    // ======================== 扫描匹配索引 ========================

    @Volatile
    private var cachedMatchIndex: MatchIndex? = null

    /** 取扫描匹配索引（扫描周期内复用，避免每批全表重读；优化建议 10） */
    @Synchronized
    private fun matchIndex(): MatchIndex =
        cachedMatchIndex ?: loadMatchIndex().also { cachedMatchIndex = it }

    /** 删除/清空歌曲后使匹配索引失效 */
    @Synchronized
    private fun invalidateMatchIndex() {
        cachedMatchIndex = null
    }

    private fun loadMatchIndex(): MatchIndex {
        val index = MatchIndex()
        db.query(
            TABLE_SONGS,
            arrayOf(COL_ID, COL_PATH, COL_REMOTE_ID, COL_SOURCE, COL_SOURCE_ID, COL_TITLE, COL_ARTIST, COL_ALBUM),
            null, null, null, null, null,
        ).use { c ->
            val idIdx = c.getColumnIndexOrThrow(COL_ID)
            val pIdx = c.getColumnIndexOrThrow(COL_PATH)
            val rIdx = c.getColumnIndexOrThrow(COL_REMOTE_ID)
            val sIdx = c.getColumnIndexOrThrow(COL_SOURCE)
            val sourceIdIdx = c.getColumnIndexOrThrow(COL_SOURCE_ID)
            val tIdx = c.getColumnIndexOrThrow(COL_TITLE)
            val arIdx = c.getColumnIndexOrThrow(COL_ARTIST)
            val alIdx = c.getColumnIndexOrThrow(COL_ALBUM)
            while (c.moveToNext()) {
                index.add(
                    MatchIndex.Entry(
                        id = c.getLong(idIdx),
                        path = if (c.isNull(pIdx)) "" else c.getString(pIdx),
                        remoteId = if (c.isNull(rIdx)) null else c.getString(rIdx),
                        source = if (c.isNull(sIdx)) null else c.getString(sIdx),
                        sourceId = if (c.isNull(sourceIdIdx)) null else c.getString(sourceIdIdx),
                        identityKey = SongMapper.identityKeyOf(
                            if (c.isNull(tIdx)) null else c.getString(tIdx),
                            if (c.isNull(arIdx)) null else c.getString(arIdx),
                            if (c.isNull(alIdx)) null else c.getString(alIdx),
                        ),
                    ),
                )
            }
        }
        return index
    }

    /** 现有歌曲的匹配索引：按 path / (remoteId+来源) / 身份键 三类键定位同一首歌。 */
    private class MatchIndex {
        class Entry(
            val id: Long,
            var path: String,
            var remoteId: String?,
            var source: String?,
            var sourceId: String?,
            var identityKey: String,
        )

        private val byPath = HashMap<String, Entry>()
        private val byRemote = HashMap<String, Entry>()
        private val byIdentity = HashMap<String, Entry>()

        fun add(entry: Entry) {
            if (entry.path.isNotEmpty()) byPath[entry.path] = entry
            val remoteId = entry.remoteId
            val sourceId = entry.sourceId
            if (!remoteId.isNullOrEmpty() && !sourceId.isNullOrEmpty()) {
                byRemote[remoteKey(remoteId, sourceId)] = entry
            }
            if (entry.remoteId == null) byIdentity[entry.identityKey] = entry
        }

        private fun remove(entry: Entry) {
            if (byPath[entry.path] === entry) byPath.remove(entry.path)
            val remoteId = entry.remoteId
            val sourceId = entry.sourceId
            if (!remoteId.isNullOrEmpty() && !sourceId.isNullOrEmpty()) {
                val key = remoteKey(remoteId, sourceId)
                if (byRemote[key] === entry) byRemote.remove(key)
            }
            if (entry.remoteId == null && byIdentity[entry.identityKey] === entry) byIdentity.remove(entry.identityKey)
        }

        fun findMatch(song: Song, source: String?): Entry? {
            val identity = song.identityKey
            val remoteId = song.remoteId
            val sourceId = song.sourceId
            if (song.isRemote) {
                if (!remoteId.isNullOrEmpty() && !sourceId.isNullOrEmpty()) {
                    byRemote[remoteKey(remoteId, sourceId)]?.let { return it }
                }
                return byPath[song.path]?.takeIf { it.sourceId == sourceId && it.remoteId == remoteId }
            }
            if (song.path.isNotEmpty()) byPath[song.path]?.takeIf { it.remoteId == null }?.let { return it }
            return byIdentity[identity]
        }

        fun onInserted(id: Long, song: Song, source: String?) {
            add(
                Entry(
                    id = id,
                    path = song.path,
                    remoteId = song.remoteId,
                    source = source,
                    sourceId = song.sourceId,
                    identityKey = song.identityKey,
                ),
            )
        }

        fun onUpdated(entry: Entry, song: Song, source: String?) {
            remove(entry)
            entry.path = song.path
            entry.remoteId = song.remoteId
            entry.source = source
            entry.sourceId = song.sourceId
            entry.identityKey = song.identityKey
            add(entry)
        }

        private fun remoteKey(remoteId: String, sourceId: String): String =
            "$sourceId|$remoteId"
    }

    private fun valuesOf(song: Song, source: String?, includeId: Boolean = true): ContentValues =
        SongMapper.toContentValues(song, includeId).apply { put(COL_SOURCE, source) }

    /** 删除歌曲（级联清理歌单绑定） */
    fun deleteSong(id: Long) {
        db.delete(TABLE_SONGS, "$COL_ID = ?", arrayOf(id.toString()))
        invalidateMatchIndex()
    }

    /**
     * 查询某位歌手（含 albumArtist 匹配）的全部歌曲。
     *
     * 第二十七轮需求：把歌手加入排除列表时，需要清掉"该歌手已有的音乐"。
     * 除了 `artist`，还要匹配 `albumArtist` —— 合辑里单曲的 `artist` 可能是
     * 「群星」，但 `albumArtist` 仍是该歌手；只按 artist 匹配会漏掉这类歌曲，
     * 表现为"排除了歌手却还留着几张他的专辑"。
     */
    fun querySongsByArtistOrAlbumArtist(artist: String): List<Song> =
        db.query(
            VIEW_VISIBLE_SONGS, null,
            "($COL_ARTIST = ? OR $COL_ALBUM_ARTIST = ?)",
            arrayOf(artist, artist),
            null, null,
            "$COL_ALBUM COLLATE NOCASE ASC, $COL_TRACK_NUMBER ASC",
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 按 id 批量删除（一次 SQL，级联清理歌单绑定），返回实际删除行数 */
    fun deleteSongsByIds(ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        val deleted = db.delete(TABLE_SONGS, "$COL_ID IN ($placeholders)", args)
        invalidateMatchIndex()
        return deleted
    }

    /** 清空歌曲表 */
    fun clearSongs() {
        db.delete(TABLE_SONGS, null, null)
        invalidateMatchIndex()
    }

    // ======================== 远程歌曲缓存 ========================

    /** 更新歌曲的缓存路径和时间戳 */
    fun updateSongCache(songId: Long, cachedPath: String) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply {
                put(COL_CACHED_PATH, cachedPath)
                put(COL_CACHE_TIMESTAMP, System.currentTimeMillis())
            },
            "$COL_ID = ?",
            arrayOf(songId.toString()),
        )
    }

    /** Enrich a cached WebDAV file without replacing its remote identity or user state. */
    fun enrichWebDavSong(songId: Long, sourceId: String, remoteId: String,
                         parsed: Song, cacheFileStem: String): Boolean {
        val values = ContentValues().apply {
            if (parsed.title.isNotBlank() && parsed.title != cacheFileStem) put(COL_TITLE, parsed.title)
            parsed.artist?.let { put(COL_ARTIST, it) }
            parsed.album?.let { put(COL_ALBUM, it) }
            parsed.albumArtist?.let { put(COL_ALBUM_ARTIST, it) }
            parsed.trackNumber?.let { put(COL_TRACK_NUMBER, it) }
            parsed.duration?.let { put(COL_DURATION, it) }
            parsed.bitrate?.let { put(COL_BITRATE, it) }
            parsed.sampleRate?.let { put(COL_SAMPLE_RATE, it) }
            parsed.bitDepth?.let { put(COL_BIT_DEPTH, it) }
            parsed.lyrics?.let { put(COL_LYRICS, it) }
            if (parsed.hasArtwork) put(COL_HAS_ARTWORK, 1)
        }
        if (values.size() == 0) return false
        val changed = db.update(TABLE_SONGS, values,
            "$COL_ID = ? AND $COL_SOURCE_ID = ? AND $COL_REMOTE_ID = ? AND $COL_SOURCE_TYPE = ?",
            arrayOf(songId.toString(), sourceId, remoteId, Song.SOURCE_TYPE_WEBDAV)) > 0
        if (changed) invalidateMatchIndex()
        return changed
    }

    /** 查询所有已缓存的远程歌曲 */
    fun queryCachedSongs(): List<Song> =
        db.query(
            TABLE_SONGS, null,
            "$COL_CACHED_PATH IS NOT NULL AND $COL_SOURCE_TYPE IN (?, ?, ?)",
            arrayOf(Song.SOURCE_TYPE_SUBSONIC, Song.SOURCE_TYPE_WEBDAV, Song.SOURCE_TYPE_EMBY), null, null, "$COL_CACHE_TIMESTAMP DESC",
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 清除歌曲的缓存记录 */
    fun clearSongCache(songId: Long) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply {
                putNull(COL_CACHED_PATH)
                putNull(COL_CACHE_TIMESTAMP)
            },
            "$COL_ID = ?",
            arrayOf(songId.toString()),
        )
    }

    /**
     * 刷新歌曲的缓存「最近使用」时间（播放/命中缓存时调用）。
     * 与下载写入（updateSongCache）使用同一口径，使淘汰接近严格 LRU（优化建议 05）。
     */
    fun touchSongCache(songId: Long) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply { put(COL_CACHE_TIMESTAMP, System.currentTimeMillis()) },
            "$COL_ID = ? AND $COL_CACHED_PATH IS NOT NULL",
            arrayOf(songId.toString()),
        )
    }

    /**
     * 清除引用指定封面缓存文件的记录（封面淘汰/删除时调用；
     * 多首歌可共享同一封面文件，按路径批量解除引用）。
     */
    fun clearArtworkCacheByPath(path: String) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply { putNull(COL_CACHED_ARTWORK_PATH) },
            "$COL_CACHED_ARTWORK_PATH = ?",
            arrayOf(path),
        )
    }

    /**
     * 重置全部缓存字段（音频与封面，优化建议 05）：
     * 「清空全部缓存」后数据库与目录一起归零，封面可重新下载。
     */
    fun clearAllCacheRecords() {
        db.execSQL(
            "UPDATE $TABLE_SONGS SET $COL_CACHED_PATH = NULL, $COL_CACHE_TIMESTAMP = NULL, " +
                "$COL_CACHED_ARTWORK_PATH = NULL WHERE $COL_CACHED_PATH IS NOT NULL " +
                "OR $COL_CACHE_TIMESTAMP IS NOT NULL OR $COL_CACHED_ARTWORK_PATH IS NOT NULL",
        )
    }

    /** 查询缓存时间最早的歌曲（用于 LRU 淘汰） */
    fun queryOldestCachedSong(): Song? =
        db.query(
            TABLE_SONGS, null,
            "$COL_CACHED_PATH IS NOT NULL AND $COL_SOURCE_TYPE IN (?, ?, ?)",
            arrayOf(Song.SOURCE_TYPE_SUBSONIC, Song.SOURCE_TYPE_WEBDAV, Song.SOURCE_TYPE_EMBY), null, null, "$COL_CACHE_TIMESTAMP ASC", "1",
        ).use { c -> if (c.moveToFirst()) SongMapper.fromCursor(c) else null }

    /** 更新歌曲的封面缓存路径 */
    fun updateArtworkCache(songId: Long, artworkPath: String) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply { put(COL_CACHED_ARTWORK_PATH, artworkPath) },
            "$COL_ID = ?",
            arrayOf(songId.toString()),
        )
    }

    /**
     * 清除歌曲的封面缓存记录（第二十六轮需求 4）。
     *
     * 永久删除在线歌曲时要连同封面缓存一起清掉，否则数据库里会残留一个
     * 指向已删除文件的路径，下次启动的"补缓存封面"会去读一个不存在的文件。
     */
    fun clearArtworkCache(songId: Long) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply { putNull(COL_CACHED_ARTWORK_PATH) },
            "$COL_ID = ?",
            arrayOf(songId.toString()),
        )
    }

    /** 更新歌曲的歌词内容 */
    fun updateSongLyrics(songId: Long, lyrics: String) {
        db.update(
            TABLE_SONGS,
            ContentValues().apply { put(COL_LYRICS, lyrics) },
            "$COL_ID = ?",
            arrayOf(songId.toString()),
        )
    }

    /** 查询封面尚未缓存的远程歌曲（coverArtId 有值但 cachedArtworkPath 为空） */
    fun querySongsNeedingArtworkCache(): List<Song> =
        db.query(
            VIEW_VISIBLE_SONGS, null,
            "$COL_SOURCE_TYPE IN (?, ?, ?) AND $COL_COVER_ART_ID IS NOT NULL " +
                "AND ($COL_CACHED_ARTWORK_PATH IS NULL OR $COL_CACHED_ARTWORK_PATH = ?)",
            arrayOf(Song.SOURCE_TYPE_SUBSONIC, Song.SOURCE_TYPE_WEBDAV, Song.SOURCE_TYPE_EMBY, ""), null, null, "$COL_ID ASC",
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    // ======================== 播放统计 ========================

    /** 递增歌曲播放次数并更新最后播放时间 */
    fun incrementPlayCount(songId: Long) {
        db.execSQL(
            "UPDATE $TABLE_SONGS SET $COL_PLAY_COUNT = $COL_PLAY_COUNT + 1, $COL_LAST_PLAYED = ? " +
                "WHERE $COL_ID = ?",
            arrayOf(System.currentTimeMillis(), songId),
        )
    }

    /** 查询最常播放的歌曲（Top N） */
    fun queryTopPlayed(limit: Int = 20): List<Song> =
        db.query(
            VIEW_VISIBLE_SONGS, null, "$COL_PLAY_COUNT > 0", null, null, null,
            "$COL_PLAY_COUNT DESC", limit.toString(),
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 查询最近播放的歌曲 */
    fun queryRecentlyPlayed(limit: Int = 50): List<Song> =
        db.query(
            VIEW_VISIBLE_SONGS, null, "$COL_LAST_PLAYED IS NOT NULL", null, null, null,
            "$COL_LAST_PLAYED DESC", limit.toString(),
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    // ======================== 播放概览聚合 ========================

    /**
     * 全库播放概览（优化建议 07）：独立的 SQL 聚合，不受 Top N 列表 50 首上限影响。
     *
     * - [totalPlayCount]：全部歌曲 playCount 求和；
     * - [playedSongCount]：playCount > 0 的歌曲数（已听歌曲）；
     * - [recentPlayCount]：`lastPlayed >= since` 的歌曲数（时间窗口由调用方指定）。
     */
    fun queryPlayOverview(recentSince: Long?): PlayOverview {
        val totalPlays = db.rawQuery(
            "SELECT IFNULL(SUM($COL_PLAY_COUNT), 0) FROM $VIEW_VISIBLE_SONGS", null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

        val playedSongs = db.rawQuery(
            "SELECT COUNT(*) FROM $VIEW_VISIBLE_SONGS WHERE $COL_PLAY_COUNT > 0", null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

        val recentPlayed = if (recentSince == null) {
            db.rawQuery(
                "SELECT COUNT(*) FROM $VIEW_VISIBLE_SONGS WHERE $COL_LAST_PLAYED IS NOT NULL", null,
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } else {
            db.rawQuery(
                "SELECT COUNT(*) FROM $VIEW_VISIBLE_SONGS WHERE $COL_LAST_PLAYED >= ?",
                arrayOf(recentSince.toString()),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }

        return PlayOverview(
            totalPlayCount = totalPlays,
            playedSongCount = playedSongs,
            recentPlayCount = recentPlayed,
        )
    }

    // ======================== 喜欢功能 ========================

    /** 切换歌曲喜欢状态 */
    fun toggleLikeSong(songId: Long) {
        db.execSQL(
            "UPDATE $TABLE_SONGS SET $COL_IS_LIKED = CASE WHEN $COL_IS_LIKED = 1 THEN 0 ELSE 1 END " +
                "WHERE $COL_ID = ?",
            arrayOf(songId),
        )
    }

    /** 查询喜欢的歌曲 */
    fun queryLikedSongs(): List<Song> =
        db.query(
            VIEW_VISIBLE_SONGS, null, "$COL_IS_LIKED = 1", null, null, null,
            "title COLLATE NOCASE ASC",
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 查询喜欢的歌曲数量（轻量一致性检查用） */
    fun queryLikedSongCount(): Int =
        db.rawQuery("SELECT COUNT(*) AS c FROM $VIEW_VISIBLE_SONGS WHERE $COL_IS_LIKED = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
}
