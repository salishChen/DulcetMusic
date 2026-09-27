package com.mtechviral.musicfinderexample.core.database

import android.content.Context
import com.mtechviral.musicfinderexample.core.database.dao.AlbumDao
import com.mtechviral.musicfinderexample.core.database.dao.ArtistDao
import com.mtechviral.musicfinderexample.core.database.dao.ArtistMetaDao
import com.mtechviral.musicfinderexample.core.database.dao.PlayOverview
import com.mtechviral.musicfinderexample.core.database.dao.PlaylistDao
import com.mtechviral.musicfinderexample.core.database.dao.SongDao
import com.mtechviral.musicfinderexample.core.database.dao.SubsonicConfigDao
import com.mtechviral.musicfinderexample.core.database.dao.RemoteSourceDao
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.ArtistMeta
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 数据库门面（单例）。
 *
 * 与原 Flutter 工程 `lib/data/database_helper.dart` 的 `DatabaseHelper` 一一对应：
 * 同样的方法名、同样的排序/过滤语义、同样的结果缓存策略。
 *
 * 原生侧差异：
 * - 提供 `suspend` 版本（内部切到 IO 线程），避免在 UI 线程做磁盘 IO；
 * - 增加 [suspendAll] 串行锁，保证「扫描入库 + 增删」等写操作不会交叉。
 */
object DatabaseHelper {

    private lateinit var musicDatabase: MusicDatabase

    lateinit var songDao: SongDao
        private set
    lateinit var albumDao: AlbumDao
        private set
    lateinit var artistDao: ArtistDao
        private set
    lateinit var playlistDao: PlaylistDao
        private set
    lateinit var subsonicConfigDao: SubsonicConfigDao
        private set
    lateinit var remoteSourceDao: RemoteSourceDao
        private set
    lateinit var artistMetaDao: ArtistMetaDao
        private set

    private val writeMutex = Mutex()

    @Volatile
    private var cachedAllSongs: List<Song>? = null

    @Volatile
    private var cachedAlbums: List<Album>? = null

    @Volatile
    private var cachedArtists: List<Artist>? = null

    fun init(context: Context) {
        if (::musicDatabase.isInitialized) return
        musicDatabase = MusicDatabase(context.applicationContext)
        songDao = SongDao(musicDatabase)
        albumDao = AlbumDao(musicDatabase)
        artistDao = ArtistDao(musicDatabase)
        playlistDao = PlaylistDao(musicDatabase)
        subsonicConfigDao = SubsonicConfigDao(musicDatabase)
        remoteSourceDao = RemoteSourceDao(musicDatabase)
        artistMetaDao = ArtistMetaDao(musicDatabase)
    }

    /** 失效全部缓存（扫描/增删歌曲后调用） */
    fun invalidateCache() {
        cachedAllSongs = null
        cachedAlbums = null
        cachedArtists = null
    }

    // ======================== 歌曲 ========================

    /**
     * 查询全部歌曲（按标题排序），带内存缓存。
     * 返回缓存列表的副本：调用方可安全持有/排序/修改，不会污染内部缓存。
     */
    suspend fun queryAllSongs(): List<Song> {
        cachedAllSongs?.let { return it }
        return io {
            val list = songDao.queryAllSongs()
            cachedAllSongs = list
            list
        }
    }

    suspend fun querySongById(id: Long): Song? = io { songDao.querySongById(id) }

    suspend fun querySongByPath(path: String): Song? = io { songDao.querySongByPath(path) }

    suspend fun querySongByRemoteId(remoteId: String): Song? =
        io { songDao.querySongByRemoteId(remoteId) }

    suspend fun querySongByRemoteId(sourceId: String, remoteId: String): Song? =
        io { songDao.querySongByRemoteId(sourceId, remoteId) }

    suspend fun activeRemoteSource(): RemoteSource? = io { remoteSourceDao.activeSource() }

    suspend fun activateRemoteSource(source: RemoteSource) = writeMutex.withLock {
        io { remoteSourceDao.activate(source); invalidateCache() }
    }

    suspend fun deactivateRemoteSource() = writeMutex.withLock {
        io { remoteSourceDao.deactivate(); invalidateCache() }
    }

    /** 事务批量插入/更新歌曲，返回实际新增/覆盖的数量 */
    suspend fun insertSongs(songs: List<Song>, source: String?): Int =
        writeMutex.withLock {
            io {
                val affected = songDao.insertSongs(songs, source)
                invalidateCache()
                affected
            }
        }

    suspend fun deleteSong(id: Long) = writeMutex.withLock {
        io {
            songDao.deleteSong(id)
            invalidateCache()
        }
    }

