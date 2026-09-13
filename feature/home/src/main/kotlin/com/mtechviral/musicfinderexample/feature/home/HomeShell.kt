package com.mtechviral.musicfinderexample.feature.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import kotlinx.coroutines.launch

/**
 * 一级页面导航外壳：拼接式侧边栏。
 *
 * 对应原 Flutter 工程 `lib/widgets/mp_nav_scaffold.dart` 的 `MPNavScaffold`：
 * - 侧边栏与主页横向拼接为一块画布：关闭时主页铺满屏幕、侧边栏藏在左外侧；
 *   在主页上向右滑动或点击顶栏目录按钮，画布整体右移，左侧露出屏幕宽度 50% 的侧边栏；
 * - 九个一级页面保活切换（`rememberSaveableStateHolder` 保留各页滚动位置，
 *   等价 Dart 的 `IndexedStack`）；
 * - 进入「正在播放」覆盖层时，整块导航区随展开进度上移 120dp 并淡出
 *   （进度后半段 0.5~1 才生效，与 Dart 完全一致）。
 *
 * @param content 按一级页面下标渲染页面内容（由 `:app` 注入，避免 feature 之间循环依赖）
 */
@Composable
fun HomeShell(
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit,
) {
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val sidebarProgress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    // 侧边栏宽度为屏幕宽度的 50%
    val sidebarWidth = screenWidth * 0.5f
    val sidebarWidthPx = with(density) { sidebarWidth.toPx() }
    val liftDistancePx = with(density) { LIFT_DISTANCE.toPx() }

    val isOpen = sidebarProgress.value > 0.5f

    fun openSidebar() {
        scope.launch {
            sidebarProgress.animateTo(1f, tween(SIDEBAR_ANIM_MS, easing = EaseOutCubic))
        }
    }

    fun closeSidebar() {
        scope.launch {
            sidebarProgress.animateTo(0f, tween(SIDEBAR_ANIM_MS, easing = EaseOutCubic))
        }
    }

    val saveableStateHolder = rememberSaveableStateHolder()

    // 播放页展开进度（0=收在底部，1=完全展开）
    val nowPlayingProgress by NowPlayingUiState.progress.collectAsStateWithLifecycle()
    // 后半段（0.5~1）才上移淡出，避免播放页刚露出时主页过早露底
    val liftT = ((nowPlayingProgress - 0.5f) / 0.5f).coerceIn(0f, 1f)

    CompositionLocalProvider(
        LocalOpenSidebar provides { if (isOpen) closeSidebar() else openSidebar() },
        LocalSelectPage provides { index ->
            pageIndex = index
            closeSidebar()
        },
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = -liftDistancePx * liftT
                    alpha = 1f - liftT
                },
        ) {
            // ---- 侧边栏（画布左侧，关闭时整体藏在屏幕左外侧） ----
            Sidebar(
                currentIndex = pageIndex,
                onSelect = { index ->
                    pageIndex = index
                    closeSidebar()
                },
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
                    .graphicsLayer {
                        translationX = sidebarWidthPx * (sidebarProgress.value - 1f)
                    },
            )

            // ---- 主页（画布右侧，保持屏幕宽度，随画布整体平移） ----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = sidebarWidthPx * sidebarProgress.value
                    }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        // 仅在关闭状态下接管手势：右滑打开侧边栏
                        state = rememberDraggableState { delta ->
                            if (!isOpen) {
                                val next = (sidebarProgress.value + delta / sidebarWidthPx)
                                    .coerceIn(0f, 1f)
                                scope.launch { sidebarProgress.snapTo(next) }
                            }
                        },
                        onDragStopped = { velocity ->
                            if (isOpen) return@draggable
                            // 与原实现一致：速度 > 500 或位移超过 40% 即展开，否则收回
                            if (velocity > DRAG_VELOCITY || sidebarProgress.value > DRAG_THRESHOLD) {
                                openSidebar()
                            } else {
                                closeSidebar()
                            }
                        },
                    ),
            ) {
                saveableStateHolder.SaveableStateProvider(pageIndex) {
                    content(pageIndex)
                }

                // 侧边栏打开时覆盖一层拦截层：左滑或点击关闭侧边栏
                // （等价 Dart 中"侧边栏打开时用 8px 小阈值急切接管左滑"的行为）
                if (isOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .draggable(
                                orientation = Orientation.Horizontal,
                                state = rememberDraggableState { delta ->
                                    val next = (sidebarProgress.value + delta / sidebarWidthPx)
                                        .coerceIn(0f, 1f)
                                    scope.launch { sidebarProgress.snapTo(next) }
                                },
                                onDragStopped = { velocity ->
                                    if (velocity < -DRAG_VELOCITY ||
                                        sidebarProgress.value < DRAG_THRESHOLD
                                    ) {
                                        closeSidebar()
                                    } else {
                                        openSidebar()
                                    }
                                },
                            )
                            .pointerInput(Unit) {
                                detectTapGestures { closeSidebar() }
                            },
                    )
                }
            }
        }
    }
}

private const val SIDEBAR_ANIM_MS = 260
private const val DRAG_VELOCITY = 500f
private const val DRAG_THRESHOLD = 0.4f

/** 播放页展开时主页上移的距离（与 Dart `_liftDistance` 一致） */
private val LIFT_DISTANCE = 120.dp
