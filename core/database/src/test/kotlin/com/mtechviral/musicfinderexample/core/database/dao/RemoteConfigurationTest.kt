package com.mtechviral.musicfinderexample.core.database.dao

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteConfigurationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: MusicDatabase
    private lateinit var dao: RemoteSourceDao

    @Before
    fun setUp() {
        context.deleteDatabase(MusicDatabase.DB_NAME)
        database = MusicDatabase(context)
        dao = RemoteSourceDao(database)
    }

    @After
    fun tearDown() { database.close(); context.deleteDatabase(MusicDatabase.DB_NAME) }

    private fun source(protocol: RemoteProtocol, suffix: String = "") = RemoteSource(
        id = protocol.name + suffix, protocol = protocol, displayName = protocol.label + suffix,
        intranetUrl = "https://${protocol.name.lowercase()}.example$suffix", publicUrl = "https://public.example$suffix",
        username = "user-${protocol.name}$suffix", rootPath = if (protocol == RemoteProtocol.WEBDAV) "Music" else "",
        libraryId = if (protocol == RemoteProtocol.WEBDAV) "" else "library-${protocol.name}",
    )

    @Test
    fun firstNavidromeConfigurationCannotReadSubsonicFields() {
        val subsonic = source(RemoteProtocol.SUBSONIC)
        dao.activate(subsonic)
        assertEquals(subsonic, dao.configuration(RemoteProtocol.SUBSONIC))
        assertNull(dao.configuration(RemoteProtocol.NAVIDROME))
        assertNull(dao.configuration(RemoteProtocol.WEBDAV))
        assertNull(dao.configuration(RemoteProtocol.EMBY))
    }

    @Test
    fun eachProtocolKeepsItsOwnFieldsCredentialsAndSourceIdAfterRestart() {
        for (protocol in RemoteProtocol.entries) {
            dao.activate(source(protocol))
            // Fixtures bypass Android Keystore, which is unavailable in Robolectric.
            database.writableDatabase.execSQL("UPDATE remote_sources SET password = ? WHERE id = ?",
                arrayOf("fixture-${protocol.name}", protocol.name))
        }
        database.close()
        database = MusicDatabase(context)
        dao = RemoteSourceDao(database)
        for (protocol in RemoteProtocol.entries) {
            assertEquals(source(protocol).copy(password = "fixture-${protocol.name}"), dao.configuration(protocol))
        }
        val subsonic = dao.configuration(RemoteProtocol.SUBSONIC)!!
        dao.activate(subsonic.copy(password = ""))
        assertEquals(subsonic.id, dao.activeSourceId())
        assertEquals("fixture-NAVIDROME", dao.configuration(RemoteProtocol.NAVIDROME)?.password)
    }

    @Test
    fun deletingInactiveProfileDoesNotChangeActiveProfile() {
        dao.activate(source(RemoteProtocol.SUBSONIC))
        dao.activate(source(RemoteProtocol.NAVIDROME))
        dao.removeConfiguration(RemoteProtocol.SUBSONIC)
        assertNull(dao.configuration(RemoteProtocol.SUBSONIC))
        assertEquals(source(RemoteProtocol.NAVIDROME), dao.activeSource())
        assertNotNull(dao.sourceById(RemoteProtocol.SUBSONIC.name)) // song provenance remains
    }

    @Test
    fun removingActiveProfileKeepsOtherSavedProfiles() {
        dao.activate(source(RemoteProtocol.SUBSONIC))
        dao.activate(source(RemoteProtocol.NAVIDROME))
        dao.deactivate()
        assertNull(dao.activeSourceId())
        assertNull(dao.configuration(RemoteProtocol.NAVIDROME))
        assertEquals(source(RemoteProtocol.SUBSONIC), dao.configuration(RemoteProtocol.SUBSONIC))
    }

    @Test
    fun replacingSameProtocolClearsOnlySupersededCredentials() {
        dao.activate(source(RemoteProtocol.SUBSONIC))
        database.writableDatabase.execSQL("UPDATE remote_sources SET password = 'old-fixture' WHERE id = 'SUBSONIC'")
        dao.activate(source(RemoteProtocol.NAVIDROME))
        database.writableDatabase.execSQL("UPDATE remote_sources SET password = 'nav-fixture' WHERE id = 'NAVIDROME'")
        val replacement = source(RemoteProtocol.SUBSONIC, "-new")
        dao.activate(replacement)
        assertEquals(replacement, dao.configuration(RemoteProtocol.SUBSONIC))
        assertEquals("", dao.sourceById("SUBSONIC")?.password)
        assertEquals("nav-fixture", dao.configuration(RemoteProtocol.NAVIDROME)?.password)
    }

    @Test
    fun v12UpgradeKeepsActiveProfileWithoutTurningHistoryIntoConfigurations() {
        dao.activate(source(RemoteProtocol.SUBSONIC))
        dao.activate(source(RemoteProtocol.NAVIDROME))
        val db = database.writableDatabase
        db.execSQL("DROP TABLE remote_configurations")
        db.version = 12
        database.close()
        database = MusicDatabase(context)
        dao = RemoteSourceDao(database)
        assertEquals(source(RemoteProtocol.NAVIDROME), dao.configuration(RemoteProtocol.NAVIDROME))
        assertNull(dao.configuration(RemoteProtocol.SUBSONIC))
        assertEquals(RemoteProtocol.NAVIDROME.name, dao.activeSourceId())
    }
}
