package com.mtechviral.musicfinderexample.core.player

import android.util.Log
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.PlayMode
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * 播放列表数据管理（单例）。
 *
 * 与原 Flutter 工程 `lib/data/playlist_data.dart` 的 `PlaylistData` 一一对应：
 * 内存维护当前播放列表、提供增删/替换/模式切换，并向 UI 暴露响应式通知
 * （Dart 端为 `ValueNotifier`，这里为 `StateFlow`）。
 *
 * 播放列表与播放模式会持久化到偏好设置，重启后可恢复；
 * 与原实现一致采用 500ms 去抖，避免歌词/封面回填时高频写盘。
 */
object PlaylistRepository {

    private const val TAG = "PlaylistRepository"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())

    /** 列表内容变更通知（增删/替换时触发） */
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)

    /** 播放模式变更通知 */
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 当前列表快照（不可变） */
    val current: List<Song> get() = _songs.value

    val length: Int get() = _songs.value.size

    private var persistJob: Job? = null

    /** 判断歌曲是否已在播放列表中（以 path 标识；同一首歌可能有多份，此处只回答"有无"） */
    fun contains(song: Song): Boolean = _songs.value.any { it.path == song.path }

    /**
     * 追加歌曲到播放列表**末尾**。
     *
     * 第十六轮变更：**不再去重** —— 同一首歌可以在播放列表内出现多次
     * （需求：向播放列表添加歌曲时不检测列表内是否已存在该歌曲）。
     * 因此本方法不再返回"是否新增"（旧签名用 false 表示"已在列表中"，该语义已不存在）。
     *
     * 注意与 Dart 的差异：Dart `PlaylistData.addSong`（`playlist_data.dart:70-75`）
     * 会先 `contains` 判重、已存在则返回 false 且不入列；此处按要求刻意取消判重。
     */
    @Synchronized
    fun addSong(song: Song) {
        notify(_songs.value + song)
    }

    /**
     * 用给定列表整体替换播放列表（"点击歌曲整列播放"场景）。
     *
     * 刻意**不**在这里按当前模式打乱：本方法也被「播放全部」「点击某首歌」等
     * 显式选列流程使用，那些场景用户期望按看到的顺序入列；随机化只应发生在
     * 「进入随机模式」与「点随机播放按钮」这两个明确入口（见 [setPlayMode] /
     * [playShuffled]）。
     */
    @Synchronized
    fun setSongs(songs: List<Song>) {
        notify(songs.toList())
    }

    /**
     * 「随机播放」入口：把 [songs] 打乱后整体替换播放列表，并切换到随机模式。
     *
     * 只发一次通知（一次持久化）。若按「先 [setPlayMode] 再 [setSongs]」两步走，
     * 第一次 notify 会用**旧列表**重建一次播放队列、紧接着第二次 notify 又用新列表
     * 重建一次，白白多打断一次正在播放的音频。
     *
     * @return 实际存入的顺序（已打乱），调用方据此决定从哪首开始播。
     */
    @Synchronized
    fun playShuffled(songs: List<Song>): List<Song> {
        _playMode.value = PlayMode.RANDOM
        val shuffled = songs.shuffled()
        notify(shuffled)
        return shuffled
    }

    /**
     * 移除指定歌曲（按 path **移除全部同名单曲**）。
     *
     * 注意：同一首歌可能在队列里有多份，本方法会把它们全部移除。
     * 播放列表页要"只删被点的那一行"时应改用 [removeAt]（按位置精确删除）。
     */
    @Synchronized
    fun removeSong(song: Song) {
        removeSongs(listOf(song.path))
    }

    /**
     * 按 path 批量移出播放列表，返回实际移除的数量。
     *
     * 用于「曲库中永久删除歌曲」时同步清理播放列表（需求：删除正在播放的歌曲时，
     * 播放列表里也要删掉它并接续播放下一首）。整批一次 [notify]，
     * 避免逐首移除导致队列被反复重建。
     */
    @Synchronized
    fun removeSongs(paths: Collection<String>): Int {
        if (paths.isEmpty()) return 0
        val targets = paths.toSet()
        val list = _songs.value
        val remaining = list.filterNot { it.path in targets }
        if (remaining.size == list.size) return 0
        notify(remaining)
        return list.size - remaining.size
    }

    /**
     * 按**位置**精确移除一项（同一首歌出现多份时只删被点的那一份）。
     * 播放列表每行的「从播放列表移除」用这个，而不是按 path 全删。
     */
    @Synchronized
    fun removeAt(index: Int) {
        val list = _songs.value
        if (index in list.indices) {
            notify(list.toMutableList().apply { removeAt(index) })
        }
    }

    /**
     * 更新指定 path 的歌曲对象（异步获取歌词/封面/喜欢状态后刷新）。
     *
     * 同一首歌可能在列表中出现多份，这里**全部一并更新**，否则第 2 份会残留
     * 旧的歌词 / 喜欢 / 缓存状态（与界面显示不一致）。
     */
    @Synchronized
    fun updateSong(updatedSong: Song) {
        val list = _songs.value
        if (list.none { it.path == updatedSong.path }) return
        notify(list.map { if (it.path == updatedSong.path) updatedSong else it })
    }

    /** 清空播放列表 */
    @Synchronized
    fun clear() {
        notify(emptyList())
    }

    /** 切换播放模式（顺序 -> 随机 -> 单曲 -> 顺序），返回切换后的模式 */
    fun togglePlayMode(): PlayMode {
        val mode = _playMode.value.next()
        setPlayMode(mode)
        return mode
    }

    /** 直接设置播放模式 */
    @Synchronized
    fun setPlayMode(mode: PlayMode) {
        val previous = _playMode.value
        _playMode.value = mode
        // 进入随机模式：把当前播放列表本身随机排序（需求：随机排序当前歌曲列表，
        // 之后顺序播放该列表）。列表顺序即播放顺序，所以这里必须真正改动列表，
        // 而不是只打开播放器内部的 shuffle（那不会改变列表顺序）。
        if (mode == PlayMode.RANDOM && previous != PlayMode.RANDOM && _songs.value.size > 1) {
            notify(_songs.value.shuffled())
        } else {
            persist()
        }
    }

    /** 内部通知：推送给监听者并持久化 */
    private fun notify(list: List<Song>) {
        _songs.value = list
        persist()
    }

    // ===================== 播放列表持久化 =====================

    /**
     * 将当前播放列表及播放模式异步写入偏好设置（500ms 去抖）。
     * 与 Dart 端一致：只保存每首歌的 id / path 标识，恢复时回查数据库。
     */
    private fun persist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            try {
                val data = JSONArray()
                for (song in _songs.value) {
                    val obj = JSONObject()
                    if (song.id != null) obj.put("id", song.id) else obj.put("id", JSONObject.NULL)
                    obj.put("path", song.path)
                    data.put(obj)
                }
                AppPreferences.putString(AppPreferences.KEY_LAST_PLAYLIST_SONGS, data.toString())
                AppPreferences.putInt(AppPreferences.KEY_LAST_PLAYLIST_MODE, _playMode.value.ordinal)
            } catch (e: Exception) {
                // 持久化失败不影响播放
                Log.w(TAG, "persist failed: ${e.message}")
            }
        }
    }

    /**
     * 从偏好设置恢复上次关闭前的播放列表与播放模式。
     * 已从库中删除的歌曲会被跳过；返回实际恢复的歌曲数。
     */
    suspend fun restoreFromPrefs(): Int {
        return try {
            val raw = AppPreferences.getString(AppPreferences.KEY_LAST_PLAYLIST_SONGS)
            val savedMode = AppPreferences.getInt(AppPreferences.KEY_LAST_PLAYLIST_MODE, -1)
            if (savedMode in PlayMode.entries.indices) {
                _playMode.value = PlayMode.fromIndex(savedMode)
            }
            if (raw.isNullOrEmpty()) return 0

            val items = JSONArray(raw)
            val restored = ArrayList<Song>(items.length())
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val id = if (obj.isNull("id")) null else obj.optLong("id")
                val path = obj.optString("path").ifEmpty { null }

                var song: Song? = null
                if (id != null) song = DatabaseHelper.querySongById(id)
                if (song == null && path != null) song = DatabaseHelper.querySongByPath(path)
                if (song != null) restored.add(song)
            }
            notify(restored)
            restored.size
        } catch (e: Exception) {
            Log.w(TAG, "恢复播放列表失败: ${e.message}")
            0
        }
    }

    private const val PERSIST_DEBOUNCE_MS = 500L
}
