/*
 * 对应 Dart 原文件：lib/widgets/mp_song_bottom_sheet.dart 中的
 *                  `_showAddToPlaylistDialog` / `_promptPlaylistName`（"添加到歌单"选择器）
 *
 * 说明：规范第 8 节未提供跨模块的歌单选择器组件，且 feature 之间不允许互相依赖，
 * 因此本模块（artists）内自带一份最小实现（文案、空状态与 Dart 一致）。
 */
package com.mtechviral.musicfinderexample.feature.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.launch

/**
 * "添加到歌单"底部选择器：列出全部歌单 + 新建歌单入口。
 *
 * @param songs 待加入的歌曲（EntityActionSheet 的 onAddToPlaylist 回调传入）
 * @param onMessage 结果提示（由调用方用 SnackbarHost 展示）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistSheet(
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            Text(
                text = "添加到歌单",
                modifier = Modifier.padding(16.dp),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.W600),
            )

            // 新建歌单入口（Dart：图标与文字均为品牌青）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showCreateDialog = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null, tint = BrandCyan)
                Spacer(Modifier.width(16.dp))
                Text(text = "新建歌单", style = TextStyle(color = BrandCyan))
            }

            if (playlists.isEmpty()) {
                Text(
                    text = "暂无歌单",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(playlists, key = { it.id ?: it.name }) { playlist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val playlistId = playlist.id ?: return@clickable
                                    scope.launch {
                                        var added = 0
                                        for (song in songs) {
                                            val songId = song.id ?: continue
                                            if (DatabaseHelper.addSongToPlaylist(playlistId, songId)) added++
                                        }
                                        onMessage(
                                            if (added > 0) "已添加到歌单「${playlist.name}」"
                                            else "歌曲已在歌单「${playlist.name}」中",
                                        )
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(text = playlist.name)
                                Text(
                                    text = "${playlist.songCount} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                scope.launch {
                    val playlistId = DatabaseHelper.createPlaylist(name)
                    if (playlistId == null) {
                        // 重名等原因创建失败（Dart 文案）
                        onMessage("新建歌单失败（可能重名）")
                    } else {
                        for (song in songs) {
                            val songId = song.id ?: continue
                            DatabaseHelper.addSongToPlaylist(playlistId, songId)
                        }
                        onMessage("已添加到歌单「$name」")
                        onDismiss()
                    }
                }
            },
        )
    }
}

/** 输入歌单名对话框（Dart `_promptPlaylistName`） */
@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建歌单") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("请输入歌单名") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
