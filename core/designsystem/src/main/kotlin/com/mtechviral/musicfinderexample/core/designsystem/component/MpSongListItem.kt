package com.mtechviral.musicfinderexample.core.designsystem.component

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytPlusDisc
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytPlusIcon
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytRowSubtitle
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository

/**
 * 通用歌曲列表项。
 *
 * 第二十三轮：按设计截图重做整行样式，与原 Dart 版本 `MpSongListItem` 的差异：
 *
 * | 项目       | 改前                          | 改后（截图）                       |
 * |------------|-------------------------------|------------------------------------|
 * | 封面圆角   | 12dp                          | 4dp（小圆角）                       |
 * | 标题字号   | bodyLarge(16) 常规字重        | 16sp Medium（截图笔画明显更粗）      |
 * | 副标题     | 仅「歌手」                    | 「歌手 - 专辑」+ 前置 SQ/HR 徽章     |
 * | 行尾按钮   | 24dp 无底色                   | 加号带 14dp 极淡圆盘、三点 21dp      |
 * | 行分隔线   | 有（0.6dp）                   | 无（截图行间只有留白）               |
 * | 缓存标识   | 时长左侧、青色 16dp            | 副标题最右、灰色 14dp                |
 *
 * 行为保持不变：点击整行由调用方处理；加号加入播放列表；更多按钮由调用方弹窗。
 *
 * @param isCurrent 是否为当前播放歌曲（高亮标题）
 * @param showDivider 是否显示行底分隔线（默认 false：截图无分隔线）
 * @param showAddButton 是否显示「加入播放列表」按钮
 * @param showDuration 是否显示歌曲时长
 * @param showCachedBadge 是否显示「已缓存」标识（默认 true）
 */
@Composable
fun MpSongListItem(
    song: Song,
    isCurrent: Boolean = false,
    onClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    showArtwork: Boolean = true,
    showDivider: Boolean = false,
    showAddButton: Boolean = true,
    showDuration: Boolean = false,
    showCachedBadge: Boolean = true,
) {
    val context = LocalContext.current
    // 缩略图按显示尺寸（50dp）采样解码，避免列表滚动时全尺寸解码卡顿
    val artworkMaxPx = with(LocalDensity.current) { MpListMetrics.ArtworkSize.roundToPx() }

    Column(modifier = modifier.fillMaxWidth()) {
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
            if (showArtwork) {
                SongArtwork(
                    song = song,
                    modifier = Modifier.size(MpListMetrics.ArtworkSize),
                    cornerRadius = MpListMetrics.ArtworkCorner,
                    maxSizePx = artworkMaxPx,
                )
                Spacer(Modifier.width(MpListMetrics.ArtworkTextGap))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    // 截图标题笔画明显比副标题粗，用 Medium 而非 Bold（Bold 过重会挤字）
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(MpListMetrics.TitleSubtitleGap))
                // 副标题行：音质徽章 + 「歌手 - 专辑」+（可选）缓存标识
                Row(verticalAlignment = Alignment.CenterVertically) {
                    song.qualityBadge?.let { quality ->
                        QualityBadge(quality = quality)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        // 截图格式为「歌手 - 专辑」（与播放列表页保持一致）
                        text = "${song.displayArtist} - ${song.displayAlbum}",
                        fontSize = 12.sp,
                        color = MaterialTheme.ytRowSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 已缓存的远程歌曲：灰色小图标，贴在本行最右侧（需求：缩小 2px + 改灰）
                    if (showCachedBadge && song.isRemote && song.isCached) {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                // 歌曲时长（默认不显示，仅其它调用方按需开启）
                if (showDuration) {
                    Text(
                        text = song.durationText,
                        fontSize = 12.sp,
                        color = MaterialTheme.ytTextSecondary,
                    )
                }
                if (showAddButton) {
                    IconButton(
                        modifier = Modifier.size(MpListMetrics.IconButtonSize),
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
                        // 截图：加号坐在一个极淡的圆盘上
                        Box(
                            modifier = Modifier
                                .size(MpListMetrics.PlusDiscSize)
                                .background(MaterialTheme.ytPlusDisc, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "添加到播放列表",
                                tint = MaterialTheme.ytPlusIcon,
                                modifier = Modifier.size(MpListMetrics.PlusIconSize),
                            )
                        }
                    }
                }
                IconButton(
                    modifier = Modifier.size(MpListMetrics.IconButtonSize),
                    onClick = onMoreClick,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MpListMetrics.MoreIconSize),
                    )
                }
            }
        }

        if (showDivider) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MpListMetrics.RowStartPadding),
                thickness = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
