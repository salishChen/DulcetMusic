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
 */
package com.mtechviral.musicfinderexample.core.player

import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 曲库删除 + 播放列表同步。
 *
 * 调用方只需负责刷新自己的界面数据（列表/快照），队列与接续播放由本入口和
 * [PlayerController] 保证。
 */
object LibrarySongDeleter {

    /**
     * 永久删除给定歌曲，并把它们移出播放列表。
     *
     * @return 实际发起删除的歌曲数（仅统计有 id、即已入库的歌曲）。
     */
    suspend fun delete(songs: Collection<Song>): Int {
        val targets = songs.toList()
        if (targets.isEmpty()) return 0

        var deleted = 0
        for (song in targets) {
            val id = song.id ?: continue
            DatabaseHelper.deleteSong(id)
            deleted++
        }

        // 关键一步：同步移出播放列表（会触发 PlayerController.syncQueue）。
        // 若被删的正是当前播放项，syncQueue 会接续播放下一首。
        PlaylistRepository.removeSongs(targets.map { it.path })
        return deleted
    }
}
