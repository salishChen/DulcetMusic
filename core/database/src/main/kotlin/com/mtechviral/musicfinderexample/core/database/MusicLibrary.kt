package com.mtechviral.musicfinderexample.core.database

import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * 曲库数据管理（单例）。
 *
 * 对应原 Flutter 工程 `lib/data/song_data.dart` 的 `SongData`：
 * 持有数据库歌曲快照，[reload] 从数据库刷新，[songs] 对外提供响应式更新
 * （扫描完成后刷新各页面）。
 *
 * 与原实现的差异：原 `SongData` 同时持有全局 AudioPlayer；
 * 原生端播放器已由 [com.mtechviral.musicfinderexample.core.player.PlayerController]
 * 统一管理，此处只负责曲库数据，职责更单一。
 */
object MusicLibrary {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())

    /** 歌曲列表变更通知 */
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    /** 当前列表快照 */
    val current: List<Song> get() = _songs.value

    val length: Int get() = _songs.value.size

    /** 最近一次[setCurrentIndex]设置的下标（对应 Dart 端 _currentSongIndex） */
    private var currentSongIndex = -1

    /** 从数据库加载曲库 */
    suspend fun load() {
        _songs.value = DatabaseHelper.queryAllSongs()
        if (currentSongIndex >= _songs.value.size) currentSongIndex = -1
    }

    /** 从数据库重新加载歌曲列表（扫描完成后调用） */
    suspend fun reload() = load()

    /** 外部传入新列表（删除后刷新等场景） */
    fun updateSongs(newSongs: List<Song>) {
        _songs.value = newSongs
        if (currentSongIndex >= newSongs.size) currentSongIndex = -1
    }

    /** 更新指定歌曲对象（异步获取歌词/封面后刷新列表中的歌曲） */
    fun updateSong(updatedSong: Song) {
        val list = _songs.value
        val idx = list.indexOfFirst { it.path == updatedSong.path }
        if (idx >= 0) {
            _songs.value = list.toMutableList().apply { this[idx] = updatedSong }
        }
    }

    fun setCurrentIndex(index: Int) {
        currentSongIndex = index
    }

    val songNumber: Int get() = currentSongIndex + 1

    val currentIndex: Int get() = currentSongIndex

    /** 下一首（会推进内部下标，与原实现一致） */
    val nextSong: Song?
        get() {
            if (currentSongIndex < length) currentSongIndex++
            if (currentSongIndex >= length) return null
            return _songs.value[currentSongIndex]
        }

    /** 随机一首 */
    val randomSong: Song?
        get() = if (_songs.value.isEmpty()) null else _songs.value[Random.nextInt(_songs.value.size)]

    /** 上一首（会回退内部下标，与原实现一致） */
    val prevSong: Song?
        get() {
            if (currentSongIndex > 0) currentSongIndex--
            if (currentSongIndex < 0 || _songs.value.isEmpty()) return null
            return _songs.value[currentSongIndex]
        }
}
