package com.mtechviral.musicfinderexample.core.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkBodyTest {
    @Test
    fun unknownLengthImageIsStoppedAtTheByteLimit() {
        fun body(size: Int): ResponseBody = object : ResponseBody() {
            private val buffer = Buffer().write(ByteArray(size) { 7 })
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = -1
            override fun source(): BufferedSource = buffer
        }
        assertEquals(16, body(16).readArtworkBytes()?.size)
        assertNull(body(4_000_001).readArtworkBytes())
    }
}
