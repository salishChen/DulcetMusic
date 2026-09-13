package com.mtechviral.musicfinderexample.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 悬浮窗歌词管理器（单例）。
 *
 * 对应原 Flutter 工程 `lib/data/lyrics_overlay_manager.dart`，但原实现需要
 * 通过 MethodChannel 跨引擎通信；原生端与 [FloatingLyricsService] 同进程，
 * 直接以「偏好设置 + 广播」协作，语义完全一致：
 * - 歌词数据写入 SharedPreferences，即使 UI 不在前台，服务也能自行同步显示；
 * - 显示/隐藏/锁定/行数等状态通过广播即时通知服务。
 */
object LyricsOverlayManager {

    /** 悬浮窗偏好文件名（与原 Java 服务、旧版 Flutter 保持一致） */
    const val PREFS_NAME = "lyrics_overlay_prefs"

    // ---- 键名（与原 Java 服务一一对应） ----
    const val KEY_LOCKED = "locked"
    const val KEY_COLOR = "color"
    const val KEY_FONT_SIZE = "font_size"
    const val KEY_POS_X = "pos_x"
    const val KEY_POS_Y = "pos_y"
    const val KEY_LINES_COUNT = "lines_count"
    const val KEY_LYRICS_RAW = "lyrics_raw"
    const val KEY_POSITION_MS = "position_ms"
    const val KEY_IS_PLAYING = "is_playing"
    const val KEY_LAST_UPDATE = "last_update"

    /** 悬浮窗是否可见（原生端以偏好标记为准，服务启动即写 true） */
    const val KEY_VISIBLE = "overlay_visible"

    const val ACTION_UPDATE_STATE =
        "com.mtechviral.musicfinderexample.UPDATE_LYRICS_STATE"
    const val ACTION_TOGGLE_LOCK =
        "com.mtechviral.musicfinderexample.TOGGLE_LYRICS_LOCK"

    private const val TAG = "LyricsOverlayManager"

    private lateinit var appContext: Context

    private val _isVisible = MutableStateFlow(false)

    /** 悬浮窗是否可见 */
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private val _isLocked = MutableStateFlow(false)

    /** 悬浮窗是否锁定 */
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _linesCount = MutableStateFlow(2)

    /** 歌词行数（1 或 2） */
    val linesCount: StateFlow<Int> = _linesCount.asStateFlow()

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        val prefs = prefs()
        _isLocked.value = prefs.getBoolean(KEY_LOCKED, false)
        _linesCount.value = prefs.getInt(KEY_LINES_COUNT, 2)
        _isVisible.value = prefs.getBoolean(KEY_VISIBLE, false)
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ===================== 权限 =====================

    /** 检查悬浮窗权限 */
    fun checkPermission(): Boolean = Settings.canDrawOverlays(appContext)

    /** 跳转到系统「显示在其他应用上层」授权页 */
    fun requestPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "请求悬浮窗权限失败: ${e.message}")
        }
    }

    // ===================== 操作 =====================

    /** 通知栏/应用内按钮点击：切换显示/隐藏（已显示且锁定则改为解锁） */
    fun onNotificationToggle() {
        when {
            !_isVisible.value -> showOverlay()
            _isLocked.value -> toggleLock()
            else -> hideOverlay()
        }
    }

    /** 显示悬浮窗（无权限则先请求，并返回 false 表示需要用户授权） */
    fun showOverlay(): Boolean {
        if (!checkPermission()) {
            requestPermission()
            return false
        }
        try {
            // 与原 Java 服务一致：普通 startService（悬浮窗不占用前台服务），
            // 进程由前台播放服务保活。
            appContext.startService(Intent(appContext, FloatingLyricsService::class.java))
            prefs().edit().putBoolean(KEY_VISIBLE, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "显示悬浮窗失败: ${e.message}")
        }
        // 即使调用失败，也标记为可见（服务可能已在运行）——与原逻辑一致
        _isVisible.value = true
        return true
    }

    /** 隐藏悬浮窗 */
    fun hideOverlay() {
        try {
            appContext.stopService(Intent(appContext, FloatingLyricsService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "隐藏悬浮窗失败: ${e.message}")
        }
        prefs().edit().putBoolean(KEY_VISIBLE, false).apply()
        _isVisible.value = false
    }

    /** 更新歌词内容（服务端会自行按位置同步显示） */
    fun updateLyrics(lyrics: String?, positionMs: Long, isPlaying: Boolean) {
        if (!_isVisible.value) return
        prefs().edit()
            .putString(KEY_LYRICS_RAW, lyrics ?: "")
            .putLong(KEY_POSITION_MS, positionMs)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
        sendUpdateBroadcast()
    }

    /** 更新播放位置 */
    fun updatePosition(positionMs: Long) {
        if (!_isVisible.value) return
        prefs().edit()
            .putLong(KEY_POSITION_MS, positionMs)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
        sendUpdateBroadcast()
    }

    /** 切换锁定状态 */
    fun toggleLock() {
        appContext.sendBroadcast(Intent(ACTION_TOGGLE_LOCK))
        // 服务收到广播后会写偏好；这里同步本地状态，保证 UI 即时反馈
        _isLocked.value = !_isLocked.value
    }

    /** 读取歌词行数配置 */
    fun getLinesCount(): Int = _linesCount.value

    /** 设置歌词行数 */
    fun setLinesCount(count: Int) {
        prefs().edit().putInt(KEY_LINES_COUNT, count).apply()
        _linesCount.value = count
        sendUpdateBroadcast()
    }

    /** 服务销毁/状态变化时回写可见性 */
    fun onServiceVisibilityChanged(visible: Boolean) {
        prefs().edit().putBoolean(KEY_VISIBLE, visible).apply()
        _isVisible.value = visible
    }

    /** 服务内切换锁定后同步状态到管理器 */
    fun onLockStateChanged(locked: Boolean) {
        _isLocked.value = locked
    }

    private fun sendUpdateBroadcast() {
        appContext.sendBroadcast(Intent(ACTION_UPDATE_STATE))
    }
}
