package com.mtechviral.musicfinderexample.core.database.dao

import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SONGS
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.longOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 专辑聚合查询（GROUP BY album，在 SQL 层聚合，不单独入库）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的 `queryAlbums` / `queryAlbumsByArtist`。
 *
 * 注：`queryLikedAlbums` 已随「喜欢只针对单曲」的需求变更删除。
 */
class AlbumDao(private val musicDatabase: MusicDatabase) {

    /**
     * 全部专辑。
     * 封面优先取「含内嵌封面的歌曲」，其次取缓存的远程封面。
     */
    fun queryAlbums(): List<Album> = query("""
        SELECT album AS title,
               COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
               MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId,
               COUNT(*) AS songCount
        FROM songs
        WHERE album IS NOT NULL AND album != ''
        GROUP BY album
        ORDER BY album COLLATE NOCASE ASC
    """.trimIndent())

    /** 指定艺术家的专辑列表 */
    fun queryAlbumsByArtist(artist: String): List<Album> = query("""
        SELECT album AS title,
               COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
               MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId,
               COUNT(*) AS songCount
        FROM songs
        WHERE artist = ? AND album IS NOT NULL AND album != ''
        GROUP BY album
        ORDER BY album COLLATE NOCASE ASC
    """.trimIndent(), arrayOf(artist))

    /** 专辑内全部歌曲（按音轨号排序） */
    fun querySongsByAlbum(album: String): List<Song> =
        musicDatabase.readableDatabase.query(
            TABLE_SONGS, null, "$COL_ALBUM = ?", arrayOf(album), null, null,
            "trackNumber ASC, title COLLATE NOCASE ASC",
        ).use { c ->
            c.mapAll { com.mtechviral.musicfinderexample.core.database.SongMapper.fromCursor(it) }
        }

    private fun query(sql: String, args: Array<String>? = null): List<Album> =
        musicDatabase.readableDatabase.rawQuery(sql, args).use { c ->
            c.mapAll { cur ->
                Album(
                    title = cur.stringOrNull("title") ?: "未知专辑",
                    artist = cur.stringOrNull("artist"),
                    coverSongId = cur.longOrNull("coverSongId"),
                    coverSongPath = cur.stringOrNull("coverSongPath"),
                    coverArtworkPath = cur.stringOrNull("coverArtworkPath"),
                    coverArtId = cur.stringOrNull("coverArtId"),
                    songCount = cur.intOrNull("songCount") ?: 0,
                )
            }
        }
}
