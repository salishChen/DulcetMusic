package com.mtechviral.musicfinderexample.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Media3 invokes this on its loader thread, including for automatic next-track playback. */
internal object RemotePlaybackDataSource {
    fun factory(context: Context): ResolvingDataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, StrictHttpDataSource.Factory())
        return ResolvingDataSource.Factory(upstream) { dataSpec ->
            val path = dataSpec.uri.toString()
            if (!path.startsWith("remote://") && !path.startsWith("subsonic://")) {
                dataSpec
            } else {
                val request = runBlocking {
                    withTimeout(20_000) {
                        val song = DatabaseHelper.querySongByPath(path)
                            ?: throw IllegalStateException("远程歌曲不属于当前数据源")
                        RemoteSessionManager.stream(song)
                    }
                }
                dataSpec.withUri(Uri.parse(request.url)).withAdditionalHeaders(request.headers)
            }
        }
    }
}
