package com.mtechviral.musicfinderexample.core.designsystem.component

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.media.ArtworkCache
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 封面组件。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_artwork.dart`：
 * - 优先读取远程歌曲的本地缓存封面文件，其次读取音频文件内嵌封面；
 * - 内存缓存避免列表滚动时重复解码；
 * - 无封面时显示紫青渐变 + 音符图标占位；
 * - 有 [coverArtId] 但无封面时，自动后台调用 Subsonic 缓存封面并在完成后刷新。
 */
@Composable
fun SongArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    ArtworkImage(
        path = song.path,
        cachedArtworkPath = song.cachedArtworkPath,
        songId = song.id,
        coverArtId = song.coverArtId,
        modifier = modifier,
        cornerRadius = cornerRadius,
        contentScale = contentScale,
    )
}

/**
 * 按路径取封面（专辑 / 艺术家等没有完整 [Song] 对象的场景）。
 *
 * @param songId 远程歌曲数据库 id（有值时后台缓存封面会写库）
 * @param coverArtId Subsonic 封面 id（无本地封面时用于后台缓存）
 */
@Composable
fun ArtworkImage(
    path: String?,
    cachedArtworkPath: String? = null,
    songId: Long? = null,
    coverArtId: String? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // 缓存 key 与原实现一致：缓存封面路径优先，其次歌曲路径
    val cacheKey = cachedArtworkPath ?: path ?: ""
    val bitmap by produceState<ImageBitmap?>(
        initialValue = ArtworkCache.peek(cacheKey)?.toImageBitmapSafe(),
        cacheKey,
    ) {
        val cached = ArtworkCache.peek(cacheKey)
        if (cached != null) {
            value = cached.toImageBitmapSafe()
            return@produceState
        }
        if (!ArtworkCache.has(cacheKey)) {
            val bytes = ArtworkCache.load(path ?: "", cachedArtworkPath)
            value = bytes?.toImageBitmapSafe()
        }

        // 无封面但存在 coverArtId：后台缓存封面文件，完成后以新路径重新加载
        if (value == null && !coverArtId.isNullOrEmpty()) {
            val artworkPath = try {
                if (songId != null) {
                    val song = DatabaseHelper.querySongById(songId)
                    if (song != null) CacheService.cacheArtwork(song) else null
                } else {
                    // 无 songId（如艺术家封面）：直接按 coverArtId 缓存封面文件
                    CacheService.cacheArtwork(
                        Song(title = "", path = path ?: "", coverArtId = coverArtId),
                    )
                }
            } catch (_: Exception) {
                null
            }
            if (!artworkPath.isNullOrEmpty()) {
                ArtworkCache.invalidate(cacheKey)
                value = ArtworkCache.load(path ?: "", artworkPath)?.toImageBitmapSafe()
            }
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(modifier = modifier.clip(shape)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        } else {
            ArtworkPlaceholder(modifier = Modifier.fillMaxSize())
        }
    }
}

/** 渐变占位（对应 Flutter `_placeholder()`） */
@Composable
private fun ArtworkPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(listOf(BrandPurple, BrandCyan)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxSize(0.55f),
        )
    }
}

/** 字节数组安全解码为 ImageBitmap（解码失败返回 null） */
private fun ByteArray.toImageBitmapSafe(): ImageBitmap? = try {
    BitmapFactory.decodeByteArray(this, 0, size)?.asImageBitmap()
} catch (_: Exception) {
    null
}
