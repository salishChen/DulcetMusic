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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 封面组件。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_artwork.dart`：
 * - 优先读取远程歌曲的本地缓存封面文件，其次读取音频文件内嵌封面；
 * - 内存缓存避免列表滚动时重复解码；
 * - 无封面时显示紫青渐变 + 音符图标占位；
 * - 有 [coverArtId] 但无封面时，自动后台调用 Subsonic 缓存封面并在完成后刷新。
 *
 * 性能约定（列表滚动卡顿的修复）：
 * - **解码一律在后台线程**（原来在组合期/主线程 `BitmapFactory.decodeByteArray`，
 *   列表每滚进一行就卡一下）；
 * - 解码结果进 [DecodedArtworkCache]（带字节预算的 LRU），划出再划回不重复解码；
 * - 列表缩略图用 [maxSizePx] 触发 `inSampleSize` 采样解码，避免把 1000px+ 的大图
 *   按全尺寸解码后只显示 50dp。
 */
@Composable
fun SongArtwork(
    song: Song,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 0.dp,
    contentScale: ContentScale = ContentScale.Crop,
    maxSizePx: Int = 0,
) {
    ArtworkImage(
        path = song.path,
        cachedArtworkPath = song.cachedArtworkPath,
        songId = song.id,
        coverArtId = song.coverArtId,
        modifier = modifier,
        cornerRadius = cornerRadius,
        contentScale = contentScale,
        maxSizePx = maxSizePx,
    )
}

/**
 * 按路径取封面（专辑 / 艺术家等没有完整 [Song] 对象的场景）。
 *
 * @param songId 远程歌曲数据库 id（有值时后台缓存封面会写库）
 * @param coverArtId Subsonic 封面 id（无本地封面时用于后台缓存）
 * @param maxSizePx 期望的显示边长（像素）。> 0 时按该尺寸采样解码，0 = 全尺寸
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
    maxSizePx: Int = 0,
) {
    // 缓存 key 与原实现一致：缓存封面路径优先，其次歌曲路径
    val cacheKey = cachedArtworkPath ?: path ?: ""
    // 采样尺寸不同的解码结果要分开缓存
    val decodeKey = if (maxSizePx > 0) "$cacheKey@$maxSizePx" else cacheKey

    // 初始值只做一次"已解码缓存"查询（廉价），绝不在组合期解码
    val bitmap by produceState<ImageBitmap?>(
        initialValue = DecodedArtworkCache.get(decodeKey),
        decodeKey,
    ) {
        if (DecodedArtworkCache.has(decodeKey)) {
            value = DecodedArtworkCache.get(decodeKey)
            return@produceState
        }
        val loaded = withContext(Dispatchers.Default) {
            val cached = ArtworkCache.peek(cacheKey)
            val bytes = cached
                ?: if (!ArtworkCache.has(cacheKey)) {
                    ArtworkCache.load(path ?: "", cachedArtworkPath)
                } else {
                    null
                }
            bytes?.decodeArtwork(maxSizePx)
        }
        DecodedArtworkCache.put(decodeKey, loaded)
        value = loaded

        // 无封面但存在 coverArtId：后台缓存封面文件，完成后以新路径重新加载
        if (loaded == null && !coverArtId.isNullOrEmpty()) {
            val artworkPath = try {
                if (songId != null) {
                    val song = DatabaseHelper.querySongById(songId)
                    if (song != null) CacheService.cacheArtwork(song) else null
                } else {
                    // Resolve a real visible song so artwork requests retain their source identity.
                    path?.let { DatabaseHelper.querySongByPath(it) }?.let { CacheService.cacheArtwork(it) }
                }
            } catch (_: Exception) {
                null
            }
            if (!artworkPath.isNullOrEmpty()) {
                ArtworkCache.invalidate(cacheKey)
                val downloaded = withContext(Dispatchers.Default) {
                    ArtworkCache.load(path ?: "", artworkPath)?.decodeArtwork(maxSizePx)
                }
                DecodedArtworkCache.put(decodeKey, downloaded)
                value = downloaded
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

/**
 * 已解码封面位图缓存（按字节预算淘汰）。
 *
 * 与 [ArtworkCache]（字节缓存）互补：这里省掉的是"重复解码"的开销，
 * 列表来回滚动时不再反复 `BitmapFactory.decodeByteArray`。
 */
private object DecodedArtworkCache {

    /** 预算 24MB：缩略图约 0.1MB，大封面约 5MB，超预算淘汰最久未用的 */
    private const val MAX_BYTES = 24L * 1024 * 1024

    private val map = object : LinkedHashMap<String, ImageBitmap?>(16, 0.75f, true) {}
    private var bytes = 0L

    fun get(key: String): ImageBitmap? = synchronized(map) { map[key] }

    fun has(key: String): Boolean = synchronized(map) { map.containsKey(key) }

    fun put(key: String, bitmap: ImageBitmap?) {
        synchronized(map) {
            val previous = map.put(key, bitmap)
            bytes += sizeOf(bitmap) - sizeOf(previous)
            val iterator = map.entries.iterator()
            while (bytes > MAX_BYTES && iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key == key) continue
                bytes -= sizeOf(entry.value)
                iterator.remove()
            }
        }
    }

    private fun sizeOf(bitmap: ImageBitmap?): Long =
        if (bitmap == null) 0L else bitmap.width.toLong() * bitmap.height.toLong() * 4L
}

/**
 * 解码为 [ImageBitmap]。
 *
 * @param maxSizePx > 0 时先读边界再算 `inSampleSize`（只保留 ≥ 期望边长的最小 2 次幂采样），
 *   避免把大图按全尺寸解码进内存。解码本身应在后台线程调用。
 */
private fun ByteArray.decodeArtwork(maxSizePx: Int): ImageBitmap? = try {
    val options = BitmapFactory.Options()
    if (maxSizePx > 0) {
        options.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(this, 0, size, options)
        var sampleSize = 1
        while (options.outWidth / (sampleSize * 2) >= maxSizePx ||
            options.outHeight / (sampleSize * 2) >= maxSizePx
        ) {
            sampleSize *= 2
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = sampleSize
    }
    BitmapFactory.decodeByteArray(this, 0, size, options)?.asImageBitmap()
} catch (_: Exception) {
    null
}
