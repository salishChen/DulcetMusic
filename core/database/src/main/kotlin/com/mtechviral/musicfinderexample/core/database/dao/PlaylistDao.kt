package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import android.database.sqlite.SQLiteConstraintException
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_PLAYLISTS
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_PLAYLIST_SONGS
import com.mtechviral.musicfinderexample.core.database.SongMapper
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.longOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import android.util.Log

/**
 * 歌单数据访问对象（playlists / playlist_songs）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的「歌单」分区。
 * 删除歌单时依赖外键 ON DELETE CASCADE 级联清理绑定记录。
 */
class PlaylistDao(private val musicDatabase: MusicDatabase) {

    /** 查询全部歌单（含歌曲数） */
    fun queryPlaylists(): List<Playlist> =
        musicDatabase.readableDatabase.rawQuery(
            """
            SELECT p.id, p.name, COUNT(s.id) AS songCount
            FROM playlists p
            LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
            LEFT JOIN visible_songs s ON s.id = ps.songId
            GROUP BY p.id
            ORDER BY p.id ASC
            """.trimIndent(),
            null,
        ).use { c ->
            c.mapAll { cur ->
                Playlist(
                    id = cur.longOrNull(COL_ID),
                    name = cur.stringOrNull("name") ?: "",
                    songCount = cur.intOrNull("songCount") ?: 0,
                )
            }
        }

    /** 新建歌单，重名返回 null */
    fun createPlaylist(name: String): Long? = try {
        musicDatabase.writableDatabase.insertOrThrow(
            TABLE_PLAYLISTS, null,
            ContentValues().apply { put("name", name) },
        )
    } catch (e: SQLiteConstraintException) {
        Log.w(TAG, "createPlaylist failed: ${e.message}")
        null
    }

    /** 删除歌单（级联删除绑定记录） */
    fun deletePlaylist(id: Long) {
        musicDatabase.writableDatabase.delete(TABLE_PLAYLISTS, "$COL_ID = ?", arrayOf(id.toString()))
    }

    /** 查询歌单内全部歌曲（按 position 排序） */
    fun querySongsInPlaylist(playlistId: Long): List<Song> =
        musicDatabase.readableDatabase.rawQuery(
            """
            SELECT s.* FROM visible_songs s
            INNER JOIN playlist_songs ps ON ps.songId = s.id
            WHERE ps.playlistId = ?
            ORDER BY ps.position ASC
            """.trimIndent(),
            arrayOf(playlistId.toString()),
        ).use { c -> c.mapAll { SongMapper.fromCursor(it) } }

    /** 添加歌曲到歌单（已存在返回 false） */
    fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean {
        val db = musicDatabase.writableDatabase
        db.query(
            TABLE_PLAYLIST_SONGS, arrayOf("songId"),
            "playlistId = ? AND songId = ?",
            arrayOf(playlistId.toString(), songId.toString()), null, null, null,
        ).use { c ->
            if (c.moveToFirst()) return false
        }
        val next = db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 AS next FROM $TABLE_PLAYLIST_SONGS WHERE playlistId = ?",
            arrayOf(playlistId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        db.insert(
            TABLE_PLAYLIST_SONGS, null,
            ContentValues().apply {
                put("playlistId", playlistId)
                put("songId", songId)
                put("position", next)
            },
        )
        return true
    }

    /** 从歌单移除歌曲 */
    fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        musicDatabase.writableDatabase.delete(
            TABLE_PLAYLIST_SONGS,
            "playlistId = ? AND songId = ?",
            arrayOf(playlistId.toString(), songId.toString()),
        )
    }

    /** 按歌单名查询（Subsonic 歌单同步时用于去重） */
    fun findByName(name: String): Playlist? =
        musicDatabase.readableDatabase.query(
            TABLE_PLAYLISTS, null, "name = ?", arrayOf(name), null, null, null,
        ).use { c ->
            if (c.moveToFirst()) {
                Playlist(id = c.longOrNull(COL_ID), name = c.stringOrNull("name") ?: "")
            } else {
                null
            }
        }

    private companion object {
        const val TAG = "PlaylistDao"
    }
}
