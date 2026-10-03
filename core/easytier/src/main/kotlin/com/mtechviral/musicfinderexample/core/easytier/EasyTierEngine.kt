package com.mtechviral.musicfinderexample.core.easytier

import android.content.Context
import android.util.Log
import com.mtechviral.musicfinderexample.core.common.UrlSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EasyTier 组网引擎（子进程托管，doc/EasyTier集成方案.md §4）。
 *
 * 以 `--no-tun` 模式运行随包分发的 `easytier-core`（`jniLibs/<abi>/libeasytier.so`，
 * 安装后位于 `nativeLibraryDir`，Android 10+ 允许执行该目录中的只读文件），
 * 通过 `--port-forward` 把虚拟网内 Subsonic 的端口转发到本地回环 ——
 * **不需要 ROOT、不需要 VpnService**。
 *
 * 省电策略（耗电优化）：
 * - **按需唤醒**：不在应用启动时拉起；仅"绑定组网的数据源"经 [awaitRunning] 唤醒；
 * - **空闲休眠**：无隧道租约且 [IDLE_STOP_MS] 无组网流量自动停止进程
 *   （状态 [State.Sleeping]），下次真实隧道访问自动唤醒；
 * - **保活按实际通道**（doc/耗电分析报告.md §4）：只有流量真正走隧道
 *   （[isTunnelUrl] 命中本地转发地址）才 [touch] / 持有 [acquireTunnelLease]；
 *   播放缓存曲、直连远程歌曲不再刷新活动时间；
 * - **省电运行参数**（[EasyTierConfig.powersaver]）：纯客户端（`--no-listener`）、
 *   按需 P2P（`--lazy-p2p`）、关闭对称 NAT 打洞（`--disable-sym-hole-punching`）。
 *
 * 与网络层解耦：本地转发地址通过 [onActiveBaseUrlChanged] 回调注入
 * （由应用启动时接线到 SubsonicService），本模块不依赖网络层。
 */
object EasyTierEngine {

    private const val TAG = "EasyTierEngine"
    private const val BINARY_NAME = "libeasytier.so"

    /** 空闲多久后自动休眠（无远程流量） */
    const val IDLE_STOP_MS = 15 * 60 * 1000L

    /** 空闲看门狗检查间隔 */
    private const val WATCHDOG_INTERVAL_MS = 30_000L

    /** 引擎运行状态 */
    sealed class State {
        /** 未启用 */
        object Disabled : State()

        /** 启动中 */
        object Starting : State()

        /** 运行中（detail 为本地转发映射或组网说明） */
        data class Running(val detail: String) : State()

        /** 已停止（手动断开） */
        object Stopped : State()

        /** 已休眠（省电：空闲自动停止，远程访问时自动唤醒） */
        object Sleeping : State()

        /** 异常（引擎缺失 / 进程退出 / 启动失败） */
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 本地转发地址变更回调（运行中且配置了端口转发时为 `http://127.0.0.1:<port>`，
     * 停止/休眠时为 null）。由应用层接线到 SubsonicService。
     */
    var onActiveBaseUrlChanged: ((String?) -> Unit)? = null

    /** 当前远程配置中的内网地址，由应用层注入；每次启动重新解析转发目标。 */
    var intranetUrlProvider: (() -> String?)? = null

    private var process: Process? = null
    private var startedConfig: EasyTierConfig? = null

    /** Suppress automatic starts until the new remote target has been committed. */
    private var configurationUpdating = false

    @Volatile
    private var appContext: Context? = null

    /** 最近一次远程活动时间（空闲看门狗依据） */
    @Volatile
    private var lastActivityAt = 0L

    /** 活跃的本地转发地址（如 `http://127.0.0.1:18080`）；未运行/未配置转发时为 null */
    @Volatile
    private var forwardPrefix: String? = null

    /**
     * 隧道实际使用中的租约数（活跃流请求持有）。
     * 持有期间看门狗不会休眠引擎 —— 长曲播放/下载经隧道传输不会被中途停止。
     */
    private val tunnelLeases = java.util.concurrent.atomic.AtomicInteger(0)

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 空闲看门狗任务：**仅在引擎运行期间存在**（不再常驻每 30 秒空转） */
    private var watchdogJob: Job? = null

    /** 应用启动时调用：保存上下文 */
    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /** 该 URL 是否经由本引擎的本地转发（即"实际使用隧道传输"） */
    fun isTunnelUrl(url: String): Boolean {
        val prefix = forwardPrefix ?: return false
        return url.startsWith(prefix)
    }

    /**
     * 引擎当前的本地转发地址（未运行/未配置转发时为 null）。
     * 供网络层在**数据源激活后重新注入**（组网可先于数据源配置）。
     */
    fun currentForwardBaseUrl(): String? = forwardPrefix

