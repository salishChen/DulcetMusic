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

    /**
     * 当前播放曲的 path（由 `PlayerController` 每次切歌时同步）。
     *
     * 用途（第二十二轮需求 4）：进入/维持随机模式而重排队列时，
     * 需要把**当前正在播放的那首放到第一位**，其余再随机排序。
     * 仓库自身不持有播放器状态，因此由播放器侧回填这个值。
     */
    @Volatile
    var currentPath: String? = null

    /**
     * 「原始顺序」快照（第二十二轮需求 4）。
     *
     * 随机模式会真正打乱 [_songs]（列表顺序即播放顺序），因此必须单独记住
     * 入列时的原始顺序；切回顺序播放模式时用它还原。
     * 与 [_songs] 保持**同一多重集**：增删改都会同步作用到两个列表。
     */
    private var originalOrder: List<Song> = emptyList()

    private var persistJob: Job? = null

    /** 判断歌曲是否已在播放列表中（以 path 标识；同一首歌可能有多份，此处只回答"有无"） */
    fun contains(song: Song): Boolean = _songs.value.any { it.path == song.path }

    /**
     * 随机排序：**当前播放曲固定在最前**，其余随机。
     *
     * 第二十二轮需求 4：进入随机模式时不能把正在播放的那首随机到别处
     * （否则列表首位与正在播放的曲目脱节，观感很怪）。
     * 当前曲不在列表中（或尚无当前曲）时退化为整体随机。
     */
    private fun shuffledKeepingCurrentFirst(list: List<Song>): List<Song> {
        if (list.size <= 1) return list
        val cur = currentPath
        val idx = if (cur == null) -1 else list.indexOfFirst { it.path == cur }
        if (idx < 0) return list.shuffled()
        val rest = list.filterIndexed { i, _ -> i != idx }.shuffled()
        return listOf(list[idx]) + rest
    }

    /** 从 [list] 中移除**一个**与 [target] 标识相同（path 一致）的元素，保持多重集一致 */
    private fun removeOneLike(list: List<Song>, target: Song): List<Song> {
        val idx = list.indexOfFirst { it.path == target.path }
        if (idx < 0) return list
        return list.toMutableList().apply { removeAt(idx) }
    }

    /**
     * 追加歌曲到播放列表**末尾**。
     *
     * 第十六轮变更：**不再去重** —— 同一首歌可以在播放列表内出现多次
     * （需求：向播放列表添加歌曲时不检测列表内是否已存在该歌曲）。
     * 因此本方法不再返回"是否新增"（旧签名用 false 表示"已在列表中"，该语义已不存在）。
     *
     * 第二十二轮：同时追加到 [originalOrder]，保证"切回顺序播放"能还原到包含该曲的原始顺序。
     * 这里刻意**不**重新打乱整个队列 —— 单曲追加应按"加到队尾"的自然语义处理，
     * 每加一首就把整队重排会让列表顺序不停跳动。
     */
    @Synchronized
    fun addSong(song: Song) {
        originalOrder = originalOrder + song
        notify(_songs.value + song)
    }

    /**
     * 把 [songs] 插入到**当前播放歌曲之后**（第二十六轮需求 2）。
     *
     * 与 [addSong]（追加到队尾）的区别：这是需求明确要求的"插入到当前播放音乐之后"，
     * 因此队列为空/没有当前曲时退化为追加到队尾。
     *
     * 同一首歌可以重复入列（第十六轮），因此插入不去重。
     * [originalOrder] 同步在同一位置插入，保持与 [_songs] 是同一多重集
     * —— 否则切回顺序播放模式会凭空少掉这几首。
     *
     * @return 实际插入的歌曲数
     */
    @Synchronized
    fun insertAfterCurrent(songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        val list = _songs.value
        if (list.isEmpty()) {
            // 队列为空：没有"当前播放曲"可插入其后，按入列处理
            setSongs(songs)
            return songs.size
        }

        // 当前曲可能有多份，取第一份之后插入（与 playSong 的定位口径一致）
        val cur = currentPath
        val currentIndex = if (cur == null) -1 else list.indexOfFirst { it.path == cur }
        val insertAt = if (currentIndex >= 0) currentIndex + 1 else list.size

        val next = list.toMutableList().apply { addAll(insertAt, songs) }

        // 原始顺序快照同步插入（随机模式下它与 _songs 顺序不同，各自按当前曲定位）
        val origCurrentIndex = if (cur == null) -1 else originalOrder.indexOfFirst { it.path == cur }
        val origInsertAt = if (origCurrentIndex >= 0) origCurrentIndex + 1 else originalOrder.size
        originalOrder = originalOrder.toMutableList().apply { addAll(origInsertAt, songs) }

        notify(next)
        return songs.size
    }

    /**
     * 用给定列表整体替换播放列表（"点击歌曲整列播放"场景）。
     *
     * 第二十二轮需求 4：入列时**记住这份原始顺序**；若当前已是随机模式，
     * 则记住之后再随机排序（当前播放曲置于首位）。切回顺序模式时会用
     * [originalOrder] 还原。
     *
     * 非随机模式下刻意保持入列顺序不变：本方法也服务于「播放全部」「点击某首歌」
     * 等显式选列流程，那些场景用户期望按看到的顺序入列。
     */
    @Synchronized
    fun setSongs(songs: List<Song>) {
        originalOrder = songs.toList()
        val next = if (_playMode.value == PlayMode.RANDOM) {
            shuffledKeepingCurrentFirst(originalOrder)
        } else {
            originalOrder
        }
        notify(next)
    }

    /**
     * 「随机播放」入口：把 [songs] 打乱后整体替换播放列表，并切换到随机模式。
     *
     * 只发一次通知（一次持久化）。若按「先 [setPlayMode] 再 [setSongs]」两步走，
     * 第一次 notify 会用**旧列表**重建一次播放队列、紧接着第二次 notify 又用新列表
     * 重建一次，白白多打断一次正在播放的音频。
     *
     * 第二十二轮：同样记住 [songs] 作为 [originalOrder]（切回顺序模式可还原）。
     * 这里是显式的"随机播放这张列表"动作，且调用方随后从**第 0 首**开始播放，
     * 因此采用**整体随机**而不把上一队列的当前曲钉在首位 —— 否则"随机播放"会
     * 每次都把刚才那首再放一遍，违背按下该按钮的预期。
     *
     * @return 实际存入的顺序（已打乱），调用方据此决定从哪首开始播。
     */
    @Synchronized
    fun playShuffled(songs: List<Song>): List<Song> {
        _playMode.value = PlayMode.RANDOM
        originalOrder = songs.toList()
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
        // 原始顺序同步移除同批歌曲（保持与 _songs 同一多重集）
        originalOrder = originalOrder.filterNot { it.path in targets }
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
            // 原始顺序里只删掉"同样的那一份"，避免重复歌曲时多删
            originalOrder = removeOneLike(originalOrder, list[index])
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
        originalOrder = originalOrder.map { if (it.path == updatedSong.path) updatedSong else it }
        notify(list.map { if (it.path == updatedSong.path) updatedSong else it })
    }

    /** 清空播放列表 */
    @Synchronized
    fun clear() {
        originalOrder = emptyList()
        currentPath = null
        notify(emptyList())
    }

    /** 切换播放模式（顺序 -> 随机 -> 单曲 -> 顺序），返回切换后的模式 */
    fun togglePlayMode(): PlayMode {
        val mode = _playMode.value.next()
        setPlayMode(mode)
        return mode
    }

    /**
     * 直接设置播放模式。
     *
     * 第二十二轮需求 4：
     * - 切到 **RANDOM**：把当前列表重新随机排序（当前播放曲置于首位）；
     * - 切回 **SEQUENTIAL**：还原为入列时记住的 [originalOrder]；
     * - **SINGLE**：不改动列表顺序（单曲循环与队列顺序无关）。
     *
     * 注意"再次切到随机要**重新**随机"：每次切入随机都重新打乱一次，而不是复用上次结果，
     * 所以这里不缓存任何"随机顺序"。
     */
    @Synchronized
    fun setPlayMode(mode: PlayMode) {
        val previous = _playMode.value
        _playMode.value = mode
        if (previous == mode) {
            persist()
            return
        }
        when (mode) {
            PlayMode.RANDOM -> {
                if (_songs.value.size > 1) {
                    notify(shuffledKeepingCurrentFirst(_songs.value))
                } else {
                    persist()
                }
            }

            PlayMode.SEQUENTIAL -> {
                // 还原原始顺序；原始快照为空（例如旧版本遗留的队列）时保持现状
                if (originalOrder.isNotEmpty()) {
                    notify(originalOrder.toList())
                } else {
                    persist()
                }
            }

            PlayMode.SINGLE -> persist()
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
            // 第二十二轮：恢复出来的就是"原始顺序"（持久化的正是入列顺序）。
            // 若上次退出时处于随机模式，_songs 需要按随机顺序呈现，
            // 但 originalOrder 必须保留这份原始顺序，供切回顺序模式时还原。
            originalOrder = restored.toList()
            val next = if (_playMode.value == PlayMode.RANDOM) {
                shuffledKeepingCurrentFirst(originalOrder)
            } else {
                originalOrder
            }
            notify(next)
            restored.size
        } catch (e: Exception) {
            Log.w(TAG, "恢复播放列表失败: ${e.message}")
            0
        }
    }

    private const val PERSIST_DEBOUNCE_MS = 500L
}
