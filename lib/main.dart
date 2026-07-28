import 'package:audio_service/audio_service.dart';
import 'package:flutter/material.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/audio_player_instance.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/playlist_data.dart';
import 'package:flute_example/data/song_data.dart';
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
            builder: (context, child) => Column(
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
      ),
    );
  }
}
