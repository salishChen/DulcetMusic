/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/scan_page.dart
 *
 * 行为对照（原 Dart -> 本文件）：
 *  - MetadataService.scanMediaLibrary / scanFolder -> MetadataScanService(context)
 *    （Dart 端 on_audio_query 内部申请媒体权限；原生端按规范第 10.6 条在点击时申请
 *      READ_MEDIA_AUDIO（API 33+）/ READ_EXTERNAL_STORAGE（低版本））
 *  - file_picker.getDirectoryPath                    -> ActivityResultContracts.OpenDocumentTree
 *    + DocumentFile.fromTreeUri 解析真实路径；解析失败退化 Environment/manual 输入（见注释）
 *  - _onProgress / _progressView                     -> processed/total/failed 状态 + ScanProgressView
 *  - _resultView（ScanResult）                       -> ScanResultView（文案逐字一致）
 *  - _run 中扫描完成后 songData.reload()             -> MusicLibrary.reload()
 *  - _clearAllSongs                                  -> 二次确认 + stopAndClear/clearSongs/clearAllCache/ArtworkCache.clear
 *  - _scanRemote（Subsonic 导入 + _cacheArtworkInBackground） -> runRemoteScan / cacheRemoteArtwork
 *  - 未配置 Subsonic 的提示                          -> Snackbar('请先在"远程配置"页面配置 Subsonic 服务器') + "去配置"动作
 */
package com.mtechviral.musicfinderexample.feature.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.media.ArtworkCache
import com.mtechviral.musicfinderexample.core.media.ExclusionFilter
import com.mtechviral.musicfinderexample.core.media.MetadataScanService
import com.mtechviral.musicfinderexample.core.media.ScanProgress
import com.mtechviral.musicfinderexample.core.media.ScanResult
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import java.io.File
import kotlinx.coroutines.launch

private const val TAG = "ScanScreen"

/** 远程歌曲入库批次大小（对应 Dart `batchSize = 50`） */
private const val REMOTE_BATCH_SIZE = 50

/** 远程封面批量缓存批次大小 */
private const val ARTWORK_BATCH_SIZE = 20

/** 扫描来源卡片渐变使用的补充色（取自 Dart scan_page.dart 的字面量） */
private val BrandPurpleLight = Color(0xFFB388FF)
private val RemoteRed = Color(0xFFFF6B6B)
private val RemoteOrange = Color(0xFFFF8E53)

/** 危险操作红（Dart 使用 Colors.red） */
private val DangerRed = Color(0xFFF44336)

/**
 * 扫描音乐一级页面（对应 Dart `ScanPage`）。
 *
 * @param onOpenSubsonicConfig 打开"远程配置"页（未配置 Subsonic 时提示去配置）
 */
