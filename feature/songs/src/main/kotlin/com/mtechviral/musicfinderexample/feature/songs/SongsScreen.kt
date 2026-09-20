/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/songs_page.dart
 * 列表项交互参照：.flutter_reference/lib/widgets/mp_song_list_item.dart
 *
 * 行为对照（原 Dart -> 本文件）：
 *  - songData.notifier / songData.songs      -> MusicLibrary.songs + collectAsStateWithLifecycle
 *  - initState 查库 + 全局刷新                -> LaunchedEffect(Unit) { MusicLibrary.reload() }
 *  - _sortSongs（6 种排序 + 升降序）          -> sortSongs(...)
 *  - 搜索按钮 showSearch(MusicSearchDelegate) -> onOpenSearch 回调（AppRoutes.SEARCH）
 *  - 点击歌曲 playlistData.setSongs + playSong -> PlaylistRepository.setSongs + PlayerController.playSong
 *  - MpSongListItem 的 more_vert 弹框             -> EntityActionSheet(SongTarget)
 *  - 空状态 _emptyView 文案                   -> EmptyLibraryView（文案逐字一致）
 *  - 多选模式 Dart `_selectionMode`           -> selectionMode + selectedPaths
 *      · 普通顶栏"多选"按钮（Icons.checklist）与长按列表行进入多选（Dart 二者都有）
 *      · 多选顶栏：关闭 / "已选择 N 首" / 全选·取消全选 / 添加到播放队列 / 删除选中
 *      · 多选行 `_buildSelectableSongItem`：Checkbox + 封面 40dp(圆角 8) + 歌名 + "艺术家 · 专辑"，
 *        点击行 = 切换选中（不播放、不弹菜单）
 *      · 批量操作文案与 Dart 一致：'已添加 N 首歌曲到播放队列'、
 *        确认删除（'确认删除' / '确定要删除选中的 N 首歌曲吗？'）→ '已删除 N 首歌曲'
 *  - "随机播放"：规范第 9 节《播放列表行为约定》。
 *    （第十八轮：按需求去掉顶部的「播放全部」按钮；
 *     第十九轮：按需求去掉顶部的「随机播放」按钮，该条只剩「共 N 首」。
 *     第二十三轮：按设计截图重做列表行 / 操作条 / 多选条（尺寸见 `MpListMetrics`）。
 *     第二十六轮：需求 1 把操作条固定到列表顶部；需求 2 多选态在底部新增
 *     「永久删除 / 添加到歌单 / 播放选中队列」三个功能，其中「播放选中队列」把选中的
 *     音乐插入到**当前播放音乐之后**（`PlayerController.playAfterCurrent`）；
 *     需求 4 的「永久删除」区分本地文件与在线歌曲（见 `LibrarySongDeleter`）。
 *     整列播放仍可通过点击任意歌曲触达；随机播放可由播放页底部的播放模式按钮进入随机模式，
 *     届时列表本身会被重排。）
 *
 * 屏幕由 `:app` 的 HomeShell 直接按索引调用（不经过 NavHost），因此跳转一律用
 * "默认参数 + 回调"，默认空实现时页面可独立渲染、不崩溃。
 */
package com.mtechviral.musicfinderexample.feature.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.MpListMetrics
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.component.QualityBadge
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytCircleButtonBg
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytCircleButtonFg
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytRowSubtitle
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytSelectCircle
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytSelectionAccent
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.LibrarySongDeleter
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 删除操作红（Dart 批量删除对话框使用 `Colors.red`） */
private val DeleteRed = Color(0xFFF44336)

/** 多选底部操作条高度（第二十六轮需求 2；图标 24dp + 文字 12sp + 内边距） */
private val SelectionBottomBarHeight = 64.dp

/** 排序方式（对应 Dart `SongSortType`） */
private enum class SongSortType { TITLE, ARTIST, ALBUM, DATE_ADDED, PLAY_COUNT, DURATION }

/** 排序方向（对应 Dart `SortDirection`） */
private enum class SortDirection { ASCENDING, DESCENDING }

/**
 * 歌曲一级页面：列出数据库中的所有音乐（对应 Dart `SongsPage`）。
 *
 * @param onOpenNowPlaying 打开播放页（等价 Dart 播放页滑出）
 * @param onOpenSearch 打开搜索页（对应 Dart showSearch，路由 AppRoutes.SEARCH）
 * @param onOpenScan 空状态"去扫描"跳转扫描页（对应 Dart `selectPage(5)`，路由 AppRoutes.SCAN）。
 */
