/*
 * 对应 Dart 原文件：`.flutter_reference/lib/widgets/entity_action_sheet.dart`
 * （歌曲目标 `SongTarget` 的菜单同时对应
 *  `.flutter_reference/lib/widgets/mp_song_bottom_sheet.dart` 的"操作卡"）
 *
 * 菜单项 / 顺序 / 图标 / 文案 / 可用性判定逐条对照 Dart：
 *   1. 喜欢 / 取消喜欢        —— **仅歌曲目标显示**（需求变更：不再支持喜欢专辑 / 艺术家）
 *   2. 播放 / 播放全部        —— 原生新增项，对应旧版"点击歌曲/播放全部"入口 → onPlay
 *   3. 添加到播放队列        —— Dart 原项（Icons.queue_music）→ onPlayNext（见下方语义说明）
 *   4. 添加到歌单            —— Dart 歌曲菜单原项（Icons.playlist_add）→ onAddToPlaylist
 *   5. 缓存全部 / 缓存歌曲    —— Dart 原项（Icons.cloud_download），仅"远程且未缓存"歌曲存在时显示
 *   6. 默认缓存本艺术家音乐    —— Dart 仅艺术家目标显示（Icons.cloud_sync）
 *   7. 永久删除 / 从本歌单移除 —— Dart 歌曲菜单原项（Icons.delete_forever，红 0xFFFF5252）；
 *      默认（`deleteFromLibrary = true`）仅在"目标内全部为本地歌曲且都有 id"时显示；
 *      传 `deleteFromLibrary = false` 时**始终显示**，文案取 `deleteLabel`（歌单详情页"从本歌单移除"）
 *
 * ============================ 回调契约（集成者已冻结/广播） ============================
 * - 喜欢/取消喜欢：**仅 `SongTarget` 显示且仅作用于其中那一首歌**，弹窗内部完成数据库切换——
 *     `DatabaseHelper.toggleLikeSong(id)`；
 *   随后 `MusicLibrary.reload()`，再回调 `onToggleLike(songs)`。
 *   → 调用方的 `onToggleLike` **只做刷新**（如重查列表），**不得再 toggle**，否则会二次取反。
 *   专辑 / 艺术家目标**不再有**喜欢项（需求变更：喜好只针对单曲）。
 * - 永久删除 / 从本歌单移除（由 [deleteFromLibrary] 决定）：
 *     `true`（默认）：**弹窗内部** `LibrarySongDeleter.delete(songs)`
 *       （= `DatabaseHelper.deleteSong(id)` + **同步移出播放列表**，若删的是正在播放的歌曲
 *         则由 `PlayerController` 接续播放下一首）+ `MusicLibrary.reload()`，再回调
 *       `onDelete(songs)`；`onDelete` 只作刷新通知（与 Dart 的 deleteSong + reload 一致）。
 *     `false`：弹窗**不写库**，仅回调 `onDelete(songs)`，删除项文案取 [deleteLabel] 且始终显示
 *       （歌单详情页用它实现"从本歌单移除"，由调用方执行 `removeSongFromPlaylist`）。
 * - 添加到播放队列：**弹窗内部** `PlaylistRepository.addSong(song)`（队列追加；仓库无"插入到下一首"
 *   API，故与 Dart 的 `playlistData.addSong` 行为一致），再回调 `onPlayNext(songs)`。
 *   `onPlayNext` 是规范第 8 节冻结的参数名，其真实语义为"添加到播放队列"。
 *   注（第十六轮）：`addSong` 已**取消判重**，同一首歌可重复入列，因此不再有
 *   "已在播放列表中"的提示分支。
 * - 播放：仅回调 `onPlay(songs)`，由调用方执行
 *   `PlaylistRepository.setSongs(songs)` + `PlayerController.playSong(...)`。
 * - 添加到歌单：仅回调 `onAddToPlaylist(songs)`，歌单选择器由调用方弹出。
 * - 缓存：**弹窗内部** `CacheService.startCaching(song)`（与 Dart `_cacheAll` 一致，后台缓存）。
 * - 排除该歌手（第二十七轮需求）：**弹窗内部** `LibrarySongDeleter.excludeArtistAndPurge(artist)` ——
 *   写排除列表 + 删除该歌手的本地文件/在线缓存 + 批量删库 + 移出播放列表；
 *   其专辑与艺术家条目因 `songs` 表聚合而自动消失。随后 `MusicLibrary.reload()`，
 *   再回调 `onExcludeArtist(artist, removedCount)`；该回调**只作刷新**，不得再次排除。
 * ===================================================================================
 */
