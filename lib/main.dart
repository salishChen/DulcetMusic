import 'package:audio_service/audio_service.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/audio_player_instance.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/playlist_data.dart';
import 'package:flute_example/data/song_data.dart';
import 'package:flute_example/data/subsonic_service.dart';
import 'package:flute_example/utils/themes.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_mini_player_bar.dart';

/// 全局播放列表（与音频处理器、InheritedWidget 共用同一实例）
final PlaylistData playlistData = PlaylistData();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // 恢复主题偏好（默认浅色）
  await loadThemeMode();
  // 初始化安卓媒体会话 / 通知栏控制器
  audioHandler = await AudioService.init(
    builder: () => MpAudioHandler(sharedAudioPlayer, playlistData),
    config: AudioServiceConfig(
      androidNotificationChannelId: 'com.mtechviral.musicfinderexample.audio',
      androidNotificationChannelName: '音乐播放',
      androidNotificationOngoing: true,
      androidShowNotificationBadge: true,
      notificationColor: kBrandPurple,
    ),
  );
  // 强制 Android 将耳机媒体按键（播放/暂停/上一曲/下一曲）路由到本应用的
  // 媒体会话。某些设备/蓝牙耳机上媒体按键可能不被自动路由，显式启用
  // 可确保按键事件被 AudioService 捕获并分发到 MpAudioHandler。
  try {
    await AudioService.androidForceEnableMediaButtons();
  } catch (e) {
    print('启用媒体按键监听失败: $e');
  }
  runApp(const MyMaterialApp());
}

class MyMaterialApp extends StatefulWidget {
  const MyMaterialApp({Key? key}) : super(key: key);

  @override
  MyMaterialAppState createState() => MyMaterialAppState();
}

class MyMaterialAppState extends State<MyMaterialApp>
    with TickerProviderStateMixin {
  SongData? songData;
  final DatabaseHelper dbHelper = DatabaseHelper.instance;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    // 全局播放页展开进度控制器：手势跟手 + 松手补间，供播放栏/播放页/主页共用
    nowPlayingController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
      value: 0.0,
    );
    initPlatformState();
  }

  @override
  void dispose() {
    nowPlayingController.dispose();
    songData?.audioPlayer.dispose();
    super.dispose();
  }

  /// 初始化：从 SQLite 数据库加载歌曲列表
  ///
  /// 不再直接查询安卓媒体库；曲库由「扫描音乐」页面扫描入库，
  /// 首次安装时曲库为空，需用户先去扫描。
  Future<void> initPlatformState() async {
    _isLoading = true;
    List<Song> songs = [];
    try {
      songs = await dbHelper.queryAllSongs();
    } catch (e) {
      print("Failed to load songs from database: '${e.toString()}'.");
    }

    if (!mounted) return;

    setState(() {
      songData = SongData(songs, dbHelper: dbHelper);
      _isLoading = false;
    });
    
    // 将 songData 设置到 audioHandler，以便更新歌曲列表中的歌曲对象
    audioHandler?.songData = songData;

    // 启动时加载 Subsonic 配置，确保重启应用后无需先进入配置页
    // 即可直接播放远程歌曲（否则 _config 为 null 会抛"Subsonic 未配置"）
    try {
      await SubsonicService.instance.loadConfig();
    } catch (e) {
      print('MyMaterialApp: 加载 Subsonic 配置失败: $e');
    }

    // 恢复上次关闭前的播放列表（从 SharedPreferences 回查入库歌曲）
    try {
      await playlistData.restoreFromPrefs(dbHelper);
    } catch (e) {
      print('MyMaterialApp: 恢复播放列表失败: $e');
    }

    // 后台继续缓存未完成的封面（启动时不阻塞 UI）
    _cachePendingArtwork();
  }

  /// 检查并缓存未完成的封面（coverArtId 有值但 cachedArtworkPath 为空的歌曲）
  Future<void> _cachePendingArtwork() async {
    try {
      final pendingSongs = await dbHelper.querySongsNeedingArtworkCache();
      if (pendingSongs.isEmpty) return;
      print('启动时发现 ${pendingSongs.length} 首歌曲封面未缓存，开始后台缓存...');
      await CacheService.instance.cacheArtworkBatch(pendingSongs);
      // 缓存完成后刷新歌曲列表
      if (mounted) {
        final refreshed = await dbHelper.queryAllSongs();
        setState(() {
          songData = SongData(refreshed, dbHelper: dbHelper);
        });
      }
    } catch (e) {
      print('启动时封面缓存失败: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    // 将 MPInheritedWidget 置于 MaterialApp 之上，
    // 确保通过 Navigator.push 跳转的页面（如播放列表页）也能访问共享状态。
    return MPInheritedWidget(
      songData,
      playlistData,
      dbHelper,
      _isLoading,
      ValueListenableBuilder<ThemeMode>(
        valueListenable: themeModeNotifier,
        builder: (context, mode, _) {
          return MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: lightTheme,
            darkTheme: darkTheme,
            themeMode: mode,
            navigatorKey: navigatorKey,
            home: MPNavScaffold(),
            // 底部播放栏常驻：导航器与栏竖向拼接，跨所有路由始终显示；
            // 「正在播放」页打开时（nowPlayingOpen）以上移+淡出动画隐藏。
            builder: (context, child) {
              // 全局滑动方向锁定阈值：只有滑过 40px 才锁定为横向/竖向主导方向。
              // 通过覆盖 MediaQuery.gestureSettings，让所有基于 GestureDetector、
              // Scrollable（ListView/GridView/PageView）、Dismissible 的手势
              // 都使用 40px 的 touchSlop 来判断主方向，避免斜向滑动时过早锁死
              // 方向（默认 Flutter touch slop 仅约 18px，易误触）。
              final mq = MediaQuery.of(context);
              return MediaQuery(
                data: mq.copyWith(
                  gestureSettings: const DeviceGestureSettings(touchSlop: 40.0),
                ),
                child: Column(
                  children: [
                    Expanded(child: child!),
                    ValueListenableBuilder<bool>(
                      valueListenable: nowPlayingOpen,
                      builder: (_, open, __) => MiniPlayerBar(hidden: open),
                    ),
                  ],
                ),
              );
            },
          );
        },
      ),
    );
  }
}
