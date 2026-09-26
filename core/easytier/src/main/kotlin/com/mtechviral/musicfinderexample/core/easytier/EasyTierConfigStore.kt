package com.mtechviral.musicfinderexample.core.easytier

import android.content.Context
import android.content.SharedPreferences
import com.mtechviral.musicfinderexample.core.database.CredentialCipher

/**
 * EasyTier 配置持久化（`easytier_prefs`）。
 *
 * 安全（doc/EasyTier集成方案.md §4.5）：
 * - [EasyTierConfig.networkSecret] 经 [CredentialCipher]（Android Keystore AES-GCM）
 *   加密后落盘，与 Subsonic 密码同等保护；
 * - 该偏好文件在备份规则中排除（backup_rules.xml / data_extraction_rules.xml），
 *   不随云备份/设备迁移离开设备；
 * - 读出解密失败（如备份恢复后密钥丢失）按空值处理，由用户重新输入。
 */
object EasyTierConfigStore {

    private const val PREFS_NAME = "easytier_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_NETWORK_NAME = "network_name"
    private const val KEY_NETWORK_SECRET_ENC = "network_secret_enc"
    private const val KEY_PEERS = "peers"
    private const val KEY_VIRTUAL_IPV4 = "virtual_ipv4"
    private const val KEY_HOSTNAME = "hostname"
    private const val KEY_SERVER_VIRTUAL_IP = "server_virtual_ip"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_LOCAL_PORT = "local_port"
    private const val KEY_SOCKS5_ENABLED = "socks5_enabled"
    private const val KEY_SOCKS5_PORT = "socks5_port"
    private const val KEY_POWERSAVER = "powersaver"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取配置（secret 解密；解密失败按空串，提示用户重新输入） */
    fun load(context: Context): EasyTierConfig {
        val p = prefs(context)
        val secretEnc = p.getString(KEY_NETWORK_SECRET_ENC, null)
        val secret = secretEnc?.let { CredentialCipher.decrypt(it) } ?: ""
        return EasyTierConfig(
            enabled = p.getBoolean(KEY_ENABLED, false),
            networkName = p.getString(KEY_NETWORK_NAME, "") ?: "",
            networkSecret = secret,
            peers = p.getString(KEY_PEERS, "") ?: "",
            virtualIpv4 = p.getString(KEY_VIRTUAL_IPV4, "") ?: "",
            hostname = p.getString(KEY_HOSTNAME, "") ?: "",
            serverVirtualIp = p.getString(KEY_SERVER_VIRTUAL_IP, "") ?: "",
            serverPort = p.getInt(KEY_SERVER_PORT, 4533),
            localPort = p.getInt(KEY_LOCAL_PORT, 18080),
            socks5Enabled = p.getBoolean(KEY_SOCKS5_ENABLED, false),
            socks5Port = p.getInt(KEY_SOCKS5_PORT, 1080),
            powersaver = p.getBoolean(KEY_POWERSAVER, true),
        )
    }

    /** 保存配置（secret 加密落盘；加密失败时不落明文，保留旧值） */
    fun save(context: Context, config: EasyTierConfig) {
        val editor = prefs(context).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_NETWORK_NAME, config.networkName)
            .putString(KEY_PEERS, config.peers)
            .putString(KEY_VIRTUAL_IPV4, config.virtualIpv4)
            .putString(KEY_HOSTNAME, config.hostname)
            .putString(KEY_SERVER_VIRTUAL_IP, config.serverVirtualIp)
            .putInt(KEY_SERVER_PORT, config.serverPort)
            .putInt(KEY_LOCAL_PORT, config.localPort)
            .putBoolean(KEY_SOCKS5_ENABLED, config.socks5Enabled)
            .putInt(KEY_SOCKS5_PORT, config.socks5Port)
            .putBoolean(KEY_POWERSAVER, config.powersaver)
        // network_secret：Keystore 加密后存储（优化建议 01 同口径）
        if (config.networkSecret.isNotEmpty()) {
            val enc = CredentialCipher.encrypt(config.networkSecret)
            if (enc != null) {
                editor.putString(KEY_NETWORK_SECRET_ENC, enc)
            }
        } else {
            editor.remove(KEY_NETWORK_SECRET_ENC)
        }
        editor.apply()
    }
}
