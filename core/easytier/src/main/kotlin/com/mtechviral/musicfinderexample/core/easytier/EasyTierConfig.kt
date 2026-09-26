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
    /** 本节点虚拟 IPv4（可选，对应 `-i/--ipv4`）；留空由网络分配（--dhcp）。
     *  已知可用配置（官方 App 对照）建议填固定地址，如 10.0.0.7（与服务器同网段） */
    val virtualIpv4: String = "",
    /** 本机设备名（可选，对应 `--hostname`）；留空由 EasyTier 取系统主机名 */
    val hostname: String = "",
    /**
     * 端口转发目标（虚拟网内 Subsonic 的 IP，如 10.0.0.222），可选。
     *
     * 无 TUN 模式下手机**无法主动访问**虚拟网内其他 IP（没有 TUN 网卡路由），
     * 必须用端口转发把「虚拟网内 Subsonic」映射到本地回环后访问。
     * 通常**留空自动取内网地址的主机**；仅当内网地址与实际转发目标不同才手动填。
     */
    val serverVirtualIp: String = "",
    /** 端口转发目标端口（虚拟网内 Subsonic 端口），留空时取内网地址的端口 */
    val serverPort: Int = 4533,
    /** 本地回环转发端口，应用侧「内网地址」= http://127.0.0.1:<localPort> */
    val localPort: Int = 18080,
    /** 是否同时开启本地 SOCKS5（仅绑回环；备用接入方式） */
    val socks5Enabled: Boolean = false,
    /** 本地 SOCKS5 端口 */
    val socks5Port: Int = 1080,
    /**
     * 省电模式（默认开启，耗电优化）：
     * 纯客户端（`--no-listener`，不接受入站连接）、按需 P2P（`--lazy-p2p`）、
     * 关闭对称 NAT 打洞（`--disable-sym-hole-punching`，该打洞流量大且持续）。
     * 配合引擎「空闲自动休眠」显著降低后台耗电。
     */
    val powersaver: Boolean = true,
) {
    /**
     * 配置是否完整到可以启动组网。
     *
     * 只要求网络名与网络密码：端口转发目标可留空（由内网地址推导），
     * 不填转发目标时仅组网不转发（此时请直接用虚拟网地址 + SOCKS5/转发访问）。
     */
    val isComplete: Boolean
        get() = networkName.isNotBlank() && networkSecret.isNotBlank()

    /** 是否配置了端口转发目标 */
    val hasPortForward: Boolean
        get() = serverVirtualIp.isNotBlank()

    /** 端口转发规则对应的本地访问地址（应用侧「内网地址」填它） */
    val localBaseUrl: String get() = "http://127.0.0.1:$localPort"

    /** 是否已生成（前缀为 `http://`） */
    fun isValidUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * 生成 easytier-core 启动参数。
     *
     * 与官方参数一一对应（[配置选项](https://easytier.cn/guide/network/configurations.html)）；
     * 日志级别 info 输出到引擎日志文件（诊断连接问题必需，内容不含 Subsonic 凭据，
     * 读取端仍经 UrlSanitizer 脱敏）。
     */
    fun toArgs(): List<String> {
        val args = mutableListOf(
            "--no-tun",
            "--network-name", networkName,
            "--network-secret", networkSecret,
            "--console-log-level", "info",
        )
        if (virtualIpv4.isNotBlank()) {
            args += listOf("--ipv4", virtualIpv4)
        } else {
            args += "--dhcp"
        }
        if (hostname.isNotBlank()) {
            args += listOf("--hostname", hostname)
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
        if (powersaver) {
            // 省电三件套：不接受入站 / 按需 P2P / 关闭对称 NAT 打洞
            args += "--no-listener"
            args += "--lazy-p2p"
            args += "--disable-sym-hole-punching"
        }
        return args
    }

    /**
     * 解析实际转发目标（主机、端口）：
     * - **端口始终取内网地址的端口**（不再单独设置，避免 IP/端口不一致）；
     * - 主机优先用「转发目标 IP」手动值，留空则取内网地址主机；
     * - 内网地址不可解析时，回退用手动主机 + 保存的端口。
     */
    fun resolveForwardTarget(intranetUrl: String): Pair<String, Int>? {
        val derived = parseHostPort(intranetUrl)
            ?: return if (serverVirtualIp.isBlank()) null else serverVirtualIp to serverPort
        val host = serverVirtualIp.ifBlank { derived.first }
        return host to derived.second
    }

    companion object {
        /**
         * 从内网/公网 URL 推导端口转发目标（主机、端口）。
         * 解析失败（非 http(s) URL / 无主机）返回 null。
         */
        fun parseHostPort(baseUrl: String): Pair<String, Int>? {
            return try {
                val trimmed = baseUrl.trim()
                val uri = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    java.net.URI(trimmed)
                } else {
                    null
                }
                val host = uri?.host?.takeIf { it.isNotEmpty() }
                if (uri == null || host == null) {
                    null
                } else {
                    val port = when {
                        uri.port != -1 -> uri.port
                        trimmed.startsWith("https://", ignoreCase = true) -> 443
                        else -> 80
                    }
                    host to port
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
