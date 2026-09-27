package com.mtechviral.musicfinderexample.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.DarkBackground
import com.mtechviral.musicfinderexample.core.designsystem.theme.DarkSurface
import com.mtechviral.musicfinderexample.core.designsystem.theme.LightBackground
import com.mtechviral.musicfinderexample.core.designsystem.theme.LightSidebarTop
import com.mtechviral.musicfinderexample.core.designsystem.theme.TextSecondaryDark
import com.mtechviral.musicfinderexample.core.designsystem.theme.TextSecondaryLight
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytDivider
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytIsDark

/**
 * 打开侧边栏的入口。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_nav_scaffold.dart` 中
 * `MPNavScaffold.of(context)?.toggleSidebar()`：
 * 一级页面的顶栏按钮通过它切换侧边栏；
 * 未处于 HomeShell 内时默认空实现（不会崩溃）。
 */
val LocalOpenSidebar = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * 切换一级页面（侧边栏下标）。
 *
 * 对应原 Flutter 工程 `MPNavScaffoldState.selectPage(index)`：
 * 供一级页面内部的"去扫描"等跨页按钮使用（如歌曲页空状态 → 扫描音乐页）。
 * 未处于 HomeShell 内时默认空实现。
 */
val LocalSelectPage = staticCompositionLocalOf<(Int) -> Unit> { {} }

/**
 * 侧边栏跟手拖拽接口。
 *
 * 主页（HomeShell）自身已通过 `draggable` 处理右滑打开；但页面内部若存在**横向可滚动区**，
 * 滚动区会先把手势吃掉，导致侧边栏打不开。这类页面可把自己的「滚到边界后剩下的横向位移」
 * 转交进来（通过 `nestedScroll` 转发）。
 *
 * 注：该接口最初是为「喜欢」页的三 Tab `HorizontalPager` 引入的；喜欢页改为单个歌曲列表后
 * 已不再使用，接口保留以备后续有横向滚动的一级页面（当前无调用方）。
 *
 * @param dragBy 跟手位移（像素，向右为正，内部按侧边栏宽度换算成进度）
 * @param settle 松手结算（像素/秒，向右为正）
 */
class SidebarDragHandle(
    val dragBy: (deltaXPx: Float) -> Unit = {},
    val settle: (velocityXPx: Float) -> Unit = {},
)

/** 由 HomeShell 提供的侧边栏拖拽接口（未处于 HomeShell 内时为空实现） */
val LocalSidebarDrag = staticCompositionLocalOf { SidebarDragHandle() }

/** 侧边栏条目定义（对应 Dart `SidebarItem`） */
data class SidebarItem(val title: String, val icon: ImageVector)

/** 十个一级页面入口（顺序与 Dart `kSidebarItems` 一致，新增「组网设置」位于「设置」上方） */
val kSidebarItems: List<SidebarItem> = listOf(
    SidebarItem("歌曲", Icons.Filled.MusicNote),
    SidebarItem("专辑", Icons.Filled.Album),
    SidebarItem("艺术家", Icons.Filled.People),
    SidebarItem("歌单", Icons.Filled.PlaylistPlay),
    SidebarItem("喜欢", Icons.Filled.Favorite),
    SidebarItem("扫描音乐", Icons.Filled.ManageSearch),
    SidebarItem("远程配置", Icons.Filled.Cloud),
    SidebarItem("统计", Icons.Filled.BarChart),
    SidebarItem("组网设置", Icons.Filled.Hub),
    SidebarItem("设置", Icons.Filled.Settings),
)

/** 音乐相关分组（索引 0-4） */
val kMusicGroup = listOf(0, 1, 2, 3, 4)

/** 设置相关分组（索引 5-9） */
val kSettingsGroup = listOf(5, 6, 7, 8, 9)

/**
 * 侧边栏内容：上方 Logo，其下两张无标题卡片（音乐 / 设置），当前页高亮。
 * 对应 Dart `MPSidebar`。
 */
@Composable
fun Sidebar(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = MaterialTheme.ytIsDark
    val unselected = if (isDark) Color(0xFFB3B3C2) else Color(0xFF333333)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(
                Brush.linearGradient(
                    colors = if (isDark) {
                        listOf(DarkSurface, DarkBackground)
                    } else {
                        listOf(LightSidebarTop, LightBackground)
                    },
                ),
            ),
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            // 顶部 Logo 区
            Row(
                modifier = Modifier.padding(
                    start = 20.dp, top = 28.dp, end = 20.dp, bottom = 24.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(
                            Brush.linearGradient(listOf(BrandPurple, BrandCyan)),
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "愉乐",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }

            // 两张无标题卡片
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                item { SidebarGroupCard(kMusicGroup, currentIndex, unselected, onSelect) }
                item { Spacer(Modifier.size(12.dp)) }
                item { SidebarGroupCard(kSettingsGroup, currentIndex, unselected, onSelect) }
            }
        }
    }
}

@Composable
private fun SidebarGroupCard(
    indexes: List<Int>,
    currentIndex: Int,
    unselected: Color,
    onSelect: (Int) -> Unit,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.ytDivider),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            indexes.forEach { index ->
                SidebarEntry(
                    item = kSidebarItems[index],
                    selected = index == currentIndex,
                    unselectedColor = unselected,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun SidebarEntry(
    item: SidebarItem,
    selected: Boolean,
    unselectedColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .background(
                brush = if (selected) {
                    Brush.linearGradient(listOf(BrandPurple, BrandCyan))
                } else {
                    Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = if (selected) Color.White else unselectedColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color.White else unselectedColor,
                maxLines = 1,
            )
        }
    }
}
