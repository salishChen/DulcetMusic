package com.mtechviral.musicfinderexample;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.ryanheise.audioservice.AudioServiceActivity;

import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;

/**
 * 主 Activity
 */
public class MainActivity extends AudioServiceActivity {

    private static final String TAG = "MainActivity";
    private static final String CHANNEL = "com.mtechviral.musicfinderexample/widget";
    private static final String LYRICS_CHANNEL = "com.mtechviral.musicfinderexample/lyrics_overlay";
    private static final String ACTION_PLAY_PAUSE = "com.mtechviral.musicfinderexample.ACTION_PLAY_PAUSE";
    private static final int OVERLAY_PERMISSION_REQUEST = 1234;
    private MethodChannel methodChannel;
    private MethodChannel lyricsChannel;
    private MethodChannel.Result pendingPermissionResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        super.configureFlutterEngine(flutterEngine);

        methodChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL);

        methodChannel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "updateWidget":
                    String title = call.argument("title");
                    Boolean isPlaying = call.argument("isPlaying");
                    String artworkPath = call.argument("artworkPath");
                    MusicWidgetUpdateHelper.updateWidgetData(
                            this,
                            title != null ? title : "未在播放",
                            isPlaying != null && isPlaying,
                            artworkPath);
                    result.success(true);
                    break;
                case "checkPendingPlayPause":
                    boolean pending = MusicWidgetUpdateHelper.hasPendingPlayPause(this);
                    if (pending) {
                        MusicWidgetUpdateHelper.clearPendingPlayPause(this);
                    }
                    result.success(pending);
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });

        // 悬浮窗歌词 MethodChannel
        lyricsChannel = new MethodChannel(
                flutterEngine.getDartExecutor().getBinaryMessenger(), LYRICS_CHANNEL);

        lyricsChannel.setMethodCallHandler((call, result) -> {
            switch (call.method) {
                case "checkOverlayPermission":
                    result.success(Settings.canDrawOverlays(this));
                    break;
                case "requestOverlayPermission":
                    requestOverlayPermission(result);
                    break;
                case "showOverlay":
                    if (Settings.canDrawOverlays(this)) {
                        startFloatingLyricsService();
                        result.success(true);
                    } else {
                        result.error("NO_PERMISSION", "没有悬浮窗权限", null);
                    }
                    break;
                case "hideOverlay":
                    stopFloatingLyricsService();
                    result.success(true);
                    break;
                case "updateLyrics":
                    updateLyricsViaPrefs(call.argument("lyrics"), call.argument("position"), call.argument("is_playing"));
                    result.success(true);
                    break;
                case "updatePosition":
                    updatePositionViaPrefs(call.argument("position"));
                    result.success(true);
                    break;
                case "toggleLock":
                    toggleLockViaBroadcast();
                    result.success(true);
                    break;
                case "getLinesCount":
                    SharedPreferences prefs = getSharedPreferences(FloatingLyricsService.PREFS_NAME, MODE_PRIVATE);
                    result.success(prefs.getInt("lines_count", 2));
                    break;
                case "setLinesCount":
                    Integer count = call.argument("count");
                    if (count != null) {
                        getSharedPreferences(FloatingLyricsService.PREFS_NAME, MODE_PRIVATE)
                                .edit().putInt("lines_count", count).apply();
                        sendUpdateBroadcast();
                    }
                    result.success(true);
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        });
    }

    private void requestOverlayPermission(MethodChannel.Result result) {
        if (Settings.canDrawOverlays(this)) {
            result.success(true);
            return;
        }
        pendingPermissionResult = result;
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            boolean hasPermission = Settings.canDrawOverlays(this);
            if (pendingPermissionResult != null) {
                pendingPermissionResult.success(hasPermission);
                pendingPermissionResult = null;
            }
        }
    }

    private void startFloatingLyricsService() {
        Log.d(TAG, "Starting FloatingLyricsService");
        try {
            Intent intent = new Intent(this, FloatingLyricsService.class);
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
        }
    }

    private void stopFloatingLyricsService() {
        Intent intent = new Intent(this, FloatingLyricsService.class);
        stopService(intent);
    }

    /**
     * 通过 SharedPreferences 更新歌词和播放状态
     */
    private void updateLyricsViaPrefs(String lyrics, Long position, Boolean isPlaying) {
        SharedPreferences prefs = getSharedPreferences(FloatingLyricsService.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        if (lyrics != null) {
            editor.putString("lyrics_raw", lyrics);
        }
        if (position != null) {
            editor.putLong("position_ms", position);
            editor.putLong("last_update", System.currentTimeMillis());
        }
        if (isPlaying != null) {
            editor.putBoolean("is_playing", isPlaying);
        }
        editor.apply();

        // 发送广播通知 Service 更新
        sendUpdateBroadcast();
    }

    /**
     * 通过 SharedPreferences 更新播放位置
     */
    private void updatePositionViaPrefs(Long position) {
        if (position == null) return;
        SharedPreferences prefs = getSharedPreferences(FloatingLyricsService.PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putLong("position_ms", position)
                .putLong("last_update", System.currentTimeMillis())
                .apply();

        sendUpdateBroadcast();
    }

    /**
     * 通过广播切换锁定状态
     */
    private void toggleLockViaBroadcast() {
        Intent intent = new Intent(FloatingLyricsService.ACTION_TOGGLE_LOCK);
        sendBroadcast(intent);
    }

    /**
     * 发送更新广播
     */
    private void sendUpdateBroadcast() {
        Intent intent = new Intent(FloatingLyricsService.ACTION_UPDATE_STATE);
        sendBroadcast(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.hasExtra("action")) {
            String action = intent.getStringExtra("action");
            if (ACTION_PLAY_PAUSE.equals(action)) {
                Log.d(TAG, "收到小组件播放/暂停请求");
                intent.removeExtra("action");
            }
        }
    }
}