@Composable
fun SongsScreen(
    onOpenNowPlaying: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenScan: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    // CompositionLocal 只能在可组合函数体内读取，不能写进普通回调 lambda
    val openSidebar = LocalOpenSidebar.current

    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var sortType by remember { mutableStateOf(SongSortType.TITLE) }
    var sortDirection by remember { mutableStateOf(SortDirection.ASCENDING) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<EntityActionTarget?>(null) }
    var addToPlaylistSongs by remember { mutableStateOf<List<Song>?>(null) }

    // ---- 第二十六轮需求 4：多选「永久删除」的目标（非 null 时弹确认框）----
    // 本地音乐需先确认再删文件；在线歌曲直接删除（弹窗内说明其缓存与排除行为）。
    var permanentDeleteTargets by remember { mutableStateOf<List<Song>?>(null) }

    // ---- 多选模式（对应 Dart _selectionMode / _selectedPaths） ----
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 长按进入多选后，行自身的 clickable 可能仍会在抬手时触发一次 onClick，用该标记吞掉它
    var suppressClickPath by remember { mutableStateOf<String?>(null) }

    // 首次进入刷新曲库（等价 Dart 页面 addListener(songData.notifier) 的初始快照）
    LaunchedEffect(Unit) {
        MusicLibrary.reload()
        loading = false
    }

    // 长按吞掉的那次点击只在极短时间内有效，超时自动清理，避免影响后续正常点击
    LaunchedEffect(suppressClickPath) {
        if (suppressClickPath != null) {
            delay(600)
            suppressClickPath = null
        }
    }

    val sortedSongs = remember(library, sortType, sortDirection) {
        sortSongs(library, sortType, sortDirection)
    }
    val currentPath = currentSong?.path

    /** 退出多选并清空选中集合（对应 Dart `_selectionMode = false; _selectedPaths.clear()`） */
    val exitSelectionMode: () -> Unit = {
        selectionMode = false
        selectedPaths = emptySet()
    }

    /** 点击整列播放：替换播放列表 -> 从本首开始播放 -> 打开播放页（Dart openNowPlayingPage） */
    val playFromList: (Song) -> Unit = { song ->
        PlaylistRepository.setSongs(sortedSongs)
        scope.launch { PlayerController.playSong(song) }
        onOpenNowPlaying()
    }

    /** 切换某行选中状态（Dart `_buildSelectableSongItem` 的 onTap） */
    val toggleSelection: (Song) -> Unit = { song ->
        selectedPaths = if (selectedPaths.contains(song.path)) {
            selectedPaths - song.path
        } else {
            selectedPaths + song.path
        }
    }

    /** 进入多选并选中该行（Dart 长按行为；列表为空时无入口） */
    val enterSelectionMode: (Song) -> Unit = { song ->
        selectionMode = true
        selectedPaths = setOf(song.path)
    }

    /**
     * 选中的歌曲（按当前排序顺序）。
     *
     * 第二十六轮需求 2：底部操作条的三个动作都以「当前选中的歌曲集合」为输入，
     * 且在动作发生后（如退出多选）仍要能拿到**当时**的集合，因此这里读出快照。
     */
    val selectedSongsSnapshot: List<Song> =
        sortedSongs.filter { selectedPaths.contains(it.path) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // 顶栏在多选态下保持原样（第二十三轮：多选条移到标题下方，见列表首项）
            PrimaryAppBar(
                title = "歌曲",
                onMenuClick = openSidebar,
            ) {
                // 搜索入口（Dart：showSearch(delegate: MusicSearchDelegate())）
                IconButton(
                    modifier = Modifier.size(MpListMetrics.IconButtonSize),
                    onClick = onOpenSearch,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(MpListMetrics.IconSize),
                    )
                }
            }
        },
        // 第二十六轮需求 2：多选态在**底部**常驻三个批量操作（永久删除 / 添加到歌单 / 播放选中队列）。
        // 放在 Scaffold 的 bottomBar 上，随多选模式出现与消失。
        bottomBar = {
            if (selectionMode) {
                SelectionBottomBar(
                    enabled = selectedPaths.isNotEmpty(),
                    onPermanentDelete = { permanentDeleteTargets = selectedSongsSnapshot },
                    onAddToPlaylist = { addToPlaylistSongs = selectedSongsSnapshot },
                    onPlaySelected = {
                        val songs = selectedSongsSnapshot
                        if (songs.isNotEmpty()) {
                            // 需求 2：插入到当前播放列表的**当前播放音乐之后**（不是队尾）
                            scope.launch { PlayerController.playAfterCurrent(songs) }
                            exitSelectionMode()
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                library.isEmpty() -> EmptyLibraryView(onOpenScan = onOpenScan)
                else -> Column(modifier = Modifier.fillMaxSize()) {
                    // 第二十六轮需求 1：操作条**固定在列表顶部**，不再作为 LazyColumn 的首项，
                    // 因此列表上下滑动时它保持不动（原先在 LazyColumn 内会随内容一起滚走）。
                    // 普通态 = 操作条；多选态 = 多选操作条（截图版式）。
                    if (selectionMode) {
                        SelectionActionRow(
                            selectedCount = selectedPaths.size,
                            allSelected = sortedSongs.isNotEmpty() &&
                                selectedPaths.size == sortedSongs.size,
                            onToggleSelectAll = {
                                // Dart：已全选则清空，否则全选
                                selectedPaths = if (sortedSongs.isNotEmpty() &&
                                    selectedPaths.size == sortedSongs.size
                                ) {
                                    emptySet()
                                } else {
                                    sortedSongs.map { it.path }.toSet()
                                }
                            },
                            onClose = exitSelectionMode,
                        )
                    } else {
                        SongsHeader(
                            count = sortedSongs.size,
                            sortType = sortType,
                            sortDirection = sortDirection,
                            sortMenuExpanded = sortMenuExpanded,
                            onShuffle = {
                                // 「随机播放」：整列随机重排后从头播放
                                scope.launch { PlayerController.playShuffled(sortedSongs, 0) }
                                onOpenNowPlaying()
                            },
                            onSortMenuExpandChange = { sortMenuExpanded = it },
                            onSelectSort = { clicked ->
                                val next = toggleSort(sortType, sortDirection, clicked)
                                sortType = next.first
                                sortDirection = next.second
                                sortMenuExpanded = false
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedPaths = emptySet()
                            },
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                    items(sortedSongs, key = { it.path }) { song ->
                        if (selectionMode) {
                            // 多选行（对应 Dart `_buildSelectableSongItem`）：点击 = 切换选中
                            SelectableSongRow(
                                song = song,
                                isSelected = selectedPaths.contains(song.path),
                                onToggle = { toggleSelection(song) },
                            )
                        } else {
                            MpSongListItem(
                                song = song,
                                isCurrent = song.path == currentPath,
                                // 需求：歌曲页去掉每行右侧的时长，并把加号右移 20px
                                showDuration = false,
                                onClick = {
                                    if (suppressClickPath == song.path) {
                                        // 长按进入多选后，子级 clickable 补发的那次点击：忽略
                                        suppressClickPath = null
                                    } else {
                                        playFromList(song)
                                    }
                                },
                                onMoreClick = {
                                    actionTarget = EntityActionTarget.SongTarget(listOf(song))
                                },
                                // 长按进入多选并选中该行（Dart 长按行为；不改 designsystem）
                                modifier = Modifier.onRowLongPress(key = song.path) {
                                    suppressClickPath = song.path
                                    enterSelectionMode(song)
                                },
                            )
                        }
                    }
                    }
                }
            }
        }

        actionTarget?.let { target ->
            EntityActionSheet(
                entity = target,
                onDismiss = { actionTarget = null },
                onPlay = { songs ->
                    actionTarget = null
                    scope.launch { PlayerController.playSongs(songs, 0) }
                    onOpenNowPlaying()
                },
                onPlayNext = {
                    // 弹窗语义 = Dart「添加到播放队列」；addSong 与提示均已在弹窗内部完成
                    actionTarget = null
                },
                onAddToPlaylist = { songs ->
                    actionTarget = null
                    addToPlaylistSongs = songs
                },
                onToggleLike = { _ ->
                    // 喜欢状态的数据库写入已由 EntityActionSheet 内部完成，此处只刷新列表
                    actionTarget = null
                    scope.launch { MusicLibrary.reload() }
                },
                onDelete = { _ ->
                    // 删除与提示已由 EntityActionSheet 内部完成，此处只刷新列表
                    actionTarget = null
                    scope.launch { MusicLibrary.reload() }
                },
                onExcludeArtist = { artist, removedCount ->
                    // 第二十七轮需求：排除歌手（含删库）已由弹窗完成，此处只刷新本页。
                    // 歌曲列表直接由 MusicLibrary 驱动，reload 后该歌手的歌立即消失。
                    actionTarget = null
                    scope.launch {
                        MusicLibrary.reload()
                        if (removedCount == 0) {
                            snackbarHostState.showSnackbar("已排除「$artist」，曲库中没有其音乐")
                        }
                    }
                },
            )
        }

        addToPlaylistSongs?.let { songs ->
            AddToPlaylistDialog(
                songs = songs,
                onDismiss = { addToPlaylistSongs = null },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )
        }

        // 第二十六轮需求 4：永久删除确认框。
        // 本地音乐 → 确认后删除本地文件；在线歌曲 → 删库 + 删缓存 + 加入排除列表。
        permanentDeleteTargets?.let { targets ->
            PermanentDeleteDialog(
                songs = targets,
                onDismiss = { permanentDeleteTargets = null },
                onConfirm = {
                    permanentDeleteTargets = null
                    scope.launch {
                        val result = LibrarySongDeleter.delete(targets)
                        MusicLibrary.reload()
                        exitSelectionMode()
                        snackbarHostState.showSnackbar(permanentDeleteMessage(result))
                    }
                },
            )
        }
    }
}

/** 永久删除结果提示文案（区分本地文件 / 在线歌曲的行为差异） */
private fun permanentDeleteMessage(result: LibrarySongDeleter.Result): String = when {
    result.deleted == 0 -> "没有可删除的歌曲"
    result.excluded > 0 -> buildString {
        append("已删除 ${result.deleted} 首在线歌曲")
        if (result.filesDeleted > 0) append("、${result.filesDeleted} 个本地文件")
        append("，已加入排除列表")
    }
    result.filesDeleted > 0 -> "已删除 ${result.filesDeleted} 个本地文件"
    else -> "已删除 ${result.deleted} 首歌曲"
}

/**
 * 永久删除确认框（第二十六轮需求 4）。
 *
 * 需求要求"若是本地音乐，就弹出确认弹框，确认后直接删除本地音乐文件"。
 * 在线歌曲同样走这个弹框（删除不可撤销），但文案明确说明：
 * 只从本机曲库移除并删除缓存，同时加入排除列表，不会删除服务器上的文件。
 */
@Composable
private fun PermanentDeleteDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val localCount = songs.count { !it.isRemote }
    val remoteCount = songs.size - localCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认永久删除") },
        text = {
            Column {
                Text(
                    text = if (songs.size > 1) {
                        "确定要永久删除选中的 ${songs.size} 首歌曲吗？"
                    } else {
                        "确定要永久删除「${songs.first().title}」吗？"
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (localCount > 0) {
                    Text(
                        text = "· $localCount 首本地音乐：将**删除本地文件**，无法恢复",
                        fontSize = 13.sp,
                        color = DeleteRed,
                    )
                }
                if (remoteCount > 0) {
                    Text(
                        text = "· $remoteCount 首在线歌曲：从曲库移除并删除本地缓存，" +
                            "歌曲名与歌手会加入排除列表（服务器文件不受影响）",
                        fontSize = 13.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("永久删除", color = DeleteRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 多选底部操作条（第二十六轮需求 2，按截图）。
 *
 * 截图版式：底部一条操作条，三个**等分**功能，图标在上、文字在下：
 * - 左：永久删除（红色垃圾桶 + 红叉，DeleteRed）
 * - 中：添加到歌单（圆形加号）
 * - 右：播放选中队列（带加号的列表图标）
 *
 * 与「多选操作条」([SelectionActionRow]) 的分工：那条在顶部负责全选/计数/退出，
 * 这条在底部负责三个实际动作。未选中任何歌曲时三项均禁用（灰化、不响应点击），
 * 避免"点了没反应"或误触发批量操作。
 */
@Composable
private fun SelectionBottomBar(
    enabled: Boolean,
    onPermanentDelete: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlaySelected: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .height(SelectionBottomBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectionBottomAction(
            icon = Icons.Filled.DeleteForever,
            label = "永久删除",
            tint = if (enabled) DeleteRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            enabled = enabled,
            onClick = onPermanentDelete,
        )
        SelectionBottomAction(
            icon = Icons.Filled.AddCircle,
            label = "添加到歌单",
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            enabled = enabled,
            onClick = onAddToPlaylist,
        )
        SelectionBottomAction(
            icon = Icons.Filled.PlaylistAdd,
            label = "播放选中队列",
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            enabled = enabled,
            onClick = onPlaySelected,
        )
    }
}

/** 底部操作条里的单个功能（上下排布的图标 + 文字，等分宽度） */
@Composable
private fun RowScope.SelectionBottomAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 12.sp,
        )
    }
}

/**
 * 多选操作条（第二十三轮：按设计截图重做）。
 *
 * 截图中它**不是顶栏**，而是标题下方的一条 44dp 操作条，且原顶栏保持原样：
 * - 左侧「全选」（深青强调色，对应 Icons.SelectAll）
 * - 中间「已选中 N 项」（居中）
 * - 右侧圆形浅底「X」= 退出多选
 *
 * 与改前的差异：删除 / 加入播放队列两个按钮在该条上不再出现（截图只有三个元素），
 * 长按列表行仍可进入多选并选中该行。
 */
@Composable
private fun SelectionActionRow(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MpListMetrics.ControlRowHeight)
            .padding(horizontal = MpListMetrics.EdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：全选 / 取消全选
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggleSelectAll)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.SelectAll,
                contentDescription = null,
                tint = MaterialTheme.ytSelectionAccent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = if (allSelected) "取消全选" else "全选",
                color = MaterialTheme.ytSelectionAccent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // 中：已选中 N 项（居中占满中间空间）
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "已选中 $selectedCount 项",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 右：圆形浅底 X（退出多选）
        Box(
            modifier = Modifier
                .size(MpListMetrics.IconButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(MpListMetrics.CloseCircleSize)
                    .background(MaterialTheme.ytCircleButtonBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "退出多选",
                    tint = MaterialTheme.ytCircleButtonFg,
                    modifier = Modifier.size(MpListMetrics.CloseIconSize),
                )
            }
        }
    }
}

/**
 * 多选行（第二十三轮：按设计截图重做）。
 *
 * 版式与截图一致：封面 50dp，圆角 4 + 标题 + 副标题（「歌手 - 专辑」，可带音质徽章），
 * 行尾是**圆形选择框**（不再有加号/更多按钮）。点击整行即切换选中。
 */
@Composable
private fun SelectableSongRow(
    song: Song,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start = MpListMetrics.RowStartPadding,
                end = MpListMetrics.RowEndPadding,
                top = MpListMetrics.RowVerticalPadding,
                bottom = MpListMetrics.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArtwork(
            song = song,
            modifier = Modifier.size(MpListMetrics.ArtworkSize),
            cornerRadius = MpListMetrics.ArtworkCorner,
            maxSizePx = with(LocalDensity.current) { MpListMetrics.ArtworkSize.roundToPx() },
        )
        Spacer(Modifier.width(MpListMetrics.ArtworkTextGap))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(MpListMetrics.TitleSubtitleGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                song.qualityBadge?.let { quality ->
                    QualityBadge(quality = quality)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    // 截图格式为「歌手 - 专辑」（与播放列表页保持一致）
                    text = "${song.displayArtist} - ${song.displayAlbum}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    color = MaterialTheme.ytRowSubtitle,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (song.isRemote && song.isCached) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.OfflinePin,
                        contentDescription = "已缓存",
                        tint = MaterialTheme.ytTextSecondary,
                        modifier = Modifier.size(MpListMetrics.CachedIconSize),
                    )
                }
            }
        }
        // 行尾圆形选择框（截图：空心圆，选中时填充 + 勾选）
        Box(
            modifier = Modifier.size(MpListMetrics.IconButtonSize),
            contentAlignment = Alignment.Center,
        ) {
            SelectionCircle(selected = isSelected)
        }
    }
}

/** 多选圆形勾选框（截图为空心圆；选中态用主题色填充 + 白勾） */
@Composable
private fun SelectionCircle(selected: Boolean) {
    val size = MpListMetrics.SelectCircleSize
    if (selected) {
        Box(
            modifier = Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .border(1.5.dp, MaterialTheme.ytSelectCircle, CircleShape),
        )
    }
}

/**
 * 长按手势包装：在 [PointerEventPass.Initial]（父级先于子级）阶段监听且不消费事件，
 * 因此不会影响 `MpSongListItem` 内部 clickable 的点击与滚动。
 * 指针移动超过 touchSlop 视为列表滑动，不触发长按。
 */
private fun Modifier.onRowLongPress(key: Any?, onLongPress: () -> Unit): Modifier =
    this.pointerInput(key) {
        // 行尾两个 IconButton（加号 / 更多）各 48dp：在按钮区域长按不进入多选
        val trailingButtonsWidth = 96.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > size.width - trailingButtonsWidth) return@awaitEachGesture
            val startPosition = down.position
            val finishedBeforeTimeout = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var finished = false
                while (!finished) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    finished = change == null ||
                        !change.pressed ||
                        (change.position - startPosition).getDistance() > viewConfiguration.touchSlop
                }
                true
            }
            if (finishedBeforeTimeout == null) onLongPress()
        }
    }

