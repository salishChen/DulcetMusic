# Flutter Music Player

A beautiful, Material 3 based local music player built with Flutter. Scan your
Android media library or any folder, browse by songs / albums / artists /
playlists, and enjoy a gesture-driven "Now Playing" experience with synced
lyrics and a blur-artwork background.

> App name: **愉乐** (Yúlè). Target platform: **Android**.

## Features

- **Music Scanning** — Scan the Android media library or a chosen folder;
  metadata (title, artist, album, duration, bitrate, sample rate, codec,
  embedded artwork & lyrics) is read directly from each file and stored in a
  local SQLite database.
- **Library Browsing** — Browse your collection by Songs, Albums, Artists and
  user-created Playlists.
- **Playlists** — Create / delete playlists and add / remove songs freely.
- **Now Playing** — A gesture-driven full-screen player: slide up from the
  mini bar to expand, drag down to dismiss, swipe left for lyrics, swipe up for
  the play queue.
- **Synced Lyrics** — Parses embedded LRC lyrics, shows the current line and a
  3-line preview, with a full scrollable panel that seeks on tap.
- **Playback Modes** — Sequential (list loop), Shuffle and Single-loop, with
  live switching.
- **Mute & Seek** — One-tap mute and draggable progress bar.
- **Android Media Session** — Lockscreen / notification controls for
  play / pause / next / previous powered by `audio_service`.
- **Theme** — Light / Dark / System theme with persistent preference.
- **Persistent Mini Player** — A bottom mini player bar stays visible across
  all routes.

## Tech Stack

| Category      | Package |
| ------------- | ------- |
| UI Framework  | Flutter / Dart (Material 3) |
| Audio         | `audioplayers` (playback), `audio_service` (media session) |
| Media Query   | `on_audio_query` (media library paths) |
| Metadata      | `audio_metadata_reader` (read tags, artwork, lyrics) |
| Storage       | `sqflite` (local database), `path_provider`, `path` |
| File Picking  | `file_picker` (folder selection) |
| Preferences   | `shared_preferences` (theme) |

## Getting Started

### Prerequisites

- Flutter SDK `>= 3.0.0` (tested with the stable channel)
- An Android device or emulator (the app relies on Android media APIs)
- Required Android permissions: read external storage / media (handled via
  `on_audio_query` permission requests).

### Installation & Run

```bash
# Clone the repository
git clone <your-repo-url>
cd Flutter-Music-Player-master

# Install dependencies
flutter pub get

# Run on a connected Android device / emulator
flutter run
```

## Usage

1. **Scan Music** — Open the *Scan Music* page, choose to scan the Android
   media library or a specific folder. The scan runs in the background
   (isolated) and reports progress; scanned tracks are written into the local
   database.
2. **Browse & Play** — On the *Songs* page (or any list), tap a track to start
   playback. A mini player bar appears at the bottom.
3. **Now Playing** — Tap the mini bar (or swipe up) to open the full-screen
   player:
   - Swipe **left** for the synced lyrics panel (tap a line to seek).
   - Swipe **up** for the current play queue; swipe down / tap back to return.
   - Drag **down** anywhere to dismiss.
4. **Playlists** — Create playlists from the *Playlists* page, tap a song's
   options to add it, and manage contents in the playlist detail page.
5. **Settings** — Switch theme (System / Light / Dark) and view app info.

## Project Structure

```
lib/
├── main.dart                 # App entry, AudioService init, global state
├── my_app.dart               # Root MaterialApp & mini player bar
├── data/                     # Data layer
│   ├── models/               # Song / Album / Artist / Playlist entities
│   ├── audio_handler.dart     # Central playback controller + media session
│   ├── audio_player_instance.dart
│   ├── database_helper.dart   # SQLite (songs / playlists / playlist_songs)
│   ├── metadata_service.dart  # Scan & parse metadata (isolated)
│   ├── playlist_data.dart     # In-memory play queue & play mode
│   └── song_data.dart         # Songs snapshot & reactive notifier
├── pages/                    # Screens (songs, albums, artists, playlists, scan, settings, now playing)
├── widgets/                  # Reusable UI (mini bar, artwork, blur, list items, drawer...)
└── utils/                    # Themes & LRC lyrics parser
```

## Notes

- The library is **empty on first launch**; you must scan music before anything
  appears.
- Metadata is read **from the audio files themselves**, not from the Android
  media store, so playback always uses the real local file path.
- Duplicate detection uses `title | artist | album`; re-scanning the same
  source is skipped, while the same song from a different source overwrites the
  older record.

## License

This project is provided for learning and personal use. Please check the
repository for the specific license before redistribution.

---

# 愉乐 · Flutter 音乐播放器

一款基于 Material 3 设计的本地音乐播放器，使用 Flutter 构建。可扫描安卓媒体库或任意文件夹，按歌曲 / 专辑 / 艺术家 / 歌单浏览，并享受手势驱动的「正在播放」页面、同步歌词与模糊封面背景。

