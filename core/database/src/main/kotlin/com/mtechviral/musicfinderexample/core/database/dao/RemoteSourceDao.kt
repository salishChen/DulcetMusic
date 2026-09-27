package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import com.mtechviral.musicfinderexample.core.database.CredentialCipher
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.model.RemoteSource

/** A single active source; inactive records only retain song provenance. */
class RemoteSourceDao(private val database: MusicDatabase) {
    private val db get() = database.writableDatabase

    fun activeSourceId(): String? = db.rawQuery(
        "SELECT activeSourceId FROM remote_state WHERE singletonId = 1", null,
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun activeSource(): RemoteSource? = activeSourceId()?.let(::sourceById)

    fun sourceById(id: String): RemoteSource? = db.query(
        "remote_sources", null, "id = ?", arrayOf(id), null, null, null, "1",
    ).use { c ->
        if (!c.moveToFirst()) return null
        val protocol = runCatching { RemoteProtocol.valueOf(c.getString(c.getColumnIndexOrThrow("protocol"))) }
            .getOrNull() ?: return null
        RemoteSource(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            protocol = protocol,
            displayName = c.getString(c.getColumnIndexOrThrow("displayName")),
            intranetUrl = c.getString(c.getColumnIndexOrThrow("intranetUrl")),
            publicUrl = c.getString(c.getColumnIndexOrThrow("publicUrl")),
            username = c.getString(c.getColumnIndexOrThrow("username")),
            password = CredentialCipher.decrypt(c.getString(c.getColumnIndexOrThrow("password"))) ?: "",
            rootPath = c.getString(c.getColumnIndexOrThrow("rootPath")),
            libraryId = c.getString(c.getColumnIndexOrThrow("libraryId")),
            serverIdentity = c.getString(c.getColumnIndexOrThrow("serverIdentity")),
        )
    }

    fun activate(source: RemoteSource) {
        val encrypted = if (source.password.isEmpty()) "" else CredentialCipher.encrypt(source.password)
            ?: throw IllegalStateException("无法安全保存远程凭据")
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", source.id)
                put("protocol", source.protocol.name)
                put("displayName", source.displayName)
                put("intranetUrl", source.intranetUrl)
                put("publicUrl", source.publicUrl)
                put("username", source.username)
                put("password", encrypted)
                put("rootPath", source.rootPath)
                put("libraryId", source.libraryId)
                put("serverIdentity", source.serverIdentity)
            }
            if (db.update("remote_sources", values, "id = ?", arrayOf(source.id)) == 0) {
                db.insertOrThrow("remote_sources", null, values)
            }
            db.execSQL("UPDATE remote_state SET activeSourceId = ? WHERE singletonId = 1", arrayOf(source.id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deactivate() {
        db.execSQL("UPDATE remote_state SET activeSourceId = NULL WHERE singletonId = 1")
    }
}
