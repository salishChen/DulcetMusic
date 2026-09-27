package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Music-only Emby API client. Tokens are scoped to this provider instance. */
class EmbyProvider(override val source: RemoteSource) : RemoteMusicProvider {
    private val client = OkHttpClient.Builder().followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var baseUrl: HttpUrl? = null
    private var token: String? = null
    private var userId: String? = null
    var serverIdentity: String? = null
        private set
    private val deviceId = "DulcetMusic-${source.id}"

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (base in listOf(source.intranetUrl, source.publicUrl).filter { it.isNotBlank() }) {
            val url = base.trim().toHttpUrlOrNull() ?: continue
            try {
                token = null
                userId = null
                serverIdentity = null
                baseUrl = null
                val body = JSONObject(call(url.newBuilder().addPathSegments("Users/AuthenticateByName").build(),
                    JSONObject().put("Username", source.username).put("Pw", source.password)
                        .toString().toRequestBody("application/json".toMediaType())))
                val authToken = body.optString("AccessToken")
                val accountId = body.optJSONObject("User")?.optString("Id").orEmpty()
                if (authToken.isBlank() || accountId.isBlank()) throw IllegalStateException("Emby 未返回登录凭据")
                token = authToken
                userId = accountId
                val identity = try {
                    JSONObject(call(url.newBuilder().addPathSegments("System/Info/Public").build()))
                        .optString("Id").takeIf { it.isNotBlank() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (source.serverIdentity != null && source.serverIdentity != identity) {
                    throw IllegalStateException("Emby 服务器身份与当前来源不一致，请作为新服务器保存")
                }
                serverIdentity = identity
                baseUrl = url
                return@withContext "Emby 登录成功"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                last = e
            }
        }
        throw last ?: IllegalArgumentException("请填写有效的 Emby 地址")
    }

    override suspend fun getAllSongs(): List<Song> = withContext(Dispatchers.IO) {
        val base = baseUrl ?: run { testConnection(); requireNotNull(baseUrl) }
        val user = requireNotNull(userId)
        val songs = ArrayList<Song>()
        var start = 0
        do {
            val url = base.newBuilder().addPathSegment("Users").addPathSegment(user).addPathSegment("Items")
                .addQueryParameter("Recursive", "true")
                .addQueryParameter("IncludeItemTypes", "Audio")
                .addQueryParameter("StartIndex", start.toString())
                .addQueryParameter("Limit", "200")
                .build()
            val response = JSONObject(call(url))
            val items = response.optJSONArray("Items") ?: break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("Id").takeIf { it.isNotBlank() } ?: continue
                val artists = item.optJSONArray("Artists")
                val artist = artists?.optString(0)?.takeIf { it.isNotBlank() }
                    ?: item.optString("Artist").takeIf { it.isNotBlank() }
                val mediaSources = item.optJSONArray("MediaSources")
                val versions = if (mediaSources == null || mediaSources.length() == 0) {
                    listOf(id to null)
                } else (0 until mediaSources.length()).mapNotNull { versionIndex ->
                    val media = mediaSources.optJSONObject(versionIndex) ?: return@mapNotNull null
                    val mediaId = media.optString("Id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    mediaId to media
                }
                for ((mediaId, media) in versions) {
                    val resourceId = resourceKey(id, mediaId)
                    val title = item.optString("Name").ifBlank { "未知歌曲" }
                    val versionName = media?.optString("Name")?.takeIf { it.isNotBlank() }
                    songs += Song(
                        title = if (versions.size > 1) "$title (${versionName ?: mediaId})" else title,
                        path = RemoteLocator.forSource(source.id, resourceId),
                        artist = artist,
                        album = item.optString("Album").takeIf { it.isNotBlank() },
                        albumArtist = item.optString("AlbumArtist").takeIf { it.isNotBlank() },
                        trackNumber = item.optInt("IndexNumber", -1).takeIf { it >= 0 },
                        duration = item.optLong("RunTimeTicks", 0).takeIf { it > 0 }?.div(10_000),
                        bitrate = media?.optInt("Bitrate", -1)?.takeIf { it > 0 },
                        format = media?.optString("Container")?.takeIf { it.isNotBlank() },
                        sourceType = Song.SOURCE_TYPE_EMBY,
                        sourceId = source.id,
                        remoteId = resourceId,
                        coverArtId = id.takeIf { item.optJSONObject("ImageTags")?.has("Primary") == true },
                    )
                }
            }
            start += items.length()
            if (items.length() == 0 || start >= response.optInt("TotalRecordCount", start)) break
        } while (true)
        songs
    }

    override suspend fun stream(song: Song): RemoteRequest {
        val base = baseUrl ?: run { testConnection(); requireNotNull(baseUrl) }
        val (itemId, mediaId) = parseResourceKey(requireNotNull(song.remoteId))
        val url = base.newBuilder().addPathSegment("Audio").addPathSegment(itemId)
            .addPathSegment("stream")
            .addQueryParameter("MediaSourceId", mediaId)
            .addQueryParameter("static", "true").build()
        return RemoteRequest(url.toString(), authHeaders())
    }

    override suspend fun artwork(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val id = song.coverArtId ?: return@withContext null
        val base = baseUrl ?: run { testConnection(); requireNotNull(baseUrl) }
        val url = base.newBuilder().addPathSegment("Items").addPathSegment(id)
            .addPathSegment("Images").addPathSegment("Primary").build()
        val request = Request.Builder().url(url).apply { authHeaders().forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            body.readArtworkBytes()
        }
    }

    override suspend fun lyrics(song: Song): String? = null

    private fun resourceKey(itemId: String, mediaSourceId: String): String =
        "${itemId.length}:$itemId$mediaSourceId"

    private fun parseResourceKey(key: String): Pair<String, String> {
        val divider = key.indexOf(':')
        val itemLength = key.substring(0, divider.coerceAtLeast(0)).toIntOrNull()
        if (divider <= 0 || itemLength == null || itemLength <= 0 ||
            key.length <= divider + 1 + itemLength) {
            // Reads songs imported by the initial Emby implementation.
            return key to key
        }
        return key.substring(divider + 1, divider + 1 + itemLength) to
            key.substring(divider + 1 + itemLength)
    }

    private fun authHeaders(): Map<String, String> = mapOf(
        "X-Emby-Token" to requireNotNull(token),
        "X-Emby-Authorization" to "Emby Client=\"DulcetMusic\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0\"",
    )

    private fun call(url: HttpUrl, post: okhttp3.RequestBody? = null): String {
        val request = Request.Builder().url(url).apply {
            header("X-Emby-Authorization", "Emby Client=\"DulcetMusic\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0\"")
            token?.let { header("X-Emby-Token", it) }
            if (post != null) post(post)
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw IllegalStateException("Emby 认证已失效")
            if (!response.isSuccessful) throw IllegalStateException("Emby 返回 HTTP ${response.code}")
            return response.body?.string() ?: throw IllegalStateException("Emby 响应为空")
        }
    }
}
