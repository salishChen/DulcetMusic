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
    private val paths = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    paths += path
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
                        path.startsWith("/emby/Users/user/Items/legacy?") ->
                            """{"Id":"legacy","MediaSources":[{"Id":"mediasource_legacy"}]}"""
                        path.contains("/Views?") ->
                            """{"Items":[{"Id":"music-library","Name":"Music","CollectionType":"music"},{"Id":"films","Name":"Films","CollectionType":"movies"}]}"""
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
        assertEquals(listOf(RemoteLibrary("music-library", "Music")), provider.libraries())
        assertTrue(requestBodies.first().contains("\"Pw\":\"pass\""))
        val songs = provider.getAllSongs()
        assertEquals(3, songs.size)
        assertEquals(3, songs.map { it.remoteId }.toSet().size)
        assertEquals(12_345L, songs.first().duration)
        assertEquals(900_000, songs.first().bitrate)
        assertTrue(paths.filter { it.contains("/Items?") }.all {
            ("http://localhost$it").toHttpUrl().queryParameter("Fields") == "MediaSources"
        })
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

    @Test
    fun selectedMusicLibraryScopesPagedItems() = runBlocking {
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:${server.localPort}/emby/",
            username = "user", password = "pass", libraryId = "music-library",
        ))
        provider.testConnection()
        provider.getAllSongs()
        assertTrue(paths.any { it.contains("/Items?") })
        assertTrue(paths.filter { it.contains("/Items?") }
            .all { it.contains("ParentId=music-library") })
    }

    @Test
    fun forwardsLoginLibraryArtworkAndPlaybackWhilePreservingProxyPath() = runBlocking {
        val requestedTargets = mutableListOf<String>()
        val source = RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://10.0.0.222:8096/emby/", username = "user", password = "pass",
        )
        val provider = EmbyProvider(source) { target ->
            requestedTargets += target
            "http://127.0.0.1:${server.localPort}"
        }
        assertEquals("Emby 登录成功", provider.testConnection())
        assertEquals(1, provider.libraries().size)
        val songs = provider.getAllSongs()
        provider.artwork(songs.first().copy(coverArtId = "item/1"))
        val stream = provider.stream(songs.first())
        assertEquals(server.localPort, stream.url.toHttpUrl().port)
        assertEquals("/emby/Audio/item%2F1/stream", stream.url.toHttpUrl().encodedPath)
        assertTrue(paths.any { it == "/emby/Users/AuthenticateByName" })
        assertTrue(paths.any { it == "/emby/Items/item%2F1/Images/Primary" })
        assertTrue(requestedTargets.all { it == source.intranetUrl })
    }

    @Test
    fun unavailableTunnelFallsBackToDirectServer() = runBlocking {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:${server.localPort}", username = "user", password = "pass",
        )) { "http://127.0.0.1:$unavailablePort" }
        assertEquals("Emby 登录成功", provider.testConnection())
        assertEquals(server.localPort, provider.stream(provider.getAllSongs().first()).url.toHttpUrl().port)
    }

    @Test
    fun wakesTunnelAgainAfterConnectionHasBeenCached() = runBlocking {
        var wakeRequests = 0
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://10.0.0.222:8096", username = "user", password = "pass",
        )) {
            wakeRequests++
            "http://127.0.0.1:${server.localPort}"
        }
        provider.testConnection()
        assertEquals(1, wakeRequests)
        provider.getAllSongs()
        assertEquals(2, wakeRequests)
        provider.stream(provider.getAllSongs().first())
        assertEquals(4, wakeRequests)
        assertEquals(1, paths.count { it.endsWith("/Users/AuthenticateByName") })
    }

    @Test
    fun removedTunnelDiscardsCachedAddressAndAuthenticatesOnPublicFallback() = runBlocking {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        var tunnelEnabled = true
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:$unavailablePort/emby/",
            publicUrl = "http://127.0.0.1:${server.localPort}/public/",
            username = "user", password = "pass",
        )) { if (tunnelEnabled) "http://127.0.0.1:${server.localPort}" else null }
        provider.testConnection()
        val song = provider.getAllSongs().first()
        tunnelEnabled = false
        val stream = provider.stream(song)
        assertEquals("/public/Audio/item%2F1/stream", stream.url.toHttpUrl().encodedPath)
        assertTrue(paths.contains("/emby/Users/AuthenticateByName"))
        assertTrue(paths.contains("/public/Users/AuthenticateByName"))
    }

    @Test
    fun legacyImportsResolveActualMediaSourceBeforePlayback() = runBlocking {
        val provider = EmbyProvider(RemoteSource(
            id = "emby", protocol = RemoteProtocol.EMBY, displayName = "Emby",
            intranetUrl = "http://127.0.0.1:${server.localPort}/emby/", username = "user", password = "pass",
        ))
        val original = provider.getAllSongs().first()
        for (legacyKey in listOf("legacy", "6:legacylegacy")) {
            val stream = provider.stream(original.copy(remoteId = legacyKey))
            assertEquals("mediasource_legacy", stream.url.toHttpUrl().queryParameter("MediaSourceId"))
        }
    }
}
