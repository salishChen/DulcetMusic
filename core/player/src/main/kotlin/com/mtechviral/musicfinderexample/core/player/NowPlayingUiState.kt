package com.mtechviral.musicfinderexample.core.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 「正在播放」页的展开状态。
 *
 * 对应原 Flutter 工程 `lib/data/audio_handler.dart` 中的两个全局对象：
 * - `nowPlayingOpen`（ValueNotifier<bool>）：是否处于播放页（用于隐藏常驻播放栏）；
 * - `nowPlayingController`（AnimationController 0..1）：播放页展开进度。
 *
 * 原生端合并为单一进度 [progress]（0 = 收在屏幕底部，1 = 完全展开），
 * 播放栏淡化、主页上移淡出、播放页偏移全部绑定到它，天然同步；
 * [isOpen] 以 0.5 为阈值（与 Flutter 端 `_controller.value > 0.5` 判定一致）。
 *
 * 动画语义与 Dart 的 `AnimationController.animateTo(value, duration: 300ms, curve: easeOutCubic)`
 * 保持一致：
 * - [setProgress]（手势跟手）立即打断补间并直接赋值——手指停即停；
 * - [open] / [close] 走 300ms easeOutCubic 补间（等价 `nowPlayingController.animateTo`）；
 * - 补间过程中再次调用 [setProgress] 可被下一次跟手即时接管。
 */
object NowPlayingUiState {

    /** 展开/收起补间时长（与 Dart `nowPlayingController` 的 300ms 一致） */
    private const val ANIM_DURATION_MS = 300L

    /** 补间帧间隔（约 60fps） */
    private const val FRAME_INTERVAL_MS = 16L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var animJob: Job? = null

    private val _progress = MutableStateFlow(0f)

    /** 展开进度 0..1 */
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _isOpen = MutableStateFlow(false)

    /** 是否已展开（进度 > 0.5） */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    val currentProgress: Float get() = _progress.value

    /** 手势跟手时直接赋值（不做补间，并打断正在进行的补间） */
    fun setProgress(value: Float) {
        animJob?.cancel()
        animJob = null
        apply(value)
    }

    fun open() = animateTo(1f)

    fun close() = animateTo(0f)

    fun toggle() {
        if (_isOpen.value) close() else open()
    }

    private fun apply(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _progress.value = clamped
        _isOpen.value = clamped > 0.5f
    }

    /** 300ms easeOutCubic 补间（等价 Dart `animateTo(..., curve: Curves.easeOutCubic)`） */
    private fun animateTo(target: Float) {
        animJob?.cancel()
        animJob = scope.launch {
            val start = _progress.value
            if (start == target) {
                apply(target)
                return@launch
            }
            val startNanos = System.nanoTime()
            while (isActive) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
                val t = (elapsedMs / ANIM_DURATION_MS).toFloat().coerceIn(0f, 1f)
                // Curves.easeOutCubic: 1 - (1 - t)^3
                val eased = 1f - (1f - t) * (1f - t) * (1f - t)
                apply(start + (target - start) * eased)
                if (t >= 1f) break
                delay(FRAME_INTERVAL_MS)
            }
            apply(target)
        }
    }
}
