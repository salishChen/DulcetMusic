/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/stats_page.dart
 *
 * 行为对照（原 Dart -> 本文件）：
 *  - initState 中 dbHelper.queryTopPlayed(50) / queryRecentlyPlayed(50) -> LaunchedEffect(library) 重查
 *    （规范 §0：聚合类数据在曲库变化时重查；Dart 端为页面 initState 单次查询）
 *  - _loading + CircularProgressIndicator            -> loading 状态
 *  - _buildOverviewCard（总播放次数/已听歌曲/最近播放） -> OverviewCard（文案逐字一致）
 *  - TabBar 最常播放 / 最近播放                       -> TabRow + Tab
 *  - _buildSongTile（排名序号、"N次"徽标、最后播放相对时间）
 *                                                    -> StatSongRow + formatLastPlayed
 *  - 点击歌曲 playlistData.setSongs + playSong        -> PlaylistRepository.setSongs + PlayerController.playSong
 *  - 空状态 '暂无播放记录'                            -> EmptyStatsView
 *
 * 说明：Dart 的统计列表行是自绘 ListTile（排名 / "N次" / 相对时间，无"更多"按钮、无封面），
 * 因此这里按原文件实现 [StatSongRow]，未使用 designsystem 的 MpSongListItem，
 * 统计页也没有 EntityActionSheet 入口（与 Dart 一致）。
 * 页面可被 `:app` 的 HomeShell 直接按索引调用（无参数、无跳转）。
 */
package com.mtechviral.musicfinderexample.feature.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.designsystem.component.PrimaryAppBar
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import com.mtechviral.musicfinderexample.feature.home.LocalOpenSidebar
import java.util.Calendar
import kotlinx.coroutines.launch

/** 空状态占位色（取自 Dart `Colors.grey[400]` / `Colors.grey[500]`） */
private val EmptyIconGrey = Color(0xFFBDBDBD)
private val EmptyTextGrey = Color(0xFF9E9E9E)

/**
 * 统计页面：播放次数排行 + 最近播放（对应 Dart `StatsPage`）。
 */
@Composable
fun StatsScreen() {
    val scope = rememberCoroutineScope()
    // CompositionLocal 只能在可组合函数体内读取，不能写进普通回调 lambda
    val openSidebar = LocalOpenSidebar.current

    val library by MusicLibrary.songs.collectAsStateWithLifecycle()

    var loading by remember { mutableStateOf(true) }
    var topPlayed by remember { mutableStateOf<List<Song>>(emptyList()) }
    var recentlyPlayed by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(0) }

    /** 查询统计数据（对应 Dart `_loadData`：queryTopPlayed(50) / queryRecentlyPlayed(50)） */
    suspend fun loadData() {
        topPlayed = DatabaseHelper.queryTopPlayed(50)
        recentlyPlayed = DatabaseHelper.queryRecentlyPlayed(50)
        loading = false
    }

    // 曲库变化时重查（规范 §0 聚合类数据统一模式；等价 Dart initState + songData 刷新）
    LaunchedEffect(library) { loadData() }

    Scaffold(
        topBar = {
            PrimaryAppBar(title = "统计", onMenuClick = openSidebar) {
                // Dart 使用 RefreshIndicator 下拉刷新；原生端以顶栏刷新按钮等价触发重查
                IconButton(onClick = { scope.launch { loadData() } }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
        },
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
                    OverviewCard(topPlayed = topPlayed, recentlyCount = recentlyPlayed.size)

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("最常播放") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("最近播放") },
                        )
                    }

                    val songs = if (selectedTab == 0) topPlayed else recentlyPlayed
                    // Dart：queue = songData?.songs ?? 当前统计列表（点击整列播放）
                    val queue = if (library.isNotEmpty()) library else songs

                    if (songs.isEmpty()) {
                        EmptyStatsView()
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(songs, key = { _, song -> song.path }) { index, song ->
                                StatSongRow(
                                    song = song,
                                    // Dart：仅"最常播放"显示排名与"N次"徽标
                                    rank = if (selectedTab == 0) index + 1 else null,
                                    onClick = {
                                        PlaylistRepository.setSongs(queue)
                                        scope.launch { PlayerController.playSong(song) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 统计列表行（对应 Dart `_buildSongTile`）：
 * 排名序号（前三名主题色）+ 歌名 + "艺术家 · 专辑"，行尾为"N次"徽标（排行）
 * 或最后播放相对时间（最近播放）。
 */
@Composable
private fun StatSongRow(song: Song, rank: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$rank",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W600,
                    color = if (rank <= 3) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.ytTextSecondary
                    },
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${song.displayArtist} · ${song.displayAlbum}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary,
            )
        }
        if (rank != null) {
            // "N次" 徽标（Dart：primary 10% 底 + 12dp 圆角 + primary 文字）
            Box(
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "${song.playCount}次",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.W500,
                )
            }
        } else {
            Text(
                text = formatLastPlayed(song.lastPlayed),
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary,
            )
        }
    }
}

/** 概览卡片（对应 Dart `_buildOverviewCard` + `_statItem`） */
@Composable
private fun OverviewCard(topPlayed: List<Song>, recentlyCount: Int) {
    val totalPlays = topPlayed.sumOf { it.playCount }
    val uniquePlayed = topPlayed.size
    val primary = MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(primary.copy(alpha = 0.15f), primary.copy(alpha = 0.05f)),
                ),
                shape = RoundedCornerShape(16.dp),
            )
            .border(1.dp, primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatItem(label = "总播放次数", value = "$totalPlays", modifier = Modifier.weight(1f))
        StatDivider()
        StatItem(label = "已听歌曲", value = "$uniquePlayed", modifier = Modifier.weight(1f))
        StatDivider()
        StatItem(label = "最近播放", value = "$recentlyCount", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.W700,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = label, fontSize = 12.sp, color = MaterialTheme.ytTextSecondary)
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** 空状态（对应 Dart `_buildSongList` 中的空列表分支） */
@Composable
private fun EmptyStatsView() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = EmptyIconGrey,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "暂无播放记录", color = EmptyTextGrey, fontSize = 16.sp)
    }
}

/** 最后播放相对时间（对应 Dart `_formatLastPlayed`） */
private fun formatLastPlayed(timestamp: Long?): String {
    if (timestamp == null) return ""
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60_000
    if (minutes < 1) return "刚刚"
    val hours = diff / 3_600_000
    if (hours < 1) return "${minutes}分钟前"
    val days = diff / 86_400_000
    if (days < 1) return "${hours}小时前"
    if (days < 7) return "${days}天前"
    val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    return "${calendar.get(Calendar.MONTH) + 1}/${calendar.get(Calendar.DAY_OF_MONTH)}"
}
