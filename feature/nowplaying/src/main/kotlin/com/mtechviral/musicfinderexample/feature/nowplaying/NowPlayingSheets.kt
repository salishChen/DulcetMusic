/*
 * 对应 Dart 原文件：.flutter_reference/lib/widgets/mp_song_bottom_sheet.dart
 * 中的「添加到歌单」弹窗（_showAddToPlaylistDialog，第 249 ~ 340 行）与
 * 「新建歌单」弹窗（_showCreatePlaylistDialog，第 350 行起）：
 *  - 列表顶部「新建歌单」（Icons.add，品牌青）；
 *  - 歌单项（Icons.queue_music + 「N 首」），点击即把歌曲加入该歌单；
 *  - 无歌单时显示「暂无歌单」；
 *  - 结果提示沿用 Dart 文案：已添加到歌单「x」/ 歌曲已在歌单「x」中 /
 *    新建歌单失败（可能重名）。
 *
 * 说明：Dart 用 SnackBar 提示，原生覆盖层没有 Scaffold，这里用 Toast 等价替代。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.model.Playlist
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.launch

/**
 * 「添加到歌单」选择器。
 *
 * @param songs 要加入歌单的歌曲（来自 EntityActionSheet.onAddToPlaylist 或本页「更多」菜单）
 * @param onDismiss 关闭并回调（父级清空待添加状态）
 */
@Composable
internal fun NowPlayingAddToPlaylistDialog(
    songs: List<Song>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // 打开时加载全部歌单（Dart: DatabaseHelper.instance.queryPlaylists()）
    LaunchedEffect(Unit) {
        playlists = DatabaseHelper.queryPlaylists()
    }

    /** 把歌曲加入指定歌单（Dart: addSongToPlaylist + 结果提示） */
    fun addTo(playlist: Playlist) {
        val playlistId = playlist.id ?: return
        scope.launch {
            var added = 0
            for (song in songs) {
                val songId = song.id ?: continue
                if (DatabaseHelper.addSongToPlaylist(playlistId, songId)) added++
            }
            val message = if (added > 0) {
                "已添加到歌单「${playlist.name}」"
            } else {
                "歌曲已在歌单「${playlist.name}」中"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            playlists = DatabaseHelper.queryPlaylists()
            onDismiss()
        }
    }

    /** 新建歌单并加入（Dart: createPlaylist → 重名返回 null） */
    fun createAndAdd() {
        val name = newName.trim()
        if (name.isEmpty()) return
        scope.launch {
            val playlistId = DatabaseHelper.createPlaylist(name)
            if (playlistId == null) {
                Toast.makeText(context, "新建歌单失败（可能重名）", Toast.LENGTH_SHORT).show()
                return@launch
            }
            for (song in songs) {
                val songId = song.id ?: continue
                DatabaseHelper.addSongToPlaylist(playlistId, songId)
            }
            Toast.makeText(context, "已添加到歌单「$name」", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (creating) "新建歌单" else "添加到歌单") },
        text = {
            if (creating) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("请输入歌单名") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = BrandCyan,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(text = "新建歌单", style = TextStyle(fontSize = 15.sp))
                    }
                    if (playlists.isEmpty()) {
                        Text(
                            text = "暂无歌单",
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                            items(items = playlists, key = { it.id ?: it.name }) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { addTo(playlist) }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = playlist.name,
                                            style = TextStyle(fontSize = 15.sp),
                                        )
                                        Text(
                                            text = "${playlist.songCount} 首",
                                            style = TextStyle(
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { createAndAdd() },
                    enabled = newName.isNotBlank(),
                ) { Text("确定") }
            } else {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        dismissButton = {
            if (creating) {
                TextButton(onClick = { creating = false }) { Text("取消") }
            }
        },
    )
}
