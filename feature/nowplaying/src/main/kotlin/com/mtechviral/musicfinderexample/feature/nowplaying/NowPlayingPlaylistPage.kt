/*
 * 对应 Dart 原文件：.flutter_reference/lib/pages/now_playing.dart
 *  - `_buildPlaylistPage`：播放列表页，与「正在播放」页同处竖向 PageView 的第 1 页
 *    （上划进入、下滑返回，共享同一张模糊封面背景）
 *  - `_currentSongCard`：顶部「正在播放」当前歌曲卡片
 *  - 列表项对应 `_buildPlaylistPage` 中的 ListTile（封面 48 圆角 6、歌名、艺术家、
 *    当前行 equalizer、已缓存 offline_pin）
 *
 * 与 Dart 的实现差异：Dart 用左滑 Dismissible 删除队列中的歌曲，原生沿用
 * 「更多」菜单（EntityActionSheet.onDelete = 从播放列表移除）替代。
 *
 * 与上一版原生实现的差异：原先是从底部弹出的 Material 抽屉（自带 surface 底色），
 * 现改回 Dart 的「同一页面的第 1 页」——背景透明（透出共享的模糊封面）、
 * 文字恒为白色，仅额外叠一层暗色蒙版保证可读性。
 */
package com.mtechviral.musicfinderexample.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtechviral.musicfinderexample.core.designsystem.component.SongArtwork
import com.mtechviral.musicfinderexample.core.model.PlayMode
import com.mtechviral.musicfinderexample.core.model.Song

/** 播放列表页额外叠加的暗色蒙版（共享背景已有一层 35% 黑，这里再加深以保证白字可读） */
private const val PLAYLIST_SCRIM_ALPHA = 0.35f

/** 顶部当前播放卡片的封面尺寸/圆角（比队列行封面更大，参照设计图） */
private val CURRENT_ARTWORK_SIZE = 64.dp
private val CURRENT_ARTWORK_CORNER = 8.dp

/**
 * 当前播放行的高亮框（第二十/二十二轮需求）：圆角 + 半透明灰底。
 * 取代原先"仅靠 equalizer 图标"的提示方式，整行一眼可辨。
 *
 * 第二十二轮（追加需求）：圆角由 20dp 改为 **15dp**。
 */
private val CURRENT_ROW_CORNER = 15.dp
private val CURRENT_ROW_HIGHLIGHT = Color(0xFF888888).copy(alpha = 0.28f)

/** 高亮框相对屏幕左右各留出的空白，使框体不贴边 */
private val ROW_HORIZONTAL_INSET = 8.dp

/**
 * 队列每行内容额外增加的左右内边距（第二十二轮追加需求：左右各加 5px）。
 * 与 [ROW_TEXT_START] 共同决定行内容的左右留白。
 */
private val ROW_HORIZONTAL_EXTRA_PADDING = 5.dp

/**
 * **整个播放队列**左右各向内收窄的距离（第二十二轮追加需求：整体左右各收 5px）。
 *
 * 加在 [LazyColumn] 容器上，因此**每一行连同它的高亮框**都跟着内缩 5px。
 * 与行内 padding（[ROW_TEXT_START]）是两层独立的收窄。
 */
private val QUEUE_HORIZONTAL_INSET = 5.dp

/**
 * 队列行内容**左侧**内边距（第二十二轮追加需求：改为 15px）。
 *
 * 与 [QUEUE_HORIZONTAL_INSET] 是两层：队列容器先左右各收 5px，
 * 行内再留 15px，因此文字距屏幕左边缘实际为 20px。
 */
private val ROW_TEXT_START = 15.dp

/** 行内容右侧留白（4dp）＋追加的 5px */
private val ROW_HORIZONTAL_TRAILING_PADDING = 4.dp + ROW_HORIZONTAL_EXTRA_PADDING

