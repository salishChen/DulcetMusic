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
 *     整列播放仍可通过点击任意歌曲触达；随机播放可由播放页底部的播放模式按钮进入随机模式，
 *     届时列表本身会被重排。）
 *
 * 屏幕由 `:app` 的 HomeShell 直接按索引调用（不经过 NavHost），因此跳转一律用
 * "默认参数 + 回调"，默认空实现时页面可独立渲染、不崩溃。
 */
package com.mtechviral.musicfinderexample.feature.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionSheet
import com.mtechviral.musicfinderexample.core.designsystem.component.EntityActionTarget
import com.mtechviral.musicfinderexample.core.designsystem.component.MpSongListItem
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
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

/** 排序方式（对应 Dart `SongSortType`） */
private enum class SongSortType { TITLE, ARTIST, ALBUM, DATE_ADDED, PLAY_COUNT, DURATION }

/** 排序方向（对应 Dart `SortDirection`） */
private enum class SortDirection { ASCENDING, DESCENDING }

/**
 * 歌曲一级页面：列出数据库中的所有音乐（对应 Dart `SongsPage`）。
 *
 * @param onOpenNowPlaying 打开播放页（等价 Dart 播放页滑出）
 * @param onOpenSearch 打开搜索页（对应 Dart showSearch，路由 AppRoutes.SEARCH）
 * @param onOpenScan 空状态"去扫描"跳转扫描页（对应 Dart `selectPage(5)`，路由 AppRoutes.SCAN）
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

    // ---- 多选模式（对应 Dart _selectionMode / _selectedPaths） ----
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
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

    /** 选中的歌曲（按当前排序顺序；排序/删除后自动与列表求交） */
    val selectedSongs: List<Song> = sortedSongs.filter { selectedPaths.contains(it.path) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                // ---- 多选顶栏（对应 Dart `_buildSelectionAppBar`）----
                SelectionAppBar(
                    selectedCount = selectedPaths.size,
                    allSelected = sortedSongs.isNotEmpty() && selectedPaths.size == sortedSongs.size,
                    onClose = exitSelectionMode,
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
                    onAddToQueue = {
                        // Dart `_addToQueue`：逐首 PlaylistRepository.addSong
                        // 第十六轮：addSong 不再判重（允许重复入列），因此选中即全部加入
                        selectedSongs.forEach { song -> PlaylistRepository.addSong(song) }
                        scope.launch {
                            snackbarHostState.showSnackbar("已添加 ${selectedSongs.size} 首歌曲到播放队列")
                        }
                        exitSelectionMode()
                    },
                    onDeleteSelected = { showDeleteConfirm = true },
                )
            } else {
                PrimaryAppBar(
                    title = "歌曲",
                    onMenuClick = openSidebar,
                ) {
                    // 搜索入口（Dart：showSearch(delegate: MusicSearchDelegate())）
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    // 排序入口（Dart：PopupMenuButton<SongSortType>）
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            SortMenuItem(
                                label = "按标题",
                                icon = Icons.Filled.TextFields,
                                selected = sortType == SongSortType.TITLE,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.TITLE)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = "按艺术家",
                                icon = Icons.Filled.Person,
                                selected = sortType == SongSortType.ARTIST,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.ARTIST)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = "按专辑",
                                icon = Icons.Filled.Album,
                                selected = sortType == SongSortType.ALBUM,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.ALBUM)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = "按添加时间",
                                icon = Icons.Filled.AccessTime,
                                selected = sortType == SongSortType.DATE_ADDED,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.DATE_ADDED)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = "按播放次数",
                                icon = Icons.Filled.PlayCircle,
                                selected = sortType == SongSortType.PLAY_COUNT,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.PLAY_COUNT)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                            SortMenuItem(
                                label = "按时长",
                                icon = Icons.Filled.Timer,
                                selected = sortType == SongSortType.DURATION,
                                ascending = sortDirection == SortDirection.ASCENDING,
                                onClick = {
                                    val next = toggleSort(sortType, sortDirection, SongSortType.DURATION)
                                    sortType = next.first
                                    sortDirection = next.second
                                    sortMenuExpanded = false
                                },
                            )
                        }
                    }
                    // 进入多选（Dart 普通顶栏的 checklist 按钮；空库时无入口）
                    if (sortedSongs.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selectionMode = true
                                selectedPaths = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.Checklist, contentDescription = "多选")
                        }
                    }
                }
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
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // 多选态隐藏顶部信息条（对应 Dart 多选列表本身没有该操作条）
                    if (!selectionMode) {
                        item(key = "songs_header") {
                            SongsHeader(count = sortedSongs.size)
                        }
                    }
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
            )
        }

        addToPlaylistSongs?.let { songs ->
            AddToPlaylistDialog(
                songs = songs,
                onDismiss = { addToPlaylistSongs = null },
                onMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
            )
        }

        // 批量删除二次确认（对应 Dart `_deleteSelected` 的 AlertDialog）
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确定要删除选中的 ${selectedPaths.size} 首歌曲吗？") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                val targets = selectedSongs
                                // 删库 + 同步移出播放列表（若删的是正在播放的歌，会自动接续下一首）
                                LibrarySongDeleter.delete(targets)
                                // Dart：删除后 queryAllSongs + songData.updateSongs(refreshed)
                                MusicLibrary.updateSongs(DatabaseHelper.queryAllSongs())
                                snackbarHostState.showSnackbar("已删除 ${targets.size} 首歌曲")
                            }
                            exitSelectionMode()
                        },
                    ) {
                        Text("删除", color = DeleteRed)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

/**
 * 多选顶栏（对应 Dart `_buildSelectionAppBar`）：
 * 左侧"关闭"、标题"已选择 N 首"、右侧 全选/取消全选 + 添加到播放队列 + 删除选中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(
    selectedCount: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    onAddToQueue: () -> Unit,
    onDeleteSelected: () -> Unit,
) {
    TopAppBar(
        title = { Text(text = "已选择 $selectedCount 首") },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭")
            }
        },
        actions = {
            // 全选/取消全选（Dart：Icons.select_all，tooltip '全选'）
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    imageVector = Icons.Filled.SelectAll,
                    contentDescription = if (allSelected) "取消全选" else "全选",
                )
            }
            // 添加到播放队列（Dart：Icons.playlist_add）
            IconButton(onClick = onAddToQueue) {
                Icon(Icons.Filled.PlaylistAdd, contentDescription = "添加到播放队列")
            }
            // 删除选中（Dart：Icons.delete）
            IconButton(onClick = onDeleteSelected) {
                Icon(Icons.Filled.Delete, contentDescription = "删除选中")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/**
 * 多选行（对应 Dart `_buildSelectableSongItem`）：
 * 左侧 Checkbox + 40dp 封面（圆角 8），歌名 + "艺术家 · 专辑"，点击整行切换选中。
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
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggle() })
        SongArtwork(
            song = song,
            modifier = Modifier.size(40.dp),
            cornerRadius = 8.dp,
            maxSizePx = with(LocalDensity.current) { 40.dp.roundToPx() },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.displayArtist} · ${song.displayAlbum}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary,
            )
        }
    }
}

/**
 * 长按手势包装：在 [PointerEventPass.Initial]（父级先于子级）阶段监听且不消费事件，
 * 因此不会影响 `MpSongListItem` 内部 clickable 的点击与滚动；
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
 * 列表顶部信息条：曲目数（规范第 9 节）。
 *
 * 第十八轮调整：按需求去掉「播放全部」按钮。
 * 第十九轮调整：按需求再去掉「随机播放」按钮 —— 该条只剩曲目数文本，
 * 因此不再需要回调参数、右侧留白与按钮。整列/随机播放仍可分别通过
 * 「点击任意歌曲」与播放页底部的播放模式按钮（切到随机模式会重排列表）触达。
 */
@Composable
private fun SongsHeader(count: Int) {
    Text(
        text = "共 $count 首",
        fontSize = 13.sp,
        color = MaterialTheme.ytTextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
    )
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

/** 排序：与 Dart `_sortSongs` 一一对应（字符串忽略大小写，缺省值取空/0） */
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
