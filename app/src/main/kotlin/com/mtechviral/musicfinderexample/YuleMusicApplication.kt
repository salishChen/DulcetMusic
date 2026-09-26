package com.mtechviral.musicfinderexample

import android.app.Application
import android.util.Log
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.common.ThemePreference
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.player.LyricsOverlayManager
import com.mtechviral.musicfinderexample.core.player.PlayerController
import com.mtechviral.musicfinderexample.core.player.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 对应原 Flutter 工程 `lib/main.dart` 的 `main()` + `MyMaterialAppState.initPlatformState()`：
 *
 * 1. 恢复主题偏好（默认浅色）；
 * 2. 初始化数据库、缓存池、偏好设置；
 * 3. 绑定前台播放服务（等价 `AudioService.init`，同时接管耳机/蓝牙媒体按键）；
 * 4. 初始化悬浮窗歌词管理器与桌面小组件；
 * 5. 后台加载曲库、Subsonic 配置、上次的播放列表，并补缓存未完成的远程封面。
 */
class YuleMusicApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // 1. 偏好设置 / 主题（默认浅色）+ 排除列表
        AppPreferences.init(this)
        ThemePreference.load()
        ExclusionList.load()

        // 2. 数据库 / 缓存池
        DatabaseHelper.init(this)
        CacheService.init(this)

        // 3. 前台播放服务（MediaSession + ExoPlayer）
        PlayerController.init(this)

        // 4. 悬浮窗歌词 / 桌面小组件
        LyricsOverlayManager.init(this)

        // 5. 后台初始化数据
        appScope.launch {
            // 曲库（不再直接查询安卓媒体库；曲库由「扫描音乐」页面扫描入库）
            try {
                MusicLibrary.load()
            } catch (e: Exception) {
                Log.w(TAG, "加载曲库失败: ${e.message}")
            }

            // 启动时加载 Subsonic 配置，确保重启后无需先进入配置页即可播放远程歌曲
            try {
                SubsonicService.loadConfig()
            } catch (e: Exception) {
                Log.w(TAG, "加载 Subsonic 配置失败: ${e.message}")
            }

            // EasyTier 组网（省电策略，耗电优化）：
            // 不在启动时拉起 —— 由远程访问按需唤醒（SubsonicService 统一触达），
            // 空闲 15 分钟自动休眠；本地转发地址经回调注入 SubsonicService
            com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine.init(this@YuleMusicApplication)
            com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine.onActiveBaseUrlChanged = { base ->
                SubsonicService.easyTierBaseUrl = base
                SubsonicService.resetConnection()
            }

            // 恢复上次关闭前的播放列表（从偏好回查入库歌曲）
            try {
                PlaylistRepository.restoreFromPrefs()
            } catch (e: Exception) {
                Log.w(TAG, "恢复播放列表失败: ${e.message}")
            }

            // 后台继续缓存未完成的封面（启动时不阻塞 UI）
            cachePendingArtwork()
        }
    }

    /**
     * 检查并缓存未完成的封面（coverArtId 有值但 cachedArtworkPath 为空的歌曲）。
     * 缓存完成后刷新曲库快照（保留当前播放态，不重建实例）。
     */
    private suspend fun cachePendingArtwork() {
        try {
            val pendingSongs = DatabaseHelper.querySongsNeedingArtworkCache()
            if (pendingSongs.isEmpty()) return
            Log.d(TAG, "启动时发现 ${pendingSongs.size} 首歌曲封面未缓存，开始后台缓存...")
            CacheService.cacheArtworkBatch(pendingSongs)
            MusicLibrary.reload()
        } catch (e: Exception) {
            Log.w(TAG, "启动时封面缓存失败: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "YuleMusicApplication"
    }
}