/**
 * 高亮框上下各内缩的距离（第二十二轮需求 2：上下各缩短 5px）。
 * 用"叠加层内缩"实现，因此不影响行高与相邻行间距。
 */
private val CURRENT_ROW_VERTICAL_TRIM = 5.dp

/** 顶部区下滑返回播放页的位移阈值（需求 1） */
private val TOP_SWIPE_BACK_DISTANCE = 60.dp

/** 顶部区下滑返回播放页的速度阈值（需求 1，px/s） */
private val TOP_SWIPE_BACK_VELOCITY = 300.dp

/** 顶部/列表之间的唯一一条分割线颜色 */
private val SECTION_DIVIDER = Color.White.copy(alpha = 0.18f)

/**
 * 播放列表页（竖向 PageView 第 1 页）。
 *
 * @param songs 播放列表内容（PlaylistRepository.songs）
 * @param currentIndex 当前播放项在 [songs] 中的**下标**（用于高亮当前行与表头计数）
 * @param currentSong 当前播放歌曲（顶部当前歌曲卡片）
 * @param listState 列表滚动状态（父级持有；列表滚到顶部后继续下滑由竖向 PageView
 *   接管并翻回播放页）
 *
 * 注意（第十六轮）：同一首歌可在播放列表内出现多次，因此**不能**再用 `song.path`
 * 作为列表 key（重复 key 会让 LazyColumn 抛 "Key was already used"），
 * 也不能用 `item.path == currentSong.path` 判断当前行（会把所有同名单曲都点亮）。
 * 统一改为按**下标**定位：key 用下标，高亮比下标，点击/移除回调也传下标。
 *
 * 第二十轮调整：
 * - 上划进入本页时**自动滚动定位到当前播放的歌曲**（由调用方触发）；
 * - 当前播放行用**20px 圆角 + 半透明灰底**的整行高亮框；
 * - 顶部当前播放卡片去掉「正在播放」文案与右侧播放状态图标；
 * - 队列每行的「更多」按钮改为**减号按钮**，直接从播放队列删掉该曲。
 *
 * 第二十一轮调整（参照设计图）：
 * - 顶部新增居中提示「此处向下轻扫以返回播放界面」；
 * - 当前播放区：封面放大到 64dp；
 * - 表头改为「[位置] / [总数]」左 · 「播放队列」中 · 「清除」右（取代原 收起 + 清空图标）；
 * - **队列行去掉封面**，歌名 +「艺术家 - 专辑」，右端保留减号按钮；
 * - **整页只在「当前播放区」与「播放队列」之间保留一条分割线**，队列内部无分割线。
 */
