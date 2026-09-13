/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_CloseDragRecognizer`：竖向拖拽收起识别器（28px 方向锁定、向下才接管、
 *    接管后双向跟手、松手按速度/比例收尾）；
 *  - `_buildMiddleContent` 中的 `onHorizontalDragUpdate` / `onHorizontalDragEnd`：
 *    封面 ↔ 详细歌词的左右拖拽切换；
 *  - `_buildPlaylistPage` 中 `NotificationListener<OverscrollNotification>`：
 *    播放列表滚到顶部后继续下滑 → 返回播放页（这里用同一个竖向识别器 + 列表滚动位置判定）。
 *
 * 与 Dart 的实现差异（详见汇报）：
 *  - Dart 依赖手势竞技场（GestureArena）裁决方向；Compose 没有竞技场，这里用
 *    「高优先级 PointerEventPass.Initial + 方向裁决回调 canStart」等价实现：
 *    未裁决前绝不消费事件，因此内部可滚动区（歌词 / 播放列表）能正常滚动；
 *    只有「内容已在顶部且向下拖」时才接管并消费事件。
 *  - 额外加入**方向锁定**（需求）：横竖两个识别器共用一个 [DragAxisLock]，
 *    并各自要求"本轴位移明显大于另一轴"（[AXIS_DOMINANCE] 余量）才接管，
 *    避免「左划进歌词页的途中向下划被竖向手势抢走并退出播放页」。
 *  - 接管瞬间把锁定前累积的行程一次性补发（死区补偿），页面/进度立刻跟手。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** 轴向判定余量：本轴位移需大于另一轴位移的该倍数，方向才成立（留出"余量"） */
internal const val AXIS_DOMINANCE = 1.25f

/**
 * 拖拽回调集合。
 *
 * 实例由 `remember` 持有（永不变化），因此 [pointerInput] 不会因为闭包变化而重启；
 * 字段在每次组合结束后由 `SideEffect` 刷新，保证手势回调拿到最新的状态与尺寸。
 */
internal class DragCallbacks {

    /**
     * 方向裁决（Dart `_CloseDragRecognizer.handleEvent` 中的 `resolve(accepted/rejected)`）。
     *
     * @param total 本识别器所在轴向累积的位移（向下 / 向右为正）。
     * @param other 另一轴向累积的位移（用于方向占优判定）。
     * @return true 表示由本识别器接管；false 表示放弃本次手势，交还子级可滚动区域。
     */
    var canStart: (total: Float, other: Float) -> Boolean = { _, _ -> true }

    /** 接管成功、正式进入拖动 */
    var onStart: () -> Unit = {}

    /** 拖动中的增量（像素，沿轴向） */
    var onDrag: (delta: Float) -> Unit = {}

    /** 松手，带速度（像素/秒，沿轴向） */
    var onEnd: (velocity: Float) -> Unit = {}

    /** 手势未接管即结束（点击、被消费等） */
    var onCancel: () -> Unit = {}
}

/**
 * 手势方向锁：单指手势一旦被某个轴向接管，另一轴向不再抢占（需求：方向锁定）。
 *
 * 横竖两个识别器共用同一个实例，从而在「横向左划进歌词」与「竖向翻页 / 收起播放页」
 * 之间形成真正的互斥，取代 Dart 端手势竞技场的作用。
 */
internal class DragAxisLock {

    private var owner: Int = AXIS_NONE

    /** 尝试占用指定轴向；已被另一轴向占用时返回 false */
    fun claim(axis: Int): Boolean {
        if (owner != AXIS_NONE && owner != axis) return false
        owner = axis
        return true
    }

    /** 释放指定轴向的占用（仅当确实是自己占用时） */
    fun release(axis: Int) {
        if (owner == axis) owner = AXIS_NONE
    }

    companion object {
        const val AXIS_NONE = 0
        const val AXIS_HORIZONTAL = 1
        const val AXIS_VERTICAL = 2
    }
}

/**
 * 竖向拖拽识别器。
 *
 * 使用 [PointerEventPass.Initial]（父节点先于子节点收到事件）以便与内部可滚动区域
 * 竞争：方向未裁决前不消费任何事件，子级滚动优先；裁决为「接管」后才消费。
 */
internal fun Modifier.nowPlayingVerticalDrag(
    lockThresholdPx: Float,
    callbacks: DragCallbacks,
    axisLock: DragAxisLock,
): Modifier = pointerInput(callbacks, axisLock) {
    awaitAxisDrag(
        vertical = true,
        axis = DragAxisLock.AXIS_VERTICAL,
        lockThresholdPx = lockThresholdPx,
        pass = PointerEventPass.Initial,
        axisLock = axisLock,
        callbacks = callbacks,
    )
}

/**
 * 横向拖拽识别器（封面 / 歌词切换）。
 *
 * 使用 [PointerEventPass.Main]：中间内容区没有横向可滚动子级，无需抢占。
 */
internal fun Modifier.nowPlayingHorizontalDrag(
    slopPx: Float,
    callbacks: DragCallbacks,
    axisLock: DragAxisLock,
): Modifier = pointerInput(callbacks, axisLock) {
    awaitAxisDrag(
        vertical = false,
        axis = DragAxisLock.AXIS_HORIZONTAL,
        lockThresholdPx = slopPx,
        pass = PointerEventPass.Main,
        axisLock = axisLock,
        callbacks = callbacks,
    )
}

/**
 * 单轴拖拽检测：累积位移超过 [lockThresholdPx] 后交由 [DragCallbacks.canStart] 裁决方向，
 * 接管后逐帧回调，松手时用 [VelocityTracker] 计算速度（对应 Dart 的
 * `DragEndDetails.velocity.pixelsPerSecond`）。
 *
 * 两个轴向的位移都会被累积，交给 [DragCallbacks.canStart] 做「方向占优」判定；
 * 接管成功时会把锁定前累积的行程一次性补发（消除死区，跟手无延迟）。
 */
private suspend fun PointerInputScope.awaitAxisDrag(
    vertical: Boolean,
    axis: Int,
    lockThresholdPx: Float,
    pass: PointerEventPass,
    axisLock: DragAxisLock,
    callbacks: DragCallbacks,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        var totalAxis = 0f
        var totalOther = 0f
        var decided = false
        var started = false

        while (true) {
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            // 子级已经接管（如列表滑动）：放弃本次手势
            if (!decided && change.isConsumed) break

            val dx = change.positionChange().x
            val dy = change.positionChange().y
            tracker.addPosition(change.uptimeMillis, change.position)

            val delta = if (vertical) dy else dx
            val deltaOther = if (vertical) dx else dy

            if (!decided) {
                totalAxis += delta
                totalOther += deltaOther
                if (abs(totalAxis) < lockThresholdPx) {
                    if (!change.pressed) break
                    continue
                }
                // 达到轴向阈值：先抢方向锁，再交给业务裁决
                decided = true
                val owned = axisLock.claim(axis)
                if (!owned || !callbacks.canStart(totalAxis, totalOther)) {
                    if (owned) axisLock.release(axis)
                    break
                }
                started = true
                callbacks.onStart()
                // 死区补偿：锁定前累积的行程一次性补发，立刻跟手
                callbacks.onDrag(totalAxis)
                change.consume()
                if (!change.pressed) break
                continue
            }

            if (!change.pressed) break
            callbacks.onDrag(delta)
            change.consume()
        }

        if (started) {
            val velocity = tracker.calculateVelocity()
            callbacks.onEnd(if (vertical) velocity.y else velocity.x)
            axisLock.release(axis)
        } else {
            callbacks.onCancel()
        }
    }
}
