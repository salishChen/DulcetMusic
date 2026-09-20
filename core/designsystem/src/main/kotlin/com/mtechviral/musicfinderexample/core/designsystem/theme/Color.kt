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
val LightBackground = Color(0xFFF5FAFD)
val LightSurface = Color(0xFFFFFFFF)
val LightCard = Color(0xFFFFFFFF)
val LightDivider = Color(0xFFECECF1)
val LightOnSurface = Color(0xFF161B1E)
val LightSidebarTop = Color(0xFFF3F2FB)

// ---------------------------------------------------------------------------
// 列表行样式（第二十三轮：按截图重做歌曲列表）
//
// 下列色值全部取自设计截图（1200x2670，density 480 = 3x）的像素采样。
// ---------------------------------------------------------------------------

/** 列表行副标题（「歌手 - 专辑」）色：截图中比正文浅、但明显深于原有的 #8A8A99 */
val RowSubtitleLight = Color(0xFF40484B)
val RowSubtitleDark = Color(0xFFB3B3C2)

/** 行尾「加号」圆盘底色与加号本身颜色（截图中圆盘极淡、加号中灰） */
val PlusDiscLight = Color(0xFFEFF4F7)
val PlusDiscDark = Color(0xFF23232E)
val PlusIconLight = Color(0xFF959FAB)
val PlusIconDark = Color(0xFFB3B3C2)

/** 音质徽章 SQ（无损）：浅青底 + 深青字 */
val QualitySqBgLight = Color(0xFFE0F2F4)
val QualitySqFgLight = Color(0xFF37779B)
val QualitySqBgDark = Color(0xFF10333C)
val QualitySqFgDark = Color(0xFF56B6D6)

/** 音质徽章 HR（高解析）：金黄底 + 黑字 */
val QualityHrBgLight = Color(0xFFF8D466)
val QualityHrFgLight = Color(0xFF161B1E)
val QualityHrBgDark = Color(0xFF5C4718)
val QualityHrFgDark = Color(0xFFF8D466)

/** 多选操作条强调色（截图「全选」为深青） */
val SelectionAccentLight = Color(0xFF0E5A66)
val SelectionAccentDark = Color(0xFF4FC3D9)

/** 圆形浅底按钮（多选操作条右侧「X」、行内圆盘同族） */
val CircleButtonBgLight = Color(0xFFECF1F4)
val CircleButtonBgDark = Color(0xFF23232E)
val CircleButtonFgLight = Color(0xFF9AA0A5)
val CircleButtonFgDark = Color(0xFFB3B3C2)

/** 多选行右侧圆形选择框描边色 */
val SelectCircleLight = Color(0xFF42474B)
val SelectCircleDark = Color(0xFFB3B3C2)

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
