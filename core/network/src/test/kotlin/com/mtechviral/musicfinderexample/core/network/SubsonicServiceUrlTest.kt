package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class SubsonicServiceUrlTest {
    @Test
    fun streamIdAndCredentialsAreEncodedAsValuesUnderReverseProxy() {
        SubsonicService.activate(SubsonicConfig(
            intranetUrl = "https://example.test/music/proxy/",
            username = "用户&admin",
            password = "secret",
        ))

        val id = "track&name=foo +/中文#%"
        val url = SubsonicService.buildStreamUrlSync(id)!!.toHttpUrl()
        assertEquals("/music/proxy/rest/stream", url.encodedPath)
        assertEquals(id, url.queryParameter("id"))
        assertEquals("用户&admin", url.queryParameter("u"))
        assertEquals(1, url.queryParameterValues("id").size)
    }
}
