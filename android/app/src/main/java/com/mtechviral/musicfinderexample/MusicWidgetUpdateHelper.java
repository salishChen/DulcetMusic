package com.mtechviral.musicfinderexample;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 小组件数据更新辅助类
 *
 * 供 Flutter 通过 MethodChannel 调用，将当前播放信息写入 SharedPreferences，
 * 然后触发小组件刷新。
 */
public class MusicWidgetUpdateHelper {

    private static final String TAG = "MusicWidgetUpdateHelper";
    private static final String PREF_NAME = "home_widget";
    private static final String KEY_SONG_TITLE = "song_title";
    private static final String KEY_IS_PLAYING = "is_playing";
    private static final String KEY_ARTWORK_PATH = "artwork_path";

    /**
     * 更新小组件数据
     *
     * @param context     上下文
     * @param songTitle   歌曲标题
     * @param isPlaying   是否正在播放
     * @param artworkPath 封面图片本地路径（可为 null）
     */
    public static void updateWidgetData(Context context, String songTitle, boolean isPlaying, String artworkPath) {
        Log.d(TAG, "更新小组件数据: title=" + songTitle + ", playing=" + isPlaying + ", art=" + artworkPath);

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_SONG_TITLE, songTitle)
                .putBoolean(KEY_IS_PLAYING, isPlaying)
                .putString(KEY_ARTWORK_PATH, artworkPath)
                .apply();

        // 触发小组件刷新
        MusicWidgetProvider.updateAllWidgets(context);
    }

    /**
     * 检查是否有待处理的播放/暂停请求
     */
    public static boolean hasPendingPlayPause(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean("pending_play_pause", false);
    }

    /**
     * 清除待处理的播放/暂停请求标记
     */
    public static void clearPendingPlayPause(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("pending_play_pause", false).apply();
    }
}
