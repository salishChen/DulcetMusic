/*
 * 从曲库永久删除歌曲的统一入口。
 *
 * 为什么需要它：删除与播放队列是两个独立的数据结构（`DatabaseHelper` / `PlaylistRepository`），
 * 若只删库不清理队列，会出现"播放列表里还留着一首已经不存在的歌"。
 * 需求（第十一轮）：在歌曲页面删除**正在播放**的那首歌时，播放列表里也要同步删掉它，
 * 并接着播放下一首 —— 于是把"删库 + 移出播放列表"收敛到这个唯一入口，
 * 队列侧的接续播放由 `PlayerController.syncQueue` 依据位置变化自动完成。
 *
 * 与 Dart 的差异：Dart 端 `deleteSong` 只删库，播放列表要等用户手动清理
 * （`Dismissible` 或播放列表菜单）；原生端按需求在删除时一并同步。
 *
 * ===================== 第二十六轮需求 4：区分本地 / 在线 =====================
 * 「永久删除」先判断音乐类型，两条链路完全不同：
 *  - **本地音乐**（`sourceType == local`）：调用方先弹确认框，确认后**直接删除本地文件**，
 *    再删库并移出播放列表；
 *  - **在线歌曲**（Subsonic）：从歌曲列表删除 + **删除其缓存**（音频与封面），
 *    并把「歌曲名 + 歌手」写入**排除列表**，避免下次扫描又被拉回来。
 *    在线歌曲不删远端服务器上的文件（本应用无权也无法安全地删除远端资源）。
 *
 * 排除列表的写入与"从界面移除"的顺序无关，先写排除再删库，
 * 即使中途失败也不会把歌留在"会被再次导入"的状态。
 * =======================================================================
 */
package com.mtechviral.musicfinderexample.core.player

import android.util.Log
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 曲库删除 + 播放列表同步。
 *
 * 调用方只需负责刷新自己的界面数据（列表/快照），队列与接续播放由本入口和
 * [PlayerController] 保证。
 */
object LibrarySongDeleter {

    private const val TAG = "LibrarySongDeleter"

    /** 删除结果，供调用方拼接提示文案 */
    data class Result(
        /** 实际删库的歌曲数 */
        val deleted: Int = 0,
        /** 实际删除的本地文件数（本地音乐） */
        val filesDeleted: Int = 0,
        /** 加入排除列表的在线歌曲数 */
        val excluded: Int = 0,
        /** 删除缓存失败的歌曲数 */
        val failed: Int = 0,
    )

    /**
     * 永久删除给定歌曲，并把它们移出播放列表。
     *
     * 按需求 4 区分类型：
     * - 本地：删除磁盘文件（失败只记日志，仍继续删库，避免"删不掉就永远删不掉"）；
     * - 在线：删除音频与封面缓存，并把「歌名 + 歌手」写入排除列表。
     *
     * @return 删除统计（[Result]）；旧调用方只关心"删了几首"时取 `result.deleted`
     */
    suspend fun delete(songs: Collection<Song>): Result {
        val targets = songs.toList()
        if (targets.isEmpty()) return Result()

        var deleted = 0
        var filesDeleted = 0
        var excluded = 0
        var failed = 0

        for (song in targets) {
            // ---- 在线歌曲：先写排除列表（保证"删掉后不会被扫描重新拉回来"）----
            if (song.isRemote) {
                try {
                    ExclusionList.addSong(song.title, song.artist)
                    excluded++
                } catch (e: Exception) {
                    Log.w(TAG, "写入排除列表失败: ${song.title} - ${e.message}")
                }
            } else {
                // ---- 本地音乐：直接删除磁盘文件 ----
                try {
                    val file = File(song.path)
                    if (file.exists() && file.isFile) {
                        if (file.delete()) {
                            filesDeleted++
                        } else {
                            failed++
                            Log.w(TAG, "本地文件删除失败: ${song.path}")
                        }
                    }
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "本地文件删除异常: ${song.path} - ${e.message}")
                }
            }

            // ---- 在线歌曲：删除音频缓存与本地封面缓存 ----
            if (song.isRemote) {
                song.id?.let { id ->
                    try {
                        CacheService.deleteCache(listOf(id))
                        deleteArtworkCacheFile(song)
                    } catch (e: Exception) {
                        failed++
                        Log.w(TAG, "删除缓存失败: ${song.title} - ${e.message}")
                    }
                }
            }

            // ---- 删库 ----
            val id = song.id ?: continue
            DatabaseHelper.deleteSong(id)
            deleted++
        }

