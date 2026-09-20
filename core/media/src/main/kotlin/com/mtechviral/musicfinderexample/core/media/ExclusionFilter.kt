package com.mtechviral.musicfinderexample.core.media

import android.util.Log
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.model.Song

/**
 * 排除列表在「扫描 / 导入」侧的过滤桥。
 *
 * 需求（第二十六轮需求 3）：删除一首在线音乐后把它写入排除列表，
 * 下次扫描时自动跳过；若排除的是歌手，则该歌手的全部歌曲一并跳过。
 *
 * 之所以单独放一个薄封装，而不是各处直接调 [ExclusionList]：
 * - 让「命中判定」在扫描与远程导入两条链路上只有一处实现；
 * - 便于集中记录被跳过的数量（日志），排查"某首歌扫不进来"这类问题。
 *
 * 注意：**只跳过"新导入"**，不会动已经入库的歌曲。也就是说，取消排除后
 * 重新扫描即可把音乐重新导入（需求：取消后在导入时可重新加入播放列表）。
 */
object ExclusionFilter {

    private const val TAG = "ExclusionFilter"

    /**
     * 过滤掉被排除的歌曲，返回应当入库的列表。
     *
     * 排除列表为空时（绝大多数情况）直接返回原列表，零开销。
     */
    fun filter(songs: List<Song>): List<Song> {
        if (songs.isEmpty() || ExclusionList.isEmpty) return songs
        val accepted = songs.filterNot { isExcluded(it) }
        if (accepted.size != songs.size) {
            Log.d(TAG, "排除列表跳过 ${songs.size - accepted.size} 首音乐")
        }
        return accepted
    }

    /** 单曲是否应被排除（命中单曲条目，或其歌手 / 专辑艺术家被整位排除） */
    fun isExcluded(song: Song): Boolean =
        ExclusionList.isExcluded(song.title, song.artist, song.albumArtist)
}