> 应用名：**愉乐**。目标平台：**Android**。

## 功能特性

- **音乐扫描** —— 扫描安卓媒体库或指定文件夹；歌曲的标题、艺术家、专辑、时长、比特率、采样率、编码、内嵌封面与歌词等元数据均直接从音频文件读取，并存入本地 SQLite 数据库。
- **曲库浏览** —— 可分别按歌曲、专辑、艺术家以及用户自建歌单浏览音乐。
- **歌单管理** —— 自由创建 / 删除歌单，并向歌单中添加 / 移除歌曲。
- **正在播放页** —— 手势驱动的全屏播放页：从底部播放栏上滑展开、下滑收起、左滑看歌词、上滑看播放列表。
- **同步歌词** —— 解析内嵌 LRC 歌词，显示当前句与最近 3 行预览，并提供可滚动详情面板，点击任意歌词即可跳转播放进度。
- **播放模式** —— 支持列表循环、随机播放、单曲循环，可实时切换。
- **静音与拖动** —— 一键静音，进度条可拖动定位。
- **安卓媒体会话** —— 借助 `audio_service` 在锁屏 / 通知栏控制播放 / 暂停 / 上一首 / 下一首。
- **主题切换** —— 支持浅色 / 深色 / 跟随系统，并持久化保存偏好。
- **常驻迷你播放栏** —— 底部迷你播放栏在所有页面始终可见。

## 技术栈

| 类别     | 依赖包 |
| -------- | ------ |
| UI 框架  | Flutter / Dart（Material 3） |
| 音频播放 | `audioplayers`（播放）、`audio_service`（媒体会话） |
| 媒体库   | `on_audio_query`（媒体库路径） |
| 元数据   | `audio_metadata_reader`（解析标签、封面、歌词） |
| 存储     | `sqflite`（本地数据库）、`path_provider`、`path` |
| 文件选择 | `file_picker`（文件夹选择） |
| 偏好设置 | `shared_preferences`（主题） |

## 环境准备

- Flutter SDK `>= 3.0.0`（已在稳定版通道测试）
- 安卓设备或模拟器（应用依赖安卓媒体相关 API）
- 所需安卓权限：读取外部存储 / 媒体（由 `on_audio_query` 在运行时申请）

## 安装与运行

```bash
# 克隆仓库
git clone <your-repo-url>
cd Flutter-Music-Player-master

# 安装依赖
flutter pub get

# 在已连接的安卓设备 / 模拟器上运行
flutter run
```

## 使用说明

1. **扫描音乐** —— 打开「扫描音乐」页，选择扫描安卓媒体库或指定文件夹。扫描在后台（独立 isolate）运行并显示进度，扫描到的曲目写入本地数据库。
2. **浏览与播放** —— 在「歌曲」页（或任意列表）点击曲目即可开始播放，底部出现迷你播放栏。
3. **正在播放** —— 点击迷你播放栏（或上滑）打开全屏播放页：
   - 向左滑打开同步歌词面板（点击某行可跳转进度）。
   - 向上滑查看当前播放队列；下滑或点返回回到播放页。
   - 在任意位置向下拖拽即可收起播放页。
4. **歌单** —— 在「歌单」页创建歌单，点击歌曲的更多选项将其加入，并在歌单详情页管理内容。
5. **设置** —— 切换主题（跟随系统 / 浅色 / 深色），并查看应用信息。

## 项目结构

```
lib/
├── main.dart                 # 应用入口、AudioService 初始化、全局状态
├── my_app.dart               # 根 MaterialApp 与迷你播放栏
├── data/                     # 数据层
│   ├── models/               # Song / Album / Artist / Playlist 实体
│   ├── audio_handler.dart     # 集中式播放控制器 + 媒体会话
│   ├── audio_player_instance.dart
│   ├── database_helper.dart   # SQLite（songs / playlists / playlist_songs）
│   ├── metadata_service.dart  # 扫描并解析元数据（独立 isolate）
│   ├── playlist_data.dart     # 内存播放队列与播放模式
│   └── song_data.dart         # 歌曲快照与响应式通知
├── pages/                    # 各页面（歌曲、专辑、艺术家、歌单、扫描、设置、正在播放）
├── widgets/                  # 可复用 UI（播放栏、封面、模糊、列表项、抽屉等）
└── utils/                    # 主题与 LRC 歌词解析器
```

## 注意事项

- 首次启动曲库为空，需先扫描音乐才会有内容显示。
- 元数据是从音频文件本体读取，而非安卓媒体库，因此播放始终使用真实的本地文件路径。
- 去重以 `标题 | 艺术家 | 专辑` 为准：相同来源重复扫描会被跳过，不同来源的同一首歌会以后扫描到的覆盖旧记录。

## 许可证

本项目仅供学习和个人使用。如需再分发，请参阅仓库中的具体许可证说明。
