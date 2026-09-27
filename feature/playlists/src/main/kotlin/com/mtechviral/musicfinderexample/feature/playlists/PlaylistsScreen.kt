/*
 * 对应 Dart 原文件：lib/pages/playlists_page.dart（歌单一级页面）
 *
 * 与 Dart 逐条对照：
 *  - 顶栏：标题"歌单"，右侧两个 action（cloud_sync = 从 Subsonic 同步歌单；add = 新建歌单）
 *  - _load()      -> LaunchedEffect 查询 DatabaseHelper.queryPlaylists()
 *  - _create()    -> 输入歌单名对话框（新建歌单 / 请输入歌单名 / 取消 / 确定）-> createPlaylist()，重名提示"新建歌单失败（可能重名）"
 *  - _delete()    -> 二次确认（删除歌单 / 确定删除歌单「x」吗？ / 取消 / 删除）-> deletePlaylist()
 *  - _syncFromSubsonic() -> 未配置提示"请先在"远程配置"页面配置 Subsonic 服务器"；
 *                    同步中显示"正在同步歌单..."不可取消进度框；完成后提示"同步完成，共同步 N 个歌单"并刷新
 *  - _pushToRemote()     -> 未配置提示；"歌单内没有远程歌曲，无法推送到 Subsonic"；
 *                    "正在推送到远程..."进度框；成功"歌单「x」已推送到 Subsonic"，失败"推送失败"
 *  - _showPlaylistActions() -> 长按歌单弹出底部菜单：播放歌单 / 推送到远程（已配置才显示）/ 删除歌单
 *  - 空状态："暂无歌单" + "新建歌单"按钮；列表项："N 首歌曲" + 删除按钮
 *
 * 与 Dart 的差异（原生新增，其余全部一致）：
 *  1) 列表项左侧封面：Dart 固定为紫青渐变 + queue_music 图标；原生优先显示"歌单内第一首含封面歌曲"的封面，
 *     歌单内无任何封面时回退为与 Dart 完全相同的渐变 + queue_music 图标。
 *  2) 顶部 Dialog（新建/删除/同步进度）用 Compose AlertDialog 实现，等价 Dart showDialog。
 *  3) Dart 的 SnackBar 提示在原生端用 Toast 呈现（文案与时长档位一致：1~2s=SHORT，3s=LONG）。
 *  4) Dart 的长按菜单用 showModalBottomSheet；原生用 ModalBottomSheet + ListTile 等价行。
 */
package com.mtechviral.musicfinderexample.feature.playlists

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.launch

/** 主色常量（与 Dart 硬编码色值一致）；internal 供同模块 PlaylistDetailScreen 复用 */
internal val DeleteRed = Color(0xFFFF5252)
internal val SecondaryGrey = Color(0xFF8A8A99)

