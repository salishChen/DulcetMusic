/*
 * 对应 Dart 原文件：lib/pages/albums_page.dart（私有组件 `_AlbumCard`）
 *
 * 专辑网格卡片：封面 + 专辑名 + "艺术家 · N 首"，右上角三点按钮。
 */
package com.mtechviral.musicfinderexample.feature.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album

/** 专辑网格卡片（Dart `_AlbumCard`，GridView crossAxisCount = 2 / childAspectRatio 0.78 / 圆角 16） */
@Composable
internal fun AlbumGridCard(
    album: Album,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 等价 Dart childAspectRatio: 0.78（宽/高），并为下方 weight(1f) 提供确定高度
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 封面占满卡片上方剩余区域（Dart：Expanded + BoxFit.cover）
            ArtworkImage(
                path = album.coverSongPath,
                cachedArtworkPath = album.coverArtworkPath,
                songId = album.coverSongId,
                coverArtId = album.coverArtId,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                // 网格封面按卡片宽度采样解码（约半屏宽），避免全尺寸解码
                maxSizePx = with(LocalDensity.current) {
                    (LocalConfiguration.current.screenWidthDp.dp / 2).roundToPx()
                },
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = album.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.W600),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${album.displayArtist} · ${album.songCount} 首",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.ytTextSecondary),
                )
            }
        }

        // 右上角三点按钮（Dart：黑色半透明圆底 + 白色 more_vert）
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onMoreClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
