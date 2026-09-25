package com.mtechviral.musicfinderexample.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放概览聚合（优化建议 07）与缓存字段一致性（优化建议 05）回归测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongDaoStatsAndCacheTest {

    private lateinit var db: MusicDatabase
    private lateinit var songDao: SongDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MusicDatabase(context)
        songDao = SongDao(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertThree(): List<Long> {
        for (i in 1..3) {
            songDao.insertSongs(
                listOf(Song(title = "S$i", path = "/m/$i.mp3", artist = "A", album = "AL")),
                "media_library",
            )
        }
        return (1..3).map { songDao.querySongByPath("/m/$it.mp3")!!.id!! }
    }

    @Test
    fun playOverviewAggregatesWholeLibrary() {
        val ids = insertThree()
        // S1 播放 2 次、S3 播放 1 次、S2 从未播放
        songDao.incrementPlayCount(ids[0])
        songDao.incrementPlayCount(ids[0])
        songDao.incrementPlayCount(ids[2])

        // 60s 窗口：三个查询应覆盖全库，而不是 Top 50 列表
        val overview = songDao.queryPlayOverview(System.currentTimeMillis() - 60_000)
        assertEquals(3L, overview.totalPlayCount)
        assertEquals(2, overview.playedSongCount)
        assertEquals(2, overview.recentPlayCount)
    }

    @Test
    fun clearAllCacheRecordsResetsAudioAndArtwork() {
        val ids = insertThree()
        songDao.updateSongCache(ids[0], "/cache/a.cache")
        songDao.updateArtworkCache(ids[0], "/cache/art.jpg")

        songDao.clearAllCacheRecords()

        val after = songDao.querySongById(ids[0])!!
        assertNull(after.cachedPath)
        assertNull(after.cachedArtworkPath)
        assertNull(after.cacheTimestamp)
    }

    @Test
    fun touchSongCacheRefreshesLastUsedAndIgnoresUncached() {
        val ids = insertThree()
        songDao.updateSongCache(ids[0], "/cache/a.cache")
        val before = songDao.querySongById(ids[0])!!.cacheTimestamp!!

        Thread.sleep(5)
        songDao.touchSongCache(ids[0])
        val afterTs = songDao.querySongById(ids[0])!!.cacheTimestamp!!
        assertTrue(afterTs >= before)

        // 未缓存的歌曲不应被 touch 出缓存时间
        songDao.touchSongCache(ids[1])
        assertNull(songDao.querySongById(ids[1])!!.cacheTimestamp)
    }

    @Test
    fun clearArtworkCacheByPathClearsAllReferences() {
        val ids = insertThree()
        songDao.updateArtworkCache(ids[0], "/cache/shared.jpg")
        songDao.updateArtworkCache(ids[1], "/cache/shared.jpg")
        songDao.updateArtworkCache(ids[2], "/cache/other.jpg")
        songDao.updateSongCache(ids[0], "/cache/a.cache")

        songDao.clearArtworkCacheByPath("/cache/shared.jpg")

        assertNull(songDao.querySongById(ids[0])!!.cachedArtworkPath)
        assertNull(songDao.querySongById(ids[1])!!.cachedArtworkPath)
        assertNotNull(songDao.querySongById(ids[2])!!.cachedArtworkPath)
        // 音频缓存不受影响
        assertEquals("/cache/a.cache", songDao.querySongById(ids[0])!!.cachedPath)
    }
}