@Composable
internal fun NowPlayingPlaylistPage(
    songs: List<Song>,
    currentIndex: Int,
    currentSong: Song,
    listState: LazyListState,
    onCollapse: () -> Unit,
    onClear: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    playMode: PlayMode,
    onCyclePlayMode: () -> Unit,
    onSwipeDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 顶部区下滑返回的累计位移（需求 1）。跨重组保持，松手后清零。
    var topDragAccum by remember { mutableFloatStateOf(0f) }
    // 阈值需在组合期换算成像素（`toPx()` 只能在 Density 作用域内调用）
    val density = LocalDensity.current
    val swipeBackDistancePx = with(density) { TOP_SWIPE_BACK_DISTANCE.toPx() }
    val swipeBackVelocityPx = with(density) { TOP_SWIPE_BACK_VELOCITY.toPx() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = PLAYLIST_SCRIM_ALPHA))
            .statusBarsPadding(),
    ) {
        // 顶部区（提示 + 当前播放卡片 + 表头）：整块可**下滑返回播放页**（第二十二轮需求 1）。
        // 该区域不在 LazyColumn 内，因此下滑不会与列表滚动冲突；
        // 若播放列表已被滚动过，父级的竖向手势会因「列表可回滚」而放行，
        // 这里补上一条独立的竖向拖拽即可覆盖"在顶部区下滑"的情形。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> topDragAccum += delta },
                    onDragStopped = { velocity ->
                        // 下滑超过阈值或带向下速度 → 返回播放页
                        if (topDragAccum > swipeBackDistancePx ||
                            velocity > swipeBackVelocityPx
                        ) {
                            onSwipeDown()
                        }
                        topDragAccum = 0f
                    },
                ),
        ) {
            // 顶部提示（第二十一轮，参照设计图）：居中一行小字，提示下滑可返回播放界面
            Text(
                text = "此处向下轻扫以返回播放界面",
                style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp),
                textAlign = TextAlign.Center,
            )

            // 当前播放区：封面 + 歌名 + 「艺术家 - 专辑」（点击翻回播放页）
            CurrentSongCard(
                song = currentSong,
                onClick = onCollapse,
            )

            // 表头：「[位置] / [总数]」左 ·「播放队列」中 ·「清除」右（第二十一轮，参照设计图）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 与设计图一致显示"当前第几首 / 总数"；无当前项（-1）时退化为不显示位置
                    text = if (currentIndex >= 0) "${currentIndex + 1} / ${songs.size}" else "${songs.size}",
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "播放队列",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Text(
                    text = "清除",
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onClear),
                    textAlign = TextAlign.End,
                )
            }
        }

        // 唯一一条分割线：分隔「当前播放区」与「播放队列」；队列内部不再有分割线
        HorizontalDivider(thickness = 1.dp, color = SECTION_DIVIDER)

        if (songs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 空态也与队列保持同样的左右收窄，避免切换时左右跳动
                    .padding(horizontal = QUEUE_HORIZONTAL_INSET)
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "播放列表为空",
                    style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    // 需求：播放队列**整体**左右各向内收窄 5px
                    // （高亮框与行内容都随之一起内缩）
                    .padding(horizontal = QUEUE_HORIZONTAL_INSET)
                    .weight(1f),
            ) {
                itemsIndexed(items = songs, key = { index, item -> "$index-${item.path}" }) { index, item ->
                    PlaylistSongRow(
                        song = item,
                        isCurrent = index == currentIndex,
                        onClick = { onPlayIndex(index) },
                        onRemoveClick = { onRemoveIndex(index) },
                    )
                }
            }
        }

        // 底部播放模式按钮（第二十二轮需求 3）：**左侧**（非居中）；
        // 点击循环切换 顺序播放 → 随机播放 → 单曲循环，与播放页的播放模式按钮
        // 共用 PlayerController.playMode（同一份状态，二者天然联动）。
        //
        // 必须先于「列表/空态」声明顺序上无所谓，但按钮本身不带 weight，
        // 所以它是固定高度的一行；上面的列表用 weight(1f) 占满剩余空间，
        // 因此按钮始终可见（此前把按钮放在 weight 之后仍被列表挤掉，
        // 需要把它移出 Column 的 weight 分配 —— 见下方 Row 包裹说明）。
        PlayModeButton(
            playMode = playMode,
            onClick = onCyclePlayMode,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(start = 16.dp, top = 8.dp, bottom = 12.dp)
                .navigationBarsPadding(),
        )
    }
}

/**
 * 底部播放模式胶囊按钮（第二十二轮需求 3）。
 *
 * 文案随 [playMode] 变化，图标同播放页的 `playModeIcon`；
 * 用 `Surface` + `CircleShape` 实现全圆角胶囊，半透明深色底保证在模糊封面上可读。
 * 状态来自 `PlayerController.playMode`，因此与播放页底部那个模式按钮**完全联动**。
 */
@Composable
private fun PlayModeButton(
    playMode: PlayMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = playModeIconFor(playMode),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = playModeLabel(playMode),
                style = TextStyle(color = Color.White, fontSize = 14.sp),
            )
        }
    }
}

