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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import kotlinx.coroutines.flow.collect
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

    // 用 derivedStateOf 只在「跨过 50%」时触发一次重组。
    // 直接写 `sidebarProgress.value > 0.5f` 会在拖动/补间期间每帧重组整页
    // （含 LazyColumn 里的所有可见行），这是侧边栏滑动明显卡顿的主因。
    val isOpen by remember { derivedStateOf { sidebarProgress.value > 0.5f } }

    // 侧边栏是否正在被手指拖动（拖动期间即使越过 50% 也要继续跟手，见下方说明）
    var sidebarDragging by remember { mutableStateOf(false) }

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

    /**
     * 松手结算：只要还有"右滑趋势"（velocity > 0）就继续滑到完全显示（需求 3），
     * 反之有左滑趋势就收回；速度很小时按当前位置就近判定。
     */
    fun settleSidebar(velocity: Float) {
        val keepOpening = velocity > SIDEBAR_SETTLE_VELOCITY ||
            (velocity > -SIDEBAR_SETTLE_VELOCITY && sidebarProgress.value > SIDEBAR_OPEN_RATIO)
        if (keepOpening) openSidebar() else closeSidebar()
    }

    fun dragSidebar(deltaXPx: Float) {
        val next = (sidebarProgress.value + deltaXPx / sidebarWidthPx).coerceIn(0f, 1f)
        scope.launch { sidebarProgress.snapTo(next) }
    }

    val saveableStateHolder = rememberSaveableStateHolder()

    // 播放页展开进度（0=收在底部，1=完全展开）。
    // 镜像成 Compose 状态，并且**只在下方的 graphicsLayer 里读取**（延迟读取），
    // 这样 300ms 展开/收起补间期间不会每帧重组整页。
    val nowPlayingProgressState = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        NowPlayingUiState.progress.collect { nowPlayingProgressState.floatValue = it }
    }

    // 供内部有横向滚动区的一级页面把「滚到边界后的剩余横向位移」转交进来
    // （最初用于「喜欢」页三 Tab 分页器；该页改为单个歌曲列表后当前无调用方）
    val sidebarDragHandle = remember(sidebarWidthPx) {
        SidebarDragHandle(
            dragBy = { dx -> dragSidebar(dx) },
            settle = { v -> settleSidebar(v) },
        )
    }

    CompositionLocalProvider(
        LocalOpenSidebar provides { if (isOpen) closeSidebar() else openSidebar() },
        LocalSelectPage provides { index ->
            pageIndex = index
            closeSidebar()
        },
        LocalSidebarDrag provides sidebarDragHandle,
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 后半段（0.5~1）才上移淡出，避免播放页刚露出时主页过早露底
                    val liftT = ((nowPlayingProgressState.floatValue - 0.5f) / 0.5f)
                        .coerceIn(0f, 1f)
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
                    }
                    // 需求：在侧边栏**内部**左划也要能收起侧边栏，与「在歌曲页面内左划」
                    // 完全一致。原先只有主页 Box 里的拦截层处理左滑，而该拦截层位于
                    // 主页 Box 之内（整个 Box 已被 translationX 推到右半屏），因此只覆盖
                    // 右半边；侧边栏自己所在的左半屏没有任何手势处理 —— 左划毫无反应。
                    // 这里补上同款横向 draggable（与下面拦截层写法一致）：
                    // 左划 → settleSidebar 判为"收回"，右划 → 回到展开态。
                    // 纵向滑动不受影响（Orientation.Horizontal 不参与纵向手势竞争），
                    // 侧边栏内的 LazyColumn 仍可正常滚动、条目仍可点击。
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta -> dragSidebar(delta) },
                        onDragStopped = { velocity -> settleSidebar(velocity) },
                    ),
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
                        // 拖动期间持续跟手：原先以 `!isOpen`（>50%）为条件，
                        // 越过一半后 delta 被丢弃、松手也不再结算，于是侧边栏卡在中间。
                        state = rememberDraggableState { delta ->
                            if (sidebarDragging || !isOpen) dragSidebar(delta)
                        },
                        onDragStarted = { sidebarDragging = true },
                        onDragStopped = { velocity ->
                            sidebarDragging = false
                            settleSidebar(velocity)
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
                                state = rememberDraggableState { delta -> dragSidebar(delta) },
                                onDragStarted = { sidebarDragging = true },
                                onDragStopped = { velocity ->
                                    sidebarDragging = false
                                    settleSidebar(velocity)
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

/** 松手时判定"有滑动趋势"的速度阈值（px/s）：超过即按趋势继续滑动，不再看位移 */
private const val SIDEBAR_SETTLE_VELOCITY = 220f

/** 速度很小时的"就近判定"比例：超过即展开，否则收回 */
private const val SIDEBAR_OPEN_RATIO = 0.32f

/** 播放页展开时主页上移的距离（与 Dart `_liftDistance` 一致） */
private val LIFT_DISTANCE = 120.dp
