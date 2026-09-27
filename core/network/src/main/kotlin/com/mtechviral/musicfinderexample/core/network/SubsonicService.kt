package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.RemoteLocator
import com.mtechviral.musicfinderexample.core.common.UrlSanitizer
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Subsonic API 客户端（单例）。
 *
 * 与原 Flutter 工程 `lib/data/subsonic_service.dart` 逐方法对应：
 * - 内网优先连接策略（内网 5s 超时，失败回退公网 10s），并把结果缓存在 [activeBaseUrl]；
 *   内网与公网地址均非必填，只填其一则只使用该地址；
 * - token 认证：`t = md5(password + salt)`，每次请求重新生成 16 位随机盐；
 * - API 版本 1.16.1，`f=json`，客户端名 `MusicPlayer`；
 * - 歌词 `getLyrics`、封面 `getCoverArt`、流地址 `stream`、歌单同步等全部保留。
 */
object SubsonicService {

    private const val API_VERSION = "1.16.1"
    private const val CLIENT_NAME = "MusicPlayer"
    private const val SALT_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val TAG = "SubsonicService"

    private var config: SubsonicConfig? = null
    private var activeBaseUrl: String? = null
    @Volatile var currentSourceId: String? = null

    /**
     * EasyTier 组网的本地转发地址（如 `http://127.0.0.1:18080`，由 core:easytier 注入）。
     *
     * 启用后地址探测顺序变为：**EasyTier → 内网 → 公网**（优化文档 doc/EasyTier集成方案.md）；
     * 未启用时为 null，现有内网/公网直连行为不变。
     */
    @Volatile
    var easyTierBaseUrl: String? = null

    private val secureRandom = SecureRandom()

    /** 普通 API 请求：30s 超时（与 Dart 端一致） */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 内网探测：5s 超时 */
    private val intranetProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /** 公网探测：10s 超时 */
    private val publicProbeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ===================== 配置 =====================

    /** 是否已配置 */
    val isConfigured: Boolean get() = config != null

    /** 当前配置（供设置页展示服务器名等） */
    val currentConfig: SubsonicConfig? get() = config

    /** 加载配置（应用启动时调用） */
    suspend fun loadConfig(): SubsonicConfig? {
        config = DatabaseHelper.querySubsonicConfig()
        activeBaseUrl = null // 重置缓存的 base URL
        return config
    }

    /** 保存配置 */
    suspend fun saveConfig(newConfig: SubsonicConfig) {
        DatabaseHelper.saveSubsonicConfig(newConfig)
        config = newConfig
        activeBaseUrl = null
    }

    /** Switch the active source without writing the legacy single-Subsonic configuration. */
    fun activate(newConfig: SubsonicConfig) {
        config = newConfig
        activeBaseUrl = null
    }

    /** 清除缓存的连接（网络变化/播放失败时调用） */
    fun resetConnection() {
        activeBaseUrl = null
    }

    /** 取当前配置，未配置时抛出 [SubsonicException] */
    private fun requireConfig(): SubsonicConfig =
        config ?: throw SubsonicException("Subsonic 未配置")

    /**
     * 远程导入使用的来源标识（优化建议 03）：`subsonic@用户名@服务器地址`。
     *
     * 同一服务器重复扫描命中同一来源；换服务器后来源不同，
     * 不会把新服务器的同 remoteId 歌曲与旧服务器的记录误合并。
     * 旧库中来源为 `subsonic` 的行由合并逻辑兼容迁移。
     */
    fun importSourceTag(): String {
        val cfg = config ?: return Song.SOURCE_TYPE_SUBSONIC
        val host = cfg.intranetUrl.trim().ifEmpty { cfg.publicUrl.trim() }
        return "${Song.SOURCE_TYPE_SUBSONIC}@${cfg.username.trim()}@$host"
    }

    // ===================== URL 构建 =====================

