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
    const val KEY_FONT_WEIGHT = "font_weight"
    const val KEY_POS_X = "pos_x"
    const val KEY_POS_Y = "pos_y"
    const val KEY_LINES_COUNT = "lines_count"
    const val KEY_LYRICS_RAW = "lyrics_raw"
    const val KEY_POSITION_MS = "position_ms"
    const val KEY_IS_PLAYING = "is_playing"
    const val KEY_LAST_UPDATE = "last_update"

    /** 悬浮窗是否可见（原生端以偏好标记为准，服务启动即写 true） */
    const val KEY_VISIBLE = "overlay_visible"

    // ---- 字体粗细（新增，默认粗体以保持原观感） ----
    const val WEIGHT_LIGHT = 0
    const val WEIGHT_NORMAL = 1
    const val WEIGHT_BOLD = 2

    /** 字号范围（与服务内 SeekBar 保持一致） */
    const val MIN_FONT_SIZE = 10f
    const val MAX_FONT_SIZE = 36f

    /** 默认字号 / 颜色 / 粗细 */
    const val DEFAULT_FONT_SIZE = 16f
    const val DEFAULT_COLOR = 0xFFFFFFFF.toInt()
    const val DEFAULT_FONT_WEIGHT = WEIGHT_BOLD

    /** 预设颜色（悬浮窗取色板与「桌面歌词」设置页共用） */
    val PRESET_COLORS = intArrayOf(
        0xFFFFFFFF.toInt(),
        0xFFFFEB3B.toInt(),
        0xFF00BCD4.toInt(),
        0xFF4CAF50.toInt(),
        0xFFFF4081.toInt(),
        0xFFFF9100.toInt(),
        0xFFE040FB.toInt(),
        0xFF64FFDA.toInt(),
    )

    const val ACTION_UPDATE_STATE =
        "com.mtechviral.musicfinderexample.UPDATE_LYRICS_STATE"
    const val ACTION_TOGGLE_LOCK =
        "com.mtechviral.musicfinderexample.TOGGLE_LYRICS_LOCK"

    /** 请求把悬浮窗重新摆回水平居中（由服务按当前屏幕宽度计算） */
    const val ACTION_RECENTER =
        "com.mtechviral.musicfinderexample.RECENTER_LYRICS"

    private const val TAG = "LyricsOverlayManager"

    private lateinit var appContext: Context

    private val _isVisible = MutableStateFlow(false)

    /** 悬浮窗是否可见 */
    val isVisible: StateFlow<Boolean> = _isVisible.asStateFlow()

    private val _isLocked = MutableStateFlow(false)

    /** 悬浮窗是否锁定（锁定后不可拖动；点击悬浮窗仍可打开设置面板解锁） */
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _linesCount = MutableStateFlow(2)

    /** 歌词行数（1 或 2） */
    val linesCount: StateFlow<Int> = _linesCount.asStateFlow()

    private val _fontSize = MutableStateFlow(DEFAULT_FONT_SIZE)

    /** 字号（sp） */
    val fontSize: StateFlow<Float> = _fontSize.asStateFlow()

    private val _color = MutableStateFlow(DEFAULT_COLOR)

    /** 文字颜色（ARGB） */
    val color: StateFlow<Int> = _color.asStateFlow()

    private val _fontWeight = MutableStateFlow(DEFAULT_FONT_WEIGHT)

    /** 字体粗细（[WEIGHT_LIGHT] / [WEIGHT_NORMAL] / [WEIGHT_BOLD]） */
    val fontWeight: StateFlow<Int> = _fontWeight.asStateFlow()

    /**
     * 设置变更信号（同进程直连，不依赖广播）。
     *
     * 悬浮窗服务与设置页/通知栏在**同一个进程**，但实测 `sendBroadcast(隐式 Intent)`
     * 在部分 ROM 上送不到动态注册的接收者，会出现"点了「词」按钮只切换显隐、
     * 锁根本解不开"的现象。这里额外用一个 StateFlow 计数做可靠通知，
     * 服务端 collect 后重新读取偏好并立即应用。
     */
    private val _settingsRevision = MutableStateFlow(0L)
    val settingsRevision: StateFlow<Long> = _settingsRevision.asStateFlow()

    private fun notifySettingsChanged() {
        _settingsRevision.value = _settingsRevision.value + 1
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        refresh()
    }

    /**
     * 重新从偏好读取全部悬浮窗设置到内存状态。
     *
     * 服务创建时、以及「桌面歌词」设置页进入时都会调用，保证两侧始终一致。
     */
    fun refresh() {
        if (!::appContext.isInitialized) return
        val p = prefs()
        _isLocked.value = p.getBoolean(KEY_LOCKED, false)
        _linesCount.value = p.getInt(KEY_LINES_COUNT, 2)
        _isVisible.value = p.getBoolean(KEY_VISIBLE, false)
        _fontSize.value = p.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
            .coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        _color.value = p.getInt(KEY_COLOR, DEFAULT_COLOR)
        _fontWeight.value = p.getInt(KEY_FONT_WEIGHT, DEFAULT_FONT_WEIGHT)
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

    /** 切换锁定状态（等价于 `setLocked(!isLocked)`，并可靠通知服务） */
    fun toggleLock() {
        setLocked(!_isLocked.value)
    }

    /** 请求把悬浮窗重新居中（设置页「重新居中」按钮） */
    fun requestRecenter() {
        appContext.sendBroadcast(Intent(ACTION_RECENTER))
    }

    /** 读取歌词行数配置 */
    fun getLinesCount(): Int = _linesCount.value

    /** 设置歌词行数 */
    fun setLinesCount(count: Int) {
        prefs().edit().putInt(KEY_LINES_COUNT, count).apply()
        _linesCount.value = count
        sendUpdateBroadcast()
        notifySettingsChanged()
    }

    /** 设置字号（sp，会裁剪到 10~36） */
    fun setFontSize(size: Float) {
        val value = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        prefs().edit().putFloat(KEY_FONT_SIZE, value).apply()
        _fontSize.value = value
        sendUpdateBroadcast()
        notifySettingsChanged()
    }

    /** 设置文字颜色（ARGB） */
    fun setColor(color: Int) {
        prefs().edit().putInt(KEY_COLOR, color).apply()
        _color.value = color
        sendUpdateBroadcast()
        notifySettingsChanged()
    }

    /** 设置字体粗细（[WEIGHT_LIGHT] / [WEIGHT_NORMAL] / [WEIGHT_BOLD]） */
    fun setFontWeight(weight: Int) {
        prefs().edit().putInt(KEY_FONT_WEIGHT, weight).apply()
        _fontWeight.value = weight
        sendUpdateBroadcast()
        notifySettingsChanged()
    }

    /**
     * 设置锁定状态并通知服务。
     *
     * 与 [toggleLock] 的区别：这里直接落到目标状态（设置页开关用），不需要服务再取反。
     */
    fun setLocked(locked: Boolean) {
        prefs().edit().putBoolean(KEY_LOCKED, locked).apply()
        _isLocked.value = locked
        sendUpdateBroadcast()
        notifySettingsChanged()
    }

    /** 服务销毁/状态变化时回写可见性 */
    fun onServiceVisibilityChanged(visible: Boolean) {
        prefs().edit().putBoolean(KEY_VISIBLE, visible).apply()
        _isVisible.value = visible
    }

    /** 服务内切换锁定后同步状态到管理器 */
    fun onLockStateChanged(locked: Boolean) {
        prefs().edit().putBoolean(KEY_LOCKED, locked).apply()
        _isLocked.value = locked
    }

    /** 服务内改色后同步状态到管理器 */
    fun onColorChanged(color: Int) {
        prefs().edit().putInt(KEY_COLOR, color).apply()
        _color.value = color
    }

    /** 服务内改字号后同步状态到管理器 */
    fun onFontSizeChanged(size: Float) {
        val value = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        prefs().edit().putFloat(KEY_FONT_SIZE, value).apply()
        _fontSize.value = value
    }

    private fun sendUpdateBroadcast() {
        appContext.sendBroadcast(Intent(ACTION_UPDATE_STATE))
    }
}
