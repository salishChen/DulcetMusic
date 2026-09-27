pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "YuYueMusic"

// ---------------------------------------------------------------------------
// app：宿主壳层（Activity / 全局导航 / 常驻迷你播放栏）
// ---------------------------------------------------------------------------
include(":app")

// ---------------------------------------------------------------------------
// core：按职责纵向切分的底层能力模块
// ---------------------------------------------------------------------------
include(":core:model")        // 数据实体：Song / Album / Artist / Playlist / SubsonicConfig / PlayMode
include(":core:common")       // 通用工具：LRC 解析、格式化、偏好设置、主题偏好
include(":core:database")     // SQLite（music_player.db）：建表/迁移/全部查询
include(":core:network")      // Subsonic REST 客户端（内网优先 + 认证 + 歌单同步）
include(":core:remote")       // 单活动远程源会话与曲库同步
include(":core:easytier")     // EasyTier 去中心化组网（无 TUN 端口转发，见 doc/EasyTier集成方案.md）
include(":core:media")        // 媒体库/文件夹扫描、元数据解析、内嵌封面读取
include(":core:cache")        // Subsonic 缓存池：下载、LRU 淘汰、封面缓存
include(":core:player")       // Media3 播放服务、播放列表、悬浮歌词、桌面小组件
include(":core:designsystem") // 主题（浅色/深色）与通用 UI 组件

// ---------------------------------------------------------------------------
// feature：按页面/业务能力横向切分的功能模块
// ---------------------------------------------------------------------------
include(":feature:home")       // 主框架：侧边抽屉 + 底部导航 + 页面容器
include(":feature:songs")      // 歌曲列表页
include(":feature:albums")     // 专辑列表页 + 专辑详情页
include(":feature:artists")    // 艺术家列表页 + 艺术家详情页
include(":feature:playlists")  // 歌单列表页 + 歌单详情页
include(":feature:favorites")  // 我喜欢（歌曲/专辑/艺术家）
include(":feature:search")     // 全库搜索
include(":feature:nowplaying") // 正在播放页（手势展开/收起、歌词、播放列表抽屉）
include(":feature:scan")       // 扫描音乐（媒体库 / 指定文件夹）
include(":feature:stats")      // 播放统计
include(":feature:cache")      // 缓存管理
include(":feature:subsonic")   // Subsonic 服务器配置
include(":feature:settings")   // 设置