        // 关键一步：同步移出播放列表（会触发 PlayerController.syncQueue）。
        // 若被删的正是当前播放项，syncQueue 会接续播放下一首。
        PlaylistRepository.removeSongs(targets.map { it.path })
        return Result(deleted = deleted, filesDeleted = filesDeleted, excluded = excluded, failed = failed)
    }

    /**
     * 把某位歌手加入排除列表，并**清掉曲库中该歌手已有的音乐**（第二十七轮需求）。
     *
     * 行为对齐「永久删除」的在线分支，但作用于整位歌手：
     * 1. 写入排除列表（歌手条目）→ 以后扫描 / 拉取音乐时跳过其全部歌曲；
     * 2. 取出该歌手的全部歌曲（**同时匹配 `artist` 与 `albumArtist`**，
     *    否则合辑里 artist=「群星」的曲目会漏掉）并逐首清理：
     *    - 本地音乐：删除磁盘文件；
     *    - 在线歌曲：删除音频缓存与封面缓存；
     * 3. 批量删库（一次 SQL，级联清理歌单绑定）。
     *
     * 专辑与艺术家的消失是**自动**的：两个页面都是从 `songs` 表聚合出来的
     * （`GROUP BY album` / `GROUP BY artist`），歌曲行删掉后聚合结果自然不再包含它们，
     * 因此无需（也不应）单独维护一张"专辑表/艺术家表"。
     *
     * 播放列表内该歌手的歌曲同样一并移出（`PlaylistRepository.removeSongs`），
     * 若移出的正是当前播放项，`PlayerController.syncQueue` 会接续播放下一首。
     *
     * @param artist 歌手名（用于匹配 `artist` / `albumArtist`）
     * @return 删除统计（[Result.excluded] 为 1 表示已写入歌手排除条目）
     */
    suspend fun excludeArtistAndPurge(artist: String): Result {
        val name = artist.trim()
        if (name.isEmpty()) return Result()

        // 1. 先写排除列表：即使后续删库中途失败，也不会再次被扫描导入
        var excluded = 0
        try {
            ExclusionList.addArtist(name)
            excluded = 1
        } catch (e: Exception) {
            Log.w(TAG, "写入歌手排除失败: $name - ${e.message}")
        }

        // 2. 取出该歌手全部歌曲（含 albumArtist 匹配）
        val targets = try {
            DatabaseHelper.querySongsByArtistIncludingAlbumArtist(name)
        } catch (e: Exception) {
            Log.w(TAG, "查询歌手歌曲失败: $name - ${e.message}")
            emptyList()
        }
        if (targets.isEmpty()) return Result(excluded = excluded)

        // 3. 逐首清理文件与缓存（与「永久删除」同一套规则）
        var filesDeleted = 0
        var failed = 0
        val ids = ArrayList<Long>(targets.size)
        for (song in targets) {
            if (!song.isRemote) {
                try {
                    val file = File(song.path)
                    if (file.exists() && file.isFile) {
                        if (file.delete()) filesDeleted++ else failed++
                    }
                } catch (e: Exception) {
                    failed++
                    Log.w(TAG, "本地文件删除异常: ${song.path} - ${e.message}")
                }
            } else {
                song.id?.let { id ->
                    try {
                        CacheService.deleteCache(listOf(id))
                        deleteArtworkCacheFile(song)
                    } catch (e: Exception) {
                        failed++
                        Log.w(TAG, "删除缓存失败: ${song.title} - ${e.message}")
                    }
                }
            }
            song.id?.let { ids.add(it) }
        }

        // 4. 批量删库（一次 SQL；歌单绑定由外键级联清理）
        val deleted = try {
            DatabaseHelper.deleteSongsByIds(ids)
        } catch (e: Exception) {
            Log.w(TAG, "批量删库失败: $name - ${e.message}")
            0
        }

        // 5. 同步移出播放列表（触发 syncQueue；删到当前播放项会自动接续下一首）
        PlaylistRepository.removeSongs(targets.map { it.path })

        return Result(
            deleted = deleted,
            filesDeleted = filesDeleted,
            excluded = excluded,
            failed = failed,
        )
    }

    /**
     * 删除本地封面缓存文件。
     *
     * [CacheService.deleteCache] 只处理音频缓存（`cachedPath`），封面文件
     * （`cachedArtworkPath`，位于 cache/artwork/）需要单独删，否则会留下孤儿文件
     * 一直占着缓存池空间。同时清掉数据库中该字段。
     */
    private suspend fun deleteArtworkCacheFile(song: Song) {
        val artworkPath = song.cachedArtworkPath
        if (!artworkPath.isNullOrEmpty()) {
            withContext(Dispatchers.IO) {
                runCatching { File(artworkPath).takeIf { it.exists() }?.delete() }
            }
        }
        song.id?.let { DatabaseHelper.clearArtworkCache(it) }
    }
}
