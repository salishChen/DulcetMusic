package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.common.LrcParser
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Opt-in integration check against a library whose tracks have synchronized lyrics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NavidromeLiveLyricsTest {
    @Test
    fun configuredServerReturnsTimedLyricsThroughTheRealProvider() = runBlocking {
        val urls = System.getenv("NAVIDROME_TEST_URLS").orEmpty().split(',').filter { it.isNotBlank() }
        val username = System.getenv("NAVIDROME_TEST_USERNAME").orEmpty()
        val password = System.getenv("NAVIDROME_TEST_PASSWORD").orEmpty()
        assumeTrue("Live Navidrome credentials must be supplied explicitly", urls.isNotEmpty() && username.isNotBlank() && password.isNotBlank())
        try {
            for ((index, url) in urls.withIndex()) {
                val provider = SubsonicProvider(RemoteSource(
                    id = "live-test-$index", protocol = RemoteProtocol.NAVIDROME,
                    displayName = "Integration test", intranetUrl = url,
                    username = username, password = password,
                ))
                provider.testConnection()
                provider.activate()
                val songs = provider.getAllSongs()
                assertTrue("The configured test library must contain songs", songs.isNotEmpty())
                for (song in songs) {
                    val lyrics = provider.lyrics(song)
                    assertTrue("Track ${song.remoteId} lost its timing tags", LrcParser.hasTimestamps(lyrics))
                    val lines = LrcParser.parse(lyrics)
                    assertTrue("Track ${song.remoteId} has no changing timeline", lines.map { it.timeMs }.distinct().size > 1)
                    assertTrue("Track ${song.remoteId} has negative timestamps", lines.all { it.timeMs >= 0 })
                }
                println("Navidrome address ${index + 1}: ${songs.size} tracks returned synchronized lyrics")
            }
        } finally {
            SubsonicService.deactivate()
        }
    }
}