/** 排序菜单项（对应 Dart `_buildSortMenuItem`：选中项显示升降序箭头） */
@Composable
private fun SortMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

/**
 * 列表顶部操作条（第二十三轮：按设计截图重做）。
 *
 * 截图版式（高 44dp）：
 * - 左：随机播放图标（shuffle）+ 「642」曲目数
 * - 右：排序图标（点击弹出排序菜单）+ 多选图标（点击进入多选模式）
 *
 * 与改前的差异：曲目数文案由「共 N 首」改为裸数字 `N`；排序与多选入口从顶栏移到本行。
 * 排序菜单内容不变（6 种排序 + 升降序），仍然挂在排序按钮上。
 */
@Composable
private fun SongsHeader(
    count: Int,
    sortType: SongSortType,
    sortDirection: SortDirection,
    sortMenuExpanded: Boolean,
    onShuffle: () -> Unit,
    onSortMenuExpandChange: (Boolean) -> Unit,
    onSelectSort: (SongSortType) -> Unit,
    onEnterSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MpListMetrics.ControlRowHeight)
            .padding(horizontal = MpListMetrics.EdgePadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：随机播放 + 曲目数
        Box(
            modifier = Modifier
                .size(MpListMetrics.IconButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onShuffle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Shuffle,
                contentDescription = "随机播放",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(MpListMetrics.LeadingGap))
        Text(
            text = "$count",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.weight(1f))

        // 右：排序（点开排序菜单，对应 Dart PopupMenuButton<SongSortType>）
        Box {
            Box(
                modifier = Modifier
                    .size(MpListMetrics.IconButtonSize)
                    .clip(CircleShape)
                    .clickable { onSortMenuExpandChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "排序",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { onSortMenuExpandChange(false) },
            ) {
                SortMenuItem(
                    label = "按标题",
                    icon = Icons.Filled.TextFields,
                    selected = sortType == SongSortType.TITLE,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.TITLE) },
                )
                SortMenuItem(
                    label = "按艺术家",
                    icon = Icons.Filled.Person,
                    selected = sortType == SongSortType.ARTIST,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.ARTIST) },
                )
                SortMenuItem(
                    label = "按专辑",
                    icon = Icons.Filled.Album,
                    selected = sortType == SongSortType.ALBUM,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.ALBUM) },
                )
                SortMenuItem(
                    label = "按添加时间",
                    icon = Icons.Filled.AccessTime,
                    selected = sortType == SongSortType.DATE_ADDED,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.DATE_ADDED) },
                )
                SortMenuItem(
                    label = "按播放次数",
                    icon = Icons.Filled.PlayCircle,
                    selected = sortType == SongSortType.PLAY_COUNT,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.PLAY_COUNT) },
                )
                SortMenuItem(
                    label = "按时长",
                    icon = Icons.Filled.Timer,
                    selected = sortType == SongSortType.DURATION,
                    ascending = sortDirection == SortDirection.ASCENDING,
                    onClick = { onSelectSort(SongSortType.DURATION) },
                )
            }
        }
        // 右：多选模式
        Box(
            modifier = Modifier
                .size(MpListMetrics.IconButtonSize)
                .clip(CircleShape)
                .clickable(onClick = onEnterSelection),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Checklist,
                contentDescription = "多选",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** 空状态：引导用户去扫描音乐（文案与 Dart `_emptyView` 完全一致） */
@Composable
private fun EmptyLibraryView(onOpenScan: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    brush = Brush.linearGradient(
                        listOf(BrandPurple.copy(alpha = 0.3f), BrandCyan.copy(alpha = 0.3f)),
                    ),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.LibraryMusic,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = Color.White.copy(alpha = 0.54f),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        Text(text = "曲库为空", fontSize = 18.sp)
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = "去「扫描音乐」页面扫描媒体库或文件夹", color = Color(0xFF8A8A99))
        Spacer(modifier = Modifier.size(16.dp))
        Button(onClick = onOpenScan) {
            Icon(Icons.Filled.ManageSearch, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("去扫描")
        }
    }
}

