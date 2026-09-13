/*
 * 对应 Dart 原文件：lib/pages/artists_page.dart（私有组件：ListTile 行 + ClipOval 封面）
 *                  以及 lib/pages/albums_page.dart 中供艺术家详情页复用的 `AlbumHorizontalCard`
 *
 * - `ArtistRow`：一级页列表行（圆形封面 / 首字头像 + 名称 + "N 首歌曲 · M 张专辑" + 更多按钮）
 * - `ArtistAvatar`：有封面用 ArtworkImage，无封面用 MpCircleAvatar（Dart：MpArtwork 占位）
 * - `ArtistAlbumCard`：艺术家详情页横向滚动的专辑卡片
 */
package com.mtechviral.musicfinderexample.feature.artists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.component.ArtworkImage
import com.mtechviral.musicfinderexample.core.designsystem.component.MpCircleAvatar
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist

/** 艺术家列表行（Dart `ArtistsPage` 的 ListTile） */
@Composable
internal fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtistAvatar(artist = artist, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artist.songCount} 首歌曲 · ${artist.albumCount} 张专辑",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 12.sp, color = MaterialTheme.ytTextSecondary),
            )
        }
        IconButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多操作",
                tint = MaterialTheme.ytTextSecondary,
            )
        }
    }
}

/**
 * 艺术家封面（圆形）。
 *
 * 有封面来源（缓存封面 / 含内嵌封面的歌曲 / 远程 coverArtId）时用 [ArtworkImage]，
 * 否则用 [MpCircleAvatar] 显示首字占位。
 */
@Composable
internal fun ArtistAvatar(
    artist: Artist,
    size: Dp,
    modifier: Modifier = Modifier,
    coverArtId: String? = artist.coverArtId,
) {
    val hasCover = !artist.coverArtworkPath.isNullOrEmpty() ||
        !artist.coverSongPath.isNullOrEmpty() ||
        !coverArtId.isNullOrEmpty()

    if (hasCover) {
        ArtworkImage(
            path = artist.coverSongPath,
            cachedArtworkPath = artist.coverArtworkPath,
            coverArtId = coverArtId,
            modifier = modifier.size(size),
            cornerRadius = size / 2,
        )
    } else {
        MpCircleAvatar(text = artist.name, modifier = modifier, size = size)
    }
}

/** 艺术家详情页的专辑横向卡片（Dart `AlbumHorizontalCard`，宽 120） */
@Composable
internal fun ArtistAlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        ArtworkImage(
            path = album.coverSongPath,
            cachedArtworkPath = album.coverArtworkPath,
            songId = album.coverSongId,
            coverArtId = album.coverArtId,
            modifier = Modifier.size(120.dp),
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 13.sp),
        )
        Text(
            text = "${album.songCount} 首",
            style = TextStyle(fontSize = 11.sp, color = MaterialTheme.ytTextSecondary),
        )
    }
}
