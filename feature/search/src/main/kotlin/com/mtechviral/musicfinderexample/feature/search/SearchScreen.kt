/*
 * 对应 Dart 原文件：`.flutter_reference/lib/widgets/music_search_delegate.dart`
 * （旧版为 `SearchDelegate`：搜索框 + "歌曲/专辑/艺术家"三个分组 Tab + 空状态提示）
 *
 * 逐条对照 Dart：
 *   - 搜索框提示文案：'搜索歌曲、歌手、专辑...'；左侧返回箭头（close）；有输入时右侧清空按钮
 *   - 输入为空：居中显示 search 图标(48) + '输入关键词搜索' + '支持搜索歌曲名、歌手名、专辑名'
 *   - 输入不足 2 个字符：'请输入至少2个字符'
 *   - 无任何结果：search_off 图标(48) + '未找到匹配结果'
 *   - 分组 Tab 文案：'歌曲 (n)' / '专辑 (n)' / '艺术家 (n)'
 *   - 分组空态：'未找到歌曲' / '未找到专辑' / '未找到艺术家'
 *   - 歌曲行：封面 50×50（圆角 12）+ 歌名 + '艺术家 · 专辑'
 *   - 专辑网格：2 列、封面圆角 12、'艺术家 · N首'
 *   - 艺术家行：圆形头像 48 + 名字 + 'N首歌 · M张专辑'
 *   - 匹配规则（Dart 三条 SQL 的等价实现，见 [SearchEngine]）：
 *     歌曲 title/artist/album 任一 LIKE '%q%'，按 title 忽略大小写升序，上限 100；
 *     专辑 album LIKE '%q%' 分组，按 album 升序，上限 50；
 *     艺术家 artist LIKE '%q%' 分组，按 artist 升序，上限 50。
 *   - 点击歌曲：等价 Dart 的 `playlistData.setSongs(songs)` + 起播 + 打开播放页。
 *
 * 与 Dart 的差异（详见汇报）：
 *   1) 搜索数据源为 `MusicLibrary.songs`（等价旧版 songData.songs）而非每次查 SQLite；
 *   2) 命中的关键词在结果文本中高亮（Dart 无高亮）；
 *   3) 当前播放行歌名用品牌色高亮（规范第 9 节"当前播放行高亮"）。
 */
package com.mtechviral.musicfinderexample.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.component.MpCircleAvatar
import com.mtechviral.musicfinderexample.core.designsystem.component.MpListMetrics
import com.mtechviral.musicfinderexample.core.designsystem.component.SongRowTextColumn
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch

/** Dart `Colors.grey[400]` */
private val HintIconGrey = Color(0xFFBDBDBD)

/** Dart `Colors.grey[500]` */
private val HintTextGrey = Color(0xFF9E9E9E)

/** 全库搜索页（路由 [com.mtechviral.musicfinderexample.core.common.AppRoutes.SEARCH]） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onBack: () -> Unit) {
    // 搜索范围 = 全量曲库（等价旧版 songData.songs）
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val currentSong by PlayerController.currentSong.collectAsStateWithLifecycle()
    val navigator = LocalSearchNavigator.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // 页面首次进入时刷新曲库（规范第 0 节统一模式）
    LaunchedEffect(Unit) { MusicLibrary.reload() }

    val results = remember(library, query) {
        if (query.length < 2) SearchResults.Empty else SearchEngine.search(library, query)
    }

    Scaffold(
        topBar = {
            SearchTopBar(
                query = query,
                onQueryChange = {
                    query = it
                    // Dart 每次重建 DefaultTabController -> 回到"歌曲"分组
                    selectedTab = 0
                },
                onBack = onBack,
                onClear = { query = "" },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                // 未输入：输入提示（Dart _buildRecentSearches）
                query.isEmpty() -> SearchHint(
                    icon = Icons.Filled.Search,
                    title = "输入关键词搜索",
                    subtitle = "支持搜索歌曲名、歌手名、专辑名",
                )

                // 至少 2 个字符
                query.length < 2 -> CenterMessage("请输入至少2个字符")

                // 无任何结果
                results.isEmpty -> SearchHint(
                    icon = Icons.Filled.SearchOff,
                    title = "未找到匹配结果",
                    subtitle = null,
                )

                else -> {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("歌曲 (${results.songs.size})") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("专辑 (${results.albums.size})") },
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("艺术家 (${results.artists.size})") },
                        )
                    }
                    when (selectedTab) {
                        0 -> SongsTab(
                            songs = results.songs,
                            query = query,
                            currentPath = currentSong?.path,
                            onSongClick = { song ->
                                // 规范第 9 节：点击歌曲 -> setSongs + 播放该曲
                                PlaylistRepository.setSongs(results.songs)
                                MusicLibrary.setCurrentIndex(
                                    results.songs.indexOfFirst { it.path == song.path },
                                )
                                scope.launch { PlayerController.playSong(song) }
                                // Dart: openNowPlayingPage(song)
                                NowPlayingUiState.open()
                            },
                        )

                        1 -> AlbumsTab(
                            albums = results.albums,
                            query = query,
                            onAlbumClick = { navigator.openAlbum(it) },
                        )

                        else -> ArtistsTab(
                            artists = results.artists,
                            query = query,
                            onArtistClick = { navigator.openArtist(it) },
                        )
                    }
                }
            }
        }
    }
}

/** 顶部搜索栏（Dart `SearchDelegate` 的 appBar：返回箭头 + 输入框 + 清空按钮） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        title = {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "搜索歌曲、歌手、专辑...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = "清除")
                }
            }
        },
    )
}

/** 分组：歌曲（Dart `_buildSongsList`） */
@Composable
private fun SongsTab(
    songs: List<Song>,
    query: String,
    currentPath: String?,
    onSongClick: (Song) -> Unit,
) {
    if (songs.isEmpty()) {
        CenterMessage("未找到歌曲")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = songs, key = { it.path }) { song ->
            SongResultRow(
                song = song,
                query = query,
                isCurrent = song.path == currentPath,
                onClick = { onSongClick(song) },
            )
        }
    }
}

