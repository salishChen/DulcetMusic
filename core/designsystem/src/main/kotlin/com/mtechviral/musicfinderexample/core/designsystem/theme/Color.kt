package com.mtechviral.musicfinderexample.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 品牌配色与明暗色板。
 *
 * 对应原 Flutter 工程 `lib/utils/themes.dart`：
 * 浅色底 `#F7F7FA` + 白卡片；深色底 `#0E0E14` + `#16161F` 面板；
 * 品牌紫 `#7C4DFF`、品牌青 `#18D2C7` 两种主题下都保留。
 */

val BrandPurple = Color(0xFF7C4DFF)
val BrandCyan = Color(0xFF18D2C7)

/** 深色主题下的主色（与 Flutter darkTheme 的 primary 一致） */
val BrandPurpleDark = Color(0xFFB388FF)

/** 次要文字色（浅色下灰、深色下浅灰） */
val TextSecondaryLight = Color(0xFF8A8A99)
val TextSecondaryDark = Color(0xFFB3B3C2)

// ---- 浅色 ----
val LightBackground = Color(0xFFF7F7FA)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFFFFFFF)
val LightDivider = Color(0xFFECECF1)
val LightOnSurface = Color(0xFF333333)
val LightSidebarTop = Color(0xFFF3F2FB)

// ---- 深色 ----
val DarkBackground = Color(0xFF0E0E14)
val DarkSurface = Color(0xFF16161F)
val DarkCard = Color(0xFF1F1F2E)
val DarkDivider = Color(0xFF2A2A3A)
val DarkOnSurface = Color(0xFFFFFFFF)

internal val LightColors = lightColorScheme(
    primary = BrandPurple,
    onPrimary = Color.White,
    secondary = BrandCyan,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightDivider,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainer = LightCard,
    surfaceContainerHigh = LightCard,
    outline = LightDivider,
    outlineVariant = LightDivider,
)

internal val DarkColors = darkColorScheme(
    primary = BrandPurpleDark,
    onPrimary = Color.White,
    secondary = BrandCyan,
    onSecondary = Color.White,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkDivider,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainer = DarkCard,
    surfaceContainerHigh = DarkCard,
    outline = DarkDivider,
    outlineVariant = DarkDivider,
)
