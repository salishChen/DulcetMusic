import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/pages/now_playing.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flutter/material.dart';

/// 贯穿全局的底部播放栏
///
/// 显示当前播放歌曲（封面 / 歌名 / 艺术家），右侧为「播放/暂停」与「播放列表」按钮。
/// 常态化显示：即使没有正在播放的歌曲也保留占位态；仅在「正在播放」页打开时隐藏。
///
/// 上划跟手：手指在栏体上上划时，播放页从屏幕底部跟随手指上移滑出（手指停即停、
/// 动即动），栏体同步上移淡出；松手按进度与速度补间完成展开或回弹收回。
/// 播放页偏移、栏体淡化、主页上移淡出均由全局 [nowPlayingController] 统一驱动。
class MiniPlayerBar extends StatefulWidget {
  /// 是否隐藏（「正在播放」页打开时为 true），仅用于手势状态门控与重置
  final bool hidden;

  const MiniPlayerBar({Key? key, this.hidden = false}) : super(key: key);

  @override
  State<MiniPlayerBar> createState() => _MiniPlayerBarState();
}

/// 手势阶段：
/// - [idle]：无拖拽
/// - [opening]：滑出跟手中 / 展开补间中（等待 completed）
/// - [closing]：回弹收回补间中（等待 dismissed 后 pop 路由）
enum _BarDragMode { idle, opening, closing }

class _MiniPlayerBarState extends State<MiniPlayerBar> {
  /// 播放条自身高度（与 _Bar 的 Container height 保持一致）
  static const double _barHeight = 80.0;

  /// 栏体淡出完成的进度阈值（前段即完成淡化，余量留给播放页滑出）
  static const double _barFadeEnd = 0.15;

  _BarDragMode _mode = _BarDragMode.idle;

  /// 本次拖拽是否已 push 播放页路由（避免重复 push）
  bool _routePushed = false;

  /// 缓存屏幕高度，用于把拖拽增量映射为 0~1 进度
  double _screenHeight = 0.0;

  @override
  void initState() {
    super.initState();
    nowPlayingController.addStatusListener(_onProgressStatus);
    final h = audioHandler;
    h?.currentSong.addListener(_onChanged);
    h?.isPlaying.addListener(_onChanged);
    h?.isMuted.addListener(_onChanged);
  }