@Composable
fun ScanScreen(onOpenSubsonicConfig: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val service = remember(context) { MetadataScanService(context) }
    // CompositionLocal 只能在可组合函数体内读取，不能写进普通回调 lambda
    val openSidebar = LocalOpenSidebar.current

    var scanning by remember { mutableStateOf(false) }
    var processed by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(0) }
    var lastResult by remember { mutableStateOf<ScanResult?>(null) }

    // 远程封面后台缓存进度（对应 Dart _cacheArtworkInBackground，Dart 无进度展示，此处补充显示）
    var artworkCached by remember { mutableStateOf(0) }
    var artworkTotal by remember { mutableStateOf(0) }

    // OpenDocumentTree 无法解析真实路径时的退化方案：手动输入目录
    var manualPathDialogVisible by remember { mutableStateOf(false) }
    var manualPath by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val remoteScan by RemoteSessionManager.scanState.collectAsState()
    val remoteScanning = remoteScan.phase == RemoteSessionManager.ScanPhase.RUNNING &&
        remoteScan.sourceId == RemoteSessionManager.activeSourceId

    val onProgress: ScanProgress = { p, t, f ->
        processed = p
        total = t
        failed = f
    }

    /** 后台批量缓存远程歌曲封面（对应 Dart `_cacheArtworkInBackground`） */
    suspend fun cacheRemoteArtwork() {
        try {
            // 查询所有有 coverArtId 但没有缓存封面的远程歌曲
            val allSongs = DatabaseHelper.queryAllSongs()
            val pendingSongs = allSongs.filter { song ->
                song.isRemote &&
                    song.coverArtId != null &&
                    song.cachedArtworkPath == null
            }
            if (pendingSongs.isEmpty()) return

            artworkCached = 0
            artworkTotal = pendingSongs.size
            var done = 0
            // Dart 逐首调用 cacheArtwork；原生端按批调用规范 §6 的 cacheArtworkBatch（等价且更省往返）
            for (chunk in pendingSongs.chunked(ARTWORK_BATCH_SIZE)) {
                val updatedSongs = CacheService.cacheArtworkBatch(chunk)
                updatedSongs.forEach { updated ->
                    if (!updated.cachedArtworkPath.isNullOrEmpty() && updated.id != null) {
                        // 同步更新内存中的歌曲对象，使封面立即可见，无需重启
                        MusicLibrary.updateSong(updated)
                        PlaylistRepository.updateSong(updated)
                        // 清除该歌曲以 path 为 key 可能残留的 null 封面缓存
                        ArtworkCache.invalidateByPathPrefix(updated.path)
                    }
                }
                done += chunk.size
                artworkCached = done
            }
            artworkCached = 0
            artworkTotal = 0
            // 封面缓存完成后再次刷新列表以更新 cachedArtworkPath
            MusicLibrary.reload()
        } catch (e: Exception) {
            Log.w(TAG, "后台缓存封面失败: ${e.message}")
            artworkCached = 0
            artworkTotal = 0
        }
    }

    LaunchedEffect(remoteScan.phase, remoteScan.sourceId) {
        if (remoteScan.sourceId != RemoteSessionManager.activeSourceId) return@LaunchedEffect
        when (remoteScan.phase) {
            RemoteSessionManager.ScanPhase.SUCCEEDED -> {
                lastResult = ScanResult(added = remoteScan.imported, total = remoteScan.total)
                cacheRemoteArtwork()
            }
            RemoteSessionManager.ScanPhase.FAILED -> {
                lastResult = ScanResult(failed = 1)
                snackbarHostState.showSnackbar("扫描失败: ${remoteScan.error ?: "未知错误"}")
            }
            else -> Unit
        }
    }

    /** 从 Subsonic 导入全部歌曲并入库（对应 Dart `_scanRemote`） */
    fun runRemoteScan() {
        if (!RemoteSessionManager.isConfigured) {
            // Dart：SnackBar('请先在"远程配置"页面配置 Subsonic 服务器')；
            // 原生端补充"去配置"动作直接跳转配置页
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "请先在远程配置页面选择服务器",
                    actionLabel = "去配置",
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) onOpenSubsonicConfig()
            }
            return
        }

        processed = 0
        total = 0
        failed = 0
        lastResult = null
        artworkCached = 0
        artworkTotal = 0

        scope.launch {
            try {
                if (!RemoteSessionManager.startScan()) {
                    snackbarHostState.showSnackbar("远程扫描已在进行中")
                }
            } catch (e: Exception) {
                Log.w(TAG, "远程扫描失败: ${e.message}")
                lastResult = ScanResult(total = 0, added = 0, failed = 1)
                snackbarHostState.showSnackbar("扫描失败: ${e.message}")
            }
        }
    }

    /** 统一扫描流程（对应 Dart `_run`）：重置进度 -> 扫描 -> 刷新曲库 -> 记录结果 */
    fun runScan(scan: suspend () -> ScanResult) {
        scanning = true
        processed = 0
        total = 0
        failed = 0
        lastResult = null
        artworkCached = 0
        artworkTotal = 0
        scope.launch {
            var result = ScanResult()
            try {
                result = scan()
            } catch (e: Exception) {
                Log.w(TAG, "扫描失败: ${e.message}")
                snackbarHostState.showSnackbar("扫描失败: ${e.message}")
            }
            // 扫描完成后刷新全局歌曲快照（通知各页面）
            MusicLibrary.reload()
            scanning = false
            lastResult = result
        }
    }

    // 媒体库读取权限（API 33+ 为 READ_MEDIA_AUDIO，低版本为 READ_EXTERNAL_STORAGE）
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runScan { service.scanMediaLibrary(onProgress) }
        } else {
            scope.launch { snackbarHostState.showSnackbar("未获得读取媒体库权限，无法扫描媒体库") }
        }
    }

    // 文件夹选择（对应 Dart FilePicker.getDirectoryPath）
    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val folderPath = resolveFolderRealPath(context, uri)
        if (folderPath != null) {
            runScan { service.scanFolder(folderPath, onProgress) }
        } else {
            // 退化方案：选中的是第三方 provider（云盘等）目录，SAF 无法映射真实路径，
            // 按约定提示用户手动输入可访问的绝对路径，默认填外部存储根目录。
            manualPath = Environment.getExternalStorageDirectory().absolutePath
            manualPathDialogVisible = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            PrimaryAppBar(title = "扫描音乐", onMenuClick = openSidebar)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("选择扫描来源", fontSize = 14.sp, color = MaterialTheme.ytTextSecondary)
            Spacer(modifier = Modifier.height(12.dp))

            SourceCard(
                icon = Icons.Filled.LibraryMusic,
                title = "扫描安卓媒体库",
                subtitle = "扫描系统媒体库中的所有音乐并入库",
                colors = listOf(BrandPurple, BrandPurpleLight),
                enabled = !scanning && !remoteScanning,
                onClick = {
                    val permission = mediaAudioPermission()
                    if (ContextCompat.checkSelfPermission(context, permission) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        runScan { service.scanMediaLibrary(onProgress) }
                    } else {
                        mediaPermissionLauncher.launch(permission)
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))

            SourceCard(
                icon = Icons.Filled.FolderOpen,
                title = "扫描指定文件夹",
                subtitle = "选择文件夹，递归扫描其中所有音乐",
                colors = listOf(BrandCyan, BrandPurple),
                enabled = !scanning && !remoteScanning,
                onClick = { folderPickerLauncher.launch(null) },
            )
            Spacer(modifier = Modifier.height(12.dp))

            SourceCard(
                icon = Icons.Filled.CloudDownload,
                title = "扫描远程音乐",
                subtitle = if (RemoteSessionManager.isConfigured) {
                    "从 Subsonic 服务器扫描音乐并入库"
                } else {
                    "请先在\"远程配置\"中配置服务器"
                },
                colors = listOf(RemoteRed, RemoteOrange),
                enabled = !scanning && !remoteScanning,
                onClick = { runRemoteScan() },
            )

            if (scanning || remoteScanning) {
                ScanProgressView(
                    processed = if (remoteScanning) remoteScan.processed else processed,
                    total = if (remoteScanning) remoteScan.total else total,
                    failed = if (remoteScanning) 0 else failed,
                )
            }
            lastResult?.let { result -> ScanResultView(result = result) }
            if (artworkTotal > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "正在缓存远程封面 $artworkCached/$artworkTotal",
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 清空按钮
            OutlinedButton(
                onClick = { showClearConfirm = true },
                enabled = !scanning && !remoteScanning,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DangerRed),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    tint = DangerRed,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("清空音乐库", color = DangerRed)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "说明：歌曲的歌名、艺术家、专辑、时长、比特率、采样率等信息" +
                    "均直接从音频文件中读取并存入本地数据库，播放时使用数据库内的路径。\n\n" +
                    "远程歌曲的封面会在扫描后自动缓存到本地，播放时优先使用缓存封面。",
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary.copy(alpha = 0.6f),
            )
        }
    }

    // 清空音乐库二次确认（对应 Dart `_clearAllSongs` 的 AlertDialog）
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空音乐库") },
            text = {
                Text("确定要清空所有已扫描入库的音乐吗？\n\n此操作不可撤销，将删除所有歌曲记录（包括本地和远程歌曲）。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scanning = true
                        scope.launch {
                            try {
                                // 先停止播放并清空当前播放列表（歌曲记录删除后索引会失效）
                                PlayerController.stopAndClear()
                                // 清空歌曲表（统计 playCount/lastPlayed 随歌曲记录一并清除）
                                DatabaseHelper.clearSongs()
                                // 同时清除所有缓存文件与封面内存缓存
                                CacheService.clearAllCache()
                                ArtworkCache.clear()
                                MusicLibrary.reload()
                                snackbarHostState.showSnackbar("已清空所有音乐")
                            } catch (e: Exception) {
                                Log.w(TAG, "清空失败: ${e.message}")
                                snackbarHostState.showSnackbar("清空失败: ${e.message}")
                            } finally {
                                scanning = false
                                lastResult = null
                            }
                        }
                    },
                ) {
                    Text("清空", color = DangerRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    // SAF 目录无法解析真实路径时的退化输入框
    if (manualPathDialogVisible) {
        AlertDialog(
            onDismissRequest = { manualPathDialogVisible = false },
            title = { Text("输入文件夹路径") },
            text = {
                Column {
                    Text(
                        text = "无法从所选目录解析出真实路径，请确认并输入文件夹的绝对路径：",
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualPath,
                        onValueChange = { manualPath = it },
                        singleLine = true,
                        placeholder = { Text("例如 /storage/emulated/0/Music") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = manualPath.trim()
                        manualPathDialogVisible = false
                        if (path.isNotEmpty()) {
                            runScan { service.scanFolder(path, onProgress) }
                        }
                    },
                ) {
                    Text("开始扫描")
                }
            },
            dismissButton = {
                TextButton(onClick = { manualPathDialogVisible = false }) { Text("取消") }
            },
        )
    }
}

/** 扫描来源卡片（对应 Dart `_sourceCard`：渐变底 + 图标块 + 标题/副标题 + 右箭头） */
@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    colors: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(colors), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.W700, color = Color.White)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f))
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
        )
    }
}