package com.mtechviral.musicfinderexample.core.designsystem.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.model.Album
import com.mtechviral.musicfinderexample.core.model.Artist
import com.mtechviral.musicfinderexample.core.model.Song
import com.mtechviral.musicfinderexample.core.player.LibrarySongDeleter
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.launch

/** 喜欢图标红色（Dart: `Colors.red`） */
private val LikedRed = Color(0xFFF44336)

/** 删除项红色（Dart: `Color(0xFFFF5252)`） */
private val DangerRed = Color(0xFFFF5252)

/**
 * 实体操作菜单目标：歌曲 / 专辑 / 艺术家。
 * 对应 Dart `EntityType`（album / artist）与歌曲操作卡。
 */
sealed interface EntityActionTarget {
    data class SongTarget(val songs: List<Song>) : EntityActionTarget
    data class AlbumTarget(val album: Album, val songs: List<Song>) : EntityActionTarget
    data class ArtistTarget(val artist: Artist, val songs: List<Song>) : EntityActionTarget
}

/**
 * 可**单个隐藏**的菜单项。
 *
 * 用于让个别入口收敛自己的菜单，而不影响其他页面（需求：播放页 / 播放列表的「更多」弹窗
 * 不再提供「播放 / 添加到播放队列 / 添加到歌单」，其余页面保持原样）。
 * 通过 [EntityActionSheet] 的 `hiddenItems` 传入。
 */
enum class EntityActionItem {
    /** 播放 / 播放全部 */
    PLAY,

    /** 添加到播放队列 */
    ADD_TO_QUEUE,

    /** 添加到歌单 */
    ADD_TO_PLAYLIST,

    /** 排除该歌手（第二十六轮需求 3；仅歌手目标显示，可被隐藏） */
    EXCLUDE_ARTIST,
}

/**
 * 实体操作菜单（底部弹出）。
 *
 * 与 Dart `EntityActionSheet` 一致：点击任一项后先收起弹窗再执行动作。
 * 关于各回调的真实语义（尤其是 `onToggleLike` / `onDelete` / `onPlayNext`），
 * 见文件顶部"回调契约"。
 *
 * @param deleteFromLibrary true（默认）= 点删除时由弹窗先 `DatabaseHelper.deleteSong` +
 *   `MusicLibrary.reload()`，再回调 `onDelete`；false = 弹窗不做任何库删除，仅回调 `onDelete`
 *   （用于歌单详情页的"从本歌单移除"，此时删除项始终显示且文案取 [deleteLabel]）。
 * @param deleteLabel 删除项文案（默认"永久删除"；歌单详情页传"从本歌单移除"）。
 * @param hiddenItems 需要**隐藏**的菜单项（默认全不隐藏）。播放页 / 播放列表的「更多」用它
 *   去掉「播放 / 添加到播放队列 / 添加到歌单」；其他调用方不传即保持完整菜单。
 * @param showHeaderCloseButton 是否显示标题栏右上角的关闭按钮（默认 true）。
 *   隐藏后仍可通过点击遮罩 / 返回键 / 点击任一菜单项关闭弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityActionSheet(
    entity: EntityActionTarget,
    onDismiss: () -> Unit,
    onPlay: (List<Song>) -> Unit = {},
    onPlayNext: (List<Song>) -> Unit = {},
    onAddToPlaylist: (List<Song>) -> Unit = {},
    onToggleLike: (List<Song>) -> Unit = {},
    onDelete: (List<Song>) -> Unit = {},
    /**
     * 「排除该歌手」完成后的刷新通知（第二十七轮需求）。
     *
     * 与 `onDelete` 同一契约：删除/排除**已在弹窗内部完成**，这里只做刷新，
     * 不得再次执行排除。`removedCount` 为本次从曲库移除的歌曲数（0 表示本来就没有）。
     */
    onExcludeArtist: (artist: String, removedCount: Int) -> Unit = { _, _ -> },
    deleteFromLibrary: Boolean = true,
    deleteLabel: String = "永久删除",
    hiddenItems: Set<EntityActionItem> = emptySet(),
    showHeaderCloseButton: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        EntityActionContent(
            entity = entity,
            onDismiss = onDismiss,
            onPlay = onPlay,
            onPlayNext = onPlayNext,
            onAddToPlaylist = onAddToPlaylist,
            onToggleLike = onToggleLike,
            onDelete = onDelete,
            onExcludeArtist = onExcludeArtist,
            deleteFromLibrary = deleteFromLibrary,
            deleteLabel = deleteLabel,
            hiddenItems = hiddenItems,
            showHeaderCloseButton = showHeaderCloseButton,
        )
    }
}

