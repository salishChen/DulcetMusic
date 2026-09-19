package com.mtechviral.musicfinderexample.core.designsystem.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytDivider
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository

/**
 * 不显示时长时，「加号」按钮额外向右偏移的距离（需求：向右挪 20px）。
 * 用 `offset` 只做视觉位移，避免改变按钮尺寸与触摸区的相对关系。
 */
private val ADD_BUTTON_SHIFT_WHEN_NO_DURATION = 20.dp

/**
 * 通用歌曲列表项。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_song_list_item.dart` 的 `MpSongListItem`：
 * - 左侧封面（50x50，圆角 12），标题 + 艺术家，行尾「加号 / 更多」两个按钮；
 * - 远程且已缓存的歌曲显示青色 `offline_pin` 缓存标识；
 * - 加号：加入当前播放列表，并轻提示"已添加到播放列表 / 该歌曲已在播放列表中"；
 * - 更多按钮与整行长按：由调用方弹出操作弹窗（`EntityActionSheet` / `SongInfoBottomSheet`）；
 * - 点击整行：由调用方执行"整列替换播放列表并从本首播放"。
 *
 * 与 Dart 的差异：Dart 用 `SnackBar` 反馈，原生改用 `Toast`
 * （无需 Scaffold 宿主，跨页面统一）。
 *
 * @param isCurrent 是否为当前播放歌曲（高亮歌名）
 * @param showDivider 是否显示行底分隔线
 * @param showAddButton 是否显示"加入播放列表"按钮（歌单/缓存管理等场景可关闭）
 * @param showDuration 是否显示歌曲时长（第二十二轮新增：歌曲页不显示时长，
 *   并把加号右移 [ADD_BUTTON_SHIFT_WHEN_NO_DURATION]，其余页面保持原样）
 */
@Composable
fun MpSongListItem(
    song: Song,
    isCurrent: Boolean = false,
    onClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    showDivider: Boolean = true,
    showAddButton: Boolean = true,
    showDuration: Boolean = true,
) {
    val context = LocalContext.current
    // 缩略图按显示尺寸（50dp）采样解码，避免列表滚动时全尺寸解码卡顿
    val artworkMaxPx = with(LocalDensity.current) { 50.dp.roundToPx() }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showArtwork) {
                SongArtwork(
                    song = song,
                    modifier = Modifier.size(50.dp),
                    cornerRadius = 12.dp,
                    maxSizePx = artworkMaxPx,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ytTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // 已缓存的远程歌曲显示缓存标识
                if (song.isRemote && song.isCached) {
                    Icon(
                        imageVector = Icons.Filled.OfflinePin,
                        contentDescription = "已缓存",
                        tint = BrandCyan,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                // 歌曲时长：歌曲页按需求去掉（加号随之右移，见下方 offset）
                if (showDuration) {
                    Text(
                        text = song.durationText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
                if (showAddButton) {
                    IconButton(
                        // 需求：不显示时长时，加号再向右挪 20dp
                        // （仅做视觉位移，不改动触摸区域以外的布局）
                        modifier = Modifier.offset(
                            x = if (showDuration) 0.dp else ADD_BUTTON_SHIFT_WHEN_NO_DURATION,
                        ),
                        onClick = {
                            // 第十六轮：不再判重，同一首歌可重复加入播放列表
                            PlaylistRepository.addSong(song)
                            Toast.makeText(
                                context,
                                "已添加到播放列表",
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "添加到播放列表",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = onMoreClick) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp),
                thickness = 0.6.dp,
                color = MaterialTheme.ytDivider,
            )
        }
    }
}
