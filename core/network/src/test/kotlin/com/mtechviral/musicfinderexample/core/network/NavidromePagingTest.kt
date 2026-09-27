package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import kotlinx.coroutines.runBlocking
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
class NavidromePagingTest {
    private lateinit var server: ServerSocket
    private lateinit var thread: Thread
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
                    while (true) {
                        if (reader.readLine().isNullOrEmpty()) break
                    }
                    val body = when {
                        path.contains("/ping?") -> payload("")
                        path.contains("/getAlbumList2?") && path.contains("offset=0") ->
                            payload("\"albumList2\":{\"album\":[${album("a")},${album("b")}]}" )
                        path.contains("/getAlbumList2?") && path.contains("offset=2") ->
                            payload("\"albumList2\":{\"album\":[${album("c")}]}" )
                        path.contains("/getMusicFolders?") ->
                            payload("\"musicFolders\":{\"musicFolder\":[{\"id\":\"folder-1\",\"name\":\"Music\"}]}" )
                        path.contains("/getAlbum?") -> {
                            val id = Regex("[?&]id=([^&]+)").find(path)?.groupValues?.get(1).orEmpty()
                            payload("\"album\":{\"song\":[{\"id\":\"song-$id\",\"title\":\"Track $id\",\"artist\":\"Singer $id\",\"bitRate\":320}]}" )
                        }
                        else -> payload("")
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
        SubsonicService.activate(SubsonicConfig(
            intranetUrl = "http://127.0.0.1:${server.localPort}/proxy/",
            username = "user", password = "pass",
        ))
    }

    @After
    fun tearDown() {
        SubsonicService.deactivate()
        server.close()
        thread.join(1000)
    }

    @Test
    fun albumPagesAndSongArtistAreReadWithoutSkippingTheLastPage() = runBlocking {
        val folders = SubsonicService.getMusicFolders(SubsonicConfig(
            intranetUrl = "http://127.0.0.1:${server.localPort}/proxy/",
            username = "user", password = "pass",
        ))
        assertEquals(listOf(RemoteLibrary("folder-1", "Music")), folders)
        val songs = SubsonicService.getAllSongsPaged(pageSize = 2, musicFolderId = "folder-1")
        assertEquals(3, songs.size)
        assertEquals(setOf("Singer a", "Singer b", "Singer c"), songs.map { it.artist }.toSet())
        assertTrue(songs.all { it.bitrate == 320_000 })
        assertTrue(paths.any { it.contains("offset=2") })
        assertTrue(paths.filter { it.contains("/getAlbumList2?") }
            .all { it.contains("musicFolderId=folder-1") })
        assertEquals(3, paths.count { it.contains("/getAlbum?") })
    }

    private fun payload(properties: String) =
        "{\"subsonic-response\":{\"status\":\"ok\"${if (properties.isEmpty()) "" else ",$properties"}}}"

    private fun album(id: String) =
        "{\"id\":\"$id\",\"name\":\"Album $id\",\"artist\":\"Outer Artist\"}"
}
