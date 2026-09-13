/*
 * 对应 Dart 原文件：lib/pages/settings_page.dart
 *   （主题三选 / 缓存池大小 / 缓存管理入口 / 关于）
 * 另参照：
 *   - lib/pages/now_playing.dart 与 lib/data/lyrics_overlay_manager.dart
 *     （悬浮窗歌词的开关与权限时机）
 *
 * 设置页：
 *   - 主题模式三选：跟随系统 / 浅色 / 深色 → ThemePreference.set(AppThemeMode.XXX)；
 *   - 缓存池大小滑块（500 ~ 51200 MB，Dart divisions = 102）→ CacheService.setCacheSizeMB；
 *   - 展示当前缓存占用（CacheService.getCacheSizeMBActual）；
 *   - 「缓存管理」入口 → onOpenCacheManage()；
 *   - 悬浮窗歌词开关 → LyricsOverlayManager（悬浮窗权限 + Android 13+ 通知权限）；
 *   - 关于（愉乐 1.0.0）。
 */

package com.mtechviral.musicfinderexample.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.AppThemeMode
import com.mtechviral.musicfinderexample.core.common.Formatters
import com.mtechviral.musicfinderexample.core.common.ThemePreference
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.TextSecondaryLight
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.player.LyricsOverlayManager
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 缓存池滑块下限 / 上限（与 Dart 的 min: 500 / max: 51200 一致） */
private const val CACHE_MIN_MB = 500
private const val CACHE_MAX_MB = 51200

/** 步进：Dart `divisions: 102` → Compose `steps = divisions - 1` */
private const val CACHE_SLIDER_STEPS = 101

/** 应用名与版本（对应 Dart AboutListTile 的 applicationName / applicationVersion） */
private const val APP_NAME = "愉乐"
private const val APP_VERSION = "1.0.0"

/**
 * 设置页。
 *
 * @param onOpenCacheManage 跳转缓存管理页（对应 Dart 中 Navigator.push(CacheManagePage)）
 */
@Composable
fun SettingsScreen(onOpenCacheManage: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeMode by ThemePreference.mode.collectAsStateWithLifecycle()
    val overlayVisible by LyricsOverlayManager.isVisible.collectAsStateWithLifecycle()
    // 顶层组合时读取一次（CompositionLocal.current 不能在非 @Composable 的 lambda 中读取）
    val openSidebar = LocalOpenSidebar.current

    var cacheSizeMB by remember { mutableStateOf(2048) }
    var actualCacheMB by remember { mutableStateOf(0) }
    var loadingCache by remember { mutableStateOf(true) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Android 13+ 通知权限（悬浮窗歌词服务依赖前台通知）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 授权结果不影响开关状态，悬浮窗权限单独判断 */ }

    // 页面每次进入时重新读取缓存池设置与实时占用（对应 Dart initState + didChangeDependencies）
    LaunchedEffect(Unit) {
        cacheSizeMB = CacheService.getCacheSizeMB()
        actualCacheMB = CacheService.getCacheSizeMBActual()
        loadingCache = false
    }

    Scaffold(
        topBar = {
            PrimaryAppBar(
                title = "设置",
                onMenuClick = openSidebar,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            // ---- 主题设置（顺序与 Dart 一致：跟随系统 / 浅色 / 深色） ----
            ThemeOptionRow(
                selected = themeMode == AppThemeMode.SYSTEM,
                icon = Icons.Filled.BrightnessAuto,
                title = "跟随系统",
                subtitle = "系统为深色时使用深色，否则使用浅色",
                onClick = { ThemePreference.set(AppThemeMode.SYSTEM) },
            )
            ThemeOptionRow(
                selected = themeMode == AppThemeMode.LIGHT,
                icon = Icons.Filled.LightMode,
                title = "浅色",
                subtitle = null,
                onClick = { ThemePreference.set(AppThemeMode.LIGHT) },
            )
            ThemeOptionRow(
                selected = themeMode == AppThemeMode.DARK,
                icon = Icons.Filled.DarkMode,
                title = "深色",
                subtitle = null,
                onClick = { ThemePreference.set(AppThemeMode.DARK) },
            )

            HorizontalDivider()

            // ---- 缓存池大小 ----
            SettingsTile(
                leading = Icons.Filled.Storage,
                title = "缓存池大小",
                subtitle = if (loadingCache) {
                    "加载中..."
                } else {
                    "已用 ${Formatters.formatSizeMB(actualCacheMB)} / 上限 ${Formatters.formatSizeMB(cacheSizeMB)}"
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "500 MB",
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
                Slider(
                    value = cacheSizeMB.toFloat().coerceIn(
                        CACHE_MIN_MB.toFloat(),
                        CACHE_MAX_MB.toFloat(),
                    ),
                    onValueChange = { value ->
                        if (!loadingCache) cacheSizeMB = value.roundToInt()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    enabled = !loadingCache,
                    valueRange = CACHE_MIN_MB.toFloat()..CACHE_MAX_MB.toFloat(),
                    steps = CACHE_SLIDER_STEPS,
                    onValueChangeFinished = {
                        if (!loadingCache) {
                            val target = cacheSizeMB
                            scope.launch { CacheService.setCacheSizeMB(target) }
                        }
                    },
                )
                Text(
                    text = "50 GB",
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
            }
            Text(
                text = "超出缓存池大小时，自动删除最早缓存的音乐",
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            Spacer(Modifier.height(8.dp))

            // ---- 缓存管理入口 ----
            SettingsTile(
                leading = Icons.AutoMirrored.Filled.ManageSearch,
                title = "缓存管理",
                subtitle = "查看和管理已缓存的远程音乐",
                showChevron = true,
                onClick = onOpenCacheManage,
            )

            HorizontalDivider()

            // ---- 悬浮窗歌词 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.MusicNote, contentDescription = null)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "悬浮窗歌词")
                    Text(
                        text = "在其他应用上层显示当前歌词",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = overlayVisible,
                    onCheckedChange = { checked ->
                        if (checked) {
                            // Android 13+ 需要通知权限才能显示前台通知
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            }
                            // 悬浮窗权限：无权限时跳转系统「显示在其他应用上层」授权页
                            if (LyricsOverlayManager.checkPermission()) {
                                LyricsOverlayManager.showOverlay()
                            } else {
                                LyricsOverlayManager.requestPermission()
                            }
                        } else {
                            LyricsOverlayManager.hideOverlay()
                        }
                    },
                )
            }

            HorizontalDivider()

            // ---- 关于 ----
            SettingsTile(
                leading = Icons.Outlined.Info,
                title = "关于",
                subtitle = "$APP_NAME $APP_VERSION",
                onClick = { showAboutDialog = true },
            )
        }
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("关于") },
            text = {
                Column {
                    Text(APP_NAME)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = APP_VERSION,
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("确定")
                }
            },
        )
    }
}

/**
 * 主题单选项（对应 Dart 的 RadioListTile：控件 → 图标 → 标题 → 副标题）。
 */
@Composable
private fun ThemeOptionRow(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Icon(imageVector = icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(text = title)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSecondaryLight,
                )
            }
        }
    }
}

/**
 * 通用设置项行（对应 Dart 的 ListTile：图标 + 标题 + 副标题 + 右侧箭头）。
 */
@Composable
private fun SettingsTile(
    leading: ImageVector,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = leading, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.ytTextSecondary,
            )
        }
    }
}
