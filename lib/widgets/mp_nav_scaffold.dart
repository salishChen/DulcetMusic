import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flute_example/pages/songs_page.dart';
import 'package:flute_example/pages/albums_page.dart';
import 'package:flute_example/pages/artists_page.dart';
import 'package:flute_example/pages/playlists_page.dart';
import 'package:flute_example/pages/favorites_page.dart';
import 'package:flute_example/pages/scan_page.dart';
import 'package:flute_example/pages/subsonic_config_page.dart';
import 'package:flute_example/pages/stats_page.dart';
import 'package:flute_example/pages/settings_page.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'mp_sidebar.dart';

/// 侧边栏水平拖拽识别器：根据侧边栏开关状态动态调整命中阈值。
///
/// - 侧边栏打开时用较小阈值（8px）急切接管，确保「左滑关侧边栏」优先于
///   子级（如喜欢页 TabBarView）的横向翻页，避免左滑关侧边栏时串到下一个 Tab；
/// - 侧边栏关闭时用较大阈值（60px）延迟接管，把横向滑动让给子级
///   TabBarView 正常翻页，同时右滑到 60px 后仍可接管开启侧边栏。
class _SidebarDragRecognizer extends HorizontalDragGestureRecognizer {
  _SidebarDragRecognizer({
    required this.isSidebarOpen,
    required void Function(double dx) onUpdate,
    required void Function(double velocity) onEnd,
    Object? debugOwner,
  }) : super(debugOwner: debugOwner) {
    onStart = (_) {};
    onUpdate = (d) => onUpdate(d.delta.dx);
    onEnd = (d) => onEnd(d.velocity.pixelsPerSecond.dx);
    onCancel = () => onEnd(0.0);
  }

  final bool Function() isSidebarOpen;

  @override
  double computeHitSlop(PointerEvent event, Matrix4? transform) {
    return isSidebarOpen() ? 8.0 : 60.0;
  }
}

/// 一级页面导航壳：拼接式侧边栏
///
/// - 左上角 toc 按钮 + 当前一级页面名称（仅一级页面显示）
/// - 侧边栏与主页横向拼接为一块画布：关闭时主页铺满屏幕、侧边栏藏在左外侧；
///   点击按钮或在主页上向右滑动，侧边栏与主页整体一起向右滑动，
///   左侧露出宽度为屏幕 50% 的侧边栏，二者并排不重叠
/// - 六个一级页面用 IndexedStack 保活切换
class MPNavScaffold extends StatefulWidget {
  /// 全局 key：供二级页面（如播放列表页）右滑返回并打开侧边栏
  static final GlobalKey<MPNavScaffoldState> globalKey =
      GlobalKey<MPNavScaffoldState>();

  MPNavScaffold() : super(key: globalKey);

  /// 就近获取导航壳状态（一级页面内使用）
  static MPNavScaffoldState? of(BuildContext context) =>
      context.findAncestorStateOfType<MPNavScaffoldState>();

  @override
  MPNavScaffoldState createState() => MPNavScaffoldState();
}