/** 分组：专辑（Dart `_buildAlbumsList`，2 列网格） */
@Composable
private fun AlbumsTab(
    albums: List<Album>,
    query: String,
    onAlbumClick: (Album) -> Unit,
) {
    if (albums.isEmpty()) {
        CenterMessage("未找到专辑")
        return
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.ytTextSecondary
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 优化建议 10：同名专辑按「专辑名 + 专辑艺术家」区分，key 用复合键
        items(items = albums, key = { "${it.title}|${it.artist}" }) { album ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAlbumClick(album) },
            ) {
                ArtworkImage(
                    path = album.coverSongPath,
                    cachedArtworkPath = album.coverArtworkPath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    cornerRadius = 12.dp,
                )
                Spacer(Modifier.height(8.dp))
                HighlightedText(
                    text = album.title,
                    query = query,
                    fontSize = 14.sp,
                    color = onSurface,
                    fontWeight = FontWeight.W500,
                )
                HighlightedText(
                    text = "${album.artist ?: "未知艺术家"} · ${album.songCount}首",
                    query = query,
                    fontSize = 11.sp,
                    color = secondary,
                )
            }
        }
    }
}

/** 分组：艺术家（Dart `_buildArtistsList`） */
@Composable
private fun ArtistsTab(
    artists: List<Artist>,
    query: String,
    onArtistClick: (String) -> Unit,
) {
    if (artists.isEmpty()) {
        CenterMessage("未找到艺术家")
        return
    }
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.ytTextSecondary
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(items = artists, key = { it.name }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(artist.name) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dart：有缓存封面显示圆形封面图，否则显示 person 图标
                if (artist.coverArtworkPath != null) {
                    ArtworkImage(
                        path = artist.coverArtworkPath,
                        cachedArtworkPath = artist.coverArtworkPath,
                        coverArtId = artist.coverArtId,
                        modifier = Modifier.size(48.dp),
                        cornerRadius = 24.dp,
                    )
                } else {
                    MpCircleAvatar(text = artist.name, size = 48.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    HighlightedText(
                        text = artist.name,
                        query = query,
                        fontSize = 15.sp,
                        color = onSurface,
                        fontWeight = FontWeight.W500,
                    )
                    HighlightedText(
                        text = "${artist.songCount}首歌 · ${artist.albumCount}张专辑",
                        query = query,
                        fontSize = 12.sp,
                        color = secondary,
                    )
                }
            }
        }
    }
}

/** 歌曲行（Dart 歌曲分组 ListTile） */
@Composable
private fun SongResultRow(
    song: Song,
    query: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.ytTextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = MpListMetrics.RowStartPadding,
                end = MpListMetrics.RowEndPadding,
                top = MpListMetrics.RowVerticalPadding,
                bottom = MpListMetrics.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            path = song.path,
            cachedArtworkPath = song.cachedArtworkPath,
            songId = song.id,
            coverArtId = song.coverArtId,
            modifier = Modifier.size(MpListMetrics.ArtworkSize),
            cornerRadius = MpListMetrics.ArtworkCorner,
        )
        Spacer(Modifier.width(MpListMetrics.ArtworkTextGap))
        // 与 MpSongListItem 同一套版式：标题 + 音质徽章 +「歌手 - 专辑」+ 缓存灰色图标
        SongRowTextColumn(
            song = song,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 命中关键词高亮（首个子串；Dart 无高亮，此处为原生端补充） */
@Composable
private fun HighlightedText(
    text: String,
    query: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    maxLines: Int = 1,
) {
    val highlightColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, query, color, fontSize, highlightColor, fontWeight, maxLines) {
        buildAnnotatedString {
            val index = if (query.isEmpty()) -1 else text.lowercase().indexOf(query.lowercase())
            if (index < 0) {
                append(text)
            } else {
                val end = (index + query.length).coerceAtMost(text.length)
                append(text.substring(0, index))
                withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold)) {
                    append(text.substring(index, end))
                }
                append(text.substring(end))
            }
        }
    }
    Text(
        text = annotated,
        fontSize = fontSize,
        color = color,
        fontWeight = fontWeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 居中提示（Dart 空态） */
@Composable
private fun CenterMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = HintTextGrey)
    }
}

