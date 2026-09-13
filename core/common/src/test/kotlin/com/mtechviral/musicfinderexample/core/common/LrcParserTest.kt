package com.mtechviral.musicfinderexample.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LRC 解析测试。
 *
 * 逐条覆盖原 Flutter 端 `lib/utils/lrc.dart` 的解析语义，
 * 保证原生实现与旧版行为完全一致。
 */
class LrcParserTest {

    @Test
    fun `解析标准时间戳`() {
        val lines = LrcParser.parse("[00:12.34]第一句\n[01:05.678]第二句")
        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("第一句", lines[0].text)
        assertEquals(65_678L, lines[1].timeMs)
        assertEquals("第二句", lines[1].text)
    }

    @Test
    fun `一位毫秒按十位补齐`() {
        // Dart 端 padRight(3,'0')：'5' -> 500ms，'45' -> 450ms，'005' -> 5ms
        // 注意总毫秒数还要加上秒数部分（[00:01.5] = 1000 + 500）
        assertEquals(1_500L, LrcParser.parse("[00:01.5]a")[0].timeMs)
        assertEquals(1_450L, LrcParser.parse("[00:01.45]a")[0].timeMs)
        assertEquals(1_005L, LrcParser.parse("[00:01.005]a")[0].timeMs)
    }

    @Test
    fun `一行多时间戳展开为多行`() {
        val lines = LrcParser.parse("[00:10.00][00:20.00]重复句")
        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(20_000L, lines[1].timeMs)
        assertTrue(lines.all { it.text == "重复句" })
    }

    @Test
    fun `无时间戳的纯文本按 0 处理`() {
        val lines = LrcParser.parse("这是一段没有时间轴的歌词")
        assertEquals(1, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals("这是一段没有时间轴的歌词", lines[0].text)
    }

    @Test
    fun `时间戳后无文本的行被丢弃`() {
        assertTrue(LrcParser.parse("[00:01.00]").isEmpty())
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("   ").isEmpty())
    }

    @Test
    fun `结果按时间升序`() {
        val lines = LrcParser.parse("[00:30.00]后\n[00:10.00]前")
        assertEquals("前", lines[0].text)
        assertEquals("后", lines[1].text)
    }

    @Test
    fun `activeIndex 返回最后一个不晚于当前位置的行`() {
        val lines = LrcParser.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, LrcParser.activeIndex(lines, 5_000L))
        assertEquals(0, LrcParser.activeIndex(lines, 10_000L))
        assertEquals(1, LrcParser.activeIndex(lines, 25_000L))
        assertEquals(2, LrcParser.activeIndex(lines, 99_000L))
    }
}
