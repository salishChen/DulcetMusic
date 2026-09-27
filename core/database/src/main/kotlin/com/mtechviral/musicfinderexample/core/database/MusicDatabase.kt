package com.mtechviral.musicfinderexample.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import java.util.UUID

/**
 * SQLite 建库/迁移定义。
 *
 * 数据库文件名、表结构与原 Flutter 工程 `lib/data/database_helper.dart`
 * 保持兼容（`music_player.db`），因此旧版本的曲库可以被直接复用；
 * 原生版版本 7 增加 remoteId 查询索引（优化建议 10），版本 8 清除
 * 远程歌曲中持久化的认证流地址（优化建议 01）。
 *
 * 五张表：
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
              sourceId TEXT,
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
        db.execSQL("CREATE INDEX idx_songs_remote_id ON songs(remoteId)")
        createArtistsMeta(db)
        createSubsonicConfig(db)
        createRemoteSources(db)
        createVisibleSongsView(db)
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
        if (oldVersion < 7) {
            // 优化建议 10：远程 ID 查询索引（重复扫描/远程歌单同步按 remoteId 查重）
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_remote_id ON songs(remoteId)")
        }
        if (oldVersion < 8) {
            // 优化建议 01：清除已持久化的认证流地址（含 u/s/t 认证参数）——
            // 远程歌曲 path 改为稳定定位符，remoteStreamUrl 清空，播放时临时生成
            scrubRemoteAuthUrls(db)
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE songs ADD COLUMN sourceId TEXT")
            createRemoteSources(db)
            migrateLegacyRemoteSource(db)
        }
        if (oldVersion < 10) createVisibleSongsView(db)
        if (oldVersion in 9..10) {
            db.execSQL("ALTER TABLE remote_sources ADD COLUMN libraryId TEXT NOT NULL DEFAULT ''")
        }
    }

    /**
     * 把远程歌曲的 `path`（旧版存的是带认证参数的流地址）改写为稳定定位符，
     * 并清空 `remoteStreamUrl`。定位符冲突时以行 id 后缀保证唯一。
     */
    private fun scrubRemoteAuthUrls(db: SQLiteDatabase) {
        val used = HashSet<String>()
        val updates = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT id, remoteId, source, title, artist, album FROM songs " +
                "WHERE sourceType = 'subsonic'",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val remoteId = c.getString(1)
                val source = c.getString(2)
                val identityKey = buildString {
                    append((c.getString(3) ?: "").trim().lowercase())
                    append('|')
                    append((c.getString(4) ?: "").trim().lowercase())
                    append('|')
                    append((c.getString(5) ?: "").trim().lowercase())
                }
                var locator = RemoteLocator.subsonic(remoteId, source, identityKey)
                if (!used.add(locator)) {
                    locator = "$locator#$id"
                    used.add(locator)
                }
                updates.add(id to locator)
            }
        }
        for ((id, locator) in updates) {
            db.execSQL(
                "UPDATE songs SET path = ?, remoteStreamUrl = NULL WHERE id = ?",
                arrayOf<Any?>(locator, id),
            )
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

    private fun createRemoteSources(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS remote_sources (
                id TEXT PRIMARY KEY NOT NULL,
                protocol TEXT NOT NULL,
                displayName TEXT NOT NULL,
                intranetUrl TEXT NOT NULL DEFAULT '',
                publicUrl TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '',
                password TEXT NOT NULL DEFAULT '',
                rootPath TEXT NOT NULL DEFAULT '',
                libraryId TEXT NOT NULL DEFAULT '',
                serverIdentity TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS remote_state (
                singletonId INTEGER PRIMARY KEY CHECK(singletonId = 1),
                activeSourceId TEXT REFERENCES remote_sources(id)
            )
        """.trimIndent())
        db.execSQL("INSERT OR IGNORE INTO remote_state(singletonId, activeSourceId) VALUES (1, NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_songs_source_id ON songs(sourceId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_songs_remote_source ON songs(sourceId, remoteId) WHERE sourceId IS NOT NULL AND remoteId IS NOT NULL")
    }

    private fun createVisibleSongsView(db: SQLiteDatabase) {
        db.execSQL("DROP VIEW IF EXISTS visible_songs")
        db.execSQL("""
            CREATE VIEW visible_songs AS
            SELECT s.* FROM songs s
            WHERE s.sourceType IS NULL OR s.sourceType = 'local'
               OR (s.sourceId IS NOT NULL AND s.sourceId =
                   (SELECT activeSourceId FROM remote_state WHERE singletonId = 1))
        """.trimIndent())
    }

    /** Only attach legacy rows when their recorded source agrees with the configured server. */
    private fun migrateLegacyRemoteSource(db: SQLiteDatabase) {
        db.rawQuery("SELECT intranetUrl, publicUrl, username, password, serverName FROM subsonic_config WHERE isActive = 1 LIMIT 1", null).use { cursor ->
            if (!cursor.moveToFirst()) return
            val intranet = cursor.getString(0) ?: ""
            val public = cursor.getString(1) ?: ""
            val username = cursor.getString(2) ?: ""
            val oldPassword = cursor.getString(3) ?: ""
            val password = when {
                CredentialCipher.isEncrypted(oldPassword) -> oldPassword
                oldPassword.isEmpty() -> ""
                else -> CredentialCipher.encrypt(oldPassword) ?: ""
            }
            if (oldPassword.isNotEmpty() && !CredentialCipher.isEncrypted(oldPassword)) {
                // The old table stays for compatibility, so remove its plaintext copy as well.
                db.execSQL(
                    "UPDATE subsonic_config SET password = ? WHERE isActive = 1",
                    arrayOf(password),
                )
            }
            val name = cursor.getString(4) ?: "Subsonic"
            val sourceId = UUID.randomUUID().toString()
            db.execSQL(
                "INSERT INTO remote_sources(id, protocol, displayName, intranetUrl, publicUrl, username, password) VALUES (?, 'SUBSONIC', ?, ?, ?, ?, ?)",
                arrayOf(sourceId, name, intranet, public, username, password),
            )
            db.execSQL("UPDATE remote_state SET activeSourceId = ? WHERE singletonId = 1", arrayOf(sourceId))
            val host = intranet.trim().ifEmpty { public.trim() }
            val sourceTag = "subsonic@${username.trim()}@$host"
            // Duplicate legacy IDs are deliberately left unbound, so the unique index cannot
            // destroy rows or their playlist references while resolving ambiguous history.
            db.execSQL("""
                UPDATE songs SET sourceId = ? WHERE id IN (
                  SELECT MIN(id) FROM songs
                  WHERE sourceType = 'subsonic' AND source = ? AND remoteId IS NOT NULL
                  GROUP BY remoteId HAVING COUNT(*) = 1
                )
            """.trimIndent(), arrayOf(sourceId, sourceTag))
        }
    }

    companion object {
        const val DB_NAME = "music_player.db"
        const val DB_VERSION = 11

        // ---- songs 表列名 ----
        const val TABLE_SONGS = "songs"
        const val VIEW_VISIBLE_SONGS = "visible_songs"
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
        const val COL_SOURCE_ID = "sourceId"
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
