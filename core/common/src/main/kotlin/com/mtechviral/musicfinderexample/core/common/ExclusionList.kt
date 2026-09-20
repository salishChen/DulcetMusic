package com.mtechviral.musicfinderexample.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 排除列表中的条目类型。
 *
 * 需求（第二十六轮需求 3）：
 * - [SONG]：排除**某一首歌**（按「歌曲名 + 歌手」定位）；
 * - [ARTIST]：排除**某位歌手**，之后拉取音乐时跳过该歌手的全部歌曲。
 */
enum class ExclusionType(val storageValue: String) {
    SONG("song"),
    ARTIST("artist");

    companion object {
        fun fromStorage(value: String?): ExclusionType =
            if (value == ARTIST.storageValue) ARTIST else SONG
    }
}

/**
 * 排除列表条目。
 *
 * @param type 条目类型（单曲 / 歌手）
 * @param title 歌曲名（[ExclusionType.ARTIST] 时为空串）
 * @param artist 歌手名（两种类型都有意义：单曲条目靠它一起定位）
 */
data class ExclusionEntry(
    val type: ExclusionType,
    val title: String,
    val artist: String,
) {
    /** 唯一标识：类型 + 歌名 + 歌手（用于列表 key 与去重） */
    val key: String
        get() = "${type.storageValue}\u0000${title.trim().lowercase()}\u0000${
            artist.trim().lowercase()
        }"

    /** 展示用文案 */
    val displayText: String
        get() = when (type) {
            ExclusionType.ARTIST -> artist.ifBlank { "未知艺术家" }
            ExclusionType.SONG -> buildString {
                append(title.ifBlank { "未知歌曲" })
                if (artist.isNotBlank()) append(" - ").append(artist)
            }
        }

    /** 展示用副标题 */
    val displayTypeText: String
        get() = if (type == ExclusionType.ARTIST) "歌手" else "歌曲"
}

/**
 * 排除列表（单例）。
 *
 * 需求（第二十六轮需求 3）：删除一首**在线音乐**时，除了删缓存，还要把
 * 「歌曲名 + 歌手」写入排除列表；下次扫描（含 Subsonic 远程拉取）时自动跳过。
 * 列表内可取消排除，取消后再次导入即可重新入库。
 *
 * 另外支持**排除歌手**：命中歌手的歌曲在导入时被跳过。
 *
 * 存储：`AppPreferences`（JSON 字符串），与其余偏好一致；进程内用
 * [StateFlow] 通知界面，设置页可即时刷新。
 */
object ExclusionList {

    private const val KEY_EXCLUSIONS = "excluded_songs_artists"

    /** 进程内快照（含单曲与歌手两类条目） */
    private val _entries = MutableStateFlow<List<ExclusionEntry>>(emptyList())
    val entries: StateFlow<List<ExclusionEntry>> = _entries.asStateFlow()

    /** 启动时从偏好恢复 */
    fun load() {
        _entries.value = parse(AppPreferences.getString(KEY_EXCLUSIONS, null))
    }

    /** 当前快照 */
    val current: List<ExclusionEntry> get() = _entries.value

    /** 仅单曲条目 */
    val songs: List<ExclusionEntry>
        get() = _entries.value.filter { it.type == ExclusionType.SONG }

    /** 仅歌手条目 */
    val artists: List<ExclusionEntry>
        get() = _entries.value.filter { it.type == ExclusionType.ARTIST }

    /** 是否为空（扫描/导入时用于快速短路） */
    val isEmpty: Boolean get() = _entries.value.isEmpty()

    /** 保存单曲排除（同一首歌重复加入时幂等） */
    fun addSong(title: String, artist: String?) {
        add(ExclusionEntry(ExclusionType.SONG, title.trim(), artist?.trim().orEmpty()))
    }

    /** 保存歌手排除（同一歌手重复加入时幂等） */
    fun addArtist(artist: String) {
        val name = artist.trim()
        if (name.isEmpty()) return
        add(ExclusionEntry(ExclusionType.ARTIST, "", name))
    }

