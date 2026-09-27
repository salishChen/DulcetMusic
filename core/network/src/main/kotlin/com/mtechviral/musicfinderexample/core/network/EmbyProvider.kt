package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import com.mtechviral.musicfinderexample.core.model.Song
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
    private val deviceId = "DulcetMusic-${source.id}"

    override suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (base in listOf(source.intranetUrl, source.publicUrl).filter { it.isNotBlank() }) {
            val url = base.trim().toHttpUrlOrNull() ?: continue
            try {
                val body = JSONObject(call(url.newBuilder().addPathSegments("Users/AuthenticateByName").build(),
                    JSONObject().put("Username", source.username).put("Pw", source.password)
                        .toString().toRequestBody("application/json".toMediaType())))
                val authToken = body.optString("AccessToken")
                val accountId = body.optJSONObject("User")?.optString("Id").orEmpty()
                if (authToken.isBlank() || accountId.isBlank()) throw IllegalStateException("Emby 未返回登录凭据")
                token = authToken
                userId = accountId
                baseUrl = url
                return@withContext "Emby 登录成功"
            } catch (e: Exception) {
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
            val url = base.newBuilder().addPathSegments("Users/$user/Items")
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
                songs += Song(
                    title = item.optString("Name").ifBlank { "未知歌曲" },
                    path = RemoteLocator.forSource(source.id, id),
                    artist = artist,
                    album = item.optString("Album").takeIf { it.isNotBlank() },
                    albumArtist = item.optString("AlbumArtist").takeIf { it.isNotBlank() },
                    trackNumber = item.optInt("IndexNumber", -1).takeIf { it >= 0 },
                    duration = item.optLong("RunTimeTicks", 0).takeIf { it > 0 }?.div(10_000),
                    sourceType = Song.SOURCE_TYPE_EMBY,
                    sourceId = source.id,
                    remoteId = id,
                    coverArtId = id.takeIf { item.optJSONObject("ImageTags")?.has("Primary") == true },
                )
            }
            start += items.length()
            if (items.length() == 0 || start >= response.optInt("TotalRecordCount", start)) break
        } while (true)
        songs
    }

    override suspend fun stream(song: Song): RemoteRequest {
        val base = baseUrl ?: run { testConnection(); requireNotNull(baseUrl) }
        val id = requireNotNull(song.remoteId)
        val url = base.newBuilder().addPathSegments("Audio/$id/stream")
            .addQueryParameter("static", "true").build()
        return RemoteRequest(url.toString(), authHeaders())
    }

    override suspend fun artwork(song: Song): ByteArray? = withContext(Dispatchers.IO) {
        val id = song.coverArtId ?: return@withContext null
        val base = baseUrl ?: run { testConnection(); requireNotNull(baseUrl) }
        val url = base.newBuilder().addPathSegments("Items/$id/Images/Primary").build()
        val request = Request.Builder().url(url).apply { authHeaders().forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            if (body.contentLength() > 4_000_000) return@withContext null
            body.bytes().takeIf { it.size <= 4_000_000 }
        }
    }

    override suspend fun lyrics(song: Song): String? = null

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
