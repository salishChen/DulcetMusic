package com.mtechviral.musicfinderexample.core.easytier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EasyTierConfig] 启动参数生成回归测试（doc/EasyTier集成方案.md §4.2）：
 * 参数必须与 easytier-core 官方配置项一致，端口转发规则格式正确。
 */
class EasyTierConfigTest {

    private val base = EasyTierConfig(
        enabled = true,
        networkName = "my-net",
        networkSecret = "secret",
        serverVirtualIp = "10.144.144.2",
        serverPort = 4533,
        localPort = 18080,
    )

    @Test
    fun `no-tun mode with port-forward rule`() {
        val args = base.toArgs()
        assertTrue(args.contains("--no-tun"))
        assertTrue(args.contains("--network-name"))
        assertTrue(args.contains("my-net"))
        assertTrue(args.contains("--network-secret"))
        val idx = args.indexOf("--port-forward")
        assertTrue(idx >= 0)
        // 端口转发规则：tcp://127.0.0.1:<local>/<服务器虚拟IP>:<端口>
        assertEquals("tcp://127.0.0.1:18080/10.144.144.2:4533", args[idx + 1])
        // 未指定虚拟 IP 时由网络分配
        assertTrue(args.contains("--dhcp"))
        assertFalse(args.contains("--socks5"))
    }

    @Test
    fun `fixed virtual ip replaces dhcp`() {
        val args = base.copy(virtualIpv4 = "10.144.144.5").toArgs()
        assertTrue(args.contains("--ipv4"))
        assertTrue(args.contains("10.144.144.5"))
        assertFalse(args.contains("--dhcp"))
    }

    @Test
    fun `multiple peers are split`() {
        val args = base.copy(peers = "udp://a:11010, tcp://b:11010").toArgs()
        val peerIdxs = args.withIndex().filter { it.value == "--peers" }.map { it.index }
        assertEquals(2, peerIdxs.size)
        assertEquals("udp://a:11010", args[peerIdxs[0] + 1])
        assertEquals("tcp://b:11010", args[peerIdxs[1] + 1])
    }

    @Test
    fun `socks5 optional and logs enabled for diagnostics`() {
        val args = base.copy(socks5Enabled = true, socks5Port = 1080).toArgs()
        assertTrue(args.contains("--socks5"))
        assertTrue(args.contains("1080"))
        // 日志级别 info 输出到引擎日志文件（排查连接问题必需；读取端脱敏）
        val logIdx = args.indexOf("--console-log-level")
        assertTrue(logIdx >= 0)
        assertEquals("info", args[logIdx + 1])
    }

    @Test
    fun `fixed virtual ip and forced hostname produce args`() {
        val args = base.copy(virtualIpv4 = "10.0.0.7", hostname = "my-phone").toArgs()
        val ipIdx = args.indexOf("--ipv4")
        assertEquals("10.0.0.7", args[ipIdx + 1])
        val hostIdx = args.indexOf("--hostname")
        assertEquals("DulcetMusic", args[hostIdx + 1])
    }

    @Test
    fun `completeness only requires network name and secret`() {
        assertFalse(base.copy(networkName = "").isComplete)
        assertFalse(base.copy(networkSecret = "").isComplete)
        // 转发目标可留空（由内网地址推导），不再是必填
        assertTrue(base.copy(serverVirtualIp = "").isComplete)
        assertFalse(base.copy(serverVirtualIp = "").hasPortForward)
        assertTrue(base.hasPortForward)
        assertEquals("http://127.0.0.1:18080", base.localBaseUrl)
    }

    @Test
    fun `forward target derived from intranet url`() {
        assertEquals("10.0.0.222" to 8002, EasyTierConfig.parseHostPort("http://10.0.0.222:8002"))
        assertEquals("10.0.0.222" to 80, EasyTierConfig.parseHostPort("http://10.0.0.222"))
        assertEquals("music.example.com" to 443, EasyTierConfig.parseHostPort("https://music.example.com"))
        assertEquals(null, EasyTierConfig.parseHostPort("10.0.0.222:8002"))
    }

    @Test
    fun `forward target always follows intranet url`() {
        // 旧配置的手动目标不能覆盖远程配置中的内网地址。
        assertEquals(
            "10.0.0.222" to 8002,
            base.copy(serverVirtualIp = "", serverPort = 4533)
                .resolveForwardTarget("http://10.0.0.222:8002"),
        )
        // 即使保存过其他目标，主机和端口也从内网地址读取。
        assertEquals(
            "10.0.0.222" to 8002,
            base.copy(serverVirtualIp = "10.0.0.9", serverPort = 4533)
                .resolveForwardTarget("http://10.0.0.222:8002"),
        )
        // 未配置内网地址时不能继续使用旧的目标。
        assertEquals(null, base.resolveForwardTarget(""))
        assertEquals(
            null,
            base.copy(serverVirtualIp = "", serverPort = 4533).resolveForwardTarget(""),
        )
    }

    @Test
    fun `powersaver adds battery saving flags`() {
        val args = base.copy(powersaver = true).toArgs()
        assertTrue(args.contains("--no-listener"))
        assertTrue(args.contains("--lazy-p2p"))
        assertTrue(args.contains("--disable-sym-hole-punching"))
        // 关闭省电时不加这些参数
        val off = base.copy(powersaver = false).toArgs()
        assertFalse(off.contains("--no-listener"))
        assertFalse(off.contains("--lazy-p2p"))
        assertFalse(off.contains("--disable-sym-hole-punching"))
    }
}
