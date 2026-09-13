/*
 * 对应 Dart 原文件：lib/pages/cache_manage_page.dart
 *
 * 缓存管理页：
 *   - 顶部渐变卡片展示缓存池已用容量（CacheService.getCacheSizeMBActual）与歌曲数量；
 *   - 列表展示已缓存歌曲（歌名 / 艺术家 · 大小 / 缓存时间）；
 *   - 支持进入多选模式批量删除（CacheService.deleteCache）与清空全部缓存（二次确认）；
 *   - 删除后清空内存封面缓存（ArtworkCache.clear）。
 */

package com.mtechviral.musicfinderexample.feature.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.Formatters
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.TextSecondaryLight
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.media.ArtworkCache
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.launch

/** 删除类操作的强调色（与原 Dart 端 Color(0xFFFF5252) 一致） */
private val DeleteRed = Color(0xFFFF5252)

/**
 * 缓存管理页。
 *
 * @param onBack 返回上一页（对应 Dart 端 AppBar 返回键的 `Navigator.pop`）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManageScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var cachedSongs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var cacheSizeMB by remember { mutableStateOf(0) }
    // reloadKey：删除 / 清空后自增，触发重新加载（等价 Dart 的 _loadCache）
    var reloadKey by remember { mutableStateOf(0) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showClearAllDialog by remember { mutableStateOf(false) }

    // 首次进入与每次删除后重新加载缓存列表与占用容量（对应 Dart 的 _loadCache）
    LaunchedEffect(reloadKey) {
        loading = true
        cachedSongs = CacheService.getCachedSongs()
        cacheSizeMB = CacheService.getCacheSizeMBActual()
        loading = false
    }

    // 提示条（对应 Dart 的 ScaffoldMessenger.showSnackBar，时长 1 秒）
    LaunchedEffect(pendingMessage) {
        val message = pendingMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        pendingMessage = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    // 无缓存时不显示任何操作按钮（与 Dart 一致）
                    if (cachedSongs.isNotEmpty()) {
                        if (selectMode) {
                            val allSelected = selectedIds.size == cachedSongs.size
                            IconButton(
                                onClick = {
                                    selectedIds = if (allSelected) {
                                        emptySet()
                                    } else {
                                        cachedSongs.mapNotNull { it.id }.toSet()
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = if (allSelected) {
                                        // Dart 用 Icons.deselect；此处用等义且必然存在的复选框图标
                                        Icons.Filled.CheckBox
                                    } else {
                                        Icons.Filled.SelectAll
                                    },
                                    contentDescription = if (allSelected) "取消全选" else "全选",
                                )
                            }
                            IconButton(
                                onClick = { showDeleteDialog = true },
                                enabled = selectedIds.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除选中",
                                    tint = if (selectedIds.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                    } else {
                                        DeleteRed
                                    },
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val next = !selectMode
                                selectMode = next
                                if (!next) selectedIds = emptySet()
                            },
                        ) {
                            Icon(
                                imageVector = if (selectMode) {
                                    Icons.Filled.Close
                                } else {
                                    Icons.Filled.Checklist
                                },
                                contentDescription = if (selectMode) "取消选择" else "批量选择",
                            )
                        }
                        if (!selectMode) {
                            IconButton(onClick = { showClearAllDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = "清空缓存",
                                    tint = DeleteRed,
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    CacheSummaryCard(
                        cacheSizeMB = cacheSizeMB,
                        songCount = cachedSongs.size,
                        selectedCount = selectedIds.size,
                        selectMode = selectMode,
                    )
                    if (cachedSongs.isEmpty()) {
                        EmptyCacheHint(modifier = Modifier.weight(1f))
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(items = cachedSongs, key = { it.path }) { song ->
                                val songId = song.id
                                CacheSongRow(
                                    song = song,
                                    selectMode = selectMode,
                                    selected = songId != null && selectedIds.contains(songId),
                                    onToggle = {
                                        song.id?.let { id ->
                                            selectedIds = if (selectedIds.contains(id)) {
                                                selectedIds - id
                                            } else {
                                                selectedIds + id
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除选中（二次确认，与 Dart 文案一致）
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除缓存") },
            text = { Text("确定删除选中的 ${selectedIds.size} 首歌曲的缓存吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        val ids = selectedIds.toList()
                        if (ids.isNotEmpty()) {
                            scope.launch {
                                CacheService.deleteCache(ids)
                                // 同步清空内存中的封面缓存（避免继续展示已删除的封面）
                                ArtworkCache.clear()
                                selectedIds = emptySet()
                                selectMode = false
                                reloadKey++
                                pendingMessage = "缓存已删除"
                            }
                        }
                    },
                ) {
                    Text("删除", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // 清空全部缓存（二次确认，与 Dart 文案一致）
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清空缓存") },
            text = { Text("确定清空所有缓存吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearAllDialog = false
                        scope.launch {
                            CacheService.clearAllCache()
                            ArtworkCache.clear()
                            selectedIds = emptySet()
                            selectMode = false
                            reloadKey++
                            pendingMessage = "缓存已清空"
                        }
                    },
                ) {
                    Text("清空", color = DeleteRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 顶部缓存信息卡片（紫青渐变） */
@Composable
private fun CacheSummaryCard(
    cacheSizeMB: Int,
    songCount: Int,
    selectedCount: Int,
    selectMode: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                brush = Brush.linearGradient(listOf(BrandPurple, BrandCyan)),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = Color.White,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "缓存池大小",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            Text(
                text = Formatters.formatSizeMB(cacheSizeMB),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$songCount 首歌曲",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
            if (selectMode) {
                Text(
                    text = "已选 $selectedCount 首",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 空状态：暂无缓存 */
@Composable
private fun EmptyCacheHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.MusicOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.ytTextSecondary.copy(alpha = 0.3f),
            )
            Spacer(Modifier.height(16.dp))
            Text(text = "暂无缓存", color = MaterialTheme.ytTextSecondary)
        }
    }
}

/**
 * 单条缓存歌曲行。
 *
 * 多选模式下左侧为复选框，普通模式为紫色音符占位块；
 * 副标题为「艺术家 · 大小」（与 Dart 完全一致），右侧额外展示缓存时间。
 */
@Composable
private fun CacheSongRow(
    song: Song,
    selectMode: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectMode && song.id != null, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = BrandPurple,
                    checkmarkColor = Color.White,
                ),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(BrandPurple.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = BrandPurple,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = "${song.displayArtist} · ${Formatters.formatSize(song.size ?: 0L)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = TextSecondaryLight,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = Formatters.formatDateTime(song.cacheTimestamp),
            fontSize = 12.sp,
            color = TextSecondaryLight,
        )
    }
}
