package com.mtechviral.musicfinderexample.core.easytier

import android.content.Context
import android.util.Log
import com.mtechviral.musicfinderexample.core.common.UrlSanitizer
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EasyTier 组网引擎（子进程托管，doc/EasyTier集成方案.md §4）。
 *
 * 以 `--no-tun` 模式运行随包分发的 `easytier-core`（`jniLibs/<abi>/libeasytier.so`，
 * 安装后位于 `nativeLibraryDir`，Android 10+ 允许执行该目录中的只读文件），
 * 通过 `--port-forward` 把虚拟网内 Subsonic 的端口转发到本地回环 ——
 * **不需要 ROOT、不需要 VpnService**，应用侧只需把「内网地址」指向
 * [EasyTierConfig.localBaseUrl]。
 *
 * 生命周期：
 * - [start] 拉起进程并把本地转发地址注入 [SubsonicService.easyTierBaseUrl]
 *   （探测顺序变为：EasyTier → 内网 → 公网）；
 * - [stop] 结束进程并移除注入；
 * - 进程意外退出时置为 [State.Error]（由界面提示，重新启用即重启）；
 * - 引擎未打包（缺少 libeasytier.so）时给出明确 [State.Error] 提示，
 *   现有内网/公网直连完全不受影响。
 */
object EasyTierEngine {

    private const val TAG = "EasyTierEngine"
    private const val BINARY_NAME = "libeasytier.so"

    /** 引擎运行状态 */
    sealed class State {
        /** 未启用 */
        object Disabled : State()

        /** 启动中 */
        object Starting : State()

        /** 运行中（localBaseUrl 为本地转发地址） */
        data class Running(val localBaseUrl: String) : State()

        /** 已停止 */
        object Stopped : State()

        /** 异常（引擎缺失 / 进程退出 / 启动失败） */
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Disabled)
    val state: StateFlow<State> = _state.asStateFlow()

    private var process: Process? = null
    private var startedConfig: EasyTierConfig? = null

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
        if (!config.isComplete) {
            _state.value = State.Error("配置不完整：网络名 / 网络密码 / 服务器虚拟 IP 必填")
            return false
        }
        if (process?.isAlive == true && startedConfig == config) {
            return true
        }
        stopInternal()

        val binary = binaryFile(context)
        if (!binary.exists()) {
            // 引擎未打包：不影响现有内网/公网直连，仅提示构建方式
            _state.value = State.Error(
                "EasyTier 引擎未打包：请运行 tools/build_easytier.ps1 后重新打包",
            )
            return false
        }

        _state.value = State.Starting
        return try {
            val workDir = File(context.filesDir, "easytier").apply { mkdirs() }
            val logFile = File(workDir, "easytier.log")
            val command = mutableListOf(binary.absolutePath) + config.toArgs()
            val pb = ProcessBuilder(command)
                .directory(workDir)
                // 输出重定向到文件：避免管道写满阻塞子进程
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectErrorStream(true)
            val p = pb.start()
            process = p
            startedConfig = config

            // 等待片刻确认进程存活（启动即崩时收集日志尾部报错）
            Thread.sleep(500)
            if (!p.isAlive) {
                val tail = runCatching { logFile.readText().takeLast(400) }.getOrDefault("")
                _state.value = State.Error("EasyTier 启动失败：${tail.ifBlank { "未知原因" }}")
                stopInternal()
                return false
            }

            // 注入本地转发地址：Subsonic 探测顺序变为 EasyTier → 内网 → 公网
            SubsonicService.easyTierBaseUrl = config.localBaseUrl
            SubsonicService.resetConnection()
            _state.value = State.Running(config.localBaseUrl)
            Log.i(TAG, "EasyTier 已启动: ${config.localBaseUrl}")
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
        SubsonicService.easyTierBaseUrl = null
        SubsonicService.resetConnection()
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
}