/** 排序：与 Dart `_sortSongs` 一一对应（字符串忽略大小写，缺省值取 '0'） */
private fun sortSongs(
    songs: List<Song>,
    type: SongSortType,
    direction: SortDirection,
): List<Song> {
    val comparator: Comparator<Song> = when (type) {
        SongSortType.TITLE -> compareBy { it.title.lowercase() }
        SongSortType.ARTIST -> compareBy { (it.artist ?: "").lowercase() }
        SongSortType.ALBUM -> compareBy { (it.album ?: "").lowercase() }
        SongSortType.DATE_ADDED -> compareBy { it.dateAdded ?: 0L }
        SongSortType.PLAY_COUNT -> compareBy { it.playCount }
        SongSortType.DURATION -> compareBy { it.duration ?: 0L }
    }
    val sorted = songs.sortedWith(comparator)
    return if (direction == SortDirection.DESCENDING) sorted.reversed() else sorted
}

/** 排序菜单点击：同类型切换升降序，不同类型重置为升序（对应 Dart onSelected） */
private fun toggleSort(
    currentType: SongSortType,
    currentDirection: SortDirection,
    clicked: SongSortType,
): Pair<SongSortType, SortDirection> = if (currentType == clicked) {
    currentType to if (currentDirection == SortDirection.ASCENDING) {
        SortDirection.DESCENDING
    } else {
        SortDirection.ASCENDING
    }
} else {
    clicked to SortDirection.ASCENDING
}

