package com.mtechviral.musicfinderexample.core.database.dao

import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SONGS
import com.mtechviral.musicfinderexample.core.database.SongMapper
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 艺术家聚合查询（GROUP BY artist）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的 `queryArtists` / `querySongsByArtist`
 * / `queryLikedArtists`。
 */
class ArtistDao(private val musicDatabase: MusicDatabase) {

    fun queryArtists(): List<Artist> = query("""
        SELECT artist AS name,
               COUNT(*) AS songCount,
               COUNT(DISTINCT album) AS albumCount,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId
        FROM songs
        WHERE artist IS NOT NULL AND artist != ''
        GROUP BY artist
        ORDER BY artist COLLATE NOCASE ASC
    """.trimIndent())

    fun queryLikedArtists(): List<Artist> = query("""
        SELECT artist AS name,
               COUNT(*) AS songCount,
               COUNT(DISTINCT album) AS albumCount,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId
        FROM songs
        WHERE artist IS NOT NULL AND artist != '' AND isLiked = 1
        GROUP BY artist
        ORDER BY artist COLLATE NOCASE ASC
    """.trimIndent())

    /** 艺术家全部歌曲（专辑升序 + 音轨号升序） */
    fun querySongsByArtist(artist: String): List<Song> =
        musicDatabase.readableDatabase.query(
            TABLE_SONGS, null, "$COL_ARTIST = ?", arrayOf(artist), null, null,
            "album COLLATE NOCASE ASC, trackNumber ASC",
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    private fun query(sql: String): List<Artist> =
        musicDatabase.readableDatabase.rawQuery(sql, null).use { c ->
            c.mapAll { cur ->
                Artist(
                    name = cur.stringOrNull("name") ?: "未知艺术家",
                    songCount = cur.intOrNull("songCount") ?: 0,
                    albumCount = cur.intOrNull("albumCount") ?: 0,
                    coverSongPath = cur.stringOrNull("coverSongPath"),
                    coverArtworkPath = cur.stringOrNull("coverArtworkPath"),
                    coverArtId = cur.stringOrNull("coverArtId"),
                )
            }
        }
}
