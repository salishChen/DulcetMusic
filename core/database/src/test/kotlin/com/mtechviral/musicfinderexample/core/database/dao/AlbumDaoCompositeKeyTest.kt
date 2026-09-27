package com.mtechviral.musicfinderexample.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 专辑复合身份回归测试（优化建议 10）：
 * 不同艺术家的同名专辑必须各自独立，详情只显示本专辑的曲目。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlbumDaoCompositeKeyTest {

    private lateinit var db: MusicDatabase
    private lateinit var albumDao: AlbumDao
    private lateinit var songDao: SongDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MusicDatabase(context)
        albumDao = AlbumDao(db)
        songDao = SongDao(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun sameAlbumNameDifferentArtistsStaySeparate() {
        songDao.insertSongs(
            listOf(
                Song(title = "T1", path = "/a.mp3", artist = "ArtistA", album = "SameName"),
                Song(title = "T2", path = "/b.mp3", artist = "ArtistB", album = "SameName"),
                Song(title = "T3", path = "/c.mp3", artist = "ArtistA", album = "SameName"),
            ),
            "media_library",
        )

        val albums = albumDao.queryAlbums()
        assertEquals(2, albums.size)

        val aSongs = albumDao.querySongsByAlbum("SameName", "ArtistA")
        assertEquals(2, aSongs.size)
        val bSongs = albumDao.querySongsByAlbum("SameName", "ArtistB")
        assertEquals(1, bSongs.size)
        assertEquals("T2", bSongs[0].title)
    }

    @Test
    fun albumArtistGroupsByAlbumArtistFallback() {
        songDao.insertSongs(
            listOf(
                Song(
                    title = "T1", path = "/a.mp3", artist = "SingerX",
                    album = "Comp", albumArtist = "Various",
                ),
            ),
            "media_library",
        )

        val albums = albumDao.queryAlbums()
        assertEquals(1, albums.size)
        assertEquals("Various", albums[0].artist)
        assertEquals(1, albumDao.querySongsByAlbum("Comp", "Various").size)
    }

    @Test
    fun coverFieldsComeFromOneRepresentativeSong() {
        songDao.insertSongs(
            listOf(
                Song(title = "First", path = "/z.mp3", artist = "Singer", album = "Record",
                    hasArtwork = true),
                Song(title = "Second", path = "/a.mp3", artist = "Singer", album = "Record",
                    cachedArtworkPath = "/cover.jpg", coverArtId = "remote-cover"),
            ),
            "media_library",
        )

        val album = albumDao.queryAlbums().single()
        val representative = songDao.querySongById(album.coverSongId!!)!!
        assertEquals(representative.path, album.coverSongPath)
        assertEquals(representative.cachedArtworkPath, album.coverArtworkPath)
        assertEquals(representative.coverArtId, album.coverArtId)
    }
}
