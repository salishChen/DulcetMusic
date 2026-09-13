package com.mtechviral.musicfinderexample.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题模式（三态：跟随系统 / 浅色 / 深色）。
 * 对应原 Flutter 工程 `lib/utils/themes.dart` 中的 `ThemeMode` 与 `themeModeNotifier`。
 */
enum class AppThemeMode(val storageValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    companion object {
        fun fromStorage(value: String?): AppThemeMode = when (value) {
            "dark" -> DARK
            "system" -> SYSTEM
            else -> LIGHT // 默认浅色
        }
    }
}

/**
 * 全局主题偏好：进程内单例 + StateFlow 通知。
 *
 * 默认浅色（与 Flutter 端一致：启动时读偏好失败也保持浅色）。
 */
object ThemePreference {

    private val _mode = MutableStateFlow(AppThemeMode.LIGHT)
    val mode: StateFlow<AppThemeMode> = _mode.asStateFlow()

    /** 启动时从偏好恢复 */
    fun load() {
        _mode.value = AppThemeMode.fromStorage(
            AppPreferences.getString(AppPreferences.KEY_THEME_MODE, "light"),
        )
    }

    /** 写入偏好并即时生效 */
    fun set(mode: AppThemeMode) {
        _mode.value = mode
        AppPreferences.putString(AppPreferences.KEY_THEME_MODE, mode.storageValue)
    }
}
