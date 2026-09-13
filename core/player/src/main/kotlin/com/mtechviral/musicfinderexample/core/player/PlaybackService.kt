package com.mtechviral.musicfinderexample.core.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 前台播放服务（MediaSessionService + ExoPlayer）。
 *
 * 对应原 Flutter 工程中 `audio_service` 提供的后台播放能力：
 * - 前台服务 + MediaStyle 通知（上一曲 / 播放暂停 / 下一曲）；
 * - 通知内额外提供「词 / 解锁」自定义按钮，与播放页的悬浮歌词联动；
 * - 耳机线控 / 蓝牙媒体按键由 MediaSession 统一接管
 *   （等价于原 `AudioService.androidForceEnableMediaButtons()` 的效果）；
 * - 通知渠道沿用原 channelId，升级后用户的渠道设置不丢失。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    lateinit var player: ExoPlayer
        private set

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exoPlayer

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.player_channel_name)
                .build(),
        )

        val session = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppPendingIntent())
            .setCallback(SessionCallback())
            .setCustomLayout(buildLyricsButtons(this, LyricsOverlayManager.isVisible.value, LyricsOverlayManager.isLocked.value))
            .build()
        mediaSession = session

        PlayerController.attachService(this, exoPlayer, session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 与原 audio_service 行为一致：任务被移除且未在播放时停止服务
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        PlayerController.detachService()
        mediaSession?.let {
            player.release()
            it.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** 打开应用的 PendingIntent（点击通知回到主界面） */
    private fun openAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.MainActivity")
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_TOGGLE_LYRICS) {
                // 与原 MpAudioHandler.customAction('toggle_lyrics') 一致
                LyricsOverlayManager.onNotificationToggle()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    companion object {
        /** 通知栏「歌词」按钮命令名（与原实现保持一致） */
        const val ACTION_TOGGLE_LYRICS = "toggle_lyrics"

        /** 通知渠道：沿用原 Flutter 端 channelId */
        const val CHANNEL_ID = "com.mtechviral.musicfinderexample.audio"

        /**
         * 构建通知栏歌词按钮。
         *
         * 图标规则与原实现一致：
         * - 未启动：ic_lyrics（普通音乐图标）
         * - 已启动未锁定：ic_lyrics_active（音乐图标 + 对勾）
         * - 已启动已锁定：ic_lyrics_locked（音乐图标 + 锁）
         */
        fun buildLyricsButtons(
            context: android.content.Context,
            visible: Boolean,
            locked: Boolean,
        ): List<CommandButton> {
            val iconRes = when {
                !visible -> R.drawable.ic_lyrics
                locked -> R.drawable.ic_lyrics_locked
                else -> R.drawable.ic_lyrics_active
            }
            val label = if (visible && locked) {
                context.getString(R.string.lyrics_unlock)
            } else {
                context.getString(R.string.lyrics_label)
            }
            val button = CommandButton.Builder()
                .setDisplayName(label)
                .setIconResId(iconRes)
                .setSessionCommand(SessionCommand(ACTION_TOGGLE_LYRICS, android.os.Bundle.EMPTY))
                .build()
            return listOf(button)
        }
    }
}

/** 便于外部（PlayerController）判断播放状态 */
internal fun Player.isActuallyPlaying(): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
