package com.mtechviral.musicfinderexample.core.player

import androidx.media3.datasource.DataSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StrictHttpDataSourceTest {
    private lateinit var server: ServerSocket
    private lateinit var thread: Thread
    private val requests = AtomicInteger()
    @Volatile private var rangeHeader: String? = null
    @Volatile private var authorizationHeader: String? = null

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)
                    while (true) {
                        val header = reader.readLine()
                        if (header.isNullOrEmpty()) break
                        if (header.startsWith("Range:", ignoreCase = true)) {
                            rangeHeader = header.substringAfter(':').trim()
                        }
                        if (header.startsWith("Authorization:", ignoreCase = true)) {
                            authorizationHeader = header.substringAfter(':').trim()
                        }
                    }
                    requests.incrementAndGet()
                    val response = if (path == "/stream/?id=track&token=secret") {
                        "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nabcdef"
                    } else if (path == "/range" || path == "/wrong-range") {
                        "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes ${if (path == "/range") "2-4" else "0-2"}/6\r\n" +
                            "Content-Length: 3\r\nConnection: close\r\n\r\ncde"
                    } else {
                        val location = when (path) {
                            "/stream?id=track&token=secret" -> "/stream/?id=track&token=secret"
                            "/seek" -> "/range"
                            "/loop" -> "/loop"
                            "/scheme-change" -> "https://127.0.0.1:${server.localPort}/stream"
                            else -> "http://127.0.0.1:${server.localPort + 1}/elsewhere"
                        }
                        "HTTP/1.1 301 Moved Permanently\r\n" +
                            "Location: $location\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    }
                    socket.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                }
            } catch (_: SocketException) {
                break
            }
        }.apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        server.close()
        thread.join(1000)
    }

    @Test
    fun playbackRejectsCrossOriginRedirectWithCredentials() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        val spec = DataSpec.Builder()
            .setUri("http://127.0.0.1:${server.localPort}/stream")
            .setHttpRequestHeaders(mapOf("Authorization" to "Basic secret"))
            .build()
        try {
            assertThrows(IOException::class.java) { source.open(spec) }
            assertEquals(1, requests.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun playbackFollowsSameOriginTrailingSlashRedirect() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        val spec = DataSpec.Builder()
            .setUri("http://127.0.0.1:${server.localPort}/stream?id=track&token=secret")
            .setHttpRequestHeaders(mapOf("Authorization" to "Basic secret"))
            .build()
        try {
            assertEquals(6L, source.open(spec))
            val bytes = ByteArray(6)
            assertEquals(6, source.read(bytes, 0, bytes.size))
            assertEquals("abcdef", String(bytes, Charsets.US_ASCII))
            assertEquals("/stream/", source.uri!!.path)
            assertEquals("id=track&token=secret", source.uri!!.query)
            assertEquals("Basic secret", authorizationHeader)
            assertEquals(2, requests.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun seekPreservesRangeAcrossSameOriginRedirect() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        try {
            assertEquals(3L, source.open(DataSpec.Builder()
                .setUri("http://127.0.0.1:${server.localPort}/seek")
                .setPosition(2).setLength(3).build()))
            assertEquals("bytes=2-4", rangeHeader)
            val bytes = ByteArray(3)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            assertEquals("cde", String(bytes, Charsets.US_ASCII))
            assertEquals(2, requests.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun playbackRejectsRedirectLoops() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        try {
            assertThrows(IOException::class.java) {
                source.open(DataSpec.Builder().setUri("http://127.0.0.1:${server.localPort}/loop").build())
            }
            assertEquals(6, requests.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun playbackRejectsSchemeChanges() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        try {
            assertThrows(IOException::class.java) {
                source.open(DataSpec.Builder().setUri("http://127.0.0.1:${server.localPort}/scheme-change").build())
            }
            assertEquals(1, requests.get())
        } finally {
            source.close()
        }
    }

    @Test
    fun seekReadsOnlyTheRequestedRange() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        val spec = DataSpec.Builder().setUri("http://127.0.0.1:${server.localPort}/range")
            .setPosition(2).setLength(3).build()
        try {
            assertEquals(3L, source.open(spec))
            assertEquals("bytes=2-4", rangeHeader)
            val bytes = ByteArray(3)
            assertEquals(3, source.read(bytes, 0, bytes.size))
            assertEquals("cde", String(bytes, Charsets.US_ASCII))
            assertEquals(-1, source.read(bytes, 0, bytes.size))
        } finally {
            source.close()
        }
    }

    @Test
    fun seekRejectsAnIncorrectPartialResponse() {
        val source = StrictHttpDataSource.Factory().createDataSource()
        val spec = DataSpec.Builder().setUri("http://127.0.0.1:${server.localPort}/wrong-range")
            .setPosition(2).setLength(3).build()
        try {
            assertThrows(IOException::class.java) { source.open(spec) }
        } finally {
            source.close()
        }
    }
}
