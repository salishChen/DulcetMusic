package com.mtechviral.musicfinderexample.core.database.dao

import android.content.ContentValues
import com.mtechviral.musicfinderexample.core.database.CredentialCipher
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

    /**
     * 查询当前活跃的 Subsonic 配置。
     *
     * 密码以 Keystore 加密形式保存（优化建议 01）：
     * 读出时解密；旧版明文密码读到后立即加密回写（自迁移）。
     * 解密失败（如备份恢复后密钥丢失）按空密码返回，由用户重新输入。
     */
    fun querySubsonicConfig(): SubsonicConfig? {
        val row = db.query(
            TABLE_SUBSONIC_CONFIG, null, "isActive = ?", arrayOf("1"), null, null, null, "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            Row(
                id = c.longOrNull(COL_ID),
                intranetUrl = c.stringOrNull("intranetUrl") ?: "",
                publicUrl = c.stringOrNull("publicUrl") ?: "",
                username = c.stringOrNull("username") ?: "",
                rawPassword = c.stringOrNull("password") ?: "",
                serverName = c.stringOrNull("serverName"),
                isActive = (c.intOrNull("isActive") ?: 1) == 1,
            )
        }
        val password = CredentialCipher.decrypt(row.rawPassword) ?: ""
        // 升级迁移：旧版明文密码 -> 立即加密回写
        if (row.rawPassword.isNotEmpty() &&
            !CredentialCipher.isEncrypted(row.rawPassword) &&
            row.id != null
        ) {
            CredentialCipher.encrypt(row.rawPassword)?.let { encrypted ->
                db.update(
                    TABLE_SUBSONIC_CONFIG,
                    ContentValues().apply { put("password", encrypted) },
                    "$COL_ID = ?",
                    arrayOf(row.id.toString()),
                )
            }
        }
        return SubsonicConfig(
            id = row.id,
            intranetUrl = row.intranetUrl,
            publicUrl = row.publicUrl,
            username = row.username,
            password = password,
            serverName = row.serverName,
            isActive = row.isActive,
        )
    }

    private data class Row(
        val id: Long?,
        val intranetUrl: String,
        val publicUrl: String,
        val username: String,
        val rawPassword: String,
        val serverName: String?,
        val isActive: Boolean,
    )

    /**
     * 保存配置（存在则更新，不存在则插入）。
     * 密码写入前用 Keystore 加密，数据库与备份中不出现明文（优化建议 01）。
     */
    fun saveSubsonicConfig(config: SubsonicConfig) {
        val existing = querySubsonicConfig()
        val storedPassword = if (config.password.isEmpty()) "" else
            CredentialCipher.encrypt(config.password)
                ?: throw IllegalStateException("无法安全保存 Subsonic 密码")
        val values = ContentValues().apply {
            put("intranetUrl", config.intranetUrl)
            put("publicUrl", config.publicUrl)
            put("username", config.username)
            put("password", storedPassword)
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
