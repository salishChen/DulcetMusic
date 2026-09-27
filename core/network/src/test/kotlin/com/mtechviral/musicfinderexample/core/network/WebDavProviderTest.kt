package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets

class WebDavProviderTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    @Before
    fun setUp() {
        server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        serverThread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)?.substringBefore('?').orEmpty()
                    var depth: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Depth:", ignoreCase = true)) depth = line.substringAfter(':').trim()
                    }
                    val body = responseFor(path, depth)
                    val bytes = body.toByteArray(StandardCharsets.UTF_8)
                    val header = "HTTP/1.1 ${if (body.isEmpty()) "404 Not Found" else "207 Multi-Status"}\r\n" +
                        "Content-Type: application/xml\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(header.toByteArray(StandardCharsets.US_ASCII))
                    socket.getOutputStream().write(bytes)
                }
            } catch (_: SocketException) {
                break
            }
        }.apply { isDaemon = true; start() }
    }

    private fun responseFor(path: String, depth: String?): String {
            val root = "/dav/Music/"
            return when {
                path == root && depth == "0" -> multi(entry(root, true))
                path == root && depth == "1" -> multi(
                    entry(root, true),
                    entry("/dav/Music/Alpha%20%2B%20%E4%B8%AD%E6%96%87.mp3", false, 123),
                    entry("/dav/Music/Live/", true),
                )
                path == "/dav/Music/Live/" && depth == "1" -> multi(
                    entry("/dav/Music/Live/", true), entry("Track%20%231.flac", false, 456),
                )
                else -> ""
            }
    }

    @After
    fun tearDown() {
        server.close()
        serverThread.join(1000)
    }

    @Test
    fun traversesRelativeHrefsAndPreservesEncodedFileIdentity() = runBlocking {
        val provider = WebDavProvider(RemoteSource(
            id = "dav-source", protocol = RemoteProtocol.WEBDAV, displayName = "DAV",
            intranetUrl = "http://127.0.0.1:${server.localPort}/dav/",
            username = "user", password = "pass", rootPath = "Music",
        ))
        assertEquals("WebDAV 目录可读取", provider.testConnection())
        val songs = provider.getAllSongs()
        assertEquals(2, songs.size)
        assertEquals(setOf("Alpha + 中文", "Track #1"), songs.map { it.title }.toSet())
        val nested = songs.first { it.title == "Track #1" }
        assertEquals("Live/Track%20%231.flac", nested.remoteId)
        assertEquals(456L, nested.size)
        val stream = provider.stream(nested)
        assertEquals("/dav/Music/Live/Track%20%231.flac", stream.url.toHttpUrl().encodedPath)
        assertEquals(Credentials.basic("user", "pass"), stream.headers["Authorization"])
        assertTrue(songs.all { it.sourceId == "dav-source" })
    }

    private fun multi(vararg entries: String) =
        "<d:multistatus xmlns:d=\"DAV:\">${entries.joinToString("")}</d:multistatus>"

    private fun entry(href: String, directory: Boolean, size: Long = 0): String =
        """<d:response><d:href>$href</d:href>
            <d:propstat><d:prop><d:resourcetype>${if (directory) "<d:collection/>" else ""}</d:resourcetype>
            <d:getcontentlength>$size</d:getcontentlength></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""
}
