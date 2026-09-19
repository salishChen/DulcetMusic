package com.mtechviral.musicfinderexample.core.designsystem.component

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandCyan
import com.mtechviral.musicfinderexample.core.designsystem.theme.BrandPurple
import com.mtechviral.musicfinderexample.core.designsystem.theme.ytTextSecondary
import com.mtechviral.musicfinderexample.core.player.NowPlayingUiState
import com.mtechviral.musicfinderexample.core.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 底部常驻迷你播放栏。
 *
 * 对应 Dart 原文件 `mp_mini_player_bar.dart`
 * （参考实现 `.flutter_reference/lib/widgets/mp_mini_player_bar.dart`）。
 *
 * 与 Dart 版本的逐条对应关系：
 * | Dart (mp_mini_player_bar.dart)                                   | 原生实现                                        |
 * |------------------------------------------------------------------|-------------------------------------------------|
 * | `audioHandler.currentSong` / `isPlaying` 监听刷新                  | `PlayerController.currentSong` / `isPlaying`     |
 * | `nowPlayingController`（AnimationController 0..1）                 | `NowPlayingUiState.progress`                     |
 * | `nowPlayingController.value = x`（跟手）                           | `NowPlayingUiState.setProgress(x)`               |
 * | `animateTo(1.0)` / `value = 0.0`（松手补间）                       | `NowPlayingUiState.open()` / `close()`           |
 * | `onTap: _openNowPlaying`                                          | `onOpenNowPlaying()`                             |
 * | `audioHandler.skipToNext/Previous()`（左滑下一曲 / 右滑上一曲）      | `PlayerController.skipToNext()` / `skipToPrevious()` |
 * | `audioHandler.pause()/resumeOrPlay()`                             | `PlayerController.togglePlayPause()`             |
 * | `_effectiveDragDistance = screenHeight * 0.8`                     | 同公式（屏幕高度 dp 的 80% 换算为 px）              |
 * | `Opacity(contentOpacity)` + 高度折叠                              | `graphicsLayer { alpha; translationY }`          |
 *
 * 手势策略（对应 Dart 的"单个 Pan 识别器 + 先判定主方向"）：
 * Compose 中 `detectVerticalDragGestures` 与 `detectHorizontalDragGestures`
 * 不能共存于一个 `pointerInput`，因此这里用 `awaitPointerEventScope`（`awaitEachGesture`）
 * 自定义手势循环：累计位移超过 40dp（等价 Flutter 端全局 `touchSlop = 40`，见
 * `.flutter_reference/lib/main.dart` 注释）后锁定主方向，再分派给
 * 「竖向展开播放页」或「横向切歌」两条分支，单指手势全程只走一条路径。
 *
 * 沉浸式（系统手势导航条）：栏体背景一直铺到屏幕最底边（`navigationBarsPadding`
 * 只把可交互内容留在导航条上方），因此不会再出现「播放栏浮在小黑条上方」的断层。
 *
 * @param onOpenNowPlaying 点击栏体 / 封面时打开「正在播放」页
 * @param hideWhenEmpty 当前歌曲为空时是否不渲染任何内容（默认 true）
 *
 * 需求 5（清空队列后播放栏不消失）**不依赖本参数**：清空队列时
 * `PlayerController` 会把 `currentSong` 置为[Song.placeholder]（非 null），
 * 因此播放栏照常渲染，只是内容变为占位展示。保持默认 true 以免改变冷启动
 * （尚未播放任何歌曲）时"不显示播放栏"的既有行为。
 */
