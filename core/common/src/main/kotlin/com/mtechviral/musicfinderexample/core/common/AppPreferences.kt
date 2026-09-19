package com.mtechviral.musicfinderexample.core.common

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局键值存储门面。
 *
 * 原生端使用独立的 SharedPreferences 文件 [PREFS_NAME]，
 * 同时**兼容读取**旧 Flutter 版本（shared_preferences 插件）写入的数据：
 * 插件把所有键统一存放在 `FlutterSharedPreferences` 文件内，并加 `flutter.` 前缀。
 *
 * 这样从旧版本覆盖安装后：
 * - 主题偏好、缓存池大小、上次播放列表与播放模式都能自动延续；
 * - 后续写入落到新文件，无需再次迁移。
 */
object AppPreferences {

    private const val PREFS_NAME = "yule_music_prefs"

    /** shared_preferences 插件使用的文件名 */
    private const val LEGACY_PREFS_NAME = "FlutterSharedPreferences"

    /** shared_preferences 插件自动添加的键前缀 */
    private const val LEGACY_KEY_PREFIX = "flutter."

    // ---- 键名：全部沿用旧版本键，保证可读出旧数据 ----
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_CACHE_SIZE_MB = "subsonic_cache_size_mb"
    const val KEY_LAST_PLAYLIST_SONGS = "last_playlist_songs"
    const val KEY_LAST_PLAYLIST_MODE = "last_playlist_mode"

    /** 「缓存我喜欢」：添加歌曲到喜欢时是否自动缓存到本地（原生新增键，旧版本无此键） */
    const val KEY_AUTO_CACHE_LIKED = "auto_cache_liked"

    private var prefs: SharedPreferences? = null
    private var legacyPrefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs = app.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun require(): SharedPreferences = requireNotNull(prefs) {
        "AppPreferences 未初始化，请在 Application.onCreate 中调用 AppPreferences.init(context)"
    }

    // ===================== 读取（新库优先，回退旧库） =====================

    fun getString(key: String, def: String? = null): String? =
        require().getString(key, null)
            ?: legacyPrefs?.getString(LEGACY_KEY_PREFIX + key, def)

    fun getInt(key: String, def: Int): Int {
        val p = require()
        if (p.contains(key)) return p.getInt(key, def)
        return legacyPrefs?.let {
            if (it.contains(LEGACY_KEY_PREFIX + key)) it.getInt(LEGACY_KEY_PREFIX + key, def) else def
        } ?: def
    }

    fun getLong(key: String, def: Long): Long {
        val p = require()
        if (p.contains(key)) return p.getLong(key, def)
        return legacyPrefs?.let {
            if (it.contains(LEGACY_KEY_PREFIX + key)) it.getLong(LEGACY_KEY_PREFIX + key, def) else def
        } ?: def
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        val p = require()
        if (p.contains(key)) return p.getBoolean(key, def)
        return legacyPrefs?.let {
            if (it.contains(LEGACY_KEY_PREFIX + key)) {
                it.getBoolean(LEGACY_KEY_PREFIX + key, def)
            } else {
                def
            }
        } ?: def
    }

    // ===================== 写入（只写新库） =====================

    fun putString(key: String, value: String?) {
        require().edit().putString(key, value).apply()
    }

    fun putInt(key: String, value: Int) {
        require().edit().putInt(key, value).apply()
    }

    fun putLong(key: String, value: Long) {
        require().edit().putLong(key, value).apply()
    }

    fun putBoolean(key: String, value: Boolean) {
        require().edit().putBoolean(key, value).apply()
    }

    /** 供批量写入场景（如悬浮窗歌词）使用 */
    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        require().edit().apply(block).apply()
    }
}
