package com.mtechviral.musicfinderexample.core.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密（优化建议 01）。
 *
 * 用 Android Keystore 保护的 AES-GCM 密钥加密敏感字段（Subsonic 密码）：
 * - 密钥保存在系统 Keystore 中，**不可导出、不随备份/迁移离开设备**；
 * - 数据库与备份中只出现密文（格式 `enc:v1:<ivBase64>:<密文Base64>`），
 *   不再出现明文密码；
 * - 升级前的明文密码由 DAO 层读取后立即加密回写（自迁移）。
 *
 * 解密失败（例如备份恢复到新设备后密钥丢失）返回 null，
 * 调用方应视作"凭据缺失"，提示用户重新输入，而不是崩溃或静默用错凭据。
 */
object CredentialCipher {

    private const val TAG = "CredentialCipher"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "yule_subsonic_credential"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128

    /** 是否为已加密的存储值 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密明文，返回带前缀的密文；加密失败时返回 null（调用方决定是否保存） */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.w(TAG, "凭据加密失败: ${e.message}")
        null
    }

    /** 解密存储值；明文（旧版未加密）原样返回，解密失败返回 null */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        return try {
            val body = stored.removePrefix(PREFIX)
            val sep = body.indexOf(':')
            if (sep <= 0) return null
            val iv = Base64.decode(body.substring(0, sep), Base64.NO_WRAP)
            val ciphertext = Base64.decode(body.substring(sep + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥丢失（备份恢复到新设备）或数据损坏：按凭据缺失处理
            Log.w(TAG, "凭据解密失败（可能需要重新输入密码）: ${e.message}")
            null
        }
    }

    /** 取（或首次生成）Keystore 密钥 */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
