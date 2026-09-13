/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_CloseDragRecognizer`：竖向拖拽收起识别器（28px 方向锁定、向下才接管、
 *    接管后双向跟手、松手按速度/比例收尾）；
 *  - `_buildMiddleContent` 中的 `onHorizontalDragUpdate` / `onHorizontalDragEnd`：
 *    封面 ↔ 详细歌词的左右拖拽切换；
 *  - `_buildPlaylistPage` 中 `NotificationListener<OverscrollNotification>`：
 *    播放列表滚到顶部后继续下滑 → 收起抽屉（这里用同一个竖向识别器 + 列表滚动位置判定）。
 *
 * 与 Dart 的实现差异（详见汇报）：
 *  - Dart 依赖手势竞技场（GestureArena）裁决方向；Compose 没有竞技场，这里用
 *    「高优先级 PointerEventPass.Initial + 方向裁决回调 canStart」等价实现：
 *    未裁决前绝不消费事件，因此内部可滚动区（歌词 / 播放列表）能正常滚动；
 *    只有「内容已在顶部且向下拖」时才接管并消费事件。
 *  - Dart 的 `_decided` 之后仍丢弃前 28px 行程（无死区补偿），这里保持一致。
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
     * @param totalDelta 本次手势沿轴向累积的位移（向下 / 向右为正）。
     * @return true 表示由本识别器接管；false 表示放弃本次手势，交还子级可滚动区域。
     */
    var canStart: (totalDelta: Float) -> Boolean = { true }

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
 * 竖向拖拽识别器。
 *
 * 使用 [PointerEventPass.Initial]（父节点先于子节点收到事件）以便与内部可滚动区域
 * 竞争：方向未裁决前不消费任何事件，子级滚动优先；裁决为「接管」后才消费。
 */
internal fun Modifier.nowPlayingVerticalDrag(
    lockThresholdPx: Float,
    callbacks: DragCallbacks,
): Modifier = pointerInput(callbacks) {
    awaitAxisDrag(
        vertical = true,
        lockThresholdPx = lockThresholdPx,
        pass = PointerEventPass.Initial,
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
): Modifier = pointerInput(callbacks) {
    awaitAxisDrag(
        vertical = false,
        lockThresholdPx = slopPx,
        pass = PointerEventPass.Main,
        callbacks = callbacks,
    )
}

/**
 * 单轴拖拽检测：累积位移超过 [lockThresholdPx] 后交由 [DragCallbacks.canStart] 裁决方向，
 * 接管后逐帧回调，松手时用 [VelocityTracker] 计算速度（对应 Dart 的
 * `DragEndDetails.velocity.pixelsPerSecond`）。
 */
private suspend fun PointerInputScope.awaitAxisDrag(
    vertical: Boolean,
    lockThresholdPx: Float,
    pass: PointerEventPass,
    callbacks: DragCallbacks,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)

        var total = 0f
        var decided = false
        var started = false

        while (true) {
            val event = awaitPointerEvent(pass)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)

            // 子级已经接管（如列表滑动）：放弃本次手势
            if (!decided && change.isConsumed) break

            val delta = if (vertical) change.positionChange().y else change.positionChange().x

            if (!decided && delta != 0f) {
                total += delta
                if (abs(total) >= lockThresholdPx) {
                    decided = true
                    if (callbacks.canStart(total)) {
                        started = true
                        callbacks.onStart()
                    } else {
                        // 方向不符 / 内部内容尚可滚动：交还子级
                        break
                    }
                }
            }

            if (started) {
                if (!change.pressed) break
                callbacks.onDrag(delta)
                change.consume()
            } else if (!change.pressed) {
                break
            }
        }

        if (started) {
            val velocity = tracker.calculateVelocity()
            callbacks.onEnd(if (vertical) velocity.y else velocity.x)
        } else {
            callbacks.onCancel()
        }
    }
}
