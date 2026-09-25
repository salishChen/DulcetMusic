package com.mtechviral.musicfinderexample.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RemoteLocator] 回归测试（优化建议 01）：
 * 定位符必须稳定（同输入同输出）、不含认证参数、按来源区分。
 */
class RemoteLocatorTest {

    @Test
    fun `locator is stable for same remote id and source`() {
        val a = RemoteLocator.subsonic("123", "subsonic@user@host", "t|a|b")
        val b = RemoteLocator.subsonic("123", "subsonic@user@host", "t|a|b")
        assertEquals(a, b)
    }

    @Test
    fun `locator differs across servers`() {
        val a = RemoteLocator.subsonic("123", "subsonic@user@hostA", "t|a|b")
        val b = RemoteLocator.subsonic("123", "subsonic@user@hostB", "t|a|b")
        assertNotEquals(a, b)
    }

    @Test
    fun `locator carries no auth params`() {
        val locator = RemoteLocator.subsonic("123", "subsonic@user@host", "t|a|b")
        assertFalse(locator.contains("u="))
        assertFalse(locator.contains("s="))
        assertFalse(locator.contains("t="))
        assertTrue(RemoteLocator.isLocator(locator))
    }

    @Test
    fun `empty remote id falls back to identity hash`() {
        val a = RemoteLocator.subsonic("", "subsonic@user@host", "title|artist|album")
        val b = RemoteLocator.subsonic(null, "subsonic@user@host", "title|artist|album")
        val c = RemoteLocator.subsonic("", "subsonic@user@host", "other|artist|album")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
