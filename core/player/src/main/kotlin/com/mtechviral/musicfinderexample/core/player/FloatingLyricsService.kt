package com.mtechviral.musicfinderexample.core.player

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.mtechviral.musicfinderexample.core.common.LrcLine
import com.mtechviral.musicfinderexample.core.common.LrcParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 全局悬浮窗歌词服务。
 *
 * 由原 Android 侧 Java 实现（`FloatingLyricsService.java`）1:1 迁移为 Kotlin，
 * 行为、偏好键名、布局 id 全部保持不变：
 * - `SYSTEM_ALERT_WINDOW` 悬浮窗，可拖动、点击展开设置面板、可锁定；
 * - 播放实时状态经 [LyricsOverlayManager.playback]（同进程 StateFlow）接收，
 *   **不再轮询偏好、不再因进度变化重复解析歌词**（耗电优化，报告 §3）；
 * - 播放中按"到下一句歌词行的剩余时间"自适应调度刷新，逐行高亮；
 *   暂停/熄屏即停表，亮屏恢复并重新校准；
 * - 支持颜色（8 个预设色）、字号（10~36）、单行/双行、锁定。
 */
class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var lyricLine1: TextView? = null
    private var lyricLine2: TextView? = null
    private var settingsPanel: View? = null
    private var lockButton: TextView? = null
    private var linesButton: TextView? = null
    private var colorRow: LinearLayout? = null

    private var isLocked = false
    private var showSettings = false
    private var lyricsColor = Color.WHITE
    private var fontSize = LyricsOverlayManager.DEFAULT_FONT_SIZE
    private var fontWeight = LyricsOverlayManager.DEFAULT_FONT_WEIGHT
    private var linesCount = 2

    // 歌词数据
    private var lyricLines: List<LrcLine> = emptyList()

    /** 播放位置基准（由播放快照提供；播放中按单调时钟外推） */
    private var basePositionMs = 0L
    private var baseClockMs = 0L
    private var isPlaying = false

    /** 最近一次解析的原始歌词（歌词只在原文变化时解析一次） */
    private var lastRawLyrics: String? = null

    /** 最近一次渲染的两行文本（显示只在活动行变化时更新） */
    private var lastRenderedLine1: String? = null
    private var lastRenderedLine2: String? = null

    // 自适应显示定时（暂停/熄屏停表）
    private lateinit var handler: Handler

    private var stateReceiver: BroadcastReceiver? = null

    /** 监听同进程的「设置变更」信号（广播在部分 ROM 上不可靠，见 LyricsOverlayManager） */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        loadSettings()
        createFloatingView()
        registerReceivers()
        observeSettings()
        observePlayback()
        // 标记悬浮窗已显示（管理器据此恢复 UI 状态）
        prefs().edit().putBoolean(LyricsOverlayManager.KEY_VISIBLE, true).apply()
        LyricsOverlayManager.onServiceVisibilityChanged(true)
    }

    /**
     * 订阅播放实时状态（同进程 StateFlow，耗电优化）。
     *
     * 歌词只在原文变化时解析一次；快照到达即刷新显示并重排显示定时 ——
     * 暂停/继续/seek 都由快照事件驱动，不再轮询偏好。
     */
    private fun observePlayback() {
        serviceScope.launch {
            LyricsOverlayManager.playback.collect { snap ->
                if (snap != null) applyPlaybackSnapshot(snap)
            }
        }
    }

    private fun applyPlaybackSnapshot(snap: LyricsOverlayManager.PlaybackSnapshot) {
        parseAndSetLyricsIfNeeded(snap.lyrics)
        basePositionMs = snap.positionMs
        baseClockMs = snap.clockMs
        isPlaying = snap.isPlaying
        updateLyricsDisplay()
        rescheduleDisplayTimer()
    }

    /**
     * 订阅设置变更信号并立即应用（锁定 / 颜色 / 字号 / 粗细 / 行数）。
     *
     * 与广播双通道：广播负责跨进程（本工程用不到），同进程的 StateFlow 是可靠主通道，
     * 修复"点「词」按钮只切换显隐、锁定解不开"的问题。
     */
    private fun observeSettings() {
        serviceScope.launch {
            LyricsOverlayManager.settingsRevision.collect {
                loadSettings()
                applyLyricsStyle()
                applyLinesCount()
                applyLockState()
                updateLyricsDisplay()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.getStringExtra("action")}")
        when (intent?.getStringExtra("action")) {
            // 兼容旧版 startService 直传协议：只更新内存状态，不再写偏好
            "update_lyrics" -> {
                intent.getStringExtra("lyrics")?.let { parseAndSetLyricsIfNeeded(it) }
                val pos = intent.getLongExtra("position", -1L)
                if (pos >= 0) {
                    basePositionMs = pos
                    baseClockMs = SystemClock.elapsedRealtime()
                }
                isPlaying = intent.getBooleanExtra("is_playing", isPlaying)
                updateLyricsDisplay()
                rescheduleDisplayTimer()
            }

            "update_position" -> {
                basePositionMs = intent.getLongExtra("position", basePositionMs)
                baseClockMs = SystemClock.elapsedRealtime()
                updateLyricsDisplay()
                rescheduleDisplayTimer()
            }

            "update_color" -> setLyricsColor(intent.getIntExtra("color", Color.WHITE))

            "update_font_size" -> setFontSize(intent.getFloatExtra("size", 16f))

            "stop" -> stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerReceivers() {
        stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent?.action
                Log.d(TAG, "Broadcast received: $action")
                when (action) {
                    LyricsOverlayManager.ACTION_TOGGLE_LOCK -> toggleLock()
                    LyricsOverlayManager.ACTION_RECENTER -> recenterOverlay()
                    LyricsOverlayManager.ACTION_UPDATE_STATE -> {
                        // 设置页/通知栏改了任何一项都走这里：重新读取并立即生效。
                        // 播放实时状态不经广播（内存 StateFlow 直传），这里不重复解析歌词
                        loadSettings()
                        applyLyricsStyle()
                        applyLinesCount()
                        updateLyricsDisplay()
                    }

                    // 熄屏：停止显示定时任务（悬浮窗本就不可见）；亮屏恢复并重新校准
                    Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(displayTick)
                    Intent.ACTION_SCREEN_ON -> {
                        updateLyricsDisplay()
                        rescheduleDisplayTimer()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LyricsOverlayManager.ACTION_TOGGLE_LOCK)
            addAction(LyricsOverlayManager.ACTION_UPDATE_STATE)
            addAction(LyricsOverlayManager.ACTION_RECENTER)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    // ===================== 自适应显示定时（耗电优化） =====================

    private val displayTick = object : Runnable {
        override fun run() {
            updateLyricsDisplay()
            rescheduleDisplayTimer()
        }
    }

    /**
     * 重新安排显示刷新。
     *
     * 与旧实现（无论播放与否固定每 200ms 轮询偏好并重绘）的区别：
     * - 暂停/停止：不排定时任务，状态变化由播放快照事件驱动；
     * - 熄屏（屏幕不可交互）：停表，亮屏后经 ACTION_SCREEN_ON 恢复；
     * - 播放中：只在"下一句歌词行临近"时唤醒刷新（[nextDisplayDelayMs]）。
     */
    private fun rescheduleDisplayTimer() {
        handler.removeCallbacks(displayTick)
        if (!isPlaying) return
        if (!isScreenInteractive()) return
        if (lyricLines.isEmpty()) return
        handler.postDelayed(displayTick, nextDisplayDelayMs())
    }

    /** 到下一句歌词行的剩余时间（截断到 [MIN_DISPLAY_TICK_MS]~[MAX_DISPLAY_TICK_MS]） */
    private fun nextDisplayDelayMs(): Long {
        val pos = extrapolatedPositionMs()
        val activeIdx = LrcParser.activeIndex(lyricLines, pos)
        val nextTime = when {
            activeIdx + 1 < lyricLines.size -> lyricLines[activeIdx + 1].timeMs
            activeIdx < 0 && lyricLines.isNotEmpty() -> lyricLines[0].timeMs
            else -> return MAX_DISPLAY_TICK_MS
        }
        return (nextTime - pos).coerceIn(MIN_DISPLAY_TICK_MS, MAX_DISPLAY_TICK_MS)
    }

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        return pm.isInteractive
    }

    /** 播放中按单调时钟外推当前位置（快照打点即校准基准） */
    private fun extrapolatedPositionMs(): Long {
        if (!isPlaying || baseClockMs <= 0) return basePositionMs
        return basePositionMs + (SystemClock.elapsedRealtime() - baseClockMs).coerceAtLeast(0L)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingView() {
        val view = LayoutInflater.from(this)
            .inflate(R.layout.floating_lyrics, null)
        floatingView = view

        lyricLine1 = view.findViewById(R.id.lyric_line1)
        lyricLine2 = view.findViewById(R.id.lyric_line2)
        settingsPanel = view.findViewById(R.id.settings_panel)
        lockButton = view.findViewById(R.id.lock_button)
        linesButton = view.findViewById(R.id.lines_button)
        colorRow = view.findViewById(R.id.color_row)

        applyLyricsStyle()
        applyLinesCount()
        initColorPicker()
        initFontSizeSeekBar()

        lockButton?.setOnClickListener { toggleLock() }
        linesButton?.setOnClickListener { toggleLinesCount() }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val dm: DisplayMetrics = resources.displayMetrics
        // 悬浮窗宽度取屏宽 90%，水平方向默认**居中**
        val defaultWidth = (dm.widthPixels * 0.9f).toInt()
        val maxX = (dm.widthPixels - defaultWidth).coerceAtLeast(0)
        val defaultX = maxX / 2

        params = WindowManager.LayoutParams(
            defaultWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 历史遗留的越界坐标（例如旧版本写入的 818）会让歌词整体偏到屏幕右侧，
            // 这里一旦发现存的位置放不下整个窗口，就直接恢复成居中。
            val storedX = loadPrefInt(LyricsOverlayManager.KEY_POS_X, defaultX)
            x = if (storedX in 0..maxX) storedX else defaultX
            val storedY = loadPrefInt(LyricsOverlayManager.KEY_POS_Y, 100)
            y = storedY.coerceIn(0, (dm.heightPixels - CLICK_TARGET_MARGIN_PX).coerceAtLeast(0))
            // 启动时就是锁定态：直接吃不到触摸（不能等 addView 之后再 updateViewLayout）
            if (isLocked) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }

        setupDragAndClick()
        applyLockState()

        try {
            windowManager.addView(view, params)
            Log.d(TAG, "Floating view added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view", e)
        }

        updateLyricsDisplay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragAndClick() {
        val view = floatingView ?: return
        var touchX = 0
        var touchY = 0
        var startRawX = 0f
        var startRawY = 0f
        var isDragging = false
        var touchDownTime = 0L

        view.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = p.x
                    touchY = p.y
                    startRawX = event.rawX
                    startRawY = event.rawY
                    isDragging = false
                    touchDownTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                    }
                    if (isDragging && !isLocked) {
                        // 夹在屏幕内，避免把歌词拖到屏幕外再回不来
                        val maxX = (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
                        p.x = (touchX + dx).toInt().coerceIn(0, maxX)
                        p.y = (touchY + dy).toInt().coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(view, p)
                        } catch (_: Exception) {
                            // ignore
                        }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging && !isLocked) {
                        savePrefInt(LyricsOverlayManager.KEY_POS_X, p.x)
                        savePrefInt(LyricsOverlayManager.KEY_POS_Y, p.y)
                    } else if (!isLocked) {
                        // 仅"解锁状态"下点击才展开/收起快捷设置面板。
                        // 锁定后点击不弹框（避免误触）；解锁入口是播放页/通知栏的「词」按钮：
                        // 已显示且锁定时点「词」= 解锁，再点一次 = 关闭桌面歌词。
                        val duration = System.currentTimeMillis() - touchDownTime
                        if (duration < CLICK_MAX_DURATION_MS) toggleSettings()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 解析 LRC 文本（与 Flutter 端 LrcParser 一致的多时间戳语义）；原文未变时不重复解析 */
    private fun parseAndSetLyricsIfNeeded(raw: String?) {
        if (raw == lastRawLyrics) return
        lastRawLyrics = raw
        if (raw.isNullOrBlank()) {
            lyricLines = emptyList()
            return
        }
        lyricLines = LrcParser.parse(raw)
        Log.d(TAG, "Parsed ${lyricLines.size} lyric lines")
    }

    private fun updateLyricsDisplay() {
        val line1 = lyricLine1 ?: return
        val line2 = lyricLine2

        // 播放中按单调时钟外推当前位置
        val currentPos = extrapolatedPositionMs()
        val activeIdx = LrcParser.activeIndex(lyricLines, currentPos)

        val text1: String
        val text2: String
        if (lyricLines.isEmpty()) {
            text1 = ""
            text2 = ""
        } else if (linesCount == 1) {
            text1 = if (activeIdx >= 0) lyricLines[activeIdx].text else lyricLines[0].text
            text2 = ""
        } else {
            if (activeIdx >= 0) {
                text1 = lyricLines[activeIdx].text
                text2 =
                    if (activeIdx + 1 < lyricLines.size) lyricLines[activeIdx + 1].text else ""
            } else {
                text1 = lyricLines[0].text
                text2 = if (lyricLines.size > 1) lyricLines[1].text else ""
            }
        }

        // 显示去重：只有活动歌词行真正变化时才触发布局与绘制
        if (text1 != lastRenderedLine1) {
            lastRenderedLine1 = text1
            line1.text = text1
        }
        if (text2 != lastRenderedLine2) {
            lastRenderedLine2 = text2
            line2?.text = text2
        }
    }

    private fun initColorPicker() {
        val row = colorRow ?: return
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for (color in LyricsOverlayManager.PRESET_COLORS) {
            val colorCircle = View(this)
            val size = (24 * density).toInt()
            val lp = LinearLayout.LayoutParams(size, size)
            val margin = (4 * density).toInt()
            lp.setMargins(margin, 0, margin, 0)
            colorCircle.layoutParams = lp

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    if (color == lyricsColor) 3 else 1,
                    if (color == lyricsColor) Color.WHITE else 0x60FFFFFF,
                )
            }
            colorCircle.background = bg
            colorCircle.setOnClickListener {
                setLyricsColor(color)
                initColorPicker()
            }
            row.addView(colorCircle)
        }
    }

    private fun initFontSizeSeekBar() {
        val view = floatingView ?: return
        val seekBar = view.findViewById<SeekBar>(R.id.font_size_seekbar)
        val sizeText = view.findViewById<TextView>(R.id.font_size_text)

        seekBar.progress = (fontSize - LyricsOverlayManager.MIN_FONT_SIZE).toInt()
        sizeText.text = fontSize.toInt().toString()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // 拖动过程中只在本地实时预览，松手才写偏好并通知设置页
                    fontSize = progress + LyricsOverlayManager.MIN_FONT_SIZE
                    sizeText.text = fontSize.toInt().toString()
                    applyLyricsStyle()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                LyricsOverlayManager.setFontSize(fontSize)
            }
        })
    }

    /** 应用颜色 / 字号 / 粗细（设置页与悬浮窗面板共用同一套偏好） */
    private fun applyLyricsStyle() {
        val typeface = typefaceFor(fontWeight)
        lyricLine1?.apply {
            setTextColor(lyricsColor)
            textSize = fontSize
            setTypeface(typeface)
        }
        lyricLine2?.apply {
            setTextColor(lyricsColor)
            textSize = fontSize
            setTypeface(typeface)
        }
    }

    /** 字体粗细 → Typeface（细 / 常规 / 粗） */
    private fun typefaceFor(weight: Int): Typeface = when (weight) {
        LyricsOverlayManager.WEIGHT_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        LyricsOverlayManager.WEIGHT_NORMAL -> Typeface.create("sans-serif", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.BOLD)
    }

    private fun applyLinesCount() {
        lyricLine2?.visibility = if (linesCount >= 2) View.VISIBLE else View.GONE
        linesButton?.setText(if (linesCount >= 2) R.string.lyrics_lines_2 else R.string.lyrics_lines_1)
    }

    private fun toggleLinesCount() {
        linesCount = if (linesCount == 1) 2 else 1
        applyLinesCount()
        LyricsOverlayManager.setLinesCount(linesCount)
        updateLyricsDisplay()
    }

    private fun setLyricsColor(color: Int) {
        lyricsColor = color
        applyLyricsStyle()
        LyricsOverlayManager.setColor(color)
    }

    private fun setFontSize(size: Float) {
        fontSize = size.coerceIn(
            LyricsOverlayManager.MIN_FONT_SIZE,
            LyricsOverlayManager.MAX_FONT_SIZE,
        )
        applyLyricsStyle()
        LyricsOverlayManager.setFontSize(fontSize)
    }

    private fun toggleSettings() {
        showSettings = !showSettings
        settingsPanel?.visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    /** 把悬浮窗摆回水平居中（设置页「重新居中」） */
    private fun recenterOverlay() {
        val view = floatingView ?: return
        val p = params ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        p.x = ((screenWidth - p.width) / 2).coerceAtLeast(0)
        p.y = p.y.coerceAtLeast(0)
        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: Exception) {
            // ignore
        }
        savePrefInt(LyricsOverlayManager.KEY_POS_X, p.x)
        savePrefInt(LyricsOverlayManager.KEY_POS_Y, p.y)
    }

    private fun toggleLock() {
        isLocked = !isLocked
        applyLockState()
        // 写偏好 + 可靠通知（管理器内部会发出设置变更信号）
        LyricsOverlayManager.setLocked(isLocked)
    }

    /**
     * 应用锁定状态。
     *
     * 锁定后除了禁止拖动/点击，还要把窗口设为 **FLAG_NOT_TOUCHABLE**：
     * 桌面歌词锁定后不应该再拦截点击事件，手指应当穿透到下面的应用
     * （否则悬浮窗会挡掉播放页/列表的点击）。
     */
    private fun applyLockState() {
        val button = lockButton
        if (isLocked) {
            button?.setText(R.string.lyrics_unlock)
            button?.setBackgroundResource(R.drawable.lock_button_locked_bg)
            settingsPanel?.visibility = View.GONE
            showSettings = false
        } else {
            button?.setText(R.string.lyrics_lock)
            button?.setBackgroundResource(R.drawable.lock_button_bg)
        }
        applyTouchable(!isLocked)
    }

    /** 切换窗口是否接收触摸（false = 触摸穿透到下层应用） */
    private fun applyTouchable(touchable: Boolean) {
        val view = floatingView ?: return
        val p = params ?: return
        val newFlags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (newFlags == p.flags) return
        p.flags = newFlags
        try {
            windowManager.updateViewLayout(view, p)
        } catch (_: Exception) {
            // ignore
        }
    }

    // ===================== 持久化 =====================

    private fun prefs(): SharedPreferences =
        getSharedPreferences(LyricsOverlayManager.PREFS_NAME, MODE_PRIVATE)

    private fun loadSettings() {
        val p = prefs()
        isLocked = p.getBoolean(LyricsOverlayManager.KEY_LOCKED, false)
        lyricsColor = p.getInt(LyricsOverlayManager.KEY_COLOR, LyricsOverlayManager.DEFAULT_COLOR)
        fontSize = p.getFloat(LyricsOverlayManager.KEY_FONT_SIZE, LyricsOverlayManager.DEFAULT_FONT_SIZE)
            .coerceIn(LyricsOverlayManager.MIN_FONT_SIZE, LyricsOverlayManager.MAX_FONT_SIZE)
        fontWeight = p.getInt(LyricsOverlayManager.KEY_FONT_WEIGHT, LyricsOverlayManager.DEFAULT_FONT_WEIGHT)
        linesCount = p.getInt(LyricsOverlayManager.KEY_LINES_COUNT, 2)
    }

    private fun savePrefInt(key: String, value: Int) {
        prefs().edit().putInt(key, value).apply()
    }

    private fun loadPrefInt(key: String, defaultVal: Int): Int = prefs().getInt(key, defaultVal)

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceScope.cancel()
        handler.removeCallbacks(displayTick)
        stateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
                // ignore
            }
        }
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // ignore
            }
        }
        floatingView = null
        prefs().edit().putBoolean(LyricsOverlayManager.KEY_VISIBLE, false).apply()
        LyricsOverlayManager.onServiceVisibilityChanged(false)
    }

    companion object {
        private const val TAG = "FloatingLyrics"

        /** 显示刷新的最短间隔（毫秒）：同刻多句歌词时的紧凑跟转 */
        private const val MIN_DISPLAY_TICK_MS = 50L

        /** 显示刷新的最长间隔（毫秒）：距下一句歌词尚远时的兜底唤醒 */
        private const val MAX_DISPLAY_TICK_MS = 1000L

        private const val CLICK_MAX_DURATION_MS = 200L

        /** 读取历史坐标时的下限余量：y 至少留出这么多像素高度的可见区域 */
        private const val CLICK_TARGET_MARGIN_PX = 200
    }
}