/** 未输入 / 无结果提示（Dart `_buildRecentSearches` / 无结果空态） */
@Composable
private fun SearchHint(icon: ImageVector, title: String, subtitle: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = HintIconGrey,
        )
        Spacer(Modifier.height(16.dp))
        Text(text = title, color = HintTextGrey, fontSize = 16.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(text = subtitle, color = HintIconGrey, fontSize = 13.sp)
        }
    }
}

/** 搜索结果容器（Dart `SearchResults`） */
internal data class SearchResults(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
) {
    val isEmpty: Boolean get() = songs.isEmpty() && albums.isEmpty() && artists.isEmpty()

    companion object {
        val Empty = SearchResults(emptyList(), emptyList(), emptyList())
    }
}

/**
 * 全库搜索（等价 Dart `MusicSearchDelegate._performSearch` 的三条 SQL：
 * `LOWER(title|artist|album) LIKE '%q%'` / `GROUP BY album` / `GROUP BY artist`，
 * 排序 `COLLATE NOCASE ASC`，上限分别为 100 / 50 / 50）。
 */
internal object SearchEngine {

    private const val SONG_LIMIT = 100
    private const val GROUP_LIMIT = 50

    fun search(all: List<Song>, rawQuery: String): SearchResults {
        val q = rawQuery.lowercase()
        return SearchResults(
            songs = searchSongs(all, q),
            albums = searchAlbums(all, q),
            artists = searchArtists(all, q),
        )
    }

    /** 歌曲：title / artist / album 任一命中；按 title 忽略大小写升序；上限 100 */
    private fun searchSongs(all: List<Song>, q: String): List<Song> = all
        .filter { song ->
            song.title.lowercase().contains(q) ||
                song.artist?.lowercase()?.contains(q) == true ||
                song.album?.lowercase()?.contains(q) == true
        }
        .sortedBy { it.title.lowercase() }
        .take(SONG_LIMIT)

    /**
     * 专辑：GROUP BY 专辑名 + 专辑艺术家（优化建议 10：不同艺术家的同名专辑不合并）；
     * 封面取含封面歌曲；按专辑名升序；上限 50。
     */
    private fun searchAlbums(all: List<Song>, q: String): List<Album> = all
        .filter { song ->
            val album = song.album
            album != null && album.isNotEmpty() && album.lowercase().contains(q)
        }
        .groupBy { "${it.album.orEmpty()}|${it.albumArtist ?: it.artist.orEmpty()}" }
        .map { (_, songs) ->
            val first = songs.first()
            val withArtwork = songs.filter { it.hasArtwork }
            Album(
                title = first.album.orEmpty(),
                // 与数据库聚合口径一致：COALESCE(albumArtist, artist)
                artist = (first.albumArtist ?: first.artist)?.takeIf { it.isNotEmpty() },
                coverSongId = withArtwork.mapNotNull { it.id }.maxOrNull(),
                coverSongPath = withArtwork.mapNotNull { it.path }.maxOrNull(),
                coverArtworkPath = songs.mapNotNull { it.cachedArtworkPath }.maxOrNull(),
                songCount = songs.size,
            )
        }
        .sortedBy { it.title.lowercase() }
        .take(GROUP_LIMIT)

    /** 艺术家：GROUP BY artist（排除空艺术家名）；专辑数取 COUNT(DISTINCT album)；上限 50 */
    private fun searchArtists(all: List<Song>, q: String): List<Artist> = all
        .filter { song ->
            val artist = song.artist
            artist != null && artist.isNotEmpty() && artist.lowercase().contains(q)
        }
        .groupBy { it.artist.orEmpty() }
        .map { (name, songs) ->
            val withArtwork = songs.filter { it.hasArtwork }
            Artist(
                name = name,
                songCount = songs.size,
                albumCount = songs.mapNotNull { it.album }.distinct().size,
                coverSongPath = withArtwork.mapNotNull { it.path }.maxOrNull(),
                coverArtworkPath = songs.mapNotNull { it.cachedArtworkPath }.maxOrNull(),
            )
        }
        .sortedBy { it.name.lowercase() }
        .take(GROUP_LIMIT)
}
