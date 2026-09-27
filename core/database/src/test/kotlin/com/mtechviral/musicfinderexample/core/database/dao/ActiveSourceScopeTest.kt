package com.mtechviral.musicfinderexample.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.Song
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActiveSourceScopeTest {
    private lateinit var database: MusicDatabase
    private lateinit var songs: SongDao
    private lateinit var albums: AlbumDao
    private lateinit var artists: ArtistDao
    private lateinit var playlists: PlaylistDao

    @Before
    fun setUp() {
        database = MusicDatabase(ApplicationProvider.getApplicationContext<Context>())
        songs = SongDao(database)
        albums = AlbumDao(database)
        artists = ArtistDao(database)
        playlists = PlaylistDao(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun switchingActiveSourceChangesEveryVisibleLibraryQueryWithoutDeletingHistory() {
        val db = database.writableDatabase
        for (id in listOf("source-a", "source-b")) {
            db.execSQL(
                "INSERT INTO remote_sources(id, protocol, displayName) VALUES (?, 'SUBSONIC', ?)",
                arrayOf(id, id),
            )
        }
        songs.insertSongs(listOf(Song(title = "Local", path = "/local.mp3", artist = "Local Artist",
            album = "Local Album")), "media_library")
        for (id in listOf("source-a", "source-b")) {
            songs.insertSongs(listOf(Song(title = id, path = "remote://$id/c29uZw",
                artist = id, album = id, sourceType = Song.SOURCE_TYPE_SUBSONIC,
                sourceId = id, remoteId = "song")), id)
        }
        val playlistId = playlists.createPlaylist("Mixed")!!
        for (id in listOf("source-a", "source-b")) {
            playlists.addSongToPlaylist(playlistId, songs.querySongByRemoteId(id, "song")!!.id!!)
        }

        fun visibleTitles() = songs.queryAllSongs().map { it.title }.toSet()
        assertEquals(setOf("Local"), visibleTitles())
        for (id in listOf("source-a", "source-b")) {
            db.execSQL("UPDATE remote_state SET activeSourceId = ? WHERE singletonId = 1", arrayOf(id))
            assertEquals(setOf("Local", id), visibleTitles())
            assertEquals(setOf("Local Album", id), albums.queryAlbums().map { it.title }.toSet())
            assertEquals(setOf("Local Artist", id), artists.queryArtists().map { it.name }.toSet())
            assertEquals(listOf(id), playlists.querySongsInPlaylist(playlistId).map { it.title })
            assertEquals(1, playlists.queryPlaylists().single().songCount)
        }
        assertEquals(3, db.rawQuery("SELECT COUNT(*) FROM songs", null).use {
            it.moveToFirst(); it.getInt(0)
        })
    }
}