/**
 * "添加到歌单"选择器（对应 Dart `_showAddToPlaylistDialog`）：
 * 列出全部歌单，支持快捷新建歌单；确认后逐首 [DatabaseHelper.addSongToPlaylist]。
 */
@Composable
private fun AddToPlaylistDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        playlists = DatabaseHelper.queryPlaylists()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到歌单") },
        text = {
            Column(modifier = Modifier.heightIn(max = 320.dp)) {
                // 新建歌单入口
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showCreateDialog = true }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = BrandCyan)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("新建歌单", color = BrandCyan)
                }
                if (playlists.isEmpty()) {
                    Text(
                        text = "暂无歌单",
                        color = MaterialTheme.ytTextSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn {
                        items(playlists, key = { it.id ?: it.name }) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val playlistId = playlist.id
                                        if (playlistId == null) {
                                            onDismiss()
                                            return@clickable
                                        }
                                        scope.launch {
                                            var added = 0
                                            songs.forEach { song ->
                                                song.id?.let { songId ->
                                                    if (DatabaseHelper.addSongToPlaylist(playlistId, songId)) added++
                                                }
                                            }
                                            onDismiss()
                                            onMessage(
                                                when {
                                                    songs.isEmpty() -> "没有可添加的歌曲"
                                                    added == songs.size -> "已添加到歌单「${playlist.name}」"
                                                    added == 0 -> "歌曲已在歌单「${playlist.name}」中"
                                                    else -> "已添加 $added 首到歌单「${playlist.name}」"
                                                },
                                            )
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.QueueMusic, contentDescription = null)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(playlist.name)
                                    Text(
                                        text = "${playlist.songCount} 首",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.ytTextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                scope.launch {
                    val playlistId = DatabaseHelper.createPlaylist(name)
                    if (playlistId == null) {
                        // Dart：'新建歌单失败（可能重名）'
                        onDismiss()
                        onMessage("新建歌单失败（可能重名）")
                        return@launch
                    }
                    songs.forEach { song ->
                        song.id?.let { songId -> DatabaseHelper.addSongToPlaylist(playlistId, songId) }
                    }
                    onDismiss()
                    onMessage("已添加到歌单「$name」")
                }
            },
        )
    }
}

/** 新建歌单名称输入框（对应 Dart `_promptPlaylistName`） */
@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("请输入歌单名", color = MaterialTheme.ytTextSecondary) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
