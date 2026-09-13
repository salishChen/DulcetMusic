package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_ARTISTS_META
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.longOrNull
import com.mtechviral.musicfinderexample.core.database.mapAll
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.ArtistMeta

/**
 * 艺术家元数据数据访问对象（artists_meta 表）。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的「艺术家元数据」分区。
 * 原实现以 `INSERT OR REPLACE` 写入，这里保持一致。
 */
class ArtistMetaDao(private val musicDatabase: MusicDatabase) {

    private val db get() = musicDatabase.writableDatabase

    /** 插入或更新艺术家元数据 */
    fun upsertArtistMeta(name: String, artistId: String?, coverArtId: String?) {
        db.execSQL(
            "INSERT OR REPLACE INTO $TABLE_ARTISTS_META (name, artistId, coverArtId) VALUES (?, ?, ?)",
            arrayOf(name, artistId, coverArtId),
        )
    }

    /** 批量插入艺术家元数据（扫描 Subsonic 艺术家后调用） */
    fun upsertArtistMetaBatch(artists: List<Triple<String, String?, String?>>) {
        if (artists.isEmpty()) return
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO $TABLE_ARTISTS_META (name, artistId, coverArtId) VALUES (?, ?, ?)",
            )
            for ((name, artistId, coverArtId) in artists) {
                stmt.clearBindings()
                stmt.bindString(1, name)
                if (artistId == null) stmt.bindNull(2) else stmt.bindString(2, artistId)
                if (coverArtId == null) stmt.bindNull(3) else stmt.bindString(3, coverArtId)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 更新艺术家封面缓存路径 */
    fun updateArtistArtworkCache(artistName: String, artworkPath: String) {
        db.update(
            TABLE_ARTISTS_META,
            ContentValues().apply { put("cachedArtworkPath", artworkPath) },
            "name = ?",
            arrayOf(artistName),
        )
    }

    /** 切换艺术家喜欢状态 */
    fun toggleLikeArtist(artistName: String) {
        db.execSQL(
            "UPDATE $TABLE_ARTISTS_META SET isLiked = CASE WHEN isLiked = 1 THEN 0 ELSE 1 END WHERE name = ?",
            arrayOf(artistName),
        )
    }

    /** 查询单个艺术家元数据 */
    fun queryArtistMeta(name: String): ArtistMeta? =
        db.query(TABLE_ARTISTS_META, null, "name = ?", arrayOf(name), null, null, null, "1")
            .use { c -> if (c.moveToFirst()) mapRow(c) else null }

    /** 查询所有艺术家元数据（按名称排序） */
    fun queryAllArtistMeta(): List<ArtistMeta> =
        db.query(TABLE_ARTISTS_META, null, null, null, null, null, "name COLLATE NOCASE ASC")
            .use { c -> c.mapAll { mapRow(it) } }

    private fun mapRow(c: android.database.Cursor): ArtistMeta = ArtistMeta(
        id = c.longOrNull(COL_ID),
        name = c.stringOrNull("name") ?: "",
        artistId = c.stringOrNull("artistId"),
        coverArtId = c.stringOrNull("coverArtId"),
        cachedArtworkPath = c.stringOrNull("cachedArtworkPath"),
        isLiked = (c.intOrNull("isLiked") ?: 0) == 1,
    )
}
