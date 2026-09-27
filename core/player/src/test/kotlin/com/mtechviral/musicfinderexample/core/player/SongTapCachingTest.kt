package com.mtechviral.musicfinderexample.core.player

import androidx.media3.datasource.DataSpec
import com.mtechviral.musicfinderexample.core.cache.CacheTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 播放流旁路缓存（耗电优化 §2）的关键回归测试：
 * 1) 解析重建的 [DataSpec] 必须保留缓存键（键丢失 = 旁路静默失效、播放却正常）；
 * 2) [CacheTap] 的顺序写、EOF 落盘、非 EOF 保留前缀行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SongTapCachingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun cacheKeySurvivesDataSpecRebuild() {
        val spec = DataSpec.Builder()
            .setUri("http://example.com/rest/stream?id=1")
            .setKey("stable-cache-key")
            .build()
            .withAdditionalHeaders(mapOf("Authorization" to "Basic x"))
        assertEquals("stable-cache-key", spec.key)
    }

    @Test
    fun tapFinalizesAtEof() {
        val partial = File(tmp.root, "k.partial")
        val target = File(tmp.root, "k.cache")
        val tap = CacheTap.open(partial, target, 0L)
        assertNotNull(tap)
        assertTrue(tap!!.append(ByteArray(4) { it.toByte() }, 0, 4))
        assertTrue(tap.append(ByteArray(2), 0, 2))
        assertTrue(tap.finish(eof = true))
        assertTrue(target.exists())
        assertEquals(6L, target.length())
        assertFalse(partial.exists())
    }

    @Test
    fun tapKeepsPrefixWithoutEof() {
        val partial = File(tmp.root, "k.partial")
        val target = File(tmp.root, "k.cache")
        val tap = CacheTap.open(partial, target, 0L)!!
        assertTrue(tap.append(ByteArray(3), 0, 3))
        assertFalse(tap.finish(eof = false))
        assertFalse(target.exists())
        assertEquals(3L, partial.length())
    }

    @Test
    fun tapResumesContiguousPrefix() {
        val partial = File(tmp.root, "k.partial")
        val target = File(tmp.root, "k.cache")
        val first = CacheTap.open(partial, target, 0L)!!
        first.append(ByteArray(5), 0, 5)
        first.finish(eof = false)

        val second = CacheTap.open(partial, target, 5L)
        assertNotNull(second)
        assertTrue(second!!.append(ByteArray(2), 0, 2))
        assertTrue(second.finish(eof = true))
        assertEquals(7L, target.length())
    }

    @Test
    fun tapRefusesPositionPastPrefix() {
        val partial = File(tmp.root, "k.partial")
        val target = File(tmp.root, "k.cache")
        val first = CacheTap.open(partial, target, 0L)!!
        first.append(ByteArray(2), 0, 2)
        first.finish(eof = false)
        // 越过前缀（空洞）：放弃旁路，保留前缀
        assertEquals(null, CacheTap.open(partial, target, 9L))
    }
}