@Composable
fun MiniPlayerBar(
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
    hideWhenEmpty: Boolean = true,
) {
    val song by PlayerController.currentSong.collectAsStateWithLifecycle()

    // 无当前歌曲且要求自动隐藏：不渲染任何内容（对应 Dart 的 `SizedBox.shrink()`）
    if (song == null && hideWhenEmpty) return

    val isPlaying by PlayerController.isPlaying.collectAsStateWithLifecycle()
    val progress by NowPlayingUiState.progress.collectAsStateWithLifecycle()

    val track = song
    // 第二十二轮需求 5：清空队列后 currentSong 为占位曲目 —— 播放栏继续显示
    // （歌名「愉乐~愉悦~」/ 歌手「Hi~」），但禁止左右滑动切歌、上划仍可进播放列表。
    val isPlaceholder = track?.isPlaceholder == true
    val hasSong = track != null && !isPlaceholder

    val scope = rememberCoroutineScope()
    // 栏内内容水平偏移（左划为负、右划为正，Dart `_barOffset`）。
    // 注意：pointerInput / awaitPointerEventScope 属于受限挂起作用域，
    // 其中不能直接调用 Animatable.snapTo 等挂起函数，因此跟手期间只做状态赋值，
    // 弹回动画统一交给 scope.launch { animate(...) } 完成。
    val dragOffset = remember { mutableFloatStateOf(0f) }
    // 进行中的弹回动画（新手势开始时取消，对应 Dart 停止并释放 _barSnapController）
    val snapJob = remember { mutableStateOf<Job?>(null) }
    // 手势在 pointerInput 中被长时间捕获，回调需取最新引用
    val openNowPlaying by rememberUpdatedState(onOpenNowPlaying)

    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.toFloat().coerceAtLeast(1f)
    val screenHeightPx = with(density) { screenHeightDp.dp.toPx() }

    // Dart：`fadeDist = (barH * 2 / screenH).clamp(0.05, 0.5)`
    // 播放页滑到距底部约 2 个播放栏高度（160dp）时栏体完全透明。
    val fadeDist = (BarHeightDp * 2f / screenHeightDp).coerceIn(0.05f, 0.5f)
    val contentAlpha = ((fadeDist - progress) / fadeDist).coerceIn(0f, 1f)
    // 等价 Dart 的 `IgnorePointer(ignoring: p > 0.001)`：
    // 这里改用"已几乎完全透明"作阈值，避免拖动刚起步时按钮先变成禁用灰。
    val interactive = contentAlpha > 0.05f

    val offsetDp = with(density) { dragOffset.value.toDp().value }
    // Dart：`tipOpacity = (offset.abs() / 80.0).clamp(0.0, 0.85)`
    val tipOpacity = (abs(offsetDp) / TipFullOffsetDp).coerceIn(0f, 0.85f)

    val primary = MaterialTheme.colorScheme.primary
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val secondaryText = MaterialTheme.ytTextSecondary
    // 歌曲名 / 占位标题必须显式取主题的 onSurface：
    // MaterialTheme.typography.titleMedium 自身不带颜色，而这里位于 MaterialTheme 之下、
    // 并无 Surface 祖先提供 LocalContentColor，最终会落到 Compose 的默认黑色
    // ——深色模式下黑字落在深色栏体上等于不可见（Dart 版由 Flutter 主题自动给出文字色）。
    val titleText = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = contentAlpha
                // Dart 中栏体固定在底部只淡出，完全透明后折叠高度；
                // 这里以「随进度向下移出自身高度」等价替代（发生在完全透明之后，肉眼不可见）。
                translationY = progress * size.height
            }
            // 第二十二轮修复：去掉 `shadow(elevation = 6.dp)`。
            // 阴影绘制在组件**边界之外**，会在栏体上方多出一条灰带，
            // 压住身后最后一行的歌曲内容（反馈："顶部多出来了一小块儿，遮挡了其他内容"）。
            // 栏体本身已有不透明底色，并用下面的 drawBehind 画了 1px 顶部描边，
            // 分隔效果足够，无需再叠一层阴影。
            .background(barColor)
            .drawBehind {
                // Dart：顶部 0.5 宽的 divider 描边
                drawLine(
                    color = dividerColor,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            // 沉浸式：底色继续铺到手势导航条下方（背景已在上一步绘制，故会被一起覆盖），
            // 手势与内容区仍只占上方 BarHeight 的高度。
            .navigationBarsPadding()
            .pointerInput(hasSong, screenHeightPx) {
                val slopPx = TouchSlop.toPx()
                val maxOffsetPx = MaxBarOffset.toPx()
                val switchOffsetPx = SwipeSwitchOffset.toPx()
                val settleVelocityPx = VerticalSettleVelocity.toPx() // px/s
                val swipeVelocityPx = HorizontalSwipeVelocity.toPx() // px/s
                // Dart：`_effectiveDragDistance = screenHeight * 0.8`
                val effectiveDragPx = screenHeightPx * 0.8f

                // 栏内内容弹回（Dart `_animateBarBack`：250ms easeOutCubic，回到 0）
                fun snapBarBack() {
                    snapJob.value?.cancel()
                    snapJob.value = scope.launch {
                        val start = dragOffset.value
                        if (start == 0f) return@launch
                        animate(
                            initialValue = start,
                            targetValue = 0f,
                            animationSpec = tween(SnapBackDurationMs, easing = EaseOutCubic),
                        ) { value, _ -> dragOffset.value = value }
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // 播放页已进入展开流程：忽略新手势
                    // （等价 Dart `IgnorePointer(ignoring: p > 0.001)`；
                    //  手势进行中不受影响，因此跟手拖动不会被自身抬高的进度打断）
                    if (NowPlayingUiState.currentProgress > 0.001f) return@awaitEachGesture

                    var direction: Boolean? = null // true = 水平（切歌），false = 竖直（展开播放页）
                    var routePushed = false // 等价 Dart 的 `_routePushed`
                    var dxAcc = 0f
                    var dyAcc = 0f
                    var settled = false
                    val tracker = VelocityTracker()

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val delta = change.positionChange()
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (!change.pressed) {
                            // ── 抬手：方向锁定后按位移/速度结算（Dart `_onPanEnd`）──
                            val velocity = tracker.calculateVelocity()
                            if (direction != null) {
                                // 已判定为拖动：消费抬手事件，取消点击判定
                                change.consume()
                            }
                            when (direction) {
                                true -> {
                                    // 水平滑动切歌：位移 60dp 或速度 600dp/s（Dart 阈值）
                                    if (dragOffset.value < -switchOffsetPx ||
                                        velocity.x < -swipeVelocityPx
                                    ) {
                                        scope.launch { PlayerController.skipToNext() }
                                    } else if (dragOffset.value > switchOffsetPx ||
                                        velocity.x > swipeVelocityPx
                                    ) {
                                        scope.launch { PlayerController.skipToPrevious() }
                                    }
                                    snapBarBack()
                                }

                                false -> if (routePushed) {
                                    // 竖直跟手结束：先看速度，再看位移进度（Dart 阈值 150dp/s 与 0.2）
                                    val vy = velocity.y
                                    val p = NowPlayingUiState.currentProgress
                                    when {
                                        vy < -settleVelocityPx -> NowPlayingUiState.open()
                                        vy > settleVelocityPx -> NowPlayingUiState.close()
                                        p >= OpenThreshold -> NowPlayingUiState.open()
                                        else -> NowPlayingUiState.close()
                                    }
                                }

                                // 位移未超过 40dp 阈值：视为点击栏体
                                // （按下/抬手被按钮消费时不响应，避免与播放/下一首按钮重复触发）
                                null -> if (!change.isConsumed) openNowPlaying()
                            }
                            settled = true
                            break
                        }

                        if (direction == null) {
                            // 方向锁：累计位移超过 40dp 才判定主方向（同 Flutter 全局 touchSlop）
                            dxAcc += delta.x
                            dyAcc += delta.y
                            if (abs(dxAcc) < slopPx && abs(dyAcc) < slopPx) continue
                            direction = abs(dxAcc) >= abs(dyAcc)
                            dxAcc = 0f
                            dyAcc = 0f
                        }

                        if (direction == true) {
                            // ── 水平：栏内内容跟手平移（受限挂起作用域内只做状态赋值）──
                            // 需求 5：占位态（队列已清空）**禁止左右滑动切歌** ——
                            // 消费事件并直接返回，栏体完全不动、也不会切歌。
                            if (!hasSong) {
                                change.consume()
                                continue
                            }
                            dragOffset.value = (dragOffset.value + delta.x)
                                .coerceIn(-maxOffsetPx, maxOffsetPx)
                            change.consume()
                        } else {
                            // ── 竖直：上划驱动播放页展开进度 ──
                            // 需求 5：占位态仍允许**上划进入播放列表**（占位曲目同样可展开）
                            if (!routePushed) {
                                // 未展开时只响应上划（Dart：`if (d.delta.dy >= 0) return;`）
                                if (delta.y >= 0f) {
                                    change.consume()
                                    continue
                                }
                                routePushed = true
                            }
                            NowPlayingUiState.setProgress(
                                (NowPlayingUiState.currentProgress - delta.y / effectiveDragPx)
                                    .coerceIn(0.002f, 1f), // Dart：`.clamp(0.002, 1.0)`
                            )
                            change.consume()
                        }
                    }

                    if (!settled) {
                        // 手势被系统打断（如中途丢失指针）：按当前状态兜底收敛，避免残留半展开进度
                        if (direction == false && routePushed) {
                            if (NowPlayingUiState.currentProgress >= OpenThreshold) {
                                NowPlayingUiState.open()
                            } else {
                                NowPlayingUiState.close()
                            }
                        } else if (direction == true) {
                            snapBarBack()
                        }
                    }
                }
            }
            .semantics {
                onClick(label = "打开正在播放") {
                    openNowPlaying()
                    true
                }
            },
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(BarHeight).clipToBounds()) {
            // 右侧背景提示：左划时显示「下一曲」（offset < 0）
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .graphicsLayer { alpha = if (offsetDp < 0f) tipOpacity else 0f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "下一曲",
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            // 左侧背景提示：右划时显示「上一曲」（offset > 0）
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .graphicsLayer { alpha = if (offsetDp > 0f) tipOpacity else 0f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = null,
                    tint = primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "上一曲",
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 主内容：跟手水平平移
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = dragOffset.value },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.width(12.dp))
                if (track != null) {
                    // 封面（圆角 12dp，尺寸 52dp，同 Dart `MpArtwork`）
                    SongArtwork(
                        song = track,
                        modifier = Modifier.size(52.dp),
                        cornerRadius = 12.dp,
                    )
                } else {
                    // 空占位态（仅在 hideWhenEmpty = false 时可见）：紫青渐变圆 + 音符图标
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(BrandPurple, BrandCyan))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (track != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = track.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = titleText,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            // 已缓存的远程歌曲显示缓存标识（对应 Dart `Icons.offline_pin`）
                            if (track.isRemote && track.isCached) {
                                Spacer(Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Filled.OfflinePin,
                                    contentDescription = null,
                                    tint = primary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        Text(
                            text = track.displayArtist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText,
                        )
                    } else {
                        Text(
                            text = "愉乐",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = titleText,
                        )
                        Text(
                            text = "还没有播放歌曲",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText,
                        )
                    }
                }

                // 播放 / 暂停：图标随播放状态切换
                MpControlButton(
                    icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    onClick = {
                        if (hasSong) {
                            scope.launch { PlayerController.togglePlayPause() }
                        } else {
                            openNowPlaying()
                        }
                    },
                    size = 44.dp,
                    iconSize = 24.dp,
                    enabled = interactive,
                    tint = primary,
                )

                // 下一首
                MpControlButton(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "下一首",
                    onClick = { scope.launch { PlayerController.skipToNext() } },
                    size = 44.dp,
                    iconSize = 22.dp,
                    enabled = interactive && hasSong,
                    tint = if (hasSong) primary else disabledTint,
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/** 播放栏高度（Dart `Container(height: 80.0)`） */
private val BarHeight = 80.dp
private const val BarHeightDp = 80f

/** 全局方向锁定阈值（Flutter 端 `DeviceGestureSettings(touchSlop: 40.0)`） */
private val TouchSlop = 40.dp

/** 水平滑动切歌的位移阈值（Dart `_barOffset < -60 / > 60`） */
private val SwipeSwitchOffset = 60.dp

/** 栏内内容水平位移上限（Dart `_barOffset.clamp(-150, 150)`） */
private val MaxBarOffset = 150.dp

/** 滑动提示完全不透明所需的位移（Dart `offset.abs() / 80.0`） */
private const val TipFullOffsetDp = 80f

/** 竖直收尾速度阈值（Dart `velocity.dy < -150 / > 150`），单位：dp/s */
private val VerticalSettleVelocity = 150.dp

/** 水平滑动切歌的速度阈值（Dart `velocity.dx < -600 / > 600`），单位：dp/s */
private val HorizontalSwipeVelocity = 600.dp

/** 进度大于该值时松手展开，否则回弹收回（Dart `p >= 0.2`） */
private const val OpenThreshold = 0.2f

/** 栏内内容弹回时长（Dart `Duration(milliseconds: 250)`） */
private const val SnapBackDurationMs = 250

/** Dart `Curves.easeOutCubic` */
private val EaseOutCubic = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)
