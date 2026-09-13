package com.mtechviral.musicfinderexample.core.model

/**
 * 播放模式。
 *
 * 顺序与枚举序号必须与数据库/SharedPreferences 中持久化的 index 一致：
 * 原 Flutter 端以 `_playMode.index` 写入偏好，读取时按 `PlayMode.values[i]` 还原。
 *
 * 对应原 Flutter 工程 `lib/data/playlist_data.dart` 中的 `PlayMode`。
 */
enum class PlayMode(val label: String) {
    /** 列表循环：依次播放，越过末尾回到开头 */
    SEQUENTIAL("列表循环"),

    /** 随机播放：打乱列表顺序后按新顺序播放 */
    RANDOM("随机播放"),

    /** 单曲循环：始终重复当前歌曲 */
    SINGLE("单曲循环");

    /** 切换到下一个模式（顺序 -> 随机 -> 单曲 -> 顺序） */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 按持久化的 index 还原，越界回退 SEQUENTIAL */
        fun fromIndex(index: Int): PlayMode =
            entries.getOrElse(index) { SEQUENTIAL }
    }
}