/** 歌单列表页：对应 Dart `PlaylistsPage` */
@Composable
fun PlaylistsScreen(onOpenPlaylist: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 对应 Dart 页面 addListener(songData.notifier)：曲库变化时重查聚合数据
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var covers by remember { mutableStateOf<Map<Long, Song>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var reloadTick by remember { mutableIntStateOf(0) }

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Playlist?>(null) }
    var actionTarget by remember { mutableStateOf<Playlist?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }
    var subsonicConfigured by remember { mutableStateOf(false) }

    val toast: (String, Boolean) -> Unit = { message, long ->
        Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // Dart 中 Subsonic 配置在应用启动时加载；此处兜底读一次，保证"推送到远程"菜单项的显示准确
    LaunchedEffect(Unit) {
        subsonicConfigured = RemoteSessionManager.source.value?.protocol in
            setOf(RemoteProtocol.SUBSONIC, RemoteProtocol.NAVIDROME)
    }

    // 对应 Dart `_load()`：查询全部歌单（含歌曲数），并取歌单内第一首含封面歌曲作为卡片封面
    LaunchedEffect(library, reloadTick) {
        val list = DatabaseHelper.queryPlaylists()
        playlists = list
        covers = list.mapNotNull { playlist ->
            val id = playlist.id ?: return@mapNotNull null
            DatabaseHelper.querySongsInPlaylist(id).firstOrNull { it.hasCover() }?.let { id to it }
        }.toMap()
        loading = false
    }

    // 对应 Dart `_syncFromSubsonic()`
    val syncFromSubsonic: () -> Unit = {
        if (!subsonicConfigured) {
            toast("请先在“远程配置”页面配置 Subsonic 服务器", false)
        } else {
            scope.launch {
                busyMessage = "正在同步歌单..."
                try {
                    val synced = SubsonicService.syncPlaylistsToLocalStorage()
                    busyMessage = null
                    toast("同步完成，共同步 $synced 个歌单", false)
                    reloadTick++ // 刷新列表
                } catch (e: Exception) {
                    busyMessage = null
                    toast("同步失败: ${e.message ?: e}", true)
                }
            }
        }
    }

    // 对应 Dart `_pushToRemote(pl)`
    val pushToRemote: (Playlist) -> Unit = { playlist ->
        if (!subsonicConfigured) {
            toast("请先在“远程配置”页面配置 Subsonic 服务器", false)
        } else {
            scope.launch {
                val playlistId = playlist.id
                if (playlistId != null) {
                    // 获取歌单内的远程歌曲
                    val remoteSongIds = DatabaseHelper.querySongsInPlaylist(playlistId)
                        .filter { it.sourceId == RemoteSessionManager.activeSourceId }
                        .mapNotNull { it.remoteId }
                    if (remoteSongIds.isEmpty()) {
                        toast("歌单内没有远程歌曲，无法推送到 Subsonic", false)
                    } else {
                        busyMessage = "正在推送到远程..."
                        try {
                            val created = SubsonicService.createPlaylist(playlist.name, remoteSongIds)
                            busyMessage = null
                            if (created != null) {
                                toast("歌单「${playlist.name}」已推送到 Subsonic", false)
                            } else {
                                toast("推送失败", false)
                            }
                        } catch (e: Exception) {
                            busyMessage = null
                            toast("推送失败: ${e.message ?: e}", true)
                        }
                    }
                }
            }
        }
    }

    // CompositionLocal.current 是 @Composable 读取，必须在 composable 函数体内取一次再传给非 @Composable 回调
    val openSidebar = LocalOpenSidebar.current

    Scaffold(
        topBar = {
            PrimaryAppBar(
                title = "歌单",
                onMenuClick = openSidebar,
                actions = {
                    IconButton(onClick = syncFromSubsonic) {
                        Icon(Icons.Filled.CloudSync, contentDescription = "从 Subsonic 同步歌单")
                    }
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "新建歌单")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                playlists.isEmpty() -> EmptyPlaylistsView(
                    onCreate = { showCreateDialog = true },
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = playlists, key = { it.id ?: it.name }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            cover = playlist.id?.let { covers[it] },
                            onOpen = { playlist.id?.let { onOpenPlaylist(it) } },
                            onLongPress = { actionTarget = playlist },
                            onDelete = { deleteTarget = playlist },
                        )
                    }
                }
            }
        }
    }

    // ===================== 对话框 / 底部菜单 =====================

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { raw ->
                showCreateDialog = false
                val name = raw.trim()
                if (name.isNotEmpty()) {
                    scope.launch {
                        val id = DatabaseHelper.createPlaylist(name)
                        if (id == null) {
                            toast("新建歌单失败（可能重名）", false)
                        }
                        reloadTick++
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        DeletePlaylistDialog(
            playlistName = target.name,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    target.id?.let { DatabaseHelper.deletePlaylist(it) }
                    reloadTick++
                }
            },
        )
    }

    actionTarget?.let { target ->
        PlaylistActionsSheet(
            playlist = target,
            subsonicConfigured = subsonicConfigured,
            onDismiss = { actionTarget = null },
            onPlayPlaylist = {
                actionTarget = null
                target.id?.let { onOpenPlaylist(it) }
            },
            onPushToRemote = {
                actionTarget = null
                pushToRemote(target)
            },
            onDelete = {
                actionTarget = null
                deleteTarget = target
            },
        )
    }

    busyMessage?.let { message -> BusyDialog(message = message) }
}

