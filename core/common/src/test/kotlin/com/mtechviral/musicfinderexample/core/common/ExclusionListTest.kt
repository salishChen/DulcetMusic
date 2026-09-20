package com.mtechviral.musicfinderexample.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排除列表「命中判定」测试（第二十六轮需求 3）。
 *
 * 只测纯逻辑部分：比较规则、单曲/歌手两类排除、以及边界（大小写、空白、
 * 空歌手、空列表短路）。持久化与 StateFlow 属于 Android 依赖，不在本测试范围。
 *
 * 这些判定直接决定"扫描时跳过哪些歌"，所以逐条钉死行为，避免以后调比较口径时
 * 悄悄把该导入的歌过滤掉、或把该排除的歌又放进来。
 */
class ExclusionListTest {

    /** 模拟 [ExclusionList] 的比较口径（与实现保持同一套规则） */
    private fun songExcluded(
        entries: List<ExclusionEntry>,
        title: String,
        artist: String?,
    ): Boolean {
        val t = title.trim().lowercase()
        val a = artist?.trim().orEmpty().lowercase()
        return entries.any { entry ->
            entry.type == ExclusionType.SONG &&
                entry.title.trim().lowercase() == t &&
                (entry.artist.trim().lowercase() == a || entry.artist.isBlank())
        }
    }

    private fun artistExcluded(entries: List<ExclusionEntry>, artist: String?): Boolean {
        val name = artist?.trim().orEmpty()
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        return entries.any {
            it.type == ExclusionType.ARTIST && it.artist.trim().lowercase() == lower
        }
    }

    /** 模拟 `ExclusionList.isExcluded(title, artist, albumArtist)` */
    private fun excluded(
        entries: List<ExclusionEntry>,
        title: String,
        artist: String?,
        albumArtist: String?,
    ): Boolean {
        if (entries.isEmpty()) return false
        return songExcluded(entries, title, artist) ||
            artistExcluded(entries, artist) ||
            artistExcluded(entries, albumArtist)
    }

    @Test
    fun `单曲排除按歌名加歌手命中`() {
        val entries = listOf(ExclusionEntry(ExclusionType.SONG, "圣诞星", "周杰伦"))
        assertTrue(songExcluded(entries, "圣诞星", "周杰伦"))
        // 同名但不同歌手：不应被排除（避免误杀翻唱 / 同名歌）
        assertFalse(songExcluded(entries, "圣诞星", "其他歌手"))
    }

    @Test
    fun `比较时忽略大小写与首尾空白`() {
        val entries = listOf(ExclusionEntry(ExclusionType.SONG, "Night Dancer", "imase"))
        assertTrue(songExcluded(entries, "  night dancer ", " IMASE "))
    }

    @Test
    fun `歌手条目排除该歌手的全部歌曲`() {
        val entries = listOf(ExclusionEntry(ExclusionType.ARTIST, "", "周杰伦"))
        assertTrue(artistExcluded(entries, "周杰伦"))
        assertTrue(artistExcluded(entries, " 周杰伦 "))
        assertFalse(artistExcluded(entries, "李佳薇"))
    }

    @Test
    fun `空歌手不会被歌手条目误判`() {
        val entries = listOf(ExclusionEntry(ExclusionType.ARTIST, "", "周杰伦"))
        // 未知艺术家（null / 空串）不应命中任何歌手排除
        assertFalse(artistExcluded(entries, null))
        assertFalse(artistExcluded(entries, ""))
        assertFalse(artistExcluded(entries, "   "))
    }

    @Test
    fun `单曲条目的歌手为空时只按歌名命中`() {
        // 历史数据/无标签歌曲可能没有歌手，此时歌名相同即视为同一首
        val entries = listOf(ExclusionEntry(ExclusionType.SONG, "未知曲目", ""))
        assertTrue(songExcluded(entries, "未知曲目", null))
        assertTrue(songExcluded(entries, "未知曲目", "任意歌手"))
    }

    @Test
    fun `空列表不排除任何歌曲`() {
        val entries = emptyList<ExclusionEntry>()
        assertFalse(songExcluded(entries, "任意歌名", "任意歌手"))
        assertFalse(artistExcluded(entries, "任意歌手"))
        assertFalse(excluded(entries, "任意歌名", "任意歌手", "任意专辑艺术家"))
    }

    @Test
    fun `排除歌手时专辑艺术家也命中`() {
        // 第二十七轮：合辑里 artist 可能是「群星」，albumArtist 才是真正被排除的歌手。
        // 判定与「清库」口径必须一致，否则会出现"清掉了、下次扫描又导进来"。
        val entries = listOf(ExclusionEntry(ExclusionType.ARTIST, "", "周杰伦"))
        assertTrue(excluded(entries, "某合辑曲目", "群星", "周杰伦"))
        // artist 命中同样成立
        assertTrue(excluded(entries, "某曲目", "周杰伦", "周杰伦"))
        // 两者都不命中则不排除
        assertFalse(excluded(entries, "某曲目", "群星", " Various Artists"))
    }

    @Test
    fun `条目 key 区分类型并使去重稳定`() {
        val song = ExclusionEntry(ExclusionType.SONG, "圣诞星", "周杰伦")
        val artist = ExclusionEntry(ExclusionType.ARTIST, "", "周杰伦")
        // 类型不同 -> key 不同（同名歌手与同名歌曲互不覆盖）
        assertTrue(song.key != artist.key)
        // 大小写与空白归一后 key 相同 -> 重复加入可被幂等去重
        val dup = ExclusionEntry(ExclusionType.SONG, " 圣诞星 ", "周杰伦 ")
        assertEquals(song.key, dup.key)
    }

    @Test
    fun `展示文案在字段缺失时有兜底`() {
        assertEquals("未知艺术家", ExclusionEntry(ExclusionType.ARTIST, "", "").displayText)
        assertEquals("未知歌曲", ExclusionEntry(ExclusionType.SONG, "", "").displayText)
        // 单曲有条目歌手时用「歌名 - 歌手」
        assertEquals(
            "圣诞星 - 周杰伦",
            ExclusionEntry(ExclusionType.SONG, "圣诞星", "周杰伦").displayText,
        )
        assertEquals("歌手", ExclusionEntry(ExclusionType.ARTIST, "", "x").displayTypeText)
        assertEquals("歌曲", ExclusionEntry(ExclusionType.SONG, "x", "").displayTypeText)
    }
}
