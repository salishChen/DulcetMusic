package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Read-only WebDAV music browser. A file's root-relative encoded path is its stable ID. */
class WebDavProvider(override val source: RemoteSource) : RemoteMusicProvider {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var rootUrl: HttpUrl? = null
    private val headers: Map<String, String> = if (source.username.isEmpty() && source.password.isEmpty()) {
        emptyMap()
    } else mapOf("Authorization" to Credentials.basic(source.username, source.password))

    private fun candidates(): List<HttpUrl> = listOf(source.intranetUrl, source.publicUrl)
        .filter { it.isNotBlank() }
        .mapNotNull { it.trim().toHttpUrlOrNull() }
        .distinct()

    private fun root(base: HttpUrl): HttpUrl {
        val segments = source.rootPath.trim().trim('/')
        return if (segments.isEmpty()) base else base.newBuilder().addPathSegments(segments).build()
    }

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (candidate in candidates()) {
            val url = root(candidate)
            try {
                val response = propfind(url, "0")
                if (response.isNotEmpty()) {
                    rootUrl = url
                    return@withContext "WebDAV 目录可读取"
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalArgumentException("请填写有效的 WebDAV 地址")
    }

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val root = rootUrl ?: run { testConnection(); requireNotNull(rootUrl) }
        val pending = ArrayDeque<HttpUrl>()
        val visited = HashSet<String>()
        val result = ArrayList<Song>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (!visited.add(directory.encodedPath.trimEnd('/'))) continue
            for (entry in propfind(directory, "1")) {
                val path = entry.url.encodedPath
                if (path.trimEnd('/') == directory.encodedPath.trimEnd('/')) continue
                if (!withinRoot(root, entry.url)) continue
                if (entry.collection) {
                    pending.add(entry.url)
                    continue
                }
                val relative = path.removePrefix(root.encodedPath.trimEnd('/'))
                val name = entry.url.pathSegments.lastOrNull().orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) continue
                val id = relative.trimStart('/')
                if (id.isEmpty()) continue
                val artist = entry.url.pathSegments.dropLast(2).lastOrNull()
                val album = entry.url.pathSegments.dropLast(1).lastOrNull()
                result += Song(
                    title = name.substringBeforeLast('.').ifBlank { name },
                    path = RemoteLocator.forSource(source.id, id),
                    artist = artist,
                    album = album,
                    size = entry.size,
                    format = ext,
                    sourceType = Song.SOURCE_TYPE_WEBDAV,
                    sourceId = source.id,
                    remoteId = id,
                )
            }
        }
        result
    }

    override suspend fun stream(song: Song): RemoteRequest {
        val root = rootUrl ?: run { testConnection(); requireNotNull(rootUrl) }
        val id = requireNotNull(song.remoteId)
        require(!id.startsWith('/') && !id.contains("..")) { "无效的 WebDAV 路径" }
        val url = root.newBuilder().encodedPath(root.encodedPath.trimEnd('/') + "/" + id).build()
        require(withinRoot(root, url))
        return RemoteRequest(url.toString(), headers)
    }

    override suspend fun artwork(song: Song): ByteArray? = null
    override suspend fun lyrics(song: Song): String? = null

    private fun withinRoot(root: HttpUrl, item: HttpUrl): Boolean =
        item.scheme == root.scheme && item.host == root.host && item.port == root.port &&
            item.encodedPath.startsWith(root.encodedPath.trimEnd('/') + "/")

    private data class DavEntry(val url: HttpUrl, val collection: Boolean, val size: Long?)

    private fun propfind(url: HttpUrl, depth: String): List<DavEntry> {
        val body = """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>"""
        val request = Request.Builder().url(url)
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", depth).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw IllegalStateException("WebDAV 没有目录读取权限")
            if (response.code != 207) throw IllegalStateException("WebDAV 返回 HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: throw IllegalStateException("WebDAV 响应为空")
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setXIncludeAware(false)
                isExpandEntityReferences = false
            }
            val xml = stream.use { factory.newDocumentBuilder().parse(it) }
            val nodes = xml.getElementsByTagNameNS("DAV:", "response")
            val entries = ArrayList<DavEntry>()
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as? Element ?: continue
                val href = element.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue
                val item = url.resolve(href) ?: continue
                // Properties can fail independently inside a 207 response.
                val statuses = element.getElementsByTagNameNS("DAV:", "propstat")
                for (j in 0 until statuses.length) {
                    val propstat = statuses.item(j) as? Element ?: continue
                    val status = propstat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent.orEmpty()
                    if (!status.contains(" 200 ")) continue
                    val collection = propstat.getElementsByTagNameNS("DAV:", "collection").length > 0
                    val size = propstat.getElementsByTagNameNS("DAV:", "getcontentlength")
                        .item(0)?.textContent?.toLongOrNull()
                    entries += DavEntry(item, collection, size)
                }
            }
            return entries
        }
    }

    private companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "ape", "aiff", "wma")
    }
}
