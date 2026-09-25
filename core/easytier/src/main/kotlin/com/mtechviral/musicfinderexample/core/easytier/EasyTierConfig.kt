package com.mtechviral.musicfinderexample.core.easytier

/**
 * EasyTier 组网配置（对应 easytier-core 命令行参数，见官方「完整配置选项」）。
 *
 * 设计要点（doc/EasyTier集成方案.md §4）：
 * - **无 TUN 模式**（`--no-tun`）：免 ROOT / 免 VpnService，只把 Subsonic
 *   这一台服务器的端口转发到本地回环（`--port-forward`）；
 * - 转发规则 `tcp://127.0.0.1:<localPort>/<serverVirtualIp>:<serverPort>`，
 *   应用层只需把「内网地址」指向 `http://127.0.0.1:<localPort>`；
 * - [networkSecret] 为敏感凭据：持久化时用 Keystore 加密（[EasyTierConfigStore]），
 *   不进备份、不打日志。
 */
data class EasyTierConfig(
    /** 是否启用 App 内置组网 */
    val enabled: Boolean = false,
    /** 网络名（同一网络的所有节点一致），对应 --network-name */
    val networkName: String = "",
    /** 网络密钥（敏感），对应 --network-secret */
    val networkSecret: String = "",
    /**
     * 初始对等节点（逗号分隔），对应 `-p/--peers`，
     * 形如 `udp://公网中继:11010` 或 `tcp://主机:端口`。可留空（局域网内自动发现）。
     */
    val peers: String = "",
    /** 本节点虚拟 IPv4（可选，对应 `-i/--ipv4`）；留空由网络分配（--dhcp） */
    val virtualIpv4: String = "",
    /** Subsonic 服务器在虚拟网中的 IPv4，如 10.144.144.2 */
    val serverVirtualIp: String = "",
    /** Subsonic 服务器端口（虚拟网内），默认 4533 */
    val serverPort: Int = 4533,
    /** 本地回环转发端口，应用侧「内网地址」= http://127.0.0.1:<localPort> */
    val localPort: Int = 18080,
    /** 是否同时开启本地 SOCKS5（仅绑回环；备用接入方式） */
    val socks5Enabled: Boolean = false,
    /** 本地 SOCKS5 端口 */
    val socks5Port: Int = 1080,
) {
    /** 配置是否完整到可以启动组网 */
    val isComplete: Boolean
        get() = networkName.isNotBlank() &&
            networkSecret.isNotBlank() &&
            serverVirtualIp.isNotBlank()

    /** 端口转发规则对应的本地访问地址（应用侧「内网地址」填它） */
    val localBaseUrl: String get() = "http://127.0.0.1:$localPort"

    /** 是否已生成（前缀为 `http://`） */
    fun isValidUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * 生成 easytier-core 启动参数。
     *
     * 与官方参数一一对应（[配置选项](https://easytier.cn/guide/network/configurations.html)）；
     * 日志默认关闭（`--console-log-level off`），避免认证信息进入 logcat。
     */
    fun toArgs(): List<String> {
        val args = mutableListOf(
            "--no-tun",
            "--network-name", networkName,
            "--network-secret", networkSecret,
            "--console-log-level", "off",
        )
        if (virtualIpv4.isNotBlank()) {
            args += listOf("--ipv4", virtualIpv4)
        } else {
            args += "--dhcp"
        }
        peers.split(',', '，', '\n', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { args += listOf("--peers", it) }
        if (serverVirtualIp.isNotBlank()) {
            // 端口转发：127.0.0.1:localPort -> 虚拟网内 Subsonic（无 TUN 模式下的接入方式）
            args += listOf(
                "--port-forward",
                "tcp://127.0.0.1:$localPort/$serverVirtualIp:$serverPort",
            )
        }
        if (socks5Enabled) {
            args += listOf("--socks5", socks5Port.toString())
        }
        return args
    }
}
