package com.mtechviral.musicfinderexample.core.network

import org.json.JSONObject
import java.util.Locale

/** Adapt OpenSubsonic songLyrics to the LRC format used by both lyrics views. */
internal fun structuredLyricsToLrc(response: JSONObject): String? {
    val tracks = response.optJSONObject("lyricsList")?.optJSONArray("structuredLyrics") ?: return null
    var plainText: String? = null
    for (trackIndex in 0 until tracks.length()) {
        val track = tracks.optJSONObject(trackIndex) ?: continue
        val lines = track.optJSONArray("line") ?: continue
        val synced = track.optBoolean("synced")
        val offset = track.optLong("offset", 0L)
        val text = buildList {
            for (lineIndex in 0 until lines.length()) {
                val line = lines.optJSONObject(lineIndex) ?: continue
                val value = line.optString("value").replace('\r', ' ').replace('\n', ' ').trim()
                if (value.isEmpty()) continue
                if (synced) {
                    val start = (line.opt("start") as? Number)?.toLong() ?: continue
                    if (start < 0) continue
                    // OpenSubsonic: positive offset makes lyrics appear sooner.
                    val timeMs = (start - offset).coerceAtLeast(0L)
                    add(String.format(Locale.ROOT, "[%02d:%02d.%03d]%s",
                        timeMs / 60_000, timeMs / 1000 % 60, timeMs % 1000, value))
                } else {
                    add(value)
                }
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }
        if (synced && text != null) return text
        if (!synced && plainText == null) plainText = text
    }
    return plainText
}