    private fun add(entry: ExclusionEntry) {
        if (entry.type == ExclusionType.ARTIST && entry.artist.isBlank()) return
        if (entry.type == ExclusionType.SONG && entry.title.isBlank() && entry.artist.isBlank()) return
        val list = _entries.value
        if (list.any { it.key == entry.key }) return
        persist(list + entry)
    }

    /** 取消一条排除 */
    fun remove(entry: ExclusionEntry) {
        val list = _entries.value
        if (list.none { it.key == entry.key }) return
        persist(list.filterNot { it.key == entry.key })
    }

    /** 取消一条排除（按标识） */
    fun removeByKey(key: String) {
        val list = _entries.value
        if (list.none { it.key == key }) return
        persist(list.filterNot { it.key == key })
    }

    /** 清空排除列表 */
    fun clear() {
        if (_entries.value.isEmpty()) return
        persist(emptyList())
    }

    // ===================== 命中判定（扫描 / 导入时调用） =====================

    /**
     * 该「歌曲名 + 歌手」是否被排除（命中单曲条目 → 跳过导入）。
     *
     * 比较规则与 [com.mtechviral.musicfinderexample.core.model.Song.identityKey] 一致：
     * 去首尾空白 + 忽略大小写。歌名与歌手**都要命中**才算排除，避免误杀同名不同歌手的歌；
     * 歌手为空时只要有同名条目即视为命中。
     */
    fun isSongExcluded(title: String, artist: String?): Boolean {
        val list = _entries.value
        if (list.isEmpty()) return false
        val t = title.trim().lowercase()
        val a = artist?.trim().orEmpty().lowercase()
        return list.any { entry ->
            entry.type == ExclusionType.SONG &&
                entry.title.trim().lowercase() == t &&
                (entry.artist.trim().lowercase() == a || entry.artist.isBlank())
        }
    }

    /** 该歌手是否被整位排除 */
    fun isArtistExcluded(artist: String?): Boolean {
        val name = artist?.trim().orEmpty()
        if (name.isEmpty()) return false
        val lower = name.lowercase()
        return _entries.value.any {
            it.type == ExclusionType.ARTIST && it.artist.trim().lowercase() == lower
        }
    }

    /** 综合判定：歌曲命中单曲条目 **或** 其歌手被整位排除 */
    fun isExcluded(title: String, artist: String?): Boolean =
        isExcluded(title, artist, null)

    /**
     * 综合判定（含专辑艺术家）。
     *
     * 第二十七轮：排除歌手的判定必须**同时看 `artist` 与 `albumArtist`**，
     * 与 `LibrarySongDeleter.excludeArtistAndPurge` 的删除口径保持一致。
     * 否则合辑里 `artist` 为「群星」、`albumArtist` 才是被排除歌手的曲目，
     * 会出现"清库时删掉了、下次扫描又被导进来"的来回跳变。
     */
    fun isExcluded(title: String, artist: String?, albumArtist: String?): Boolean {
        if (isEmpty) return false
        return isSongExcluded(title, artist) ||
            isArtistExcluded(artist) ||
            isArtistExcluded(albumArtist)
    }

    // ===================== 持久化 =====================

    private fun persist(list: List<ExclusionEntry>) {
        _entries.value = list
        try {
            val arr = JSONArray()
            for (entry in list) {
                val obj = JSONObject()
                obj.put("type", entry.type.storageValue)
                obj.put("title", entry.title)
                obj.put("artist", entry.artist)
                arr.put(obj)
            }
            AppPreferences.putString(KEY_EXCLUSIONS, arr.toString())
        } catch (_: Exception) {
            // 持久化失败不影响内存态；下次写入会重试
        }
    }

    private fun parse(raw: String?): List<ExclusionEntry> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<ExclusionEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val type = ExclusionType.fromStorage(obj.optString("type"))
                val title = obj.optString("title")
                val artist = obj.optString("artist")
                if (type == ExclusionType.SONG && title.isBlank() && artist.isBlank()) continue
                if (type == ExclusionType.ARTIST && artist.isBlank()) continue
                out.add(ExclusionEntry(type, title, artist))
            }
            // 去重（历史数据可能含重复项）
            out.distinctBy { it.key }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