  @override
  void didUpdateWidget(covariant MiniPlayerBar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!widget.hidden && oldWidget.hidden) {
      // 播放页已关闭（路由 pop）：重置手势状态，允许下次重新 push；
      // 同时兜底把进度归零，防止残留进度（如直接 pop 时的 0.002）。
      _mode = _BarDragMode.idle;
      _routePushed = false;
      if (nowPlayingController.value != 0.0) {
        nowPlayingController.value = 0.0;
      }
    }
  }

  @override
  void dispose() {
    nowPlayingController.removeStatusListener(_onProgressStatus);
    final h = audioHandler;
    h?.currentSong.removeListener(_onChanged);
    h?.isPlaying.removeListener(_onChanged);
    h?.isMuted.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  /// 进度动画状态变化：补间结束时收敛手势状态
  void _onProgressStatus(AnimationStatus s) {
    if (!mounted) return;
    if (s == AnimationStatus.completed && _mode == _BarDragMode.opening) {
      // 展开补间完成：进入活动态，路由保留，等待用户关闭。
      // 注意：拖拽途中 value 被拖到 1.0 也会同步触发 completed，
      // _onDragUpdate 每次都会重新置回 opening，不影响后续松手处理。
      _mode = _BarDragMode.idle;
    } else if (s == AnimationStatus.dismissed) {
      // 收起补间完成：重置手势状态，允许下次重新 push 播放页。
      // pop 路由的职责已全部收归 NowPlaying 自身（其监听 dismissed 自行
      // pop），此处不再 pop，避免双重 pop 误弹主页。
      _mode = _BarDragMode.idle;
      _routePushed = false;
    }
  }

  Song? get _song => audioHandler?.currentSong.value;
  bool get _hasSong => _song != null;

  /// 上滑路由：复用统一的 [nowPlayingSlideRoute]，确保底部栏入口与歌曲列表入口
  /// 打开的是同一个播放页（同转场、同下滑关闭跟手行为）。
  Route<void> _fadeRoute(Widget page) => nowPlayingSlideRoute(page);

  /// 点击 / 按钮入口：push 路由 + 补间展开
  void _openNowPlaying([Song? target]) {
    if (_routePushed || widget.hidden) return;
    _routePushed = true;
    _mode = _BarDragMode.opening;
    final s = target ?? _song;
    navigatorKey.currentState?.push(_fadeRoute(NowPlaying(s, nowPlayTap: true)));
    nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
  }

  void _openPlaylist() {
    final s = _song;
    if (s == null) return;
    if (_routePushed || widget.hidden) return;
    _routePushed = true;
    _mode = _BarDragMode.opening;
    navigatorKey.currentState?.push(
      _fadeRoute(NowPlaying(s, nowPlayTap: true, showPlaylist: true)),
    );
    nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
  }

  /// 手指上划跟手：首次上划即 push 路由（progress≈0 不可见），随后以
  /// 控制器当前值为唯一事实来源、按增量驱动——手指停即停、动即动。
  ///
  /// 不再用累计量（_dragDy）驱动：拖拽途中 value 抵达 1.0 会同步触发
  /// completed 回调，若回调重置累计量会导致进度突跳、松手守卫失效，
  /// 进度卡在中间且路由永不 pop（其 ModalBarrier 拦截主页所有点击）。
  void _onDragUpdate(DragUpdateDetails d) {
    if (!_hasSong) return;
    if (!_routePushed) {
      // hidden 只拦「新手势的发起」——播放页 push 后约一帧
      // nowPlayingOpen 置 true、本组件重建为 hidden，若在 update/end 里
      // 也检查 hidden，会把同一次拖拽的后续事件全部吞掉：进度冻结在
      // 起步值、松手不收敛，透明路由残留拦截一切点击。
      if (widget.hidden) return;
      // 仅向上划才触发打开；向下划忽略
      if (d.delta.dy >= 0) return;
      _routePushed = true;
      _screenHeight = MediaQuery.of(context).size.height;
      navigatorKey.currentState?.push(
        _fadeRoute(NowPlaying(_song, nowPlayTap: true)),
      );
    }
    // 拖拽期间始终保持 opening（value 拖到 1.0 触发的 completed 会置 idle）
    _mode = _BarDragMode.opening;
    // 下限钳制到 0.002 而非 0：value 被拖到 0 会同步触发 dismissed，
    // 导致拖拽途中状态被误重置（_routePushed=false），继续上划就会
    // push 第二个透明播放页路由，pop 不干净后 ModalBarrier 挡住点击。
    nowPlayingController.value =
        (nowPlayingController.value - d.delta.dy / _screenHeight)
            .clamp(0.002, 1.0);
  }

  /// 松手：取最后滑动趋势（速度方向）补间——展开或回弹收回；停手则就近收敛。
  /// 守卫只用 _routePushed（不检查 hidden，拖拽中途 hidden 已翻转为 true）：
  /// 保证只要本次拖拽 push 过路由，松手必然收敛到 0 或 1，绝不留在中间。
  void _onDragEnd(DragEndDetails d) {
    if (!_routePushed) return;
    final v = d.primaryVelocity ?? 0.0;
    final p = nowPlayingController.value;
    if (v < -300) {
      // 最后趋势向上 → 展开
      _mode = _BarDragMode.opening;
      nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
    } else if (v > 300) {
      // 最后趋势向下 → 收回
      _mode = _BarDragMode.closing;
      // 直接设置为0以确保 dismissed 状态立即触发
      nowPlayingController.value = 0.0;
    } else {
      // 停手 → 就近收敛
      if (p >= 0.5) {
        _mode = _BarDragMode.opening;
        nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
      } else {
        _mode = _BarDragMode.closing;
        // 直接设置为0以确保 dismissed 状态立即触发
        nowPlayingController.value = 0.0;
      }
    }
  }

  /// 拖拽被系统取消（如来电、通知栏下拉）：按当前进度就近收敛，避免悬停
  void _onDragCancel() {
    if (!_routePushed) return;
    final p = nowPlayingController.value;
    if (p >= 0.5) {
      _mode = _BarDragMode.opening;
      nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
    } else {
      _mode = _BarDragMode.closing;
      // 直接设置为0以确保 dismissed 状态立即触发
      nowPlayingController.value = 0.0;
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = audioHandler;
    if (h == null) return const SizedBox.shrink();

    return AnimatedBuilder(
      animation: nowPlayingController,
      builder: (context, child) {
        final p = nowPlayingController.value;
        // 栏体在前段（p:0~_barFadeEnd）淡出并高度同步收缩，让上层 Expanded
        // 同步扩展、播放页占满屏幕，消除播放页下方原栏占位留下的黑色空白。
        // 始终保留 child（GestureDetector）：跟手期间栏体虽淡出，但
        // GestureDetector 不可脱离渲染树，否则进行中的拖拽手势会被取消。
        final barT = (p / _barFadeEnd).clamp(0.0, 1.0);
        return SizedBox(
          height: _barHeight * (1.0 - barT),
          child: ClipRect(
            // OverflowBox 让栏体始终按完整 80px 布局（仅视觉上被裁掉），
            // 避免收缩期间 Row/Column 被压扁导致 RenderFlex overflow。
            child: OverflowBox(
              minHeight: _barHeight,
              maxHeight: _barHeight,
              alignment: Alignment.topCenter,
              child: Opacity(opacity: 1.0 - barT, child: child),
            ),
          ),
        );
      },
      child: GestureDetector(
        onTap: () => _openNowPlaying(),
        onVerticalDragUpdate: _onDragUpdate,
        onVerticalDragEnd: _onDragEnd,
        onVerticalDragCancel: _onDragCancel,
        child: _Bar(
          h.currentSong.value,
          h,
          onOpenPlaylist: _openPlaylist,
          onOpenNowPlaying: _openNowPlaying,
        ),
      ),
    );
  }
}

class _Bar extends StatelessWidget {
  final Song? song;
  final MpAudioHandler h;
  final VoidCallback onOpenPlaylist;
  final VoidCallback onOpenNowPlaying;

  const _Bar(
    this.song,
    this.h, {
    required this.onOpenPlaylist,
    required this.onOpenNowPlaying,
  });

  bool get hasSong => song != null;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final artwork = hasSong
        ? MpArtwork(
            song!.path,
            width: 52.0,
            height: 52.0,
            borderRadius: BorderRadius.circular(12.0),
          )
        : Container(
            width: 52.0,
            height: 52.0,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(
                colors: [
                  theme.colorScheme.primary,
                  const Color(0xFF18D2C7),
                ],
              ),
            ),
            child: const Icon(Icons.music_note, color: Colors.white, size: 26.0),
          );

    final info = hasSong
        ? Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                song!.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.titleMedium
                    ?.copyWith(fontWeight: FontWeight.w600),
              ),
              Text(
                song!.displayArtist,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall,
              ),
            ],
          )
        : Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('愉乐',
                  style: theme.textTheme.titleMedium
                      ?.copyWith(fontWeight: FontWeight.w700)),
              Text('还没有播放歌曲',
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: const Color(0xFF8A8A99))),
            ],
          );

    return Container(
      height: 80.0,
      decoration: BoxDecoration(
        color: theme.cardColor,
        border: Border(
          top: BorderSide(
            color: theme.dividerColor,
            width: 0.5,
          ),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.08),
            blurRadius: 10.0,
            offset: const Offset(0, -2),
          ),
        ],
      ),
      child: Row(
        children: [
          const SizedBox(width: 12.0),
          artwork,
          const SizedBox(width: 12.0),
          Expanded(child: info),
          ValueListenableBuilder<bool>(
            valueListenable: h.isPlaying,
            builder: (_, playing, __) => IconButton(
              icon: Icon(
                playing ? Icons.pause : Icons.play_arrow,
                color: theme.colorScheme.primary,
              ),
              onPressed: () {
                if (hasSong) {
                  playing ? h.pause() : h.resumeOrPlay();
                } else {
                  onOpenNowPlaying();
                }
              },
            ),
          ),
          IconButton(
            icon: Icon(Icons.queue_music,
                color: hasSong
                    ? theme.colorScheme.primary
                    : theme.disabledColor),
            onPressed: hasSong ? onOpenPlaylist : null,
          ),
          const SizedBox(width: 4.0),
        ],
      ),
    );
  }
}
