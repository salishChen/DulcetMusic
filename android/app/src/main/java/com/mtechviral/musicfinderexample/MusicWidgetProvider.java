package com.mtechviral.musicfinderexample;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * 音乐播放器桌面小组件 Provider
 *
 * 功能：
 * - 显示当前播放歌曲的封面（左侧）、歌名（右上）、播放/暂停按钮（右下）
 * - 点击播放/暂停按钮控制音乐播放
 * - 点击小组件其他区域打开应用
 */
public class MusicWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "MusicWidgetProvider";
    private static final String ACTION_PLAY_PAUSE = "com.mtechviral.musicfinderexample.ACTION_PLAY_PAUSE";
    private static final String PREF_NAME = "home_widget";
    private static final String KEY_SONG_TITLE = "song_title";
    private static final String KEY_IS_PLAYING = "is_playing";
    private static final String KEY_ARTWORK_PATH = "artwork_path";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_PLAY_PAUSE.equals(intent.getAction())) {
            Log.d(TAG, "播放/暂停按钮被点击");
            // 通过 MethodChannel 通知 Flutter 切换播放/暂停
            sendPlayPauseToFlutter(context);
        }
    }

    /**
     * 更新小组件显示
     */
    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_music_player);

        // 从 SharedPreferences 读取数据
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String songTitle = prefs.getString(KEY_SONG_TITLE, context.getString(R.string.widget_default_title));
        boolean isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false);
        String artworkPath = prefs.getString(KEY_ARTWORK_PATH, null);

        // 设置歌名
        views.setTextViewText(R.id.widget_song_title, songTitle);

        // 设置播放/暂停图标
        int iconRes = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        views.setImageViewResource(R.id.widget_play_pause, iconRes);

        // 设置专辑封面
        if (artworkPath != null && !artworkPath.isEmpty()) {
            Bitmap bitmap = loadBitmapFromFile(artworkPath);
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_album_art, bitmap);
            } else {
                views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher);
            }
        } else {
            views.setImageViewResource(R.id.widget_album_art, R.mipmap.ic_launcher);
        }

        // 设置播放/暂停按钮点击事件
        Intent playPauseIntent = new Intent(context, MusicWidgetProvider.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePendingIntent = PendingIntent.getBroadcast(
                context, 0, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePendingIntent);

        // 设置整个小组件点击事件（打开应用）
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launchIntent != null) {
            launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent launchPendingIntent = PendingIntent.getActivity(
                    context, 1, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            views.setOnClickPendingIntent(R.id.widget_album_art, launchPendingIntent);
            // 设置歌名区域点击也能打开应用
            views.setOnClickPendingIntent(R.id.widget_song_title, launchPendingIntent);
        }

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    /**
     * 通知所有小组件更新
     */
    public static void updateAllWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName widget = new ComponentName(context, MusicWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(widget);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    /**
     * 从文件路径加载 Bitmap
     */
    private static Bitmap loadBitmapFromFile(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                // 采样加载，避免 OOM
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);

                int targetSize = 144; // 72dp * 2 (屏幕密度)
                int scale = 1;
                while (options.outWidth / scale > targetSize || options.outHeight / scale > targetSize) {
                    scale *= 2;
                }

                options.inJustDecodeBounds = false;
                options.inSampleSize = scale;
                return BitmapFactory.decodeFile(path, options);
            }
        } catch (Exception e) {
            Log.e(TAG, "加载封面图片失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 通过 SharedPreferences 与 MethodChannel 通知 Flutter 播放/暂停
     */
    private void sendPlayPauseToFlutter(Context context) {
        // 写入一个 pending action 标记，Flutter 端轮询检查
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean("pending_play_pause", true).apply();

        // 启动 MainActivity 并携带 action
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchIntent.putExtra("action", ACTION_PLAY_PAUSE);
        context.startActivity(launchIntent);
    }
}
