package com.mtechviral.musicfinderexample.core.network

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream

private const val MAX_ARTWORK_BYTES = 4_000_000

/** Refuse oversized or unbounded image responses before allocating the whole body. */
internal fun ResponseBody.readArtworkBytes(): ByteArray? {
    if (contentLength() > MAX_ARTWORK_BYTES) return null
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    byteStream().use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_ARTWORK_BYTES) return null
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
