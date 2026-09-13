package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import com.mtechviral.musicfinderexample.core.database.MusicDatabase
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.COL_ID
import com.mtechviral.musicfinderexample.core.database.MusicDatabase.Companion.TABLE_SUBSONIC_CONFIG
import com.mtechviral.musicfinderexample.core.database.intOrNull
import com.mtechviral.musicfinderexample.core.database.longOrNull
import com.mtechviral.musicfinderexample.core.database.stringOrNull
import com.mtechviral.musicfinderexample.core.model.SubsonicConfig

/**
 * Subsonic 服务器配置数据访问对象。
 *
 * 对应原 Flutter 工程 `DatabaseHelper` 中的「Subsonic 配置」分区：
 * 单条活跃配置记录，存在则更新、不存在则插入。
 */
class SubsonicConfigDao(private val musicDatabase: MusicDatabase) {

    private val db get() = musicDatabase.writableDatabase

    /** 查询当前活跃的 Subsonic 配置 */
    fun querySubsonicConfig(): SubsonicConfig? =
        db.query(
            TABLE_SUBSONIC_CONFIG, null, "isActive = ?", arrayOf("1"), null, null, null, "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            SubsonicConfig(
                id = c.longOrNull(COL_ID),
                intranetUrl = c.stringOrNull("intranetUrl") ?: "",
                publicUrl = c.stringOrNull("publicUrl") ?: "",
                username = c.stringOrNull("username") ?: "",
                password = c.stringOrNull("password") ?: "",
                serverName = c.stringOrNull("serverName"),
                isActive = (c.intOrNull("isActive") ?: 1) == 1,
            )
        }

    /** 保存配置（存在则更新，不存在则插入） */
    fun saveSubsonicConfig(config: SubsonicConfig) {
        val existing = querySubsonicConfig()
        val values = ContentValues().apply {
            put("intranetUrl", config.intranetUrl)
            put("publicUrl", config.publicUrl)
            put("username", config.username)
            put("password", config.password)
            put("serverName", config.serverName)
            put("isActive", if (config.isActive) 1 else 0)
        }
        if (existing?.id != null) {
            db.update(TABLE_SUBSONIC_CONFIG, values, "$COL_ID = ?", arrayOf(existing.id.toString()))
        } else {
            db.insert(TABLE_SUBSONIC_CONFIG, null, values)
        }
    }

    /** 删除配置 */
    fun deleteSubsonicConfig(id: Long) {
        db.delete(TABLE_SUBSONIC_CONFIG, "$COL_ID = ?", arrayOf(id.toString()))
    }
}
