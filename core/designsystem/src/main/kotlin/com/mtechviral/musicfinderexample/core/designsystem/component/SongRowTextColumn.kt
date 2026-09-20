package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytRowSubtitle
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 歌曲行的「标题 + 副标题」文字列（第二十三轮抽出复用）。
 *
 * 版式按设计截图：
 * - 第 1 行：歌名（16sp / Medium）
 * - 第 2 行：音质徽章（SQ/HR，有则显示）+「歌手 - 专辑」+ 已缓存灰色小图标
 *
 * 供自绘行的页面（我喜欢、搜索结果）复用，保证与 [MpSongListItem] 视觉一致。
 *
 * @param trailing 副标题行最右侧的额外内容（如「取消喜欢」按钮）
 */
@Composable
fun SongRowTextColumn(
    song: Song,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    showCachedBadge: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Text(
            text = song.title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = titleColor ?: if (isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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
                // 截图格式为「歌手 - 专辑」
                text = "${song.displayArtist} - ${song.displayAlbum}",
                fontSize = 12.sp,
                color = subtitleColor ?: MaterialTheme.ytRowSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showCachedBadge && song.isRemote && song.isCached) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.OfflinePin,
                    contentDescription = "已缓存",
                    tint = MaterialTheme.ytTextSecondary,
                    modifier = Modifier.size(MpListMetrics.CachedIconSize),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(4.dp))
                trailing()
            }
        }
    }
}

/** 歌曲行通用的右侧图标按钮排布（与 [MpSongListItem] 的行尾间距一致） */
@Composable
fun SongRowTrailing(content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        content()
    }
}
