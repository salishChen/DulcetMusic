package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.ui.unit.dp

/**
 * 列表 / 顶栏的统一尺寸常量（第二十三轮：按设计截图重做列表样式）。
 *
 * 全部数值由设计截图（1200x2670 px，density 480 = 3x）反推得到，
 * 原始像素值除以 3 即为 dp：
 *
 * | 元素                     | 截图 px        | dp    |
 * |--------------------------|----------------|-------|
 * | 封面                     | 150 x 150      | 50    |
 * | 封面圆角                 | ~10            | 4     |
 * | 行间距（行高 - 封面）     | 66（上下各33） | 11    |
 * | 行首内边距（封面左）      | 48             | 16    |
 * | 行尾内边距（末按钮右）    | 18             | 6     |
 * | 封面与文字列间距          | 43             | 14    |
 * | 图标按钮盒（含热区）      | 132            | 44    |
 * | 图标本体                 | 72             | 24    |
 * | 顶栏高（状态栏之下）      | 192            | 64    |
 * | 操作条高                 | 132            | 44    |
 *
 * 图标盒相邻时中心间距 44dp、两侧外边距 6dp —— 因此
 * 「最右图标中心」= 屏宽 - 6 - 22 = 屏宽 - 28dp，与截图实测一致。
 */
object MpListMetrics {

    /** 顶栏 / 操作条 / 行尾的左右外边距 */
    val EdgePadding = 6.dp

    /** 图标按钮的盒子边长（也是相邻按钮的中心间距） */
    val IconButtonSize = 44.dp

    /** 图标本体尺寸 */
    val IconSize = 24.dp

    /** 前导图标盒与标题之间的间距 */
    val LeadingGap = 6.dp

    /** 行首内边距（封面左边缘到屏幕左边） */
    val RowStartPadding = 16.dp

    /** 行尾内边距（最右按钮到屏幕右边） */
    val RowEndPadding = 6.dp

    /** 行上下内边距（行高 72dp - 封面 50dp，上下各 11dp） */
    val RowVerticalPadding = 11.dp

    /** 封面边长 */
    val ArtworkSize = 50.dp

    /** 封面圆角（截图为小圆角，不是原来的 12dp） */
    val ArtworkCorner = 4.dp

    /** 封面与右侧文字列的间距 */
    val ArtworkTextGap = 14.dp

    /**
     * 标题行与副标题行之间的额外间距。
     *
     * 截图文字块总高 96px = 32dp，正好等于「标题行盒 19dp + 副标题行盒 14dp」，
     * 两行紧贴，因此这里是 0。
     */
    val TitleSubtitleGap = 0.dp

    /** 顶栏高度（不含状态栏） */
    val AppBarHeight = 64.dp

    /** 标题下方操作条 / 多选条高度 */
    val ControlRowHeight = 44.dp

    /** 行尾「加号」圆盘直径（截图实测 42px / 3 = 14dp） */
    val PlusDiscSize = 14.dp

    /**
     * 行尾「加号」图标盒尺寸。
     *
     * 截图中加号笔画本身是 18px = 6dp；Material 的 Add 矢量在图标盒内留白约 42%，
     * 因此图标盒取 10dp 才能画出约 6dp 的可见加号。
     */
    val PlusIconSize = 10.dp

    /** 「更多」三点图标尺寸（截图中比标准 24dp 略小） */
    val MoreIconSize = 21.dp

    /** 缓存标识图标尺寸（原 16dp，需求：缩小 2px → 14dp） */
    val CachedIconSize = 14.dp

    /** 多选态圆形选择框直径 */
    val SelectCircleSize = 20.dp

    /** 多选条右侧「X」圆底直径 */
    val CloseCircleSize = 22.dp

    /** 多选条右侧「X」图标尺寸 */
    val CloseIconSize = 14.dp

    /** 音质徽章字号 */
    val BadgeFontSize = 9.dp

    /** 副标题（歌手 - 专辑）字号 */
    val SubtitleFontSize = 12.dp

    /** 标题字号 */
    val TitleFontSize = 16.dp
}
