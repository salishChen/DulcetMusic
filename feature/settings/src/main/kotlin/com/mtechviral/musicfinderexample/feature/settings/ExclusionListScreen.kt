/*
 * 「排除列表」设置页（第二十六轮需求 3）。
 *
 * 需求：
 *  - 删除一首在线音乐后，其「歌曲名 + 歌手」自动进入本列表；
 *  - 下次扫描时自动排除列表中的音乐（见 ExclusionFilter / MetadataScanService）；
 *  - 列表内可**取消排除**，取消后下次导入即可把音乐重新加入曲库；
 *  - 还可以排除**歌手**：排除后拉取音乐时跳过该歌手的全部歌曲。
 *
 * 页面结构：
 *  - 顶栏（返回 + 标题）；
 *  - 两个分区：已排除的歌曲 / 已排除的歌手，各自可逐条取消；
 *  - 空状态提示；
 *  - 底部「全部清空」（二次确认）。
 */
package com.mtechviral.musicfinderexample.feature.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.common.ExclusionEntry
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.common.ExclusionType
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary

/**
 * 排除列表页。
 *
 * @param onBack 返回上一页（顶栏返回按钮）
 */
@Composable
fun ExclusionListScreen(onBack: () -> Unit) {
    // 直接观察单例，取消排除后本页即时刷新
    val entries by ExclusionList.entries.collectAsStateWithLifecycle()
    val songs = entries.filter { it.type == ExclusionType.SONG }
    val artists = entries.filter { it.type == ExclusionType.ARTIST }

    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                    )
                }
                Text(
                    text = "排除列表",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Text(
                text = "删除在线音乐时会自动加入此列表；排除歌手会一并移除其曲库中的音乐与专辑。" +
                    "扫描时自动跳过，取消排除后重新扫描即可再次导入。",
                fontSize = 12.sp,
                color = MaterialTheme.ytTextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = MaterialTheme.ytTextSecondary,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "排除列表为空",
                            color = MaterialTheme.ytTextSecondary,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (songs.isNotEmpty()) {
                        item(key = "header_songs") { SectionHeader("已排除的歌曲（${songs.size}）") }
                        items(songs, key = { it.key }) { entry ->
                            ExclusionRow(entry = entry, onRestore = { ExclusionList.remove(entry) })
                        }
                    }
                    if (artists.isNotEmpty()) {
                        item(key = "header_artists") { SectionHeader("已排除的歌手（${artists.size}）") }
                        items(artists, key = { it.key }) { entry ->
                            ExclusionRow(entry = entry, onRestore = { ExclusionList.remove(entry) })
                        }
                    }
                }

                HorizontalDivider()
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text("全部清空")
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("全部清空") },
            text = { Text("确定要清空排除列表吗？清空后这些音乐在下次扫描时会被重新导入。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    ExclusionList.clear()
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 分区标题 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.ytTextSecondary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * 单条排除项：图标 + 名称 + 右侧「取消排除」。
 *
 * 点击右侧按钮即取消该条排除（需求：排除列表内可取消排除）。
 */
@Composable
private fun ExclusionRow(
    entry: ExclusionEntry,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (entry.type == ExclusionType.ARTIST) {
                    Icons.Filled.Person
                } else {
                    Icons.Filled.Block
                },
                contentDescription = null,
                tint = MaterialTheme.ytTextSecondary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayText,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.displayTypeText,
                    fontSize = 12.sp,
                    color = MaterialTheme.ytTextSecondary,
                )
            }
        }

        TextButton(onClick = onRestore) {
            Icon(
                imageVector = Icons.Filled.Restore,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("取消排除", fontSize = 13.sp)
        }
    }
}
