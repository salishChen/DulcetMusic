package com.mtechviral.musicfinderexample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            YuleMusicApp()
        }
    }
}
