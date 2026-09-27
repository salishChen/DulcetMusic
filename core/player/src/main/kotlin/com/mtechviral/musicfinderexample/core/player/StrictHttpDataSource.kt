package com.mtechviral.musicfinderexample.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Keeps source-scoped credentials on the exact URL chosen by the provider. */
internal class StrictHttpDataSource private constructor(private val client: OkHttpClient) : BaseDataSource(true) {
    class Factory : DataSource.Factory {
        private val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun createDataSource(): DataSource = StrictHttpDataSource(client)
    }

    @Volatile private var call: Call? = null
    private var response: Response? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val builder = Request.Builder().url(dataSpec.uri.toString())
        dataSpec.httpRequestHeaders.forEach { (name, value) -> builder.header(name, value) }
        val position = dataSpec.position
        val length = dataSpec.length
        if (position > 0 || length != C.LENGTH_UNSET.toLong()) {
            val end = if (length != C.LENGTH_UNSET.toLong()) position + length - 1 else null
            builder.header("Range", "bytes=$position-${end ?: ""}")
        }
        val request = builder.get().build()
        val nextCall = client.newCall(request)
        call = nextCall
        try {
            val nextResponse = nextCall.execute()
            response = nextResponse
            if (nextResponse.code !in 200..299) throw IOException("远程音频返回 HTTP ${nextResponse.code}")
            val body = nextResponse.body ?: throw IOException("远程音频响应为空")
            val stream = body.byteStream()
            input = stream
            if (nextResponse.code == 200 && position > 0) skipFully(stream, position)
            remaining = when {
                length != C.LENGTH_UNSET.toLong() -> length
                body.contentLength() < 0 -> C.LENGTH_UNSET.toLong()
                nextResponse.code == 200 -> (body.contentLength() - position).coerceAtLeast(0)
                else -> body.contentLength()
            }
            uri = dataSpec.uri
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = input ?: throw IOException("远程音频未打开")
        val count = stream.read(buffer, offset, if (remaining == C.LENGTH_UNSET.toLong()) length
            else minOf(length.toLong(), remaining).toInt())
        if (count == -1) {
            if (remaining != C.LENGTH_UNSET.toLong()) throw EOFException("远程音频提前结束")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        response?.headers?.toMultimap() ?: emptyMap()

    override fun close() {
        release()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun release() {
        call?.cancel()
        input?.close()
        response?.close()
        call = null
        input = null
        response = null
        uri = null
        remaining = C.LENGTH_UNSET.toLong()
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var left = bytes
        val discard = ByteArray(8192)
        while (left > 0) {
            val count = stream.read(discard, 0, minOf(left, discard.size.toLong()).toInt())
            if (count < 0) throw EOFException("服务器未支持所需的音频范围")
            left -= count
        }
    }
}
