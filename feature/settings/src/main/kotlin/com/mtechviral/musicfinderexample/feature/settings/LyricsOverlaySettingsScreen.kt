/*
 * 新增页面（无 Dart 对应页）：把原本只能长按悬浮窗打开的小面板，做成正式的
 * 「桌面歌词」设置页，便于设置字号 / 粗细 / 颜色 / 行数 / 位置锁定。
 *
 * 数据全部落在 `lyrics_overlay_prefs`（与服务、旧版 Flutter 共用同一份偏好），
 * 修改后写偏好 + 发 ACTION_UPDATE_STATE 广播，正在运行的悬浮窗会**立即生效**。
 */
package com.mtechviral.musicfinderexample.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.player.LyricsOverlayManager

/**
 * 「桌面歌词」设置页。
 *
 * @param onBack 返回上一页（二级页面 AppBar 返回键）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsOverlaySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val visible by LyricsOverlayManager.isVisible.collectAsStateWithLifecycle()
    val locked by LyricsOverlayManager.isLocked.collectAsStateWithLifecycle()
    val linesCount by LyricsOverlayManager.linesCount.collectAsStateWithLifecycle()
    val prefColor by LyricsOverlayManager.color.collectAsStateWithLifecycle()
    val prefWeight by LyricsOverlayManager.fontWeight.collectAsStateWithLifecycle()
    val prefFontSize by LyricsOverlayManager.fontSize.collectAsStateWithLifecycle()

    // 拖动中的字号只在本地预览，松手才落库（避免每帧写偏好）
    var fontSize by remember { mutableStateOf(prefFontSize) }

    // Android 13+ 通知权限（悬浮窗依赖前台播放服务的通知）
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }

    // 进入页面：先从偏好刷新一次（悬浮窗自带面板里改过的值也要反映过来）
    LaunchedEffect(Unit) { LyricsOverlayManager.refresh() }
    LaunchedEffect(prefFontSize) { fontSize = prefFontSize }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面歌词") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            // ---- 实时预览 ----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1F1F23))
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "桌面歌词预览",
                    color = Color(prefColor),
                    fontSize = fontSize.sp,
                    fontWeight = weightToFontWeight(prefWeight),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---- 显示开关 ----
            SwitchRow(
                title = "显示桌面歌词",
                subtitle = "在其他应用上层显示当前播放的歌词",
                checked = visible,
                onCheckedChange = { checked ->
                    if (checked) {
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

            HorizontalDivider()

            // ---- 字号 ----
            SectionTitle("字号")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = LyricsOverlayManager.MIN_FONT_SIZE.toInt().toString(),
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
                Slider(
                    value = fontSize.coerceIn(
                        LyricsOverlayManager.MIN_FONT_SIZE,
                        LyricsOverlayManager.MAX_FONT_SIZE,
                    ),
                    onValueChange = { fontSize = it },
                    valueRange = LyricsOverlayManager.MIN_FONT_SIZE..LyricsOverlayManager.MAX_FONT_SIZE,
                    steps = (LyricsOverlayManager.MAX_FONT_SIZE - LyricsOverlayManager.MIN_FONT_SIZE).toInt() - 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    onValueChangeFinished = { LyricsOverlayManager.setFontSize(fontSize) },
                )
                Text(
                    text = "${fontSize.toInt()}",
                    fontSize = 13.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
            }

            // ---- 字体粗细 ----
            SectionTitle("字体粗细")
            WeightOption(
                title = "细",
                selected = prefWeight == LyricsOverlayManager.WEIGHT_LIGHT,
                onClick = { LyricsOverlayManager.setFontWeight(LyricsOverlayManager.WEIGHT_LIGHT) },
            )
            WeightOption(
                title = "常规",
                selected = prefWeight == LyricsOverlayManager.WEIGHT_NORMAL,
                onClick = { LyricsOverlayManager.setFontWeight(LyricsOverlayManager.WEIGHT_NORMAL) },
            )
            WeightOption(
                title = "粗体",
                selected = prefWeight == LyricsOverlayManager.WEIGHT_BOLD,
                onClick = { LyricsOverlayManager.setFontWeight(LyricsOverlayManager.WEIGHT_BOLD) },
            )

            // ---- 文字颜色 ----
            SectionTitle("文字颜色")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LyricsOverlayManager.PRESET_COLORS.forEach { preset ->
                    val selected = preset == prefColor
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(preset))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.White.copy(alpha = 0.35f)
                                },
                                shape = CircleShape,
                            )
                            .clickable { LyricsOverlayManager.setColor(preset) },
                    )
                }
            }

            // ---- 行数 ----
            SectionTitle("显示行数")
            WeightOption(
                title = "单行（只显示当前句）",
                selected = linesCount == 1,
                onClick = { LyricsOverlayManager.setLinesCount(1) },
            )
            WeightOption(
                title = "双行（当前句 + 下一句）",
                selected = linesCount >= 2,
                onClick = { LyricsOverlayManager.setLinesCount(2) },
            )

            HorizontalDivider()

            // ---- 位置锁定 ----
            SwitchRow(
                title = "锁定位置",
                subtitle = "锁定后不会被误拖动、点击也不弹面板；解锁请点播放页或通知栏的「词」按钮",
                checked = locked,
                onCheckedChange = { LyricsOverlayManager.setLocked(it) },
            )

            // ---- 重新居中 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { LyricsOverlayManager.requestRecenter() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = null,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "重新居中")
                    Text(
                        text = "把桌面歌词摆回屏幕水平中央",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "提示：解锁后可直接在屏幕上拖动悬浮歌词（默认水平居中，拖出屏幕会自动收回）；" +
                    "解锁状态下点击悬浮窗可展开快捷面板。播放页/通知栏「词」按钮的循环是：" +
                    "未显示 → 显示；已显示且锁定 → 解锁；已显示未锁定 → 关闭。",
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

/** 分区标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** 带开关的设置行 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 单选项行（粗细 / 行数共用） */
@Composable
private fun WeightOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text = title)
    }
}

/** 粗细偏好 → Compose 字重（与悬浮窗服务里的 Typeface 映射保持一致） */
private fun weightToFontWeight(weight: Int): FontWeight = when (weight) {
    LyricsOverlayManager.WEIGHT_LIGHT -> FontWeight.Light
    LyricsOverlayManager.WEIGHT_NORMAL -> FontWeight.Normal
    else -> FontWeight.Bold
}