class MPNavScaffoldState extends State<MPNavScaffold>
    with TickerProviderStateMixin {
  int _pageIndex = 0;

  /// 侧边栏展开进度控制器（0 关闭 -> 1 全开）
  late final AnimationController _controller;

  /// 进入播放页时主页上移并淡出的距离（px）
  static const double _liftDistance = 120.0;

  bool get isOpen => _controller.value > 0.5;
  int get pageIndex => _pageIndex;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 260),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void openSidebar() =>
      _controller.animateTo(1.0, curve: Curves.easeOutCubic);

  void closeSidebar() =>
      _controller.animateTo(0.0, curve: Curves.easeOutCubic);

  void toggleSidebar() => isOpen ? closeSidebar() : openSidebar();

  /// 切换一级页面并收起侧边栏
  void selectPage(int index) {
    setState(() => _pageIndex = index);
    closeSidebar();
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    // 侧边栏宽度为屏幕宽度的 50%
    final sidebarWidth = screenWidth * 0.5;

    // 九个一级页面（IndexedStack 保活）
    // RepaintBoundary：侧边栏平移动画期间将内容层缓存为独立图层，
    // 避免每帧重绘整棵页面树，显著提升滑动丝滑度。
    final pages = RepaintBoundary(
      child: IndexedStack(
        index: _pageIndex,
        children: const [
          SongsPage(),
          AlbumsPage(),
          ArtistsPage(),
          PlaylistsPage(),
          FavoritesPage(),
          ScanPage(),
          SubsonicConfigPage(),
          StatsPage(),
          SettingsPage(),
        ],
      ),
    );

    final body = AnimatedBuilder(
        animation: _controller,
        // 将页面树作为 child 传入：动画每帧重建时复用该 Element，
        // 主页内容（含列表滚动位置）保持不变，仅随画布整体平移，不重绘刷新。
        child: pages,
        builder: (context, child) {
          final t = _controller.value;
          // 拼接画布：侧边栏在左、主页在右，二者横向拼接为一块画布整体平移。
          // 关闭(t=0)：画布左移 sidebarWidth，主页铺满屏幕、侧边栏藏在左外侧；
          // 打开(t=1)：画布归位，侧边栏占左半屏、主页占右半屏（拼接，不重叠）。
          final dx = -sidebarWidth * (1.0 - t);

          // Stack 填满屏幕，hit-test 范围覆盖整屏，主页任意位置均可交互。
          // ClipRect 裁剪侧边栏关闭时超出屏幕左侧的绘制区域。
          return ClipRect(
            child: Stack(
              children: [
                // 侧边栏
                Positioned(
                  left: dx,
                  top: 0,
                  bottom: 0,
                  width: sidebarWidth,
                  child: MPSidebar(
                    currentIndex: _pageIndex,
                    onSelect: selectPage,
                  ),
                ),
                // 右侧主页：RawGestureDetector 使用自定义 _SidebarDragRecognizer
                // 自动与子组件（如喜欢页 TabBarView）的横向滑动在手势竞技场中竞争。
                // - 侧边栏打开：较小阈值急切接管，左滑优先关侧边栏而非翻 Tab；
                // - 侧边栏关闭：较大阈值延迟接管，把横向滑动让给 TabBarView 正常翻页。
                Positioned(
                  left: dx + sidebarWidth,
                  top: 0,
                  bottom: 0,
                  width: screenWidth,
                  child: RawGestureDetector(
                    behavior: HitTestBehavior.translucent,
                    gestures: <Type, GestureRecognizerFactory>{
                      _SidebarDragRecognizer: GestureRecognizerFactoryWithHandlers<
                          _SidebarDragRecognizer>(
                        () => _SidebarDragRecognizer(
                          debugOwner: this,
                          isSidebarOpen: () => isOpen,
                          onUpdate: (dx) {
                            _controller.value += dx / sidebarWidth;
                          },
                          onEnd: (vx) {
                            if (vx > 500) {
                              openSidebar();
                            } else if (vx < -500) {
                              closeSidebar();
                            } else {
                              _controller.value > 0.4
                                  ? openSidebar()
                                  : closeSidebar();
                            }
                          },
                        ),
                        (instance) {},
                      ),
                    },
                    child: child,
                  ),
                ),

              ],
            ),
          );
        },
      );

    // 进入「正在播放」页时，整块导航区（主页）随之上移 120px 并淡出，
    // 与从底部滑入的播放页形成「平齐上移」的过渡。动画只作用于本主页，
    // 不影响 Navigator overlay 之上的播放页路由，故播放页不会被隐藏。
    return AnimatedBuilder(
      animation: nowPlayingController,
      builder: (_, child) {
        // 后半段（p:0.5~1）才上移淡出，避免播放页刚露出时主页过早露底
        final liftT =
            ((nowPlayingController.value - 0.5) / 0.5).clamp(0.0, 1.0);
        return Transform.translate(
          offset: Offset(0.0, -_liftDistance * liftT),
          child: Opacity(opacity: 1.0 - liftT, child: child),
        );
      },
      child: Scaffold(
        backgroundColor: Theme.of(context).scaffoldBackgroundColor,
        body: body,
      ),
    );
  }
}

/// 一级页面统一 AppBar：左上角 toc 按钮 + 页面名称
///
/// 仅在一级页面中使用（二级页面走普通 AppBar 返回键）。
PreferredSizeWidget buildPrimaryAppBar(
  BuildContext context,
  String title, {
  List<Widget>? actions,
}) {
  return AppBar(
    leading: IconButton(
      icon: const Icon(Icons.toc),
      tooltip: '打开侧边栏',
      onPressed: () => MPNavScaffold.of(context)?.toggleSidebar(),
    ),
    // 按钮旁显示一级页面的名称
    title: Text(title),
    centerTitle: false,
    titleSpacing: 0,
    actions: actions,
  );
}