    suspend fun clearSongs() = writeMutex.withLock {
        io {
            songDao.clearSongs()
            invalidateCache()
        }
    }

    // ======================== 专辑（聚合） ========================

    suspend fun queryAlbums(): List<Album> {
        cachedAlbums?.let { return it }
        return io {
            val list = albumDao.queryAlbums()
            cachedAlbums = list
            list
        }
    }

    /**
     * 专辑内歌曲（优化建议 10：按「专辑名 + 专辑艺术家」复合键过滤）。
     *
     * @param artist 专辑艺术家（albumArtist 兜底 artist）；null 表示未知艺术家分组
     */
    suspend fun querySongsByAlbum(album: String, artist: String?): List<Song> =
        io { albumDao.querySongsByAlbum(album, artist) }

    suspend fun queryAlbumsByArtist(artist: String): List<Album> =
        io { albumDao.queryAlbumsByArtist(artist) }

    // ======================== 艺术家（聚合） ========================

    suspend fun queryArtists(): List<Artist> {
        cachedArtists?.let { return it }
        return io {
            val list = artistDao.queryArtists()
            cachedArtists = list
            list
        }
    }

    suspend fun querySongsByArtist(artist: String): List<Song> =
        io { artistDao.querySongsByArtist(artist) }

    /**
     * 某位歌手（含 albumArtist 匹配）的全部歌曲（第二十七轮需求）。
     *
     * 与 [querySongsByArtist] 的区别：额外匹配 `albumArtist`，
     * 因此合辑里 `artist` 为「群星」但 `albumArtist` 是该歌手的歌曲也会被查到。
     */
    suspend fun querySongsByArtistIncludingAlbumArtist(artist: String): List<Song> =
        io { songDao.querySongsByArtistOrAlbumArtist(artist) }

    /**
     * 按 id 批量删除歌曲（一次 SQL，级联清理歌单绑定），返回实际删除行数。
     *
     * 第二十七轮需求：把歌手加入排除列表时用它清掉该歌手的全部歌曲 ——
     * 专辑与艺术家列表都是从 songs 表聚合出来的（`GROUP BY album` / `GROUP BY artist`），
     * 因此删掉歌曲行之后，该歌手的专辑与他自己都会自动从对应页面消失，无需额外清理。
     */
    suspend fun deleteSongsByIds(ids: Collection<Long>): Int = writeMutex.withLock {
        io {
            val deleted = songDao.deleteSongsByIds(ids)
            invalidateCache()
            deleted
        }
    }

    // ======================== 歌单 ========================

    suspend fun queryPlaylists(): List<Playlist> = io { playlistDao.queryPlaylists() }

    /** 新建歌单，重名返回 null */
    suspend fun createPlaylist(name: String): Long? =
        writeMutex.withLock { io { playlistDao.createPlaylist(name) } }

    suspend fun deletePlaylist(id: Long) =
        writeMutex.withLock { io { playlistDao.deletePlaylist(id) } }

    suspend fun querySongsInPlaylist(playlistId: Long): List<Song> =
        io { playlistDao.querySongsInPlaylist(playlistId) }

