package com.mtechviral.musicfinderexample.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UrlSanitizer] 回归测试（优化建议 01）：
 * 日志/异常消息中的认证参数（u / s / t）必须被脱敏。
 */
class UrlSanitizerTest {

    @Test
    fun `auth params are redacted`() {
        val url = "http://host/rest/stream?id=1&u=admin&s=abc123&t=deadbeef&v=1.16.1"
        val redacted = UrlSanitizer.redact(url)
        assertEquals(
            "http://host/rest/stream?id=1&u=***&s=***&t=***&v=1.16.1",
            redacted,
        )
    }

    @Test
    fun `leading auth param is redacted`() {
        val url = "https://host/rest/ping?u=admin&s=salt&t=token"
        assertEquals("https://host/rest/ping?u=***&s=***&t=***", UrlSanitizer.redact(url))
    }

    @Test
    fun `id and other params are preserved`() {
        val url = "http://host/rest/stream?id=42&size=100"
        assertEquals(url, UrlSanitizer.redact(url))
    }

    @Test
    fun `redaction is idempotent`() {
        val url = "http://host/rest/stream?u=admin&s=x&t=y"
        val once = UrlSanitizer.redact(url)
        assertEquals(once, UrlSanitizer.redact(once))
    }
}
