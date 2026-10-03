package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.LrcParser
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.*
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
class SubsonicLyricsTest {
    private lateinit var server: ServerSocket
    private lateinit var thread: Thread
    private lateinit var provider: SubsonicProvider
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile private var structured = """{"synced":true,"line":[{"start":5700,"value":"First"},{"start":9840,"value":"Second"}]}"""
    @Volatile private var unsupported = false
    private val song = Song(
        id = 99, title = "Track", artist = "Singer", path = "remote://source/track",
        remoteId = "track&edition=live", sourceId = "source", sourceType = Song.SOURCE_TYPE_SUBSONIC,
    )

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    paths += path
                    while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                    val body = when {
                        path.contains("/getLyricsBySongId?") && unsupported ->
                            """{"subsonic-response":{"status":"failed","error":{"code":70,"message":"Not implemented"}}}"""
                        path.contains("/getLyricsBySongId?") ->
                            """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[$structured]}}}"""
                        path.contains("/getLyrics?") ->
                            """{"subsonic-response":{"status":"ok","lyrics":{"value":"Legacy plain lyrics"}}}"""
                        else -> """{"subsonic-response":{"status":"ok"}}"""
                    }
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
                }
            } catch (_: SocketException) { break }
        }.apply { isDaemon = true; start() }
        val baseUrl = "http://127.0.0.1:${server.localPort}/music/"
        provider = SubsonicProvider(RemoteSource(
            id = "source", protocol = RemoteProtocol.NAVIDROME, displayName = "Test",
            intranetUrl = baseUrl, username = "user", password = "pass",
        ))
        SubsonicService.activate(SubsonicConfig(intranetUrl = baseUrl, username = "user", password = "pass"))
    }

    @After
    fun tearDown() {
        SubsonicService.deactivate()
        server.close()
        thread.join(1000)
    }

    @Test
    fun syncedLyricsFollowPlaybackAndUseRemoteSongId() = runBlocking {
        val lines = LrcParser.parse(provider.lyrics(song))
        assertEquals(listOf(5700L, 9840L), lines.map { it.timeMs })
        assertEquals(-1, LrcParser.activeIndex(lines, 5699))
        assertEquals(0, LrcParser.activeIndex(lines, 5700))
        assertEquals(1, LrcParser.activeIndex(lines, 9840))
        assertEquals(0, LrcParser.activeIndex(lines, 6000)) // seeking back restores the previous line
        val url = ("http://localhost" + paths.single { it.contains("/getLyricsBySongId?") }).toHttpUrl()
        assertEquals("/music/rest/getLyricsBySongId", url.encodedPath)
        assertEquals(song.remoteId, url.queryParameter("id"))
        assertTrue(paths.none { it.contains("/getLyrics?") })
    }

    @Test
    fun syncedTrackIsPreferredOverPlainTrackAndOffsetIsApplied() = runBlocking {
        structured = """{"synced":false,"line":[{"value":"Plain"}]},{"synced":true,"offset":250,"line":[{"start":10500,"value":"Timed"}]}"""
        assertEquals(10_250L, LrcParser.parse(provider.lyrics(song)).single().timeMs)
    }

    @Test
    fun negativeOffsetDelaysLyricsAndEarlyStartsAreClampedToZero() = runBlocking {
        structured = """{"synced":true,"offset":-200,"line":[{"start":100,"value":"Delayed"}]}"""
        assertEquals(300L, LrcParser.parse(provider.lyrics(song)).single().timeMs)
        structured = """{"synced":true,"offset":200,"line":[{"start":100,"value":"Early"}]}"""
        assertEquals(0L, LrcParser.parse(provider.lyrics(song)).single().timeMs)
    }

    @Test
    fun unsupportedExtensionFallsBackToLegacyLyrics() = runBlocking {
        unsupported = true
        assertEquals("Legacy plain lyrics", provider.lyrics(song))
        assertTrue(paths.any { it.contains("/getLyricsBySongId?") })
        assertTrue(paths.any { it.contains("/getLyrics?") })
    }

    @Test
    fun emptyStructuredLyricsFallBackToLegacyLyrics() = runBlocking {
        structured = ""
        assertEquals("Legacy plain lyrics", provider.lyrics(song))
        assertTrue(paths.any { it.contains("/getLyrics?") })
    }

    @Test
    fun unsyncedLyricsRemainPlainText() = runBlocking {
        structured = """{"synced":false,"line":[{"value":"First"},{"value":"Second"}]}"""
        assertEquals("First\nSecond", provider.lyrics(song))
    }

    @Test
    fun invalidSyncedLinesDoNotBecomeFakeZeroTimeLyrics() = runBlocking {
        structured = """{"synced":true,"line":[{"value":"Missing start"},{"start":null,"value":"Null start"},{"start":5700,"value":"Valid"}]}"""
        val lines = LrcParser.parse(provider.lyrics(song))
        assertEquals(listOf(5700L), lines.map { it.timeMs })
        assertEquals("Valid", lines.single().text)
    }
}