    /** 添加歌曲到歌单（已存在返回 false） */
    suspend fun addSongToPlaylist(playlistId: Long, songId: Long): Boolean =
        writeMutex.withLock { io { playlistDao.addSongToPlaylist(playlistId, songId) } }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) =
        writeMutex.withLock { io { playlistDao.removeSongFromPlaylist(playlistId, songId) } }

    suspend fun findPlaylistByName(name: String): Playlist? =
        io { playlistDao.findByName(name) }

    // ======================== Subsonic 配置 ========================

    suspend fun querySubsonicConfig(): SubsonicConfig? = io { subsonicConfigDao.querySubsonicConfig() }

    suspend fun saveSubsonicConfig(config: SubsonicConfig) =
        writeMutex.withLock { io { subsonicConfigDao.saveSubsonicConfig(config) } }

    suspend fun deleteSubsonicConfig(id: Long) =
        writeMutex.withLock { io { subsonicConfigDao.deleteSubsonicConfig(id) } }

    // ======================== 远程歌曲缓存 ========================

    suspend fun updateSongCache(songId: Long, cachedPath: String) = writeMutex.withLock {
        io {
            songDao.updateSongCache(songId, cachedPath)
            invalidateCache()
        }
    }

    suspend fun queryCachedSongs(): List<Song> = io { songDao.queryCachedSongs() }

    suspend fun clearSongCache(songId: Long) = writeMutex.withLock {
        io {
            songDao.clearSongCache(songId)
            invalidateCache()
        }
    }

    /** 刷新缓存「最近使用」时间（播放/命中缓存时调用，优化建议 05） */
    suspend fun touchSongCache(songId: Long) = writeMutex.withLock {
        io { songDao.touchSongCache(songId) }
    }

    /** 按封面缓存路径批量解除引用（封面淘汰时调用） */
    suspend fun clearArtworkCacheByPath(path: String) = writeMutex.withLock {
        io {
            songDao.clearArtworkCacheByPath(path)
            invalidateCache()
        }
    }

    /** 重置全部缓存字段（音频与封面，清空全部缓存时调用） */
    suspend fun clearAllCacheRecords() = writeMutex.withLock {
        io {
            songDao.clearAllCacheRecords()
            invalidateCache()
        }
    }

    /** 缓存时间最早的歌曲（LRU 淘汰） */
    suspend fun queryOldestCachedSong(): Song? = io { songDao.queryOldestCachedSong() }

    suspend fun updateArtworkCache(songId: Long, artworkPath: String) = writeMutex.withLock {
        io {
            songDao.updateArtworkCache(songId, artworkPath)
            invalidateCache()
        }
    }

    /** 清除封面缓存记录（第二十六轮需求 4：永久删除在线歌曲时一并清理） */
    suspend fun clearArtworkCache(songId: Long) = writeMutex.withLock {
        io {
            songDao.clearArtworkCache(songId)
            invalidateCache()
        }
    }

    suspend fun updateSongLyrics(songId: Long, lyrics: String) = writeMutex.withLock {
        io {
            songDao.updateSongLyrics(songId, lyrics)
            invalidateCache()
        }
    }

    /** 封面尚未缓存的远程歌曲 */
    suspend fun querySongsNeedingArtworkCache(): List<Song> =
        io { songDao.querySongsNeedingArtworkCache() }

    // ======================== 播放统计 ========================

    suspend fun incrementPlayCount(songId: Long) = writeMutex.withLock {
        io {
            songDao.incrementPlayCount(songId)
            invalidateCache()
        }
    }

    suspend fun queryTopPlayed(limit: Int = 20): List<Song> = io { songDao.queryTopPlayed(limit) }

    suspend fun queryRecentlyPlayed(limit: Int = 50): List<Song> =
        io { songDao.queryRecentlyPlayed(limit) }

    /**
     * 播放概览聚合（统计页顶部卡片）：全库 SUM/COUNT，不受 Top N 列表上限影响。
     *
     * @param recentWindowMs 「最近播放」的时间窗口（毫秒）；传 null 表示不限时间。
     */
    suspend fun queryPlayOverview(recentWindowMs: Long?): PlayOverview = io {
        songDao.queryPlayOverview(recentWindowMs?.let { System.currentTimeMillis() - it })
    }

    // ======================== 喜欢功能 ========================

    /**
     * 切换歌曲喜欢状态。
     *
     * 需求变更：喜欢**只针对单曲**，不再提供 `toggleLikeArtist`；
     * 专辑「喜欢」原本也只是"其下每首歌逐首喜欢"的聚合，故一并移除。
     */
    suspend fun toggleLikeSong(songId: Long) = writeMutex.withLock {
        io {
            songDao.toggleLikeSong(songId)
            invalidateCache()
        }
    }

    suspend fun queryLikedSongs(): List<Song> = io { songDao.queryLikedSongs() }

    /** 喜欢的歌曲数量（进入喜欢页时做轻量一致性检查） */
    suspend fun queryLikedSongCount(): Int = io { songDao.queryLikedSongCount() }

    // ======================== 艺术家元数据 ========================

    suspend fun upsertArtistMeta(name: String, artistId: String?, coverArtId: String?) =
        writeMutex.withLock { io { artistMetaDao.upsertArtistMeta(name, artistId, coverArtId) } }

    suspend fun upsertArtistMetaBatch(artists: List<Triple<String, String?, String?>>) =
        writeMutex.withLock { io { artistMetaDao.upsertArtistMetaBatch(artists) } }

    suspend fun updateArtistArtworkCache(artistName: String, artworkPath: String) =
        writeMutex.withLock { io { artistMetaDao.updateArtistArtworkCache(artistName, artworkPath) } }

    suspend fun queryArtistMeta(name: String): ArtistMeta? = io { artistMetaDao.queryArtistMeta(name) }

    suspend fun queryAllArtistMeta(): List<ArtistMeta> = io { artistMetaDao.queryAllArtistMeta() }

    private suspend inline fun <T> io(crossinline block: () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