/** 模式文案（需求 3 指定用"…模式"后缀，与播放页 tooltip 的短文案区分开） */
private fun playModeLabel(mode: PlayMode): String = when (mode) {
    PlayMode.SEQUENTIAL -> "顺序播放模式"
    PlayMode.RANDOM -> "随机播放模式"
    PlayMode.SINGLE -> "单曲循环模式"
}

/** 模式图标：与播放页 `playModeIcon` 保持一致 */
private fun playModeIconFor(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.Filled.Repeat
    PlayMode.RANDOM -> Icons.Filled.Shuffle
    PlayMode.SINGLE -> Icons.Filled.RepeatOne
}

/**
 * 顶部当前歌曲卡片（Dart `_currentSongCard` 的简化版）。
 *
 * 第二十轮调整（按需求）：去掉「正在播放」标题文字与右侧播放状态图标，
 * 只留封面 + 歌名 + 艺术家；点击仍翻回播放页。
 */
@Composable
private fun CurrentSongCard(
    song: Song,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArtwork(
            song = song,
            modifier = Modifier.size(CURRENT_ARTWORK_SIZE),
            cornerRadius = CURRENT_ARTWORK_CORNER,
            maxSizePx = with(LocalDensity.current) { CURRENT_ARTWORK_SIZE.roundToPx() },
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.size(2.dp))
            Text(
                // 设计图为「艺术家 - 专辑」；占位态（队列已清空）专辑位置留空，只显示「Hi~」
                text = if (song.isPlaceholder) {
                    song.displayArtist
                } else {
                    "${song.displayArtist} - ${song.displayAlbum}"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
            )
        }
    }
}

/**
 * 播放列表行：歌名 +「艺术家 - 专辑」+ 缓存标识 + 减号按钮。
 *
 * 第二十轮调整：当前播放行用圆角 + 半透明灰底的整行高亮框；
 * 右侧「更多」按钮改为**减号按钮**，点击即从当前播放队列删除该曲。
 *
 * 第二十一轮调整（参照设计图）：**去掉行左侧封面**，歌名直接从左边距起排；
 * 队列内部不画分割线（整页只在当前播放区与队列之间有一条分割线）。
 *
 * 第二十二轮调整：高亮框圆角改为 **15dp** 并上下各内缩 5dp（框体更矮、行高不变）；
 * 行内容左右各加 5px padding（`ROW_HORIZONTAL_EXTRA_PADDING`）。
 */
@Composable
private fun PlaylistSongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRemoveClick: () -> Unit,
) {
    // 行容器：内容尺寸由 Row 自身决定；高亮框作为**叠加层**绘制，
    // 上下各内缩 CURRENT_ROW_VERTICAL_TRIM（5dp），因此框体比行内容更矮，
    // 而不会影响行的实际高度与相邻行的间距（需求 2）。
    Box(modifier = Modifier.fillMaxWidth()) {
        // 当前播放行的高亮框：20dp 圆角 + 半透明灰底，左右各留 8dp、上下各内缩 5dp
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = ROW_HORIZONTAL_INSET)
                    .padding(vertical = CURRENT_ROW_VERTICAL_TRIM)
                    .background(CURRENT_ROW_HIGHLIGHT, RoundedCornerShape(CURRENT_ROW_CORNER)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    start = ROW_TEXT_START,
                    end = ROW_HORIZONTAL_TRAILING_PADDING,
                    top = 10.dp,
                    bottom = 10.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    // 设计图为「艺术家 - 专辑」
                    text = "${song.displayArtist} - ${song.displayAlbum}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp),
                )
            }
            // 已缓存的远程歌曲显示缓存标识（Dart：offline_pin）
            if (song.isRemote && song.isCached) {
                Icon(
                    imageVector = Icons.Filled.OfflinePin,
                    contentDescription = "已缓存",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            // 减号按钮：从当前播放队列中删掉该歌曲（第二十轮，取代原「更多」菜单）
            IconButton(onClick = onRemoveClick) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "从播放队列移除",
                    tint = Color.White,
                )
            }
        }
    }
}
