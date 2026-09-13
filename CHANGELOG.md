## [2.0.0] - 原生 Android 重写

以 Kotlin + Jetpack Compose + Media3 完整重写，不再依赖 Flutter 框架（见 `android` 分支）。

* 架构：按职责拆分为 `core:{model,common,database,network,media,cache,player,designsystem}`
  与 `feature:{home,songs,albums,artists,playlists,favorites,search,nowplaying,scan,stats,cache,subsonic,settings}`。
* 播放：Media3 ExoPlayer + `MediaSessionService`，通知栏（上一曲/播放暂停/下一曲 + 自定义「词/解锁」按钮）、
  耳机与蓝牙媒体按键、列表循环 / 随机 / 单曲循环。
* 播放页：手势展开收起的「正在播放」覆盖层，封面/歌词切换、逐行歌词高亮、播放列表抽屉。
* 曲库：媒体库扫描与文件夹扫描（分批解析、进度回调）、专辑/艺术家/歌单/喜欢聚合、全库搜索、播放统计。
* 远程：Subsonic 内网优先连接、远程曲库导入、歌单同步与推送、歌词与封面拉取、缓存池 LRU 淘汰与缓存管理。
* 系统集成：全局悬浮窗歌词（可拖动/锁定/配色/字号/单双行）、桌面小组件。
* 兼容：沿用原 `applicationId`、数据库文件与版本、缓存目录、偏好键与通知渠道，可直接覆盖安装复用旧数据。

## [0.0.1] - Alpha Release

* Beautiful Music Player with all basic functionalities.
* Minor Bugs
* Only Android support as of now.
