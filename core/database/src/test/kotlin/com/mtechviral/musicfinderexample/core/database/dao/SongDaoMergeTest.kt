package com.mtechviral.musicfinderexample.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 扫描合并语义回归测试（优化建议 03 / 建议 11）。
 *
 * 覆盖文档确认的缺陷：
 * - 同源重扫不得清掉喜欢 / 播放统计 / 歌词 / 缓存；
 * - 同路径改标签重扫保留主键（歌单绑定不丢）；
 * - 跨源同名导入保留用户状态；
 * - 换 Subsonic 服务器不误复用旧服务器的同 remoteId 记录；
 * - 旧行（来源 `subsonic`）按身份键兼容迁移。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongDaoMergeTest {

    private lateinit var db: MusicDatabase
    private lateinit var songDao: SongDao
    private lateinit var playlistDao: PlaylistDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MusicDatabase(context)
        songDao = SongDao(db)
        playlistDao = PlaylistDao(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(
        title: String = "Song",
        path: String = "/music/a.mp3",
        artist: String = "Artist",
        album: String = "Album",
        sourceType: String = Song.SOURCE_TYPE_LOCAL,
        remoteId: String? = null,
        duration: Long? = null,
    ) = Song(
        title = title,
        path = path,
        artist = artist,
        album = album,
        sourceType = sourceType,
        remoteId = remoteId,
        duration = duration,
    )

    @Test
    fun sameSourceRescanUpdatesMetadataButKeepsUserState() {
        songDao.insertSongs(listOf(song(duration = 1000L)), "media_library")
        val row = songDao.querySongByPath("/music/a.mp3")!!
        songDao.toggleLikeSong(row.id!!)
        songDao.incrementPlayCount(row.id!!)
        songDao.updateSongLyrics(row.id!!, "[00:01.00] hello")
        songDao.updateSongCache(row.id!!, "/cache/a.cache")

        // 同源重扫：身份不变，元数据（时长）更新
        songDao.insertSongs(listOf(song(duration = 2000L)), "media_library")

        val after = songDao.querySongById(row.id!!)!!
        assertEquals(row.id, after.id)
        assertEquals(2000L, after.duration)
        assertTrue(after.isLiked)
        assertEquals(1, after.playCount)
        assertEquals("[00:01.00] hello", after.lyrics)
        assertEquals("/cache/a.cache", after.cachedPath)
    }

    @Test
    fun samePathRetagKeepsPrimaryKeyAndPlaylistBinding() {
        songDao.insertSongs(listOf(song(title = "Old", artist = "A1", album = "AL1")), "media_library")
        val row = songDao.querySongByPath("/music/a.mp3")!!
        songDao.toggleLikeSong(row.id!!)
        val playlistId = playlistDao.createPlaylist("P")!!
        playlistDao.addSongToPlaylist(playlistId, row.id!!)

        // 同路径改标签：身份键变化，旧行曾被 INSERT OR REPLACE 删除、歌单绑定级联丢失
        songDao.insertSongs(listOf(song(title = "New", artist = "A2", album = "AL2")), "media_library")

        val rows = songDao.queryAllSongs()
        assertEquals(1, rows.size)
        assertEquals(row.id, rows[0].id)
        assertEquals("New", rows[0].title)
        assertTrue(rows[0].isLiked)
        assertEquals(1, playlistDao.querySongsInPlaylist(playlistId).size)
    }

    @Test
    fun crossSourceSameSongKeepsUserState() {
        songDao.insertSongs(listOf(song(path = "/x/a.mp3")), "folderX")
        val row = songDao.querySongByPath("/x/a.mp3")!!
        songDao.toggleLikeSong(row.id!!)
        songDao.incrementPlayCount(row.id!!)

        // 跨来源同名导入（不同路径）：后来者的元数据覆盖，但用户状态保留
        songDao.insertSongs(listOf(song(path = "/y/b.mp3", duration = 5000L)), "folderY")

        val rows = songDao.queryAllSongs()
        assertEquals(1, rows.size)
        assertEquals(row.id, rows[0].id)
        assertEquals("/y/b.mp3", rows[0].path)
        assertTrue(rows[0].isLiked)
        assertEquals(1, rows[0].playCount)
    }

    @Test
    fun differentServerSameRemoteIdDoesNotMerge() {
        // 服务器 A 的歌（旧库来源 'subsonic'）
        songDao.insertSongs(
            listOf(
                song(
                    title = "SongA", artist = "A", album = "AL",
                    path = "subsonic://1@aaa",
                    sourceType = Song.SOURCE_TYPE_SUBSONIC, remoteId = "1",
                ),
            ),
            "subsonic",
        )
        // 换服务器：同 remoteId 但不同歌曲（身份不同）→ 不得误合并
        songDao.insertSongs(
            listOf(
                song(
                    title = "SongB", artist = "B", album = "BL",
                    path = "subsonic://1@bbb",
                    sourceType = Song.SOURCE_TYPE_SUBSONIC, remoteId = "1",
                ),
            ),
            "subsonic@user@hostB",
        )

        assertEquals(2, songDao.queryAllSongs().size)
    }

    @Test
    fun legacySourceRowMigratesOnSameSongRescan() {
        songDao.insertSongs(
            listOf(
                song(
                    title = "Same", sourceType = Song.SOURCE_TYPE_SUBSONIC, remoteId = "9",
                ),
            ),
            "subsonic",
        )
        val row = songDao.querySongByPath("/music/a.mp3")!!
        songDao.toggleLikeSong(row.id!!)

        // 新来源标识重扫同一首歌（身份一致）：合并并迁移来源
        songDao.insertSongs(
            listOf(
                song(
                    title = "Same", sourceType = Song.SOURCE_TYPE_SUBSONIC, remoteId = "9",
                ),
            ),
            "subsonic@user@host",
        )

        val rows = songDao.queryAllSongs()
        assertEquals(1, rows.size)
        assertEquals(row.id, rows[0].id)
        assertEquals("subsonic@user@host", rows[0].source)
        assertTrue(rows[0].isLiked)
    }
}
