package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * 一级页面统一顶栏。
 *
 * 第二十三轮（按设计截图）：目录按钮由 `Icons.toc`（带下划线的列表）改为
 * 截图中的三横线汉堡按钮 `Icons.Menu`；顶栏高度按截图定为 64dp。
 *
 * 版式与截图一致：左上角菜单按钮 + 页面名称（左对齐、紧贴按钮）+ 右侧 actions，
 * 三者都距屏幕左右边缘 [MpListMetrics.EdgePadding]。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PrimaryAppBar(
    title: String,
    onMenuClick: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                // 截图标题字号约 17sp、字重偏粗（Medium），与 PrimaryAppBar 原样式一致
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleLarge,
            )
        },
        navigationIcon = {
            IconButton(
                modifier = Modifier.size(MpListMetrics.IconButtonSize),
                onClick = onMenuClick,
            ) {
                Icon(
                    imageVector = Icons.Filled.Menu,
                    contentDescription = "打开侧边栏",
                    modifier = Modifier.size(MpListMetrics.IconSize),
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        // 截图顶栏高 192px = 64dp（不含状态栏）
        modifier = Modifier.height(MpListMetrics.AppBarHeight),
    )
}