    /**
     * 解析活跃的 Base URL（内网优先，超时回退公网）。
     *
     * 内网与公网地址都不要求必填：只填写其中一个时只探测该地址，
     * 两个都填写时按「内网 5s → 公网 10s」的顺序依次探测。
     */
    private suspend fun resolveBaseUrl(): String {
        activeBaseUrl?.let {
            // 省电：远程访问即视为组网活动，阻止空闲休眠
            com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine.touch()
            return it
        }
        val cfg = config ?: throw SubsonicException("Subsonic 未配置")

        // 省电：EasyTier 组网按需唤醒（空闲 15 分钟自动休眠），就绪后再探测
        com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine.touch()
        com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine.awaitRunning()

        val candidates = cfg.resolvableBaseUrls()
        if (candidates.isEmpty()) {
            throw SubsonicException("Subsonic 未配置服务器地址（内网或公网地址至少填写一个）")
        }

        for ((baseUrl, probeClient) in candidates) {
            try {
                val body = httpGet(buildUrl(baseUrl, "ping"), probeClient)
                if (body != null && JSONObject(body).optJSONObject("subsonic-response")
                        ?.optString("status") == "ok"
                ) {
                    activeBaseUrl = baseUrl
                    return baseUrl
                }
            } catch (_: Exception) {
                // 该地址不可达，继续尝试下一个
            }
        }

        throw SubsonicException("无法连接到 Subsonic 服务器（已填写的地址均不可达）")
    }

    /**
     * 按优先级列出候选地址及其探测客户端：
     * EasyTier 本地转发（5s）在最前，内网（5s 超时）次之，公网（10s 超时）最后。
     * 未填写/未启用的地址直接跳过。
     */
    private fun SubsonicConfig.resolvableBaseUrls(): List<Pair<String, OkHttpClient>> = listOfNotNull(
        easyTierBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { it to intranetProbeClient },
        intranetUrl.trim().takeIf { it.isNotEmpty() }?.let { it to intranetProbeClient },
        publicUrl.trim().takeIf { it.isNotEmpty() }?.let { it to publicProbeClient },
    )

    /**
     * 探测失败时的兜底地址（与探测顺序一致：优先 EasyTier，其次内网，最后公网）；
     * 均未填写时返回空串。
     */
    private fun SubsonicConfig.preferredBaseUrl(): String =
        easyTierBaseUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: intranetUrl.trim().ifEmpty { publicUrl.trim() }

    private fun buildUrl(baseUrl: String, endpoint: String,
                         parameters: List<Pair<String, String>> = emptyList()): String =
        buildUrl(baseUrl, endpoint, requireConfig(), parameters)

    /** 以指定配置构建 API URL（连接测试用，不读取/修改全局 [config]） */
    private fun buildUrl(baseUrl: String, endpoint: String, cfg: SubsonicConfig,
                         parameters: List<Pair<String, String>> = emptyList()): String {
        require(endpoint.isNotBlank() && endpoint.all { it.isLetterOrDigit() })
        val base = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("服务器地址格式错误")
        val builder = base.newBuilder().addPathSegment("rest").addPathSegment(endpoint)
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        val salt = generateSalt(16)
        val token = md5Hex(cfg.password + salt)
        builder.addQueryParameter("u", cfg.username)
            .addQueryParameter("s", salt)
            .addQueryParameter("t", token)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        return builder.build().toString()
    }

    /** 生成随机盐值 */
    private fun generateSalt(length: Int): String =
        buildString(length) {
            repeat(length) { append(SALT_CHARS[secureRandom.nextInt(SALT_CHARS.length)]) }
        }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ===================== 请求 =====================

