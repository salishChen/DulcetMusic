package com.mtechviral.musicfinderexample.core.database.dao

import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ALBUM_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ARTIST
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SONGS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.VIEW_VISIBLE_SONGS
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.longOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 专辑聚合查询（GROUP BY 专辑名 + 专辑艺术家，在 SQL 层聚合，不单独入库）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的 `queryAlbums` / `queryAlbumsByArtist`。
 *
 * 优化建议 10：按「专辑名 + 专辑艺术家（albumArtist 兜底 artist）」复合键分组，
 * 不同艺术家的同名专辑不再混为一张。
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
               COALESCE(albumArtist, artist) AS artist,
               MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId,
               COUNT(*) AS songCount
        FROM visible_songs
        WHERE album IS NOT NULL AND album != ''
        GROUP BY album, COALESCE(albumArtist, artist)
        ORDER BY album COLLATE NOCASE ASC, artist COLLATE NOCASE ASC
    """.trimIndent())

    /** 指定艺术家的专辑列表（含其担任专辑艺术家的合辑） */
    fun queryAlbumsByArtist(artist: String): List<Album> = query("""
        SELECT album AS title,
               COALESCE(albumArtist, artist) AS artist,
               MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
               MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
               MAX(cachedArtworkPath) AS coverArtworkPath,
               MAX(coverArtId) AS coverArtId,
               COUNT(*) AS songCount
        FROM visible_songs
        WHERE (artist = ? OR albumArtist = ?) AND album IS NOT NULL AND album != ''
        GROUP BY album, COALESCE(albumArtist, artist)
        ORDER BY album COLLATE NOCASE ASC, artist COLLATE NOCASE ASC
    """.trimIndent(), arrayOf(artist, artist))

    /**
     * 专辑内全部歌曲（按音轨号排序）。
     *
     * 优化建议 10：按「专辑名 + 专辑艺术家」复合键过滤；
     * [artist] 为 null 表示专辑艺术家未知（与聚合分组口径一致）。
     */
    fun querySongsByAlbum(album: String, artist: String?): List<Song> {
        val artistKey = "COALESCE($COL_ALBUM_ARTIST, $COL_ARTIST)"
        val where = if (artist == null) {
            "$COL_ALBUM = ? AND ($artistKey IS NULL OR $artistKey = '')"
        } else {
            "$COL_ALBUM = ? AND $artistKey = ?"
        }
        val args = if (artist == null) arrayOf(album) else arrayOf(album, artist)
        return musicDatabase.readableDatabase.query(
            VIEW_VISIBLE_SONGS, null, where, args, null, null,
            "trackNumber ASC, title COLLATE NOCASE ASC",
        ).use { c ->
            c.mapAll { com.mtechviral.musicfinderexample.core.database.SongMapper.fromCursor(it) }
        }
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