    /** Refresh the forwarding process only if EasyTier was enabled before this change. */
    suspend fun <T> withRemoteConfigurationUpdate(changed: Boolean, update: suspend () -> T): T {
        val ctx = appContext
        val enabled = changed && ctx != null && EasyTierConfigStore.load(ctx).enabled
        return updateTunnelConfiguration(
            enabled = enabled,
            changed = changed,
            stop = {
                withContext(Dispatchers.IO) {
                    synchronized(this@EasyTierEngine) {
                        configurationUpdating = true
                        stop()
                    }
                }
            },
            start = {
                val context = requireNotNull(ctx)
                val latestConfig = try {
                    EasyTierConfigStore.load(context)
                } finally {
                    synchronized(this@EasyTierEngine) { configurationUpdating = false }
                }
                // A user may have disabled the switch while the save was in progress.
                if (latestConfig.enabled) start(context, latestConfig)
            },
            update = update,
        )
    }

    /**
     * 申请隧道传输租约（流请求打开时调用）。
     *
     * 返回 null 表示当前没有活跃转发（未走隧道），调用方无须释放；
     * 持有期间引擎不会空闲休眠，[AutoCloseable.close] 释放并刷新活动时间。
     */
    fun acquireTunnelLease(): AutoCloseable? {
        if (forwardPrefix == null) return null
        tunnelLeases.incrementAndGet()
        touch()
        return AutoCloseable {
            touch()
            tunnelLeases.decrementAndGet()
        }
    }

    /**
     * 空闲看门狗：无租约且远程流量停止超过 [IDLE_STOP_MS] 自动停止引擎（省电）。
     * 随引擎启动，进程停止后自行退出 —— 引擎关闭期间不再有周期性检查。
     */
    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = engineScope.launch {
            while (process?.isAlive == true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (tunnelLeases.get() > 0) continue
                val idleFor = System.currentTimeMillis() - lastActivityAt
                if (idleFor <= IDLE_STOP_MS) continue
                synchronized(this@EasyTierEngine) {
                    if (process?.isAlive == true && tunnelLeases.get() == 0 &&
                        System.currentTimeMillis() - lastActivityAt > IDLE_STOP_MS
                    ) {
                        Log.i(TAG, "空闲 ${IDLE_STOP_MS / 60000} 分钟，组网引擎休眠（省电）")
                        stopInternal()
                        _state.value = State.Sleeping
                    }
                }
            }
        }
    }

    /** 记录一次**隧道**活动（阻止空闲休眠；仅真实隧道流量调用） */
    fun touch() {
        lastActivityAt = System.currentTimeMillis()
    }

    /**
     * 按需唤醒（非挂起）：已启用且未运行时拉起引擎。
     * 所有远程访问入口（SubsonicService）都会经过这里。
     */
    fun ensureStarted() {
        val ctx = appContext ?: return
        touch()
        synchronized(this) {
            if (configurationUpdating) return
            if (process?.isAlive == true) return
            if (_state.value is State.Starting) return
        }
        val cfg = runCatching { EasyTierConfigStore.load(ctx) }.getOrNull() ?: return
        if (!cfg.enabled) return
        engineScope.launch { start(ctx, cfg) }
    }