// ===================== 列表项 =====================

/** 歌单卡片：对应 Dart `ListTile(leading: 渐变图标, title: 名称, subtitle: "N 首歌曲", trailing: 删除)` */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: Playlist,
    cover: Song?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                ArtworkImage(
                    path = cover.path,
                    cachedArtworkPath = cover.cachedArtworkPath,
                    songId = cover.id,
                    coverArtId = cover.coverArtId,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 与 Dart 完全一致的占位：紫青渐变 + queue_music
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    BrandPurple.copy(alpha = 0.6f),
                                    BrandCyan.copy(alpha = 0.6f),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = Color.White)
                }
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songCount} 首歌曲",
                fontSize = 12.sp,
                color = SecondaryGrey,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "删除歌单", tint = DeleteRed)
        }
    }
}

/** 空状态：对应 Dart "暂无歌单" + FilledButton.icon("新建歌单") */
@Composable
private fun EmptyPlaylistsView(onCreate: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "暂无歌单", color = SecondaryGrey)
        Spacer(modifier = Modifier.size(12.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "新建歌单")
        }
    }
}

// ===================== 对话框 =====================

/** 新建歌单对话框：对应 Dart `promptPlaylistName()`（标题"新建歌单"、输入框提示"请输入歌单名"） */
@Composable
internal fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = "请输入歌单名") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                // Dart: onSubmitted: (v) => Navigator.pop(dialogContext, v)
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text(text = "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/** 删除歌单二次确认：对应 Dart `_delete()` 中的 AlertDialog */
@Composable
internal fun DeletePlaylistDialog(
    playlistName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除歌单") },
        text = { Text(text = "确定删除歌单「$playlistName」吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "删除", color = DeleteRed) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}

/** 加载对话框：对应 Dart `showDialog(barrierDismissible: false)` + CircularProgressIndicator + 文案 */
@Composable
internal fun BusyDialog(message: String) {
    AlertDialog(
        onDismissRequest = { /* barrierDismissible: false */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {},
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = message)
            }
        },
    )
}

// ===================== 歌单操作菜单 =====================

/** 歌单操作底部菜单：对应 Dart `_showPlaylistActions()` */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistActionsSheet(
    playlist: Playlist,
    subsonicConfigured: Boolean,
    onDismiss: () -> Unit,
    onPlayPlaylist: () -> Unit,
    onPushToRemote: () -> Unit,
    onDelete: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = playlist.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.W600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(16.dp),
            )
            SheetActionRow(
                icon = Icons.Filled.PlayCircleOutline,
                title = "播放歌单",
                onClick = onPlayPlaylist,
            )
            if (subsonicConfigured) {
                SheetActionRow(
                    icon = Icons.Filled.CloudUpload,
                    title = "推送到远程",
                    subtitle = "将歌单推送到 Subsonic 服务器",
                    tint = BrandPurple,
                    onClick = onPushToRemote,
                )
            }
            SheetActionRow(
                icon = Icons.Filled.DeleteOutline,
                title = "删除歌单",
                tint = DeleteRed,
                titleColor = DeleteRed,
                onClick = onDelete,
            )
        }
    }
}

/** 底部菜单行：等价 Dart 的 `ListTile(leading/title/subtitle)` */
@Composable
internal fun SheetActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color = LocalContentColor.current,
    titleColor: Color = LocalContentColor.current,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, color = titleColor)
            if (subtitle != null) {
                Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.ytTextSecondary)
            }
        }
    }
}

// ===================== 数据辅助 =====================

/**
 * 是否具备可展示的封面：内嵌封面 / 已缓存封面 / 远程 coverArtId 三者之一。
 * 对应 Dart `MpArtwork` 的取值优先级（cachedArtworkPath -> 内嵌封面 -> coverArtId）。
 */
private fun Song.hasCover(): Boolean =
    !cachedArtworkPath.isNullOrEmpty() || hasArtwork || !coverArtId.isNullOrEmpty()