    private suspend fun httpGet(url: String, client: OkHttpClient): String? =
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        }

    /** 发送 API 请求并校验 `subsonic-response.status` */
    private suspend fun request(endpoint: String,
                                parameters: List<Pair<String, String>> = emptyList()): JSONObject {
        val baseUrl = resolveBaseUrl()
        val url = buildUrl(baseUrl, endpoint, parameters)
        val body = httpGet(url, apiClient)
            ?: throw SubsonicException("HTTP 请求失败: ${redactSensitive(url)}")
        val json = JSONObject(body)
        val subsonicResponse = json.optJSONObject("subsonic-response")
            ?: throw SubsonicException("响应缺少 subsonic-response 字段")
        if (subsonicResponse.optString("status") != "ok") {
            val error = subsonicResponse.optJSONObject("error")
            val code = error?.opt("code") ?: "unknown"
            val message = error?.optString("message") ?: "未知错误"
            throw SubsonicException("Subsonic 错误 ($code): $message")
        }
        return subsonicResponse
    }

    /** 测试连接 */
    suspend fun ping(): Boolean = try {
        val baseUrl = resolveBaseUrl()
        val body = httpGet(buildUrl(baseUrl, "ping"), publicProbeClient)
        if (body == null) false
        else JSONObject(body).optJSONObject("subsonic-response")?.optString("status") == "ok"
    } catch (_: Exception) {
        false
    }

    // ===================== 连接测试（不写入配置） =====================

    /** 「测试连接」的结果分类：区分地址格式错误、认证失败与网络不可达。 */
    sealed class ConnectionTestResult {
        /** 连接成功 */
        object Success : ConnectionTestResult()

        /** 服务器可达但认证失败（用户名/密码错误） */
        data class AuthFailed(val message: String) : ConnectionTestResult()

        /** 网络不可达或 HTTP 失败 */
        data class Unreachable(val message: String) : ConnectionTestResult()

        /** 服务器地址格式错误 */
        data class InvalidUrl(val message: String) : ConnectionTestResult()
    }

    /**
     * 测试连接：仅用 [testConfig] 探测服务器，
     * **不写数据库、不修改当前活跃配置**（测试失败不影响已保存的服务器配置）。
     *
     * 两个地址都填写时按「内网 → 公网」顺序探测，任一成功即成功；
     * 全部失败时返回最后一次失败的分类结果。
     */
    suspend fun testConnection(testConfig: SubsonicConfig): ConnectionTestResult {
        val candidates = testConfig.resolvableBaseUrls()
        if (candidates.isEmpty()) {
            return ConnectionTestResult.InvalidUrl("未填写服务器地址（内网或公网至少填写一个）")
        }
        // 先校验地址格式（OkHttp 非法 URL 会抛 IllegalArgumentException）
        for ((baseUrl, _) in candidates) {
            if (!isHttpUrl(baseUrl)) {
                return ConnectionTestResult.InvalidUrl("服务器地址需以 http:// 或 https:// 开头")
            }
        }

        var lastFailure: ConnectionTestResult = ConnectionTestResult.Unreachable("无法连接到服务器")
        for ((baseUrl, probeClient) in candidates) {
            val result = probePing(testConfig, baseUrl, probeClient)
            if (result is ConnectionTestResult.Success) return result
            lastFailure = result
        }
        return lastFailure
    }

    private suspend fun probePing(
        cfg: SubsonicConfig,
        baseUrl: String,
        probeClient: OkHttpClient,
    ): ConnectionTestResult = try {
        val body = httpGet(buildUrl(baseUrl, "ping", cfg), probeClient)
        when {
            body == null -> ConnectionTestResult.Unreachable("HTTP 请求失败")
            else -> {
                val response = JSONObject(body).optJSONObject("subsonic-response")
                when {
                    response == null -> ConnectionTestResult.Unreachable("响应缺少 subsonic-response 字段")
                    response.optString("status") == "ok" -> ConnectionTestResult.Success
                    else -> {
                        val error = response.optJSONObject("error")
                        val code = error?.optInt("code", -1) ?: -1
                        // 40/41/42：用户名或密码错误 / token 认证不支持
                        if (code in setOf(40, 41, 42)) {
                            ConnectionTestResult.AuthFailed("用户名或密码错误")
                        } else {
                            val message = error?.optString("message") ?: "未知错误"
                            ConnectionTestResult.Unreachable("Subsonic 错误 ($code): $message")
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        if (e is IllegalArgumentException) {
            ConnectionTestResult.InvalidUrl("服务器地址格式错误")
        } else {
            ConnectionTestResult.Unreachable(redactSensitive(e.message ?: "连接失败"))
        }
    }

    private fun isHttpUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    // ===================== 曲库 =====================

    /**
     * 获取所有歌曲（getArtists -> getArtist -> getAlbum 三级遍历）。
     * 与 Dart 端一致：单个艺术家/专辑失败时跳过继续，不影响整体。
     */
    suspend fun getAllSongs(): List<Song> {
        val allSongs = ArrayList<Song>()

        val artistsResponse = request("getArtists")
        val indexes = artistsResponse.optJSONObject("artists")?.optJSONArray("index")
            ?: JSONArray()

        for (i in 0 until indexes.length()) {
            val index = indexes.optJSONObject(i) ?: continue
            val artists = index.optJSONArray("artist") ?: JSONArray()
            for (j in 0 until artists.length()) {
                val artist = artists.optJSONObject(j) ?: continue
                val artistId = artist.opt("id")?.toString() ?: continue
                val artistName = artist.optString("name").ifEmpty { "未知艺术家" }

                try {
                    val albumResponse = request("getArtist", listOf("id" to artistId))
                    val albums = albumResponse.optJSONObject("artist")?.optJSONArray("album")
                        ?: JSONArray()
                    for (k in 0 until albums.length()) {
                        val album = albums.optJSONObject(k) ?: continue
                        val albumId = album.opt("id")?.toString() ?: continue
                        // Subsonic getArtist 返回的 album 对象使用 name 字段（而非 title）
                        val albumName = album.optString("name")
                            .ifEmpty { album.optString("title") }
                            .ifEmpty { "未知专辑" }
                        val albumCoverArt = album.optString("coverArt").ifEmpty { null }

                        try {
                            val songResponse = request("getAlbum", listOf("id" to albumId))
                            val songs = songResponse.optJSONObject("album")?.optJSONArray("song")
                                ?: JSONArray()
                            for (m in 0 until songs.length()) {
                                val songObj = songs.optJSONObject(m) ?: continue
                                allSongs.add(
                                    parseSong(
                                        songObj, artistName, albumName,
                                        albumCoverArtFallback = albumCoverArt ?: albumId,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            log("获取专辑 $albumName 歌曲失败: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    log("获取艺术家 $artistName 专辑失败: ${e.message}")
                }
            }
        }
        return allSongs
    }

    /** 解析 getAlbum 的 song 项为 [Song] */
    private fun parseSong(
        song: JSONObject,
        artistName: String,
        albumName: String,
        albumCoverArtFallback: String?,
    ): Song {
        val id = song.opt("id")?.toString() ?: ""
        val title = song.optString("title").ifEmpty { "未知歌曲" }
        val durationSeconds = song.optInt("duration", -1).takeIf { it >= 0 }
        val track = song.optInt("track", -1).takeIf { it >= 0 }
        val size = song.optLong("size", -1L).takeIf { it >= 0 }
        val suffix = song.optString("suffix").ifEmpty { null }
        val bitRate = song.optInt("bitRate", -1).takeIf { it >= 0 }
        val contentType = song.optString("contentType").ifEmpty { null }
        val coverArt = song.optString("coverArt").ifEmpty { null }

        // 优化建议 01：path 只存稳定定位符，不持久化带认证参数的流地址；
        // 播放/下载时由 getStreamUrl / buildStreamUrlSync 临时生成
        val songArtist = song.optString("artist").ifBlank { artistName }
        val albumArtist = song.optString("albumArtist").takeIf { it.isNotBlank() }
        val identityKey = "${title.trim().lowercase()}|${songArtist.trim().lowercase()}|" +
            albumName.trim().lowercase()
        val locator = RemoteLocator.subsonic(id, importSourceTag(), identityKey)

        return Song(
            title = title,
            path = locator,
            artist = songArtist,
            album = albumName,
            albumArtist = albumArtist,
            trackNumber = track,
            duration = durationSeconds?.let { it * 1000L },
            size = size,
            format = suffix,
            codec = contentType,
            bitrate = bitRate?.times(1000),
            sourceType = Song.SOURCE_TYPE_SUBSONIC,
            remoteId = id,
            remoteStreamUrl = null,
            coverArtId = coverArt ?: albumCoverArtFallback,
            dateAdded = System.currentTimeMillis(),
        )
    }

    /** 获取流媒体 URL（先解析活跃 Base URL，失败回退到优先地址） */
    suspend fun getStreamUrl(songId: String): String {
        val cfg = config ?: throw SubsonicException("Subsonic 未配置")
        val baseUrl = try {
            resolveBaseUrl()
        } catch (_: Exception) {
            cfg.preferredBaseUrl()
        }
        return buildUrl(baseUrl, "stream", listOf("id" to songId))
    }

    /**
     * 同步构建流地址（**不发起网络探测**）：队列同步等不能挂起的场景使用。
     *
     * 未探测到活跃地址时回退优先地址（与 [getStreamUrl] 的兜底一致）。
     * 返回的 URL 带认证参数、**只存在于内存/播放器中，不落数据库**
     * （优化建议 01：库里只保存 [RemoteLocator] 稳定定位符）。
     */
    fun buildStreamUrlSync(remoteId: String): String? {
        val cfg = config ?: return null
        val baseUrl = activeBaseUrl ?: cfg.preferredBaseUrl()
        if (baseUrl.isEmpty()) return null
        return buildUrl(baseUrl, "stream", cfg, listOf("id" to remoteId))
    }

    // ===================== 歌单 =====================

    /** 获取歌单列表 */
    suspend fun getPlaylists(): List<RemotePlaylist> {
        val response = request("getPlaylists")
        val array = response.optJSONObject("playlists")?.optJSONArray("playlist") ?: JSONArray()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            RemotePlaylist(
                id = obj.opt("id")?.toString() ?: return@mapNotNull null,
                name = obj.optString("name").ifEmpty { "未命名歌单" },
            )
        }
    }

    /** 获取歌单详情（包含歌曲列表） */
    suspend fun getPlaylistDetail(playlistId: String): RemotePlaylistDetail {
        val response = request("getPlaylist", listOf("id" to playlistId))
        val playlist = response.optJSONObject("playlist") ?: JSONObject()
        val entries = playlist.optJSONArray("entry") ?: JSONArray()
        val songs = (0 until entries.length()).mapNotNull { i ->
            val obj = entries.optJSONObject(i) ?: return@mapNotNull null
            RemoteSong(
                id = obj.opt("id")?.toString() ?: return@mapNotNull null,
                title = obj.optString("title").ifEmpty { "未知歌曲" },
                artist = obj.optString("artist").ifEmpty { null },
                album = obj.optString("album").ifEmpty { null },
                durationSeconds = obj.optInt("duration", -1).takeIf { it >= 0 },
                size = obj.optLong("size", -1L).takeIf { it >= 0 },
                suffix = obj.optString("suffix").ifEmpty { null },
                contentType = obj.optString("contentType").ifEmpty { null },
                bitRate = obj.optInt("bitRate", -1).takeIf { it >= 0 },
                track = obj.optInt("track", -1).takeIf { it >= 0 },
                coverArt = obj.optString("coverArt").ifEmpty { null },
            )
        }
        return RemotePlaylistDetail(
            id = playlist.opt("id")?.toString() ?: playlistId,
            name = playlist.optString("name").ifEmpty { "未命名歌单" },
            entries = songs,
        )
    }

    /** 创建远程歌单，返回新歌单 id */
    suspend fun createPlaylist(name: String, songIds: List<String>): String? = try {
        val parameters = listOf("name" to name) + songIds.map { "songId" to it }
        val response = request("createPlaylist", parameters)
        response.optJSONObject("playlist")?.opt("id")?.toString()
    } catch (e: Exception) {
        log("创建歌单失败: ${e.message}")
        null
    }

    /**
     * 同步远程歌单到本地库，返回同步成功的歌单数量。
     *
     * 与 Dart 端逻辑一致：同名歌单复用本地记录，歌单内歌曲按 remoteId
     * 去重后入库并追加到歌单末尾。
     */
    suspend fun syncPlaylistsToLocalStorage(): Int {
        val sourceId = currentSourceId ?: throw SubsonicException("远程来源未绑定")
        val remotePlaylists = getPlaylists()
        var synced = 0

        for (remote in remotePlaylists) {
            val remoteName = remote.name
            val remoteId = remote.id

            // 检查本地是否已存在同名歌单
            val localName = "远程·$remoteName (${sourceId.take(8)})"
            val existing = DatabaseHelper.findPlaylistByName(localName)
            val localPlaylistId: Long = existing?.id
                ?: DatabaseHelper.createPlaylist(localName)
                ?: continue

            try {
                val detail = getPlaylistDetail(remoteId)
                for (song in detail.entries) {
                    val songId = song.id
                    // 优化建议 01：path 存稳定定位符，不持久化认证 URL
                    val identityKey = "${song.title.trim().lowercase()}|" +
                        "${(song.artist ?: "").trim().lowercase()}|" +
                        (song.album ?: "").trim().lowercase()
                    val locator = RemoteLocator.forSource(sourceId, songId)

                    // 检查本地是否已有该远程歌曲
                    val existingSong = DatabaseHelper.querySongByRemoteId(sourceId, songId)
                    val existingSongId = existingSong?.id
                    val localSongId: Long
                    if (existingSongId != null) {
                        localSongId = existingSongId
                    } else {
                        // 插入新歌曲记录
                        val newSong = Song(
                            title = song.title,
                            path = locator,
                            artist = song.artist,
                            album = song.album,
                            trackNumber = song.track,
                            duration = song.durationSeconds?.let { it * 1000L },
                            size = song.size,
                            format = song.suffix,
                            codec = song.contentType,
                            sourceType = Song.SOURCE_TYPE_SUBSONIC,
                            sourceId = sourceId,
                            remoteId = songId,
                            remoteStreamUrl = null,
                            dateAdded = System.currentTimeMillis(),
                        )
                        val affected = DatabaseHelper.insertSongs(listOf(newSong), importSourceTag())
                        if (affected == 0) continue
                        // 重新查询获取 id
                        localSongId = DatabaseHelper.querySongByRemoteId(sourceId, songId)?.id ?: continue
                    }

                    DatabaseHelper.addSongToPlaylist(localPlaylistId, localSongId)
                }
                synced++
            } catch (e: Exception) {
                log("同步歌单 $remoteName 失败: ${e.message}")
            }
        }
        return synced
    }

    // ===================== 封面 / 歌词 =====================

    /**
     * 获取封面图片字节。
     * 只检查状态码和数据长度（某些服务器不返回正确的 content-type）。
     */
    suspend fun getCoverArt(coverArtId: String): ByteArray? = try {
        val baseUrl = resolveBaseUrl()
        val url = buildUrl(baseUrl, "getCoverArt", listOf("id" to coverArtId))
        withContext(Dispatchers.IO) {
            apiClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val bytes = response.body?.bytes()
                if (response.isSuccessful && bytes != null && bytes.size > 100) {
                    bytes
                } else {
                    log(
                        "封面响应异常 - 状态码: ${response.code}, " +
                            "大小: ${bytes?.size ?: 0}",
                    )
                    null
                }
            }
        }
    } catch (e: Exception) {
        log("获取封面失败 ($coverArtId): ${e.message}")
        null
    }

    /** 获取歌词文本（LRC），失败返回 null */
    suspend fun getLyrics(artist: String, title: String): String? = try {
        val response = request("getLyrics", listOf("artist" to artist, "title" to title))
        val lyrics = response.optJSONObject("lyrics")?.optString("value")
        if (!lyrics.isNullOrBlank()) {
            log("成功获取歌词 - $artist - $title")
            lyrics.trim()
        } else {
            log("歌词为空 - $artist - $title")
            null
        }
    } catch (e: Exception) {
        log("获取歌词失败 - $artist - $title: ${e.message}")
        null
    }

    private fun log(message: String) {
        android.util.Log.d(TAG, redactSensitive(message))
    }

    /**
     * 日志/错误信息脱敏：把 URL 查询串中的认证参数（u / s / t）替换为 `***`，
     * 防止用户名、盐值与认证 token 进入日志或异常消息（优化建议 01）。
     */
    private fun redactSensitive(text: String): String = UrlSanitizer.redact(text)
}
