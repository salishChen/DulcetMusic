import 'package:flutter/material.dart';
import 'package:flute_example/pages/songs_page.dart';
import 'package:flute_example/pages/albums_page.dart';
import 'package:flute_example/pages/artists_page.dart';
import 'package:flute_example/pages/playlists_page.dart';
import 'package:flute_example/pages/scan_page.dart';
import 'package:flute_example/pages/settings_page.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'mp_sidebar.dart';

/// 一级页面导航壳：推动式侧边栏
///
/// - 左上角 toc 按钮 + 当前一级页面名称（仅一级页面显示）
/// - 点击按钮或在页面内容上向右滑动，整个页面跟随向右滑动，
///   左侧露出宽度为屏幕 50% 的侧边栏
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

  void _onDragUpdate(DragUpdateDetails d, double sidebarWidth) {
    _controller.value += d.delta.dx / sidebarWidth;
  }

  void _onDragEnd(DragEndDetails d) {
    final v = d.primaryVelocity ?? 0.0;
    if (v > 250) {
      openSidebar();
    } else if (v < -250) {
      closeSidebar();
    } else {
      _controller.value > 0.5 ? openSidebar() : closeSidebar();
    }
  }

  @override
  Widget build(BuildContext context) {
    final screenWidth = MediaQuery.of(context).size.width;
    // 侧边栏宽度为屏幕宽度的 50%
    final sidebarWidth = screenWidth * 0.5;

    // 六个一级页面（IndexedStack 保活）
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
          ScanPage(),
          SettingsPage(),
        ],
      ),
    );

    final body = AnimatedBuilder(
        animation: _controller,
        // 将页面树作为 child 传入：侧边栏平移动画每帧重建时复用该 Element，
        // 不再重建四个一级页面，从而保留列表滚动位置、消除「刷新」观感。
        child: pages,
        builder: (context, child) {
          final t = _controller.value;
          final dx = sidebarWidth * t;
          return Stack(
            children: [
              // 左侧固定侧边栏
              SizedBox(
                width: sidebarWidth,
                height: double.infinity,
                child: MPSidebar(
                  currentIndex: _pageIndex,
                  onSelect: selectPage,
                ),
              ),
              // 页面内容层：整体向右平移，跟随手势
              Transform.translate(
                offset: Offset(dx, 0),
                child: GestureDetector(
                  onHorizontalDragUpdate: (d) =>
                      _onDragUpdate(d, sidebarWidth),
                  onHorizontalDragEnd: _onDragEnd,
                  // 侧边栏展开时，点击内容区收起
                  onTap: t > 0.5 ? closeSidebar : null,
                  child: AbsorbPointer(
                    // 展开时吸收内容区的点击（避免误触页面内交互）
                    absorbing: t > 0.5,
                    child: Container(
                      decoration: BoxDecoration(
                        borderRadius:
                            BorderRadius.circular(t * 18.0),
                        boxShadow: t > 0.01
                            ? [
                                BoxShadow(
                                  color: Colors.black
                                      .withOpacity(0.35 * t),
                                  blurRadius: 16.0,
                                  offset: const Offset(-4, 0),
                                ),
                              ]
                            : null,
                      ),
                      clipBehavior:
                          t > 0.01 ? Clip.antiAlias : Clip.none,
                      child: child,
                    ),
                  ),
                ),
              ),
            ],
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
