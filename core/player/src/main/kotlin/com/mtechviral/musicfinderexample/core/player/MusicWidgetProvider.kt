package com.mtechviral.musicfinderexample.core.player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.RemoteViews
import java.io.File

/**
 * 音乐播放器桌面小组件 Provider。
 *
 * 由原 Android 侧 `MusicWidgetProvider.java` 迁移为 Kotlin：
 * - 左侧封面、右上歌名、右下播放/暂停按钮；
 * - 点击播放/暂停按钮直接调用 [PlayerController] 切换播放状态
 *   （原实现通过「写偏好 + 唤起 Activity + Flutter 轮询」中转，
 *   原生端同进程无需轮询，行为对外一致）；
 * - 点击其余区域打开应用。
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (ACTION_PLAY_PAUSE == intent.action) {
            Log.d(TAG, "播放/暂停按钮被点击")
            PlayerController.togglePlayPauseAsync()
        }
    }

    companion object {
        private const val TAG = "MusicWidgetProvider"

        /** 与原实现保持一致的动作名 */
        const val ACTION_PLAY_PAUSE =
            "com.mtechviral.musicfinderexample.ACTION_PLAY_PAUSE"

        /** 更新单个小组件显示 */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_music_player)
            val prefs = context.getSharedPreferences(
                MusicWidgetUpdater.PREF_NAME,
                Context.MODE_PRIVATE,
            )

            val songTitle = prefs.getString(
                MusicWidgetUpdater.KEY_SONG_TITLE,
                context.getString(R.string.widget_default_title),
            ) ?: context.getString(R.string.widget_default_title)
            val isPlaying = prefs.getBoolean(MusicWidgetUpdater.KEY_IS_PLAYING, false)
            val artworkPath = prefs.getString(MusicWidgetUpdater.KEY_ARTWORK_PATH, null)

            views.setTextViewText(R.id.widget_song_title, songTitle)

            val iconRes = if (isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
            views.setImageViewResource(R.id.widget_play_pause, iconRes)

            if (!artworkPath.isNullOrEmpty()) {
                val bitmap = loadBitmapFromFile(artworkPath)
                if (bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_album_art, bitmap)
                } else {
                    views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_default_artwork)
                }
            } else {
                views.setImageViewResource(R.id.widget_album_art, R.drawable.ic_default_artwork)
            }

            // 播放/暂停按钮
            val playPauseIntent = Intent(context, MusicWidgetProvider::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            val playPausePendingIntent = PendingIntent.getBroadcast(
                context, 0, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_play_pause, playPausePendingIntent)

            // 整个小组件点击 -> 打开应用
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                val launchPendingIntent = PendingIntent.getActivity(
                    context, 1, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_album_art, launchPendingIntent)
                views.setOnClickPendingIntent(R.id.widget_song_title, launchPendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** 通知所有小组件更新 */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val widget = ComponentName(context, MusicWidgetProvider::class.java)
            for (id in manager.getAppWidgetIds(widget)) {
                updateWidget(context, manager, id)
            }
        }

        /** 从文件路径加载封面 Bitmap（采样加载，避免 OOM） */
        private fun loadBitmapFromFile(path: String): Bitmap? = try {
            val file = File(path)
            if (!file.exists()) {
                null
            } else {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, options)
                var scale = 1
                while (options.outWidth / scale > TARGET_SIZE ||
                    options.outHeight / scale > TARGET_SIZE
                ) {
                    scale *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = false
                        inSampleSize = scale
                    },
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载封面图片失败: ${e.message}")
            null
        }

        /** 目标尺寸：72dp * 2（与原实现一致） */
        private const val TARGET_SIZE = 144
    }
}
