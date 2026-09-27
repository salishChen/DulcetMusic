package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbyProviderTest {
    private lateinit var server: ServerSocket
    private lateinit var thread: Thread
    private val requestBodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val chars = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val count = reader.read(chars, read, contentLength - read)
                        if (count < 0) break
                        read += count
                    }
                    requestBodies += String(chars, 0, read)
                    val body = when {
                        path.endsWith("/Users/AuthenticateByName") ->
                            """{"AccessToken":"token","User":{"Id":"user"}}"""
                        path.endsWith("/System/Info/Public") -> """{"Id":"server-1"}"""
                        path.contains("StartIndex=0") ->
                            """{"TotalRecordCount":2,"Items":[{"Id":"item/1","Name":"Song","RunTimeTicks":123450000,"Artists":["Singer"],"MediaSources":[{"Id":"first","Name":"FLAC","Container":"flac","Bitrate":900000},{"Id":"second","Name":"MP3","Container":"mp3","Bitrate":320000}]}]}"""
                        path.contains("StartIndex=1") ->
                            """{"TotalRecordCount":2,"Items":[{"Id":"item2","Name":"Other","MediaSources":[{"Id":"only"}]}]}"""
                        else -> "{}"
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
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
    fun pagesItemsAndKeepsMediaVersionsSeparateForDirectPlayback() = runBlocking {
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:${server.localPort}/emby/",
            username = "user", password = "pass",
        ))
        assertEquals("Emby 登录成功", provider.testConnection())
        assertEquals("server-1", provider.serverIdentity)
        assertTrue(requestBodies.first().contains("\"Pw\":\"pass\""))
        val songs = provider.getAllSongs()
        assertEquals(3, songs.size)
        assertEquals(3, songs.map { it.remoteId }.toSet().size)
        assertEquals(12_345L, songs.first().duration)
        assertEquals(900_000, songs.first().bitrate)
        val stream = provider.stream(songs.first())
        val url = stream.url.toHttpUrl()
        assertEquals("/emby/Audio/item%2F1/stream", url.encodedPath)
        assertEquals("first", url.queryParameter("MediaSourceId"))
        assertEquals("true", url.queryParameter("static"))
        assertEquals("token", stream.headers["X-Emby-Token"])
    }

    @Test
    fun knownServerIdentityRejectsAnAddressPointingElsewhere() = runBlocking {
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:${server.localPort}/emby/",
            username = "user", password = "pass", serverIdentity = "another-server",
        ))
        val error = runCatching { provider.testConnection() }.exceptionOrNull()
        assertTrue(error?.message?.contains("服务器身份") == true)
    }
}
