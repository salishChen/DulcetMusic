package com.mtechviral.musicfinderexample.core.database.dao

import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SONGS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.VIEW_VISIBLE_SONGS
import com.mtechviral.musicfinderexample.core.database.SongMapper
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 艺术家聚合查询（GROUP BY artist）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的 `queryArtists` / `querySongsByArtist`。
 *
 * 注：`queryLikedArtists` 已随「喜欢只针对单曲」的需求变更删除。
 */
class ArtistDao(private val musicDatabase: MusicDatabase) {

    fun queryArtists(): List<Artist> = query("""
        SELECT grouped.name, grouped.songCount, grouped.albumCount,
               cover.path AS coverSongPath, cover.cachedArtworkPath AS coverArtworkPath,
               cover.coverArtId
        FROM (
            SELECT artist AS name, COUNT(*) AS songCount,
                   COUNT(DISTINCT album) AS albumCount,
                   COALESCE(MAX(CASE WHEN hasArtwork = 1 THEN id END),
                            MAX(CASE WHEN cachedArtworkPath IS NOT NULL THEN id END),
                            MAX(CASE WHEN coverArtId IS NOT NULL THEN id END), MAX(id)) AS coverSongId
            FROM visible_songs
            WHERE artist IS NOT NULL AND artist != ''
            GROUP BY artist
        ) grouped JOIN songs cover ON cover.id = grouped.coverSongId
        ORDER BY grouped.name COLLATE NOCASE ASC
    """.trimIndent())

    /** 艺术家全部歌曲（专辑升序 + 音轨号升序） */
    fun querySongsByArtist(artist: String): List<Song> =
        musicDatabase.readableDatabase.query(
            VIEW_VISIBLE_SONGS, null, "$COL_ARTIST = ?", arrayOf(artist), null, null,
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
