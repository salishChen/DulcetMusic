package com.mtechviral.musicfinderexample.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 远程音频数据源链（耗电优化，doc/耗电分析报告.md §2）：
 *
 * `ResolvingDataSource`（解析远程流地址）→ [SongTapDataSource]（播放流旁路写缓存）
 * → `DefaultDataSource` + [StrictHttpDataSource]（实际传输）。
 *
 * 首次播放未缓存远程歌曲时，播放器读到的字节直接构成整曲缓存 ——
 * 播放与缓存共用同一次网络传输，不再并发两套完整下载。
 */
internal object RemotePlaybackDataSource {
    fun factory(context: Context): DataSource.Factory {
        val upstream = SongTapDataSource.Factory(
            DefaultDataSource.Factory(context, StrictHttpDataSource.Factory()),
        )
        return ResolvingDataSource.Factory(upstream) { dataSpec ->
            val path = dataSpec.uri.toString()
            if (!path.startsWith("remote://") && !path.startsWith("subsonic://")) {
                dataSpec
            } else {
                val resolved = runBlocking {
                    withTimeout(20_000) {
                        val song = DatabaseHelper.querySongByPath(path)
                            ?: throw IllegalStateException("远程歌曲不属于当前数据源")
                        val req = RemoteSessionManager.stream(song)
                        Triple(req, CacheService.cacheKeyOf(song), SongTapRegistry.Ref(song, RemoteSessionManager.sessionRevision))
                    }
                }
                val request = resolved.first
                val cacheKey = resolved.second
                if (cacheKey != null) SongTapRegistry.bind(cacheKey, resolved.third)
                // 重建 DataSpec：携带稳定缓存键（供 SongTapDataSource 旁路写缓存）
                DataSpec.Builder()
                    .setUri(Uri.parse(request.url))
                    .setPosition(dataSpec.position)
                    .setLength(dataSpec.length)
                    .setKey(cacheKey)
                    .setFlags(dataSpec.flags)
                    .setHttpRequestHeaders(dataSpec.httpRequestHeaders)
                    .build()
                    .withAdditionalHeaders(request.headers)
            }
        }
    }
}