/** 扫描进度（对应 Dart `_progressView`：圆环 + processed/total + 失败数） */
@Composable
private fun ScanProgressView(processed: Int, total: Int, failed: Int) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            if (total > 0) {
                CircularProgressIndicator(
                    progress = { processed.toFloat() / total },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                // total 未知时保持不确定进度（对应 Dart progress = null）
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 8.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            Text(
                text = if (total > 0) "$processed/$total" else "准备中",
                fontSize = 16.sp,
                fontWeight = FontWeight.W600,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "正在扫描入库…（失败 $failed）", color = MaterialTheme.ytTextSecondary)
    }
}

/** 扫描结果汇总（对应 Dart `_resultView`） */
@Composable
private fun ScanResultView(result: ScanResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "扫描完成：共 ${result.total} 个文件，入库 ${result.added} 首，失败 ${result.failed} 个",
            fontSize = 14.sp,
        )
    }
}

/** 当前系统版本下扫描媒体库所需的运行时权限 */
@Suppress("DEPRECATION")
private fun mediaAudioPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * 将 SAF 目录树 URI 转换为文件系统真实路径。
 *
 * 原 Dart 端 `FilePicker.getDirectoryPath` 直接返回真实路径，原生端 SAF 只给出
 * `content://` 树 URI，因此这里：
 *  1. 先用 [DocumentFile.fromTreeUri] 确认所选目录有效（目录、可读）；
 *  2. 再依据 [DocumentsContract.getTreeDocumentId] 的 documentId（形如 `primary:Music/Album`）
 *     拼接出真实路径：`primary` -> [Environment.getExternalStorageDirectory]，
 *     其它卷 -> `/storage/<volumeId>`；
 *  3. 得到的路径必须真实存在且为目录才返回，否则返回 null，由调用方退化为
 *     "手动输入路径"（默认填外部存储根目录），并在注释中说明原因。
 */
private fun resolveFolderRealPath(context: Context, treeUri: Uri): String? = try {
    val documentFile = DocumentFile.fromTreeUri(context, treeUri)
    if (documentFile == null || !documentFile.isDirectory) {
        null
    } else {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val parts = documentId.split(":", limit = 2)
        val relative = if (parts.size == 2) parts[1] else ""
        val base = if (parts.size < 2 || parts[0].equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/${parts[0]}")
        }
        val resolved = if (relative.isEmpty()) base else File(base, relative)
        Log.d(TAG, "选择文件夹: ${documentFile.name} -> ${resolved.absolutePath}")
        resolved.takeIf { it.exists() && it.isDirectory }?.absolutePath
    }
} catch (e: Exception) {
    Log.w(TAG, "解析文件夹真实路径失败: ${e.message}")
    null
}
