package com.mtechviral.musicfinderexample.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 建库/迁移定义。
 *
 * 数据库文件名、表结构与版本号与原 Flutter 工程 `lib/data/database_helper.dart`
 * 完全一致（`music_player.db`，version = 6），因此旧版本的曲库可以被直接复用。
 *
 * 四张表：
 * - songs：歌曲表（扫描入库的全部元数据）
 * - playlists：歌单表（歌单名）
 * - playlist_songs：歌单-歌曲绑定子表（含歌单内排序）
 * - artists_meta：艺术家元数据（Subsonic 艺术家 id / 封面）
 * - subsonic_config：Subsonic 服务器配置
 */
class MusicDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 启用外键，保证删除歌单/歌曲时级联清理绑定子表
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE songs (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT NOT NULL,
              path TEXT NOT NULL UNIQUE,
              artist TEXT,
              album TEXT,
              albumArtist TEXT,
              trackNumber INTEGER,
              duration INTEGER,
              bitrate INTEGER,
              sampleRate INTEGER,
              bitDepth INTEGER,
              size INTEGER,
              format TEXT,
              codec TEXT,
              dateAdded INTEGER,
              dateModified INTEGER,
              hasArtwork INTEGER DEFAULT 0,
              lyrics TEXT,
              source TEXT,
              sourceType TEXT DEFAULT 'local',
              remoteId TEXT,
              remoteStreamUrl TEXT,
              cachedPath TEXT,
              cacheTimestamp INTEGER,
              cachedArtworkPath TEXT,
              coverArtId TEXT,
              playCount INTEGER DEFAULT 0,
              isLiked INTEGER DEFAULT 0,
              lastPlayed INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playlists (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE playlist_songs (
              playlistId INTEGER NOT NULL,
              songId INTEGER NOT NULL,
              position INTEGER NOT NULL,
              PRIMARY KEY (playlistId, songId),
              FOREIGN KEY (playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
              FOREIGN KEY (songId) REFERENCES songs(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_songs_album ON songs(album)")
        db.execSQL("CREATE INDEX idx_songs_artist ON songs(artist)")
        createArtistsMeta(db)
        createSubsonicConfig(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE songs ADD COLUMN lyrics TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE songs ADD COLUMN source TEXT")
        }
        if (oldVersion < 4) {
            // Subsonic 远程音乐支持
            db.execSQL("ALTER TABLE songs ADD COLUMN sourceType TEXT DEFAULT 'local'")
            db.execSQL("ALTER TABLE songs ADD COLUMN remoteId TEXT")
            db.execSQL("ALTER TABLE songs ADD COLUMN remoteStreamUrl TEXT")
            db.execSQL("ALTER TABLE songs ADD COLUMN cachedPath TEXT")
            db.execSQL("ALTER TABLE songs ADD COLUMN cacheTimestamp INTEGER")
            createSubsonicConfig(db)
        }
        if (oldVersion < 5) {
            // 远程封面缓存支持
            db.execSQL("ALTER TABLE songs ADD COLUMN cachedArtworkPath TEXT")
            db.execSQL("ALTER TABLE songs ADD COLUMN coverArtId TEXT")
        }
        if (oldVersion < 6) {
            // 播放统计和喜欢功能
            db.execSQL("ALTER TABLE songs ADD COLUMN playCount INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE songs ADD COLUMN isLiked INTEGER DEFAULT 0")
            db.execSQL("ALTER TABLE songs ADD COLUMN lastPlayed INTEGER")
            createArtistsMeta(db)
        }
    }

    private fun createArtistsMeta(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS artists_meta (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              artistId TEXT,
              coverArtId TEXT,
              cachedArtworkPath TEXT,
              isLiked INTEGER DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    private fun createSubsonicConfig(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS subsonic_config (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              intranetUrl TEXT NOT NULL,
              publicUrl TEXT NOT NULL,
              username TEXT NOT NULL,
              password TEXT NOT NULL,
              serverName TEXT,
              isActive INTEGER DEFAULT 1
            )
            """.trimIndent(),
        )
    }

    companion object {
        const val DB_NAME = "music_player.db"
        const val DB_VERSION = 6

        // ---- songs 表列名 ----
        const val TABLE_SONGS = "songs"
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_PATH = "path"
        const val COL_ARTIST = "artist"
        const val COL_ALBUM = "album"
        const val COL_ALBUM_ARTIST = "albumArtist"
        const val COL_TRACK_NUMBER = "trackNumber"
        const val COL_DURATION = "duration"
        const val COL_BITRATE = "bitrate"
        const val COL_SAMPLE_RATE = "sampleRate"
        const val COL_BIT_DEPTH = "bitDepth"
        const val COL_SIZE = "size"
        const val COL_FORMAT = "format"
        const val COL_CODEC = "codec"
        const val COL_DATE_ADDED = "dateAdded"
        const val COL_DATE_MODIFIED = "dateModified"
        const val COL_HAS_ARTWORK = "hasArtwork"
        const val COL_LYRICS = "lyrics"
        const val COL_SOURCE = "source"
        const val COL_SOURCE_TYPE = "sourceType"
        const val COL_REMOTE_ID = "remoteId"
        const val COL_REMOTE_STREAM_URL = "remoteStreamUrl"
        const val COL_CACHED_PATH = "cachedPath"
        const val COL_CACHE_TIMESTAMP = "cacheTimestamp"
        const val COL_CACHED_ARTWORK_PATH = "cachedArtworkPath"
        const val COL_COVER_ART_ID = "coverArtId"
        const val COL_PLAY_COUNT = "playCount"
        const val COL_IS_LIKED = "isLiked"
        const val COL_LAST_PLAYED = "lastPlayed"

        // ---- 其它表 ----
        const val TABLE_PLAYLISTS = "playlists"
        const val TABLE_PLAYLIST_SONGS = "playlist_songs"
        const val TABLE_ARTISTS_META = "artists_meta"
        const val TABLE_SUBSONIC_CONFIG = "subsonic_config"
    }
}