    /**
     * 等待引擎进入运行态（用于远程请求前的冷启动窗口）。
     * 未启用或超时返回 false，调用方按无组网回退处理。
     */
    suspend fun awaitRunning(timeoutMs: Long = 5000): Boolean {
        if (process?.isAlive == true) return true
        val ctx = appContext ?: return false
        val cfg = runCatching { EasyTierConfigStore.load(ctx) }.getOrNull() ?: return false
        if (!cfg.enabled) return false
        ensureStarted()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_state.value is State.Running) return true
            if (_state.value is State.Error) return false
            kotlinx.coroutines.delay(200)
        }
        return _state.value is State.Running
    }

    /** 引擎二进制是否已随包分发 */
    fun engineAvailable(context: Context): Boolean = binaryFile(context).exists()

    private fun binaryFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)

    /**
     * 启动组网（幂等）：已在运行且配置未变时直接复用。
     *
     * @return true 表示进程已拉起（真实连通性以远程配置页「测试连接」为准）
     */
    suspend fun start(context: Context, config: EasyTierConfig): Boolean =
        withContext(Dispatchers.IO) {
            synchronized(this@EasyTierEngine) { startLocked(context, config) }
        }

    /** 实际启动逻辑（持有 [EasyTierEngine] 锁调用；全程阻塞 IO，运行在 IO 调度器上） */
    private fun startLocked(context: Context, config: EasyTierConfig): Boolean {
        if (configurationUpdating) return false
        if (!config.isComplete) {
            _state.value = State.Error("配置不完整：网络名 / 网络密码 必填")
            return false
        }
        val target = config.resolveForwardTarget(intranetUrlProvider?.invoke().orEmpty())
        if (target == null) {
            _state.value = State.Error("请先在远程配置中填写有效的内网地址")
            return false
        }
        val effectiveConfig = config.copy(
            hostname = EasyTierConfig.DEVICE_NAME,
            serverVirtualIp = target.first,
            serverPort = target.second,
        )
        if (process?.isAlive == true && startedConfig == effectiveConfig) {
            return true
        }
        stopInternal()
        touch()

        val binary = binaryFile(context)
        if (!binary.exists()) {
            // 引擎不可执行：可能是打包时未包含 libeasytier.so，
            // 或 APK 未解压原生库（需 jniLibs.useLegacyPackaging = true）
            _state.value = State.Error(
                "EasyTier 引擎不可用：请确认 APK 已打包 libeasytier.so " +
                    "且以传统打包（useLegacyPackaging=true）安装",
            )
            return false
        }

        _state.value = State.Starting
        return try {
            val workDir = File(context.filesDir, "easytier").apply { mkdirs() }
            val logFile = File(workDir, "easytier.log")
            val command = mutableListOf(binary.absolutePath) + effectiveConfig.toArgs()
            val pb = ProcessBuilder(command)
                .directory(workDir)
                // 输出重定向到文件：避免管道写满阻塞子进程
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(true)
            val p = pb.start()
            process = p
            startedConfig = effectiveConfig

            // 等待片刻确认进程存活（启动即崩时收集日志尾部报错）
            Thread.sleep(500)
            if (!p.isAlive) {
                val tail = runCatching { logFile.readText().takeLast(400) }.getOrDefault("")
                _state.value = State.Error("EasyTier 启动失败：${tail.ifBlank { "未知原因" }}")
                stopInternal()
                return false
            }

            // 注入本地转发地址（仅配置了端口转发时）：
            // Subsonic 探测顺序变为 EasyTier → 内网 → 公网。
            // 无 TUN 模式下虚拟网地址只能经端口转发访问，直填虚拟 IP 不通
            val baseUrl = effectiveConfig.localBaseUrl
            forwardPrefix = baseUrl
            onActiveBaseUrlChanged?.invoke(baseUrl)
            _state.value = State.Running(
                effectiveConfig.localBaseUrl + " → ${target.first}:${target.second}",
            )
            // 空闲看门狗随引擎生命周期启停
            startWatchdog()
            Log.i(TAG, "EasyTier 已启动: ${effectiveConfig.localBaseUrl}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "EasyTier 启动异常: ${e.message}")
            _state.value = State.Error("启动异常：${e.message ?: "未知错误"}")
            stopInternal()
            false
        }
    }

    /** 停止组网（幂等） */
    @Synchronized
    fun stop() {
        stopInternal()
        _state.value = State.Stopped
    }

    /** 启用开关关闭时的入口：停止并回到 Disabled */
    @Synchronized
    fun disable() {
        stopInternal()
        _state.value = State.Disabled
    }

    private fun stopInternal() {
        onActiveBaseUrlChanged?.invoke(null)
        forwardPrefix = null
        startedConfig = null
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
            }
        }.onFailure { Log.w(TAG, "停止 EasyTier 失败: ${it.message}") }
    }

    /**
     * 健康检查：进程存活且本地转发端口可建立 TCP 连接。
     * 真实的服务器可达性由 Subsonic「测试连接」验证。
     */
    suspend fun healthy(): Boolean = withContext(Dispatchers.IO) {
        val p = process ?: return@withContext false
        if (!p.isAlive) return@withContext false
        val cfg = startedConfig ?: return@withContext false
        runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", cfg.localPort), 2000)
            }
        }.isSuccess
    }

    /** 读取引擎日志尾部（诊断用；输出经 [UrlSanitizer] 脱敏） */
    fun logTail(context: Context, maxChars: Int = 2000): String = runCatching {
        val logFile = File(File(context.filesDir, "easytier"), "easytier.log")
        UrlSanitizer.redact(logFile.readText().takeLast(maxChars))
    }.getOrDefault("")

    /**
     * 端到端检测转发通道（诊断用）：
     * 1) 本地回环端口是否监听；2) 经转发访问虚拟网目标是否有 HTTP 响应。
     * 用于区分「转发没通」与「Subsonic 服务/配置问题」。
     */
    suspend fun probeForward(config: EasyTierConfig): String = withContext(Dispatchers.IO) {
        val target = "${config.serverVirtualIp}:${config.serverPort}"
        if (!config.hasPortForward) return@withContext "未配置转发目标，无法检测"
        if (process?.isAlive != true) return@withContext "引擎未运行，请先「保存并连接」"

        val localUp = runCatching {
            java.net.Socket().use {
                it.connect(java.net.InetSocketAddress("127.0.0.1", config.localPort), 2000)
            }
        }.isSuccess
        if (!localUp) {
            return@withContext "本地端口 ${config.localPort} 未监听：转发未建立（请查看引擎日志）"
        }
        try {
            val url = java.net.URL("http://127.0.0.1:${config.localPort}/rest/ping")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            "转发通道正常：目标 $target 返回 HTTP $code（服务可达）"
        } catch (e: java.net.SocketTimeoutException) {
            "本地转发已监听，但 $target 无响应（超时）：" +
                "请确认目标 IP:端口 正确、目标节点在线、且服务监听 0.0.0.0"
        } catch (e: Exception) {
            "经转发访问 $target 失败：${e.message ?: e.javaClass.simpleName}"
        }
    }
}
