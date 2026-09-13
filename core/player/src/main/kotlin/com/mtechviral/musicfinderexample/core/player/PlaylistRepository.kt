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

    /** 判断歌曲是否已在播放列表中（以 path 唯一标识） */
    fun contains(song: Song): Boolean = _songs.value.any { it.path == song.path }

    /** 添加歌曲；已存在则返回 false */
    @Synchronized
    fun addSong(song: Song): Boolean {
        if (contains(song)) return false
        notify(_songs.value + song)
        return true
    }

    /** 用给定列表整体替换播放列表（"点击歌曲整列播放"场景） */
    @Synchronized
    fun setSongs(songs: List<Song>) {
        notify(songs.toList())
    }

    /** 移除指定歌曲 */
    @Synchronized
    fun removeSong(song: Song) {
        notify(_songs.value.filterNot { it.path == song.path })
    }

    /** 按索引移除 */
    @Synchronized
    fun removeAt(index: Int) {
        val list = _songs.value
        if (index in list.indices) {
            notify(list.toMutableList().apply { removeAt(index) })
        }
    }

    /** 更新指定 path 的歌曲对象（异步获取歌词/封面后刷新） */
    @Synchronized
    fun updateSong(updatedSong: Song) {
        val list = _songs.value
        val idx = list.indexOfFirst { it.path == updatedSong.path }
        if (idx >= 0) {
            notify(list.toMutableList().apply { this[idx] = updatedSong })
        }
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
    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        persist()
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
