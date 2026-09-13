package com.mtechviral.musicfinderexample.core.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 桌面小组件数据更新辅助类。
 *
 * 对应原 Android 侧 `MusicWidgetUpdateHelper`：把当前播放信息写入
 * `home_widget` 偏好（键名与旧版本一致，升级后继续可用），再触发小组件刷新。
 */
object MusicWidgetUpdater {

    private const val TAG = "MusicWidgetUpdater"

    const val PREF_NAME = "home_widget"
    const val KEY_SONG_TITLE = "song_title"
    const val KEY_IS_PLAYING = "is_playing"
    const val KEY_ARTWORK_PATH = "artwork_path"

    /** 旧版遗留的"待处理播放/暂停"标记（新版走进程内广播，仅作兼容清理） */
    const val KEY_PENDING_PLAY_PAUSE = "pending_play_pause"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /**
     * 更新小组件数据。
     *
     * @param songTitle 歌曲标题
     * @param isPlaying 是否正在播放
     * @param artworkPath 封面本地路径（可为 null）
     */
    fun updateWidgetData(
        context: Context,
        songTitle: String,
        isPlaying: Boolean,
        artworkPath: String?,
    ) {
        Log.d(TAG, "更新小组件数据: title=$songTitle, playing=$isPlaying, art=$artworkPath")
        prefs(context).edit()
            .putString(KEY_SONG_TITLE, songTitle)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .putString(KEY_ARTWORK_PATH, artworkPath)
            .apply()
        MusicWidgetProvider.updateAllWidgets(context)
    }

    /**
     * 按当前歌曲刷新小组件。
     *
     * 封面优先使用已缓存的远程封面路径（对应 Dart 端
     * `WidgetService.updateWidget` 中 `song.cachedArtworkPath` 的取值规则）。
     */
    fun updateFromSong(context: Context, song: Song?, isPlaying: Boolean) {
        val title = song?.title ?: context.getString(R.string.widget_default_title)
        val artworkPath = song?.cachedArtworkPath?.takeIf { it.isNotEmpty() }
        updateWidgetData(context, title, isPlaying, artworkPath)
    }
}
