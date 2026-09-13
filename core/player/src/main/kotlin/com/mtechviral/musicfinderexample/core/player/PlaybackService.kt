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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台播放服务（MediaSessionService + ExoPlayer）。
 *
 * 对应原 Flutter 工程中 `audio_service` 提供的后台播放能力：
 * - 前台服务 + MediaStyle 通知（上一曲 / 播放暂停 / 下一曲）；
 * - 通知内额外提供「词 / 解锁」自定义按钮，与播放页的悬浮歌词联动；
 * - 耳机线控 / 蓝牙媒体按键由 MediaSession 统一接管
 *   （等价于原 `AudioService.androidForceEnableMediaButtons()` 的效果）；
 * - 通知渠道沿用原 channelId，升级后用户的渠道设置不丢失。
 *
 * 通知的展示前提：会话上必须存在已连接的 `MediaController`（Media3 的
 * `MediaNotificationManager.shouldShowNotification` 会检查），因此 App 侧
 * 统一通过 [PlayerController] 里的 `MediaController` 控制播放，而不是直接持有 ExoPlayer。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 服务内协程作用域（观察悬浮窗歌词开关，刷新通知按钮图标） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 服务内唯一的 ExoPlayer 实例。
     *
     * 刻意保持 private：App 侧必须通过 `MediaController`（见 [PlayerController]）下发命令，
     * 直接持有播放器会导致 Media3 不展示媒体通知（详见 [PlayerController.init] 的说明）。
     */
    private lateinit var player: ExoPlayer

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
            // Media3 1.9：通知栏按钮取自「媒体按键偏好」，只设置 customLayout 不会出现在通知里
            .setMediaButtonPreferences(buildLyricsButtons(this, LyricsOverlayManager.isVisible.value, LyricsOverlayManager.isLocked.value))
            .build()
        mediaSession = session

        // 悬浮窗歌词开关/锁定状态变化 -> 刷新通知栏「词」按钮图标
        serviceScope.launch {
            LyricsOverlayManager.isVisible.collect { refreshLyricsButton() }
        }
        serviceScope.launch {
            LyricsOverlayManager.isLocked.collect { refreshLyricsButton() }
        }
    }

    /**
     * 刷新通知栏「词」按钮图标与文案。
     *
     * 注意 Media3 1.9 的取值链路（见 `MediaNotificationManager.updateNotification`）：
     * 通知栏按钮 = **已连接控制器的 `getMediaButtonPreferences()`**，
     * 因此除了会话级偏好，还要逐个控制器刷新，图标/文案才会实时跟着悬浮歌词状态变。
     */
    private fun refreshLyricsButton() {
        val s = mediaSession ?: return
        val buttons = buildLyricsButtons(
            this,
            LyricsOverlayManager.isVisible.value,
            LyricsOverlayManager.isLocked.value,
        )
        // customLayout 供（旧）控制器读取；mediaButtonPreferences 才是通知栏按钮来源
        s.setCustomLayout(buttons)
        s.setMediaButtonPreferences(buttons)
        s.connectedControllers.forEach { info -> s.setMediaButtonPreferences(info, buttons) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 与原 audio_service 行为一致：任务被移除且未在播放时停止服务
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
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

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // 自定义「词」命令必须在这里声明为"该控制器可用命令"，
            // 否则它会被 Media3 从「媒体按键偏好」里过滤掉，
            // 通知栏/媒体控制中心就只剩上一曲 / 暂停 / 下一曲。
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(ACTION_TOGGLE_LYRICS, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setMediaButtonPreferences(
                    buildLyricsButtons(
                        this@PlaybackService,
                        LyricsOverlayManager.isVisible.value,
                        LyricsOverlayManager.isLocked.value,
                    ),
                )
                .build()
        }

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
         * 图标规则与原实现一致（但图标本体改为「词」字）：
         * - 未启动：ic_lyrics_word（「词」字）
         * - 已启动未锁定：ic_lyrics_word_active（「词」字 + 对勾）
         * - 已启动已锁定：ic_lyrics_word_locked（「词」字 + 小锁）
         *
         * **必须声明槽位**（[CommandButton.SLOT_OVERFLOW]）：Media3 1.9 的
         * `CommandButton.getCustomLayoutFromMediaButtonPreferences` 只会保留带槽位的按钮，
         * 没有槽位的自定义按钮会被静默丢弃 —— 表现就是通知栏里只有「上一曲 / 暂停 / 下一曲」，
         * 看不到歌词开关。
         */
        fun buildLyricsButtons(
            context: android.content.Context,
            visible: Boolean,
            locked: Boolean,
        ): List<CommandButton> {
            val iconRes = when {
                !visible -> R.drawable.ic_lyrics_word
                locked -> R.drawable.ic_lyrics_word_locked
                else -> R.drawable.ic_lyrics_word_active
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
                // 放进「更多」槽位：作为通知/媒体控制面板里的第 4 个按钮（展开后可见），
                // 不挤占紧凑视图里的上一曲 / 暂停 / 下一曲
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
            return listOf(button)
        }
    }
}

/** 便于外部（PlayerController）判断播放状态 */
internal fun Player.isActuallyPlaying(): Boolean =
    playWhenReady && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED
