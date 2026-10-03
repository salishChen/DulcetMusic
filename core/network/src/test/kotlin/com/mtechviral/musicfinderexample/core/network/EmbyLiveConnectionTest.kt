package com.mtechviral.musicfinderexample.core.network

import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit credentials only; the optional forward can be an ADB mapping to the phone's EasyTier port. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbyLiveConnectionTest {
    @Test
    fun authenticatesAndReadsMusicThroughTheRealProvider() = runBlocking {
        val url = System.getenv("EMBY_TEST_URL").orEmpty()
        val username = System.getenv("EMBY_TEST_USERNAME").orEmpty()
        val password = System.getenv("EMBY_TEST_PASSWORD").orEmpty()
        val forward = System.getenv("EMBY_TEST_FORWARD_URL")?.takeIf { it.isNotBlank() }
        assumeTrue("Live Emby credentials must be supplied explicitly",
            url.isNotBlank() && username.isNotBlank() && password.isNotBlank())
        val provider = EmbyProvider(RemoteSource(
            id = "live-emby", protocol = RemoteProtocol.EMBY, displayName = "Integration test",
            intranetUrl = url, username = username, password = password,
        )) { forward }
        assertEquals("Emby 登录成功", provider.testConnection())
        assertTrue("Server identity missing", !provider.serverIdentity.isNullOrBlank())
        val libraries = provider.libraries()
        val songs = provider.getAllSongs()
        assertTrue("Test music library must contain songs", songs.isNotEmpty())
        val stream = provider.stream(songs.first())
        val request = Request.Builder().url(stream.url).header("Range", "bytes=0-1023")
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }.build()
        OkHttpClient().newCall(request).execute().use { response ->
            assertTrue("Stream HTTP ${response.code}", response.isSuccessful)
            assertTrue("Empty audio stream", response.body!!.source().readByteArray(16).isNotEmpty())
        }
        println("Emby: ${libraries.size} music libraries, ${songs.size} songs, authenticated audio response")
    }
}
