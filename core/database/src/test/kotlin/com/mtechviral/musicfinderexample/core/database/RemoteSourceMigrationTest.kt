package com.mtechviral.musicfinderexample.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteSourceMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(MusicDatabase.DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(MusicDatabase.DB_NAME)
    }

    @Test
    fun v6V7V8UpgradeOnlyBindsUnambiguousLegacyRowsAndRemovesPlaintextPassword() {
        for (version in listOf(6, 7, 8)) {
            context.deleteDatabase(MusicDatabase.DB_NAME)
            verifyUpgrade(version)
        }
    }

    private fun verifyUpgrade(version: Int) {
        val file = context.getDatabasePath(MusicDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE songs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, path TEXT UNIQUE,
                artist TEXT, album TEXT, source TEXT, sourceType TEXT, remoteId TEXT,
                remoteStreamUrl TEXT
            )""")
            old.execSQL("""CREATE TABLE subsonic_config (
                id INTEGER PRIMARY KEY AUTOINCREMENT, intranetUrl TEXT NOT NULL,
                publicUrl TEXT NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL,
                serverName TEXT, isActive INTEGER DEFAULT 1
            )""")
            old.execSQL("INSERT INTO subsonic_config(intranetUrl, publicUrl, username, password, serverName) VALUES (?, '', 'user', 'legacy-secret', 'Music')",
                arrayOf("https://music.example"))
            old.execSQL("INSERT INTO songs(title, path, source, sourceType, remoteId) VALUES ('bound', 'subsonic://one', ?, 'subsonic', 'one')",
                arrayOf("subsonic@user@https://music.example"))
            old.execSQL("INSERT INTO songs(title, path, source, sourceType, remoteId) VALUES ('ambiguous', 'subsonic://two', 'subsonic', 'subsonic', 'two')")
            old.execSQL("INSERT INTO songs(title, path, source, sourceType) VALUES ('local', '/local.mp3', 'media_library', 'local')")
            old.version = version
        }

        MusicDatabase(context).use { helper ->
            val db = helper.readableDatabase
            assertEquals("upgrade from v$version", MusicDatabase.DB_VERSION, db.version)
            val activeId = db.rawQuery("SELECT activeSourceId FROM remote_state", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            assertNotNull(activeId)
            val bindings = mutableMapOf<String, String?>()
            db.rawQuery("SELECT title, sourceId FROM songs", null).use {
                while (it.moveToNext()) bindings[it.getString(0)] = it.getString(1)
            }
            assertEquals(activeId, bindings["bound"])
            assertNull(bindings["ambiguous"])
            assertNull(bindings["local"])
            val visible = mutableSetOf<String>()
            db.rawQuery("SELECT title FROM visible_songs", null).use {
                while (it.moveToNext()) visible += it.getString(0)
            }
            assertEquals(setOf("bound", "local"), visible)
            val oldPassword = db.rawQuery("SELECT password FROM subsonic_config", null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            assertFalse(oldPassword == "legacy-secret")
            db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun v10UpgradeDefaultsToAllAccessibleLibraries() {
        val file = context.getDatabasePath(MusicDatabase.DB_NAME)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("""CREATE TABLE remote_sources (
                id TEXT PRIMARY KEY NOT NULL, protocol TEXT NOT NULL, displayName TEXT NOT NULL,
                intranetUrl TEXT NOT NULL DEFAULT '', publicUrl TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL DEFAULT '', password TEXT NOT NULL DEFAULT '',
                rootPath TEXT NOT NULL DEFAULT '', serverIdentity TEXT
            )""")
            old.execSQL("INSERT INTO remote_sources(id, protocol, displayName) VALUES ('source', 'NAVIDROME', 'Music')")
            old.version = 10
        }
        MusicDatabase(context).use { helper ->
            val db = helper.readableDatabase
            assertEquals(11, db.version)
            db.rawQuery("SELECT libraryId FROM remote_sources WHERE id = 'source'", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("", it.getString(0))
            }
        }
    }
}
