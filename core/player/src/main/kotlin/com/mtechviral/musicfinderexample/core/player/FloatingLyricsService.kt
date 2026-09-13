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
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

/**
 * 全局悬浮窗歌词服务。
 *
 * 由原 Android 侧 Java 实现（`FloatingLyricsService.java`）1:1 迁移为 Kotlin，
 * 行为、偏好键名、广播协议、布局 id 全部保持不变：
 * - `SYSTEM_ALERT_WINDOW` 悬浮窗，可拖动、点击展开设置面板、可锁定；
 * - 歌词与播放位置通过 SharedPreferences 接收，**独立于 UI 进程刷新**；
 * - 播放中按 200ms 定时器外推播放位置，逐行高亮；
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
    private var fontSize = 16f
    private var linesCount = 2

    // 歌词数据
    private var lyricLines: List<LrcLine> = emptyList()
    private var currentPositionMs = 0L
    private var isPlaying = false
    private var lastUpdateTime = 0L

    // 定时更新
    private lateinit var handler: Handler
    private lateinit var updateRunnable: Runnable

    private var stateReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler = Handler(Looper.getMainLooper())
        loadSettings()
        loadLyricsFromPrefs()
        createFloatingView()
        registerReceivers()
        startUpdateTimer()
        // 标记悬浮窗已显示（管理器据此恢复 UI 状态）
        prefs().edit().putBoolean(LyricsOverlayManager.KEY_VISIBLE, true).apply()
        LyricsOverlayManager.onServiceVisibilityChanged(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.getStringExtra("action")}")
        when (intent?.getStringExtra("action")) {
            "update_lyrics" -> {
                intent.getStringExtra("lyrics")?.let { raw ->
                    savePrefString(LyricsOverlayManager.KEY_LYRICS_RAW, raw)
                    parseAndSetLyrics(raw)
                }
                val pos = intent.getLongExtra("position", -1L)
                if (pos >= 0) {
                    currentPositionMs = pos
                    lastUpdateTime = System.currentTimeMillis()
                    savePrefLong(LyricsOverlayManager.KEY_POSITION_MS, pos)
                    savePrefLong(LyricsOverlayManager.KEY_LAST_UPDATE, lastUpdateTime)
                }
                isPlaying = intent.getBooleanExtra("is_playing", isPlaying)
                savePrefBool(LyricsOverlayManager.KEY_IS_PLAYING, isPlaying)
                updateLyricsDisplay()
            }

            "update_position" -> {
                currentPositionMs =
                    intent.getLongExtra("position", currentPositionMs)
                lastUpdateTime = System.currentTimeMillis()
                savePrefLong(LyricsOverlayManager.KEY_POSITION_MS, currentPositionMs)
                savePrefLong(LyricsOverlayManager.KEY_LAST_UPDATE, lastUpdateTime)
                updateLyricsDisplay()
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
                    LyricsOverlayManager.ACTION_UPDATE_STATE -> {
                        loadSettings()
                        loadLyricsFromPrefs()
                        updateLyricsDisplay()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LyricsOverlayManager.ACTION_TOGGLE_LOCK)
            addAction(LyricsOverlayManager.ACTION_UPDATE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    private fun startUpdateTimer() {
        updateRunnable = object : Runnable {
            override fun run() {
                if (isPlaying) updateLyricsDisplay()
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
        handler.post(updateRunnable)
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
        val defaultWidth = (dm.widthPixels * 0.9f).toInt()

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
            x = loadPrefInt(LyricsOverlayManager.KEY_POS_X, (dm.widthPixels - defaultWidth) / 2)
            y = loadPrefInt(LyricsOverlayManager.KEY_POS_Y, 100)
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
                        p.x = (touchX + dx).toInt()
                        p.y = (touchY + dy).toInt()
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
                        val duration = System.currentTimeMillis() - touchDownTime
                        if (duration < CLICK_MAX_DURATION_MS) toggleSettings()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 解析 LRC 文本（与 Flutter 端 LrcParser 一致的多时间戳语义） */
    private fun parseAndSetLyrics(raw: String?) {
        if (raw.isNullOrBlank()) {
            lyricLines = emptyList()
            Log.d(TAG, "Lyrics raw is empty")
            return
        }
        lyricLines = LrcParser.parse(raw)
        Log.d(TAG, "Parsed ${lyricLines.size} lyric lines")
    }

    private fun updateLyricsDisplay() {
        val line1 = lyricLine1 ?: return
        val line2 = lyricLine2

        // 播放中按经过时间外推当前位置
        var currentPos = currentPositionMs
        if (isPlaying && lastUpdateTime > 0) {
            currentPos += System.currentTimeMillis() - lastUpdateTime
        }

        val activeIdx = LrcParser.activeIndex(lyricLines, currentPos)

        if (lyricLines.isEmpty()) {
            line1.text = ""
            line2?.text = ""
        } else if (linesCount == 1) {
            line1.text =
                if (activeIdx >= 0) lyricLines[activeIdx].text else lyricLines[0].text
        } else {
            if (activeIdx >= 0) {
                line1.text = lyricLines[activeIdx].text
                line2?.text =
                    if (activeIdx + 1 < lyricLines.size) lyricLines[activeIdx + 1].text else ""
            } else {
                line1.text = lyricLines[0].text
                line2?.text = if (lyricLines.size > 1) lyricLines[1].text else ""
            }
        }
    }

    private fun initColorPicker() {
        val row = colorRow ?: return
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for (color in PRESET_COLORS) {
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

        seekBar.progress = (fontSize - MIN_FONT_SIZE).toInt()
        sizeText.text = fontSize.toInt().toString()

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    fontSize = (progress + MIN_FONT_SIZE).toFloat()
                    sizeText.text = fontSize.toInt().toString()
                    applyLyricsStyle()
                    savePrefFloat(LyricsOverlayManager.KEY_FONT_SIZE, fontSize)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun applyLyricsStyle() {
        lyricLine1?.apply {
            setTextColor(lyricsColor)
            textSize = fontSize
        }
        lyricLine2?.apply {
            setTextColor(lyricsColor)
            textSize = fontSize
        }
    }

    private fun applyLinesCount() {
        lyricLine2?.visibility = if (linesCount >= 2) View.VISIBLE else View.GONE
        linesButton?.setText(if (linesCount >= 2) R.string.lyrics_lines_2 else R.string.lyrics_lines_1)
    }

    private fun toggleLinesCount() {
        linesCount = if (linesCount == 1) 2 else 1
        applyLinesCount()
        savePrefInt(LyricsOverlayManager.KEY_LINES_COUNT, linesCount)
        LyricsOverlayManager.setLinesCount(linesCount)
        updateLyricsDisplay()
    }

    private fun setLyricsColor(color: Int) {
        lyricsColor = color
        applyLyricsStyle()
        savePrefInt(LyricsOverlayManager.KEY_COLOR, color)
    }

    private fun setFontSize(size: Float) {
        fontSize = size.coerceIn(MIN_FONT_SIZE.toFloat(), MAX_FONT_SIZE.toFloat())
        applyLyricsStyle()
        savePrefFloat(LyricsOverlayManager.KEY_FONT_SIZE, fontSize)
    }

    private fun toggleSettings() {
        showSettings = !showSettings
        settingsPanel?.visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    private fun toggleLock() {
        isLocked = !isLocked
        applyLockState()
        savePrefBool(LyricsOverlayManager.KEY_LOCKED, isLocked)
        LyricsOverlayManager.onLockStateChanged(isLocked)
    }

    private fun applyLockState() {
        val button = lockButton ?: return
        if (isLocked) {
            button.setText(R.string.lyrics_unlock)
            button.setBackgroundResource(R.drawable.lock_button_locked_bg)
            settingsPanel?.visibility = View.GONE
            showSettings = false
        } else {
            button.setText(R.string.lyrics_lock)
            button.setBackgroundResource(R.drawable.lock_button_bg)
        }
    }

    // ===================== 持久化 =====================

    private fun prefs(): SharedPreferences =
        getSharedPreferences(LyricsOverlayManager.PREFS_NAME, MODE_PRIVATE)

    private fun loadSettings() {
        val p = prefs()
        isLocked = p.getBoolean(LyricsOverlayManager.KEY_LOCKED, false)
        lyricsColor = p.getInt(LyricsOverlayManager.KEY_COLOR, Color.WHITE)
        fontSize = p.getFloat(LyricsOverlayManager.KEY_FONT_SIZE, 16f)
        linesCount = p.getInt(LyricsOverlayManager.KEY_LINES_COUNT, 2)
        currentPositionMs = p.getLong(LyricsOverlayManager.KEY_POSITION_MS, 0L)
        isPlaying = p.getBoolean(LyricsOverlayManager.KEY_IS_PLAYING, false)
        lastUpdateTime = p.getLong(LyricsOverlayManager.KEY_LAST_UPDATE, 0L)
    }

    private fun loadLyricsFromPrefs() {
        prefs().getString(LyricsOverlayManager.KEY_LYRICS_RAW, null)?.let { parseAndSetLyrics(it) }
    }

    private fun savePrefBool(key: String, value: Boolean) {
        prefs().edit().putBoolean(key, value).apply()
    }

    private fun savePrefInt(key: String, value: Int) {
        prefs().edit().putInt(key, value).apply()
    }

    private fun savePrefFloat(key: String, value: Float) {
        prefs().edit().putFloat(key, value).apply()
    }

    private fun savePrefLong(key: String, value: Long) {
        prefs().edit().putLong(key, value).apply()
    }

    private fun savePrefString(key: String, value: String) {
        prefs().edit().putString(key, value).apply()
    }

    private fun loadPrefInt(key: String, defaultVal: Int): Int = prefs().getInt(key, defaultVal)

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        handler.removeCallbacks(updateRunnable)
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
        private const val UPDATE_INTERVAL_MS = 200L
        private const val CLICK_MAX_DURATION_MS = 200L
        private const val MIN_FONT_SIZE = 10
        private const val MAX_FONT_SIZE = 36

        /** 颜色选项（与原实现一致） */
        private val PRESET_COLORS = intArrayOf(
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            0xFFFF4081.toInt(),
            0xFFFF9100.toInt(),
            0xFFE040FB.toInt(),
            0xFF64FFDA.toInt(),
        )
    }
}
