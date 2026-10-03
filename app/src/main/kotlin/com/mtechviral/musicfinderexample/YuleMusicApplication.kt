package com.mtechviral.musicfinderexample

import android.app.Application
import android.util.Log
import com.mtechviral.musicfinderexample.core.cache.CacheService
import com.mtechviral.musicfinderexample.core.common.AppPreferences
import com.mtechviral.musicfinderexample.core.common.AppVisibility
import com.mtechviral.musicfinderexample.core.common.ExclusionList
import com.mtechviral.musicfinderexample.core.common.ThemePreference
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.database.MusicLibrary
import com.mtechviral.musicfinderexample.core.easytier.EasyTierEngine
import com.mtechviral.musicfinderexample.core.model.RemoteProtocol
import com.mtechviral.musicfinderexample.core.network.SubsonicService
import com.mtechviral.musicfinderexample.core.remote.RemoteSessionManager
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

        // 1. 偏好设置 / 主题（默认浅色）+ 排除列表 + 前后台可见性
        AppPreferences.init(this)
        ThemePreference.load()
        ExclusionList.load()
        AppVisibility.init(this)

        // 2. 数据库 / 缓存池
        DatabaseHelper.init(this)
        CacheService.init(this)

        // 3. 前台播放服务（MediaSession + ExoPlayer）
        PlayerController.init(this)

        // 4. 悬浮窗歌词 / 桌面小组件
        LyricsOverlayManager.init(this)

        // 5. 后台初始化数据
        appScope.launch {
            try {
                RemoteSessionManager.load()
            } catch (e: Exception) {
                Log.w(TAG, "加载远程配置失败: ${e.message}")
            }
            // 曲库（不再直接查询安卓媒体库；曲库由「扫描音乐」页面扫描入库）
            try {
                MusicLibrary.load()
            } catch (e: Exception) {
                Log.w(TAG, "加载曲库失败: ${e.message}")
            }

            // EasyTier 组网（省电策略，耗电优化，doc/耗电分析报告.md §4）：
            // 不在启动时拉起；转发目标从当前数据源的内网地址解析，
            // Subsonic/Navidrome/Emby 数据源按需唤醒，实际隧道流量经租约保活，
            // 空闲 15 分钟自动休眠；直连数据源不启动/不保活引擎
            EasyTierEngine.init(this@YuleMusicApplication)
            EasyTierEngine.intranetUrlProvider = { RemoteSessionManager.source.value?.intranetUrl }
            // EmbyProvider obtains a target-checked forward directly from the engine.
            SubsonicService.tunnelEligible = { easyTierEligibleForActiveSource() }
            EasyTierEngine.onActiveBaseUrlChanged = { base ->
                SubsonicService.easyTierBaseUrl = base.takeIf { easyTierEligibleForActiveSource() }
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
     * 组网隧道是否可用于当前数据源（**组网与数据源解耦**：先组网、后配数据源同样生效）。
     *
     * 只要是 Subsonic/Navidrome 源即允许走隧道（探测顺序 EasyTier → 内网 → 公网，
     * 不可达自动回退），不再要求"数据源先于组网存在"的绑定关系。
     * 引擎唤醒与保活以此为前提（报告 §4.2）：其余协议的数据源不启动/不保活引擎。
     */
    private fun easyTierEligibleForActiveSource(): Boolean =
        RemoteSessionManager.source.value?.protocol in setOf(
            RemoteProtocol.SUBSONIC,
            RemoteProtocol.NAVIDROME,
        )

    /**
     * 检查并缓存未完成的封面（coverArtId 有值但 cachedArtworkPath 为空的歌曲）。
     *
     * 耗电优化（报告 §6）：批任务有预算、按封面 ID 去重、失败退避，
     * 低电量或计费网络未充电时跳过 —— 大曲库/弱网下不再每次启动全量补下载。
     * 缓存完成后刷新曲库快照（保留当前播放态，不重建实例）。
     */
    private suspend fun cachePendingArtwork() {
        try {
            val pendingSongs = DatabaseHelper.querySongsNeedingArtworkCache()
            if (pendingSongs.isEmpty()) return
            Log.d(TAG, "启动时发现 ${pendingSongs.size} 首歌曲封面未缓存，开始后台补缓存...")
            val updated = CacheService.cacheArtworkBatch(pendingSongs)
            if (updated.isNotEmpty()) MusicLibrary.reload()
        } catch (e: Exception) {
            Log.w(TAG, "启动时封面缓存失败: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "YuleMusicApplication"
    }
}
