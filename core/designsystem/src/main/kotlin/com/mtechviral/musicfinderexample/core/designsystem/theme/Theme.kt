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

// ---------------------------------------------------------------------------
// 列表行样式（第二十三轮）
// ---------------------------------------------------------------------------

/** 列表行副标题（「歌手 - 专辑」）颜色 */
val MaterialTheme.ytRowSubtitle: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) RowSubtitleDark else RowSubtitleLight

/** 行尾「加号」圆盘底色 */
val MaterialTheme.ytPlusDisc: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) PlusDiscDark else PlusDiscLight

/** 行尾「加号」图标颜色 */
val MaterialTheme.ytPlusIcon: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) PlusIconDark else PlusIconLight

/** 圆形浅底按钮底色（多选条「X」） */
val MaterialTheme.ytCircleButtonBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) CircleButtonBgDark else CircleButtonBgLight

/** 圆形浅底按钮前景色 */
val MaterialTheme.ytCircleButtonFg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) CircleButtonFgDark else CircleButtonFgLight

/** 多选行右侧选择框描边色 */
val MaterialTheme.ytSelectCircle: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) SelectCircleDark else SelectCircleLight

/** 多选操作条强调色（「全选」按钮） */
val MaterialTheme.ytSelectionAccent: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) SelectionAccentDark else SelectionAccentLight

/** SQ 徽章底色 */
val MaterialTheme.ytQualitySqBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) QualitySqBgDark else QualitySqBgLight

/** SQ 徽章文字色 */
val MaterialTheme.ytQualitySqFg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) QualitySqFgDark else QualitySqFgLight

/** HR 徽章底色 */
val MaterialTheme.ytQualityHrBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) QualityHrBgDark else QualityHrBgLight

/** HR 徽章文字色 */
val MaterialTheme.ytQualityHrFg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (ytIsDark) QualityHrFgDark else QualityHrFgLight

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