@Composable
private fun EntityActionContent(
    entity: EntityActionTarget,
    onDismiss: () -> Unit,
    onPlay: (List<Song>) -> Unit,
    onPlayNext: (List<Song>) -> Unit,
    onAddToPlaylist: (List<Song>) -> Unit,
    onToggleLike: (List<Song>) -> Unit,
    onDelete: (List<Song>) -> Unit,
    onExcludeArtist: (artist: String, removedCount: Int) -> Unit,
    deleteFromLibrary: Boolean,
    deleteLabel: String,
    hiddenItems: Set<EntityActionItem>,
    showHeaderCloseButton: Boolean,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    val songs: List<Song> = when (entity) {
        is EntityActionTarget.SongTarget -> entity.songs
        is EntityActionTarget.AlbumTarget -> entity.songs
        is EntityActionTarget.ArtistTarget -> entity.songs
    }

    // ---- 标题（Dart: 图标 + 名称 + [专辑艺术家] + "N 首歌曲" + 关闭按钮） ----
    val headerIcon: ImageVector = when (entity) {
        is EntityActionTarget.AlbumTarget -> Icons.Filled.Album
        is EntityActionTarget.ArtistTarget -> Icons.Filled.Person
        is EntityActionTarget.SongTarget -> Icons.Filled.MusicNote
    }
    val headerTitle: String = when (entity) {
        is EntityActionTarget.AlbumTarget -> entity.album.title
        is EntityActionTarget.ArtistTarget -> entity.artist.name
        is EntityActionTarget.SongTarget -> songs.firstOrNull()?.title.orEmpty()
    }
    // 仅专辑显示所属艺术家（Dart: entityType == album && artistName != null）
    val headerArtist: String? =
        (entity as? EntityActionTarget.AlbumTarget)?.album?.artist?.takeIf { it.isNotBlank() }

    // 曲库（MusicLibrary）变化即等价于 Dart 的 songData.notifier 触发，
    // 用曲库中的最新对象覆盖传入快照，保证弹窗内状态实时。
    val library by MusicLibrary.songs.collectAsStateWithLifecycle()
    val liveSongs = remember(songs, library) {
        songs.map { s -> library.firstOrNull { it.path == s.path } ?: s }
    }
    // 需求变更：喜欢只针对**单曲**，因此只有单曲目标（SongTarget(listOf(song))）
    // 才显示「喜欢 / 取消喜欢」；专辑 / 艺术家目标不再提供该入口。
    val likeableSong: Song? =
        (entity as? EntityActionTarget.SongTarget)?.songs?.singleOrNull()
    val isLiked = likeableSong?.let { target ->
        library.firstOrNull { it.path == target.path } ?: target
    }?.isLiked == true

    val uncachedRemoteSongs = liveSongs.filter { it.isRemote && !it.isCached }
    val deletable = liveSongs.isNotEmpty() && liveSongs.none { it.isRemote } && liveSongs.all { it.id != null }

    Column(modifier = Modifier.fillMaxWidth()) {
        // ===================== 标题栏 =====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = headerIcon, contentDescription = null, tint = primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headerTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W600,
                    color = onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (headerArtist != null) {
                    Text(
                        text = headerArtist,
                        fontSize = 13.sp,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${liveSongs.size} 首歌曲",
                    fontSize = 12.sp,
                    color = secondary,
                )
            }
            if (showHeaderCloseButton) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭", tint = onSurface)
                }
            }
        }
        HorizontalDivider()

        // ========== 1. 喜欢 / 取消喜欢（仅歌曲目标；需求变更：不再支持专辑 / 艺术家） ==========
        if (likeableSong != null) {
            ActionRow(
                icon = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                iconColor = if (isLiked) LikedRed else primary,
                title = if (isLiked) "取消喜欢" else "喜欢",
                subtitle = if (isLiked) "从喜欢列表中移除" else "添加到喜欢列表",
            ) {
                val wasLiked = isLiked
                val songId = likeableSong.id
                val autoCache = CacheService.isAutoCacheLikedEnabled()
                scope.launch {
                    songId?.let { DatabaseHelper.toggleLikeSong(it) }
                    // 设置「缓存我喜欢」开启时，标记为喜欢后自动缓存（远程歌曲；本地歌曲本就在磁盘上）
                    if (!wasLiked && songId != null) CacheService.autoCacheLiked(songId)
                    MusicLibrary.reload()
                    onToggleLike(listOf(likeableSong))
                    val message = when {
                        wasLiked -> "已取消喜欢"
                        likeableSong.isRemote && autoCache -> "已添加到喜欢，将自动缓存"
                        else -> "已添加到喜欢"
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            }
        }

        // ========== 2. 播放 / 播放全部（可被 hiddenItems 隐藏） ==========
        if (EntityActionItem.PLAY !in hiddenItems) {
            ActionRow(
                icon = Icons.Filled.PlayArrow,
                iconColor = primary,
                title = if (liveSongs.size > 1) "播放全部" else "播放",
                subtitle = if (liveSongs.size > 1) "播放 ${liveSongs.size} 首歌曲" else "立即播放这首歌曲",
            ) {
                onDismiss()
                onPlay(liveSongs)
            }
        }

        // ========== 3. 添加到播放队列（Dart 原项；可被 hiddenItems 隐藏） ==========
        if (EntityActionItem.ADD_TO_QUEUE !in hiddenItems) {
            ActionRow(
                icon = Icons.Filled.QueueMusic,
                iconColor = primary,
                title = "添加到播放队列",
                subtitle = "将 ${liveSongs.size} 首歌曲添加到队列",
            ) {
                onDismiss()
                // Dart _addToQueue：逐首 playlistData.addSong（队列追加）
                // 注：提示文案由调用方（onPlayNext）负责，此处不再弹 Toast，避免与页面 Snackbar 重复
                for (song in liveSongs) {
                    PlaylistRepository.addSong(song)
                }
                onPlayNext(liveSongs)
            }
        }

        // ========== 4. 添加到歌单（可被 hiddenItems 隐藏） ==========
        if (EntityActionItem.ADD_TO_PLAYLIST !in hiddenItems) {
            ActionRow(
                icon = Icons.Filled.PlaylistAdd,
                iconColor = primary,
                title = "添加到歌单",
                subtitle = "添加到自建歌单",
            ) {
                onDismiss()
                onAddToPlaylist(liveSongs)
            }
        }

        // ===================== 5. 缓存全部（仅远程未缓存歌曲存在时） =====================
        if (uncachedRemoteSongs.isNotEmpty()) {
            ActionRow(
                icon = Icons.Filled.CloudDownload,
                iconColor = primary,
                title = if (uncachedRemoteSongs.size > 1) "缓存全部" else "缓存歌曲",
                subtitle = if (uncachedRemoteSongs.size > 1) {
                    "缓存 ${uncachedRemoteSongs.size} 首未缓存的远程歌曲"
                } else {
                    "下载到本地缓存，离线可播放"
                },
            ) {
                // Dart _cacheAll：CacheService.startCaching（后台缓存，不阻塞 UI）
                for (song in uncachedRemoteSongs) {
                    CacheService.startCaching(song)
                }
                Toast.makeText(
                    appContext,
                    if (uncachedRemoteSongs.size > 1) {
                        "已在后台开始缓存 ${uncachedRemoteSongs.size} 首歌曲"
                    } else {
                        "开始缓存「${uncachedRemoteSongs.first().title}」"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            }
        }

        // ========== 6. 默认缓存本艺术家音乐（仅艺术家） ==========
        if (entity is EntityActionTarget.ArtistTarget) {
            ActionRow(
                icon = Icons.Filled.CloudSync,
                iconColor = primary,
                title = "默认缓存本艺术家音乐",
                subtitle = "自动缓存该艺术家的新歌曲",
            ) {
                // Dart _setDefaultCacheForArtist：当前仅提示（未落库）
                Toast.makeText(
                    appContext,
                    "已设置默认缓存 ${entity.artist.name} 的音乐",
                    Toast.LENGTH_SHORT,
                ).show()
                onDismiss()
            }
        }

        // ========== 6.5 排除该歌手（第二十六轮需求 3） ==========
        // 可作用于**歌曲目标**（拿这首歌的歌手）与**歌手目标**；排除后拉取音乐时跳过其全部歌曲。
        val excludeArtistName: String? = when (entity) {
            is EntityActionTarget.ArtistTarget -> entity.artist.name
            is EntityActionTarget.SongTarget -> entity.songs.firstOrNull()?.artist
            else -> null
        }
        if (EntityActionItem.EXCLUDE_ARTIST !in hiddenItems &&
            !excludeArtistName.isNullOrBlank()
        ) {
            val artistName = excludeArtistName
            val alreadyExcluded = ExclusionList.isArtistExcluded(artistName)
            ActionRow(
                icon = Icons.Filled.Block,
                iconColor = DangerRed,
                titleColor = DangerRed,
                title = if (alreadyExcluded) "已排除该歌手" else "排除该歌手",
                subtitle = if (alreadyExcluded) {
                    "该歌手的音乐已从曲库移除，拉取时也不再导入"
                } else {
                    // 第二十七轮需求：排除歌手要**同时清掉曲库内已有的音乐**
                    // （歌曲列表、其专辑、播放列表内的该歌手音乐），不只是"以后不再导入"。
                    "从曲库移除「$artistName」的全部音乐与专辑，以后也不再导入"
                },
            ) {
                if (alreadyExcluded) {
                    onDismiss()
                } else {
                    scope.launch {
                        // 内部完成：写排除列表 + 删本地文件/缓存 + 批量删库 + 移出播放列表。
                        // 专辑与艺术家列表由 songs 表聚合而来，删行后自动消失。
                        val result = LibrarySongDeleter.excludeArtistAndPurge(artistName)
                        MusicLibrary.reload()
                        Toast.makeText(
                            appContext,
                            when {
                                result.deleted > 0 -> "已排除歌手「$artistName」并移除 ${result.deleted} 首音乐"
                                else -> "已排除歌手「$artistName」"
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                        // 通知调用方刷新（与 onDelete 同一契约：只做刷新，不得再删）
                        onExcludeArtist(artistName, result.deleted)
                        onDismiss()
                    }
                }
            }
        }

        // ========== 7. 永久删除（仅本地歌曲可用）/ 从本歌单移除（deleteFromLibrary = false 时始终显示） ==========
        val showDeleteItem = liveSongs.isNotEmpty() && (!deleteFromLibrary || deletable)
        if (showDeleteItem) {
            ActionRow(
                icon = Icons.Filled.DeleteForever,
                iconColor = DangerRed,
                titleColor = DangerRed,
                title = deleteLabel,
                subtitle = when {
                    // deleteFromLibrary = false：调用方语义（歌单"从本歌单移除"等），文案随 deleteLabel
                    !deleteFromLibrary -> if (liveSongs.size > 1) {
                        "从当前列表中移除这 ${liveSongs.size} 首歌曲"
                    } else {
                        "从当前列表中移除这首歌曲"
                    }

                    liveSongs.size > 1 -> "从曲库中永久删除这 ${liveSongs.size} 首本地歌曲"
                    else -> "从曲库中永久删除这首本地歌曲"
                },
            ) {
                val targets = liveSongs
                if (!deleteFromLibrary) {
                    // 弹窗不写库，仅回调（歌单详情页执行 removeSongFromPlaylist）
                    onDelete(targets)
                    onDismiss()
                } else {
                    scope.launch {
                        // 删库 + 同步移出播放列表（若删的是正在播放的歌，会自动接续下一首）
                        LibrarySongDeleter.delete(targets)
                        MusicLibrary.reload()
                        // 提示文案由调用方（onDelete）负责，避免与页面 Snackbar 重复
                        onDelete(targets)
                        onDismiss()
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 单条操作项（对应 Dart `_buildActionItem` 的 ListTile：
 * 左侧图标 + 标题 + 12sp 次要色副标题，整行可点击）。
 */
@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color? = null,
    titleColor: Color? = null,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor ?: primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                color = titleColor ?: onSurface,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
