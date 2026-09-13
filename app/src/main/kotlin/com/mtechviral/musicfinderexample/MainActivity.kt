package com.mtechviral.musicfinderexample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mtechviral.musicfinderexample.ui.YuleMusicApp

/**
 * 主界面 Activity。
 *
 * 对应原 Flutter 工程的 `MainActivity`（Flutter 嵌入宿主）：
 * 原生端只负责承载 Compose 内容，播放控制、通知栏、悬浮窗、桌面小组件
 * 全部由 `:core:player` 的服务与单例承担。
 *
 * `launchMode="singleTop"` + `enableEdgeToEdge()`：
 * 桌面小组件点击回到本 Activity 时复用同一实例，不做重建。
 *
 * 额外补充（旧版由 audio_service 隐式处理）：Android 13+ 首次进入时申请
 * `POST_NOTIFICATIONS`，否则前台播放服务通知不可见。
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 用户选择的结果无需额外处理：拒绝时播放本身不受影响，只是通知不可见
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YuleMusicApp()
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
