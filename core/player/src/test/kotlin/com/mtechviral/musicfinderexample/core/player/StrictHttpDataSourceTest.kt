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

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    reader.readLine()
                    while (true) {
                        if (reader.readLine().isNullOrEmpty()) break
                    }
                    requests.incrementAndGet()
                    val response = "HTTP/1.1 302 Found\r\n" +
                        "Location: http://127.0.0.1:${server.localPort}/elsewhere\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
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
    fun playbackNeverFollowsRedirectWithCredentials() {
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
}
