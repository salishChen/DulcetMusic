package com.mtechviral.musicfinderexample.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.mtechviral.musicfinderexample.core.common.AppThemeMode

/**
 * 全局主题。
 *
 * 对应原 Flutter 工程 `lib/utils/themes.dart` 中的 `lightTheme` / `darkTheme`
 * 与 `themeMode`（跟随系统 / 浅色 / 深色，默认浅色）。
 *
 * 由 `:app` 在顶层调用一次，内部依据 [AppThemeMode] 选择色板。
 */
@Composable
fun YuleMusicTheme(
    mode: AppThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

/** 次要文字色（等价 Flutter 的 `theme.textTheme.bodySmall.color`） */
val MaterialTheme.ytTextSecondary: Color
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.onSurfaceVariant

/** 卡片底色（浅色白 / 深色 `#1F1F2E`） */
val MaterialTheme.ytCard: Color
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.surfaceContainer

/** 分隔线颜色 */
val MaterialTheme.ytDivider: Color
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.outlineVariant

/** 当前是否为深色模式 */
val MaterialTheme.ytIsDark: Boolean
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.background.luminance() < 0.5f

private fun Color.luminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}
