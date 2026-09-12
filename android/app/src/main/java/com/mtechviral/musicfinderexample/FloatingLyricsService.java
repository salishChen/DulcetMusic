package com.mtechviral.musicfinderexample;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局悬浮窗歌词服务
 *
 * 使用 SYSTEM_ALERT_WINDOW 权限在其他应用之上显示歌词悬浮窗。
 * 歌词数据通过 SharedPreferences 接收，独立于 Flutter 引擎运行。
 */
public class FloatingLyricsService extends Service {

    private static final String TAG = "FloatingLyrics";
    public static final String PREFS_NAME = "lyrics_overlay_prefs";
    public static final String ACTION_UPDATE_STATE = "com.mtechviral.musicfinderexample.UPDATE_LYRICS_STATE";
    public static final String ACTION_TOGGLE_LOCK = "com.mtechviral.musicfinderexample.TOGGLE_LYRICS_LOCK";

    private static final String KEY_LOCKED = "locked";
    private static final String KEY_COLOR = "color";
    private static final String KEY_FONT_SIZE = "font_size";
    private static final String KEY_POS_X = "pos_x";
    private static final String KEY_POS_Y = "pos_y";
    private static final String KEY_LINES_COUNT = "lines_count";
    private static final String KEY_LYRICS_RAW = "lyrics_raw";
    private static final String KEY_POSITION_MS = "position_ms";
    private static final String KEY_IS_PLAYING = "is_playing";
    private static final String KEY_LAST_UPDATE = "last_update";

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;

    private TextView lyricLine1, lyricLine2;
    private View settingsPanel;
    private TextView lockButton, linesButton;
    private LinearLayout colorRow;

    private boolean isLocked = false;
    private boolean showSettings = false;
    private int lyricsColor = Color.WHITE;
    private float fontSize = 16f;
    private int linesCount = 2;

    // 歌词数据
    private List<LyricLine> lyricLines = new ArrayList<>();
    private long currentPositionMs = 0;
    private boolean isPlaying = false;
    private long lastUpdateTime = 0;

    // 定时更新
    private Handler handler;
    private Runnable updateRunnable;

    // 广播接收器
    private BroadcastReceiver stateReceiver;

    // 颜色选项
    private static final int[] PRESET_COLORS = {
            Color.WHITE,
            Color.YELLOW,
            Color.CYAN,
            Color.GREEN,
            0xFFFF4081,
            0xFFFF9100,
            0xFFE040FB,
            0xFF64FFDA
    };

    // 歌词行数据
    private static class LyricLine {
        long timeMs;
        String text;

        LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        loadSettings();
        loadLyricsFromPrefs();
        createFloatingView();
        registerReceivers();
        startUpdateTimer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: " + (intent != null ? intent.getStringExtra("action") : "null"));
        if (intent != null) {
            String action = intent.getStringExtra("action");
            if ("update_lyrics".equals(action)) {
                String raw = intent.getStringExtra("lyrics");
                if (raw != null) {
                    savePrefString(KEY_LYRICS_RAW, raw);
                    parseAndSetLyrics(raw);
                }
                long pos = intent.getLongExtra("position", -1);
                if (pos >= 0) {
                    currentPositionMs = pos;
                    lastUpdateTime = System.currentTimeMillis();
                    savePrefLong(KEY_POSITION_MS, pos);
                    savePrefLong(KEY_LAST_UPDATE, lastUpdateTime);
                }
                boolean playing = intent.getBooleanExtra("is_playing", this.isPlaying);
                this.isPlaying = playing;
                savePrefBool(KEY_IS_PLAYING, playing);
                updateLyricsDisplay();
            } else if ("update_position".equals(action)) {
                long pos = intent.getLongExtra("position", currentPositionMs);
                currentPositionMs = pos;
                lastUpdateTime = System.currentTimeMillis();
                savePrefLong(KEY_POSITION_MS, pos);
                savePrefLong(KEY_LAST_UPDATE, lastUpdateTime);
                updateLyricsDisplay();
            } else if ("update_color".equals(action)) {
                int color = intent.getIntExtra("color", Color.WHITE);
                setLyricsColor(color);
            } else if ("update_font_size".equals(action)) {
                float size = intent.getFloatExtra("size", 16f);
                setFontSize(size);
            } else if ("stop".equals(action)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerReceivers() {
        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Broadcast received: " + action);
                if (ACTION_TOGGLE_LOCK.equals(action)) {
                    toggleLock();
                } else if (ACTION_UPDATE_STATE.equals(action)) {
                    loadSettings();
                    loadLyricsFromPrefs();
                    updateLyricsDisplay();
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TOGGLE_LOCK);
        filter.addAction(ACTION_UPDATE_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private void startUpdateTimer() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isPlaying) {
                    updateLyricsDisplay();
                }
                handler.postDelayed(this, 200);
            }
        };
        handler.post(updateRunnable);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createFloatingView() {
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_lyrics, null);

        lyricLine1 = floatingView.findViewById(R.id.lyric_line1);
        lyricLine2 = floatingView.findViewById(R.id.lyric_line2);
        settingsPanel = floatingView.findViewById(R.id.settings_panel);
        lockButton = floatingView.findViewById(R.id.lock_button);
        linesButton = floatingView.findViewById(R.id.lines_button);
        colorRow = floatingView.findViewById(R.id.color_row);

        applyLyricsStyle();
        applyLinesCount();
        initColorPicker();
        initFontSizeSeekBar();

        lockButton.setOnClickListener(v -> toggleLock());
        linesButton.setOnClickListener(v -> toggleLinesCount());

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int defaultWidth = (int) (dm.widthPixels * 0.9);

        params = new WindowManager.LayoutParams(
                defaultWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = loadPrefInt(KEY_POS_X, (dm.widthPixels - defaultWidth) / 2);
        params.y = loadPrefInt(KEY_POS_Y, 100);

        setupDragAndClick();
        applyLockState();

        try {
            windowManager.addView(floatingView, params);
            Log.d(TAG, "Floating view added to window");
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating view", e);
        }

        updateLyricsDisplay();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragAndClick() {
        final float[] touchX = {0};
        final float[] touchY = {0};
        final float[] startRawX = {0};
        final float[] startRawY = {0};
        final boolean[] isDragging = {false};
        final long[] touchDownTime = {0};

        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchX[0] = params.x;
                    touchY[0] = params.y;
                    startRawX[0] = event.getRawX();
                    startRawY[0] = event.getRawY();
                    isDragging[0] = false;
                    touchDownTime[0] = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - startRawX[0];
                    float dy = event.getRawY() - startRawY[0];

                    if (!isDragging[0] && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging[0] = true;
                    }

                    if (isDragging[0] && !isLocked) {
                        params.x = (int) (touchX[0] + dx);
                        params.y = (int) (touchY[0] + dy);
                        try {
                            windowManager.updateViewLayout(floatingView, params);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (isDragging[0] && !isLocked) {
                        savePrefInt(KEY_POS_X, params.x);
                        savePrefInt(KEY_POS_Y, params.y);
                    } else if (!isLocked) {
                        long duration = System.currentTimeMillis() - touchDownTime[0];
                        if (duration < 200) {
                            toggleSettings();
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    private void parseAndSetLyrics(String raw) {
        lyricLines.clear();
        if (raw == null || raw.trim().isEmpty()) {
            Log.d(TAG, "Lyrics raw is empty");
            return;
        }

        String[] lines = raw.split("\n");
        Pattern pattern = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\](.*)");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            long timeMs = 0;
            String text = line;

            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                int min = Integer.parseInt(matcher.group(1));
                int sec = Integer.parseInt(matcher.group(2));
                String msStr = matcher.group(3);
                int ms = 0;
                if (msStr != null) {
                    ms = Integer.parseInt(msStr.replace(".", "").replace(":", ""));
                    if (msStr.length() == 2) ms *= 10;
                    if (msStr.length() == 1) ms *= 100;
                }
                timeMs = min * 60000L + sec * 1000L + ms;
                text = matcher.group(4).trim();
            }

            if (!text.isEmpty()) {
                lyricLines.add(new LyricLine(timeMs, text));
            }
        }

        lyricLines.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        Log.d(TAG, "Parsed " + lyricLines.size() + " lyric lines");
    }

    private void updateLyricsDisplay() {
        if (lyricLine1 == null) return;

        long currentPos = currentPositionMs;
        if (isPlaying && lastUpdateTime > 0) {
            currentPos += System.currentTimeMillis() - lastUpdateTime;
        }

        int activeIdx = -1;
        for (int i = lyricLines.size() - 1; i >= 0; i--) {
            if (lyricLines.get(i).timeMs <= currentPos) {
                activeIdx = i;
                break;
            }
        }

        if (lyricLines.isEmpty()) {
            lyricLine1.setText("");
            lyricLine2.setText("");
        } else if (linesCount == 1) {
            String text = activeIdx >= 0 ? lyricLines.get(activeIdx).text : lyricLines.get(0).text;
            lyricLine1.setText(text);
        } else {
            if (activeIdx >= 0) {
                lyricLine1.setText(lyricLines.get(activeIdx).text);
                if (activeIdx + 1 < lyricLines.size()) {
                    lyricLine2.setText(lyricLines.get(activeIdx + 1).text);
                } else {
                    lyricLine2.setText("");
                }
            } else {
                lyricLine1.setText(lyricLines.get(0).text);
                lyricLine2.setText(lyricLines.size() > 1 ? lyricLines.get(1).text : "");
            }
        }
    }

    private void initColorPicker() {
        colorRow.removeAllViews();
        for (int color : PRESET_COLORS) {
            View colorCircle = new View(this);
            int size = (int) (24 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            int margin = (int) (4 * getResources().getDisplayMetrics().density);
            lp.setMargins(margin, 0, margin, 0);
            colorCircle.setLayoutParams(lp);

            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(color);
            bg.setStroke(color == lyricsColor ? 3 : 1, color == lyricsColor ? Color.WHITE : 0x60FFFFFF);
            colorCircle.setBackground(bg);

            colorCircle.setOnClickListener(v -> {
                setLyricsColor(color);
                initColorPicker();
            });

            colorRow.addView(colorCircle);
        }
    }

    private void initFontSizeSeekBar() {
        SeekBar seekBar = floatingView.findViewById(R.id.font_size_seekbar);
        TextView sizeText = floatingView.findViewById(R.id.font_size_text);

        seekBar.setProgress((int) (fontSize - 10));
        sizeText.setText(String.valueOf((int) fontSize));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    fontSize = progress + 10;
                    sizeText.setText(String.valueOf((int) fontSize));
                    applyLyricsStyle();
                    savePrefFloat(KEY_FONT_SIZE, fontSize);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void applyLyricsStyle() {
        if (lyricLine1 == null) return;
        lyricLine1.setTextColor(lyricsColor);
        lyricLine1.setTextSize(fontSize);
        if (lyricLine2 != null) {
            lyricLine2.setTextColor(lyricsColor);
            lyricLine2.setTextSize(fontSize);
        }
    }

    private void applyLinesCount() {
        if (lyricLine2 == null) return;
        lyricLine2.setVisibility(linesCount >= 2 ? View.VISIBLE : View.GONE);
        if (linesButton != null) {
            linesButton.setText(linesCount + "行");
        }
    }

    private void toggleLinesCount() {
        linesCount = linesCount == 1 ? 2 : 1;
        applyLinesCount();
        savePrefInt(KEY_LINES_COUNT, linesCount);
        updateLyricsDisplay();
    }

    private void setLyricsColor(int color) {
        lyricsColor = color;
        applyLyricsStyle();
        savePrefInt(KEY_COLOR, color);
    }

    private void setFontSize(float size) {
        fontSize = Math.max(10, Math.min(36, size));
        applyLyricsStyle();
        savePrefFloat(KEY_FONT_SIZE, fontSize);
    }

    private void toggleSettings() {
        showSettings = !showSettings;
        settingsPanel.setVisibility(showSettings ? View.VISIBLE : View.GONE);
    }

    private void toggleLock() {
        isLocked = !isLocked;
        applyLockState();
        savePrefBool(KEY_LOCKED, isLocked);
    }

    private void applyLockState() {
        if (lockButton == null) return;
        if (isLocked) {
            lockButton.setText("解锁");
            lockButton.setBackgroundResource(R.drawable.lock_button_locked_bg);
            settingsPanel.setVisibility(View.GONE);
            showSettings = false;
        } else {
            lockButton.setText("锁定");
            lockButton.setBackgroundResource(R.drawable.lock_button_bg);
        }
    }

    // ===================== 持久化 =====================

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        isLocked = prefs.getBoolean(KEY_LOCKED, false);
        lyricsColor = prefs.getInt(KEY_COLOR, Color.WHITE);
        fontSize = prefs.getFloat(KEY_FONT_SIZE, 16f);
        linesCount = prefs.getInt(KEY_LINES_COUNT, 2);
        currentPositionMs = prefs.getLong(KEY_POSITION_MS, 0);
        isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false);
        lastUpdateTime = prefs.getLong(KEY_LAST_UPDATE, 0);
    }

    private void loadLyricsFromPrefs() {
        String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LYRICS_RAW, null);
        if (raw != null) {
            parseAndSetLyrics(raw);
        }
    }

    private void savePrefBool(String key, boolean value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private void savePrefInt(String key, int value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(key, value).apply();
    }

    private void savePrefFloat(String key, float value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat(key, value).apply();
    }

    private void savePrefLong(String key, long value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putLong(key, value).apply();
    }

    private void savePrefString(String key, String value) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(key, value).apply();
    }

    private int loadPrefInt(String key, int defaultVal) {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(key, defaultVal);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        if (handler != null && updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
        if (stateReceiver != null) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Exception e) {
                // ignore
            }
        }
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // ignore
            }
        }
    }
}
