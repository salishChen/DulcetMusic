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
/// 上划跟手：手指在栏体上上划时，播放页从屏幕底部跟随手指上移滑出，栏体固定不动
/// 仅淡出；40px 手指行程即可从完全透明到完全不透明。松手按进度与速度补间完成展开
/// 或回弹收回。左右滑动切歌：左滑下一曲，右滑上一曲。
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

class _MiniPlayerBarState extends State<MiniPlayerBar>
    with TickerProviderStateMixin {
  _BarDragMode _mode = _BarDragMode.idle;

  /// 本次拖拽是否已 push 播放页路由（避免重复 push）
  bool _routePushed = false;

  /// 缓存屏幕高度，用于把拖拽增量映射为 0~1 进度
  double _screenHeight = 0.0;

  /// 栏内内容水平偏移（左划为负、右划为正），用于跟手视觉效果
  double _barOffset = 0.0;

  /// 本次拖拽方向：null 未锁定、true 水平、false 竖直
  bool? _dragDirection;

  /// 栏内内容弹回动画控制器（新手势开始时取消）
  AnimationController? _barSnapController;

  /// 有效拖拽行程：用屏幕高度的 80% 作为从 0 到 1 的全行程，
  /// 让播放页以约 1.25 倍手指速度跟手上移，接近 1:1 跟手。
  double get _effectiveDragDistance => _screenHeight * 0.8;

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
      _barOffset = 0.0;
      _dragDirection = null;
      if (nowPlayingController.value != 0.0) {
        nowPlayingController.value = 0.0;
      }
    }
  }

  @override
  void dispose() {
    _barSnapController?.dispose();
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
    var needsRebuild = false;
    if (s == AnimationStatus.completed && _mode == _BarDragMode.opening) {
      // 展开补间完成：进入活动态，路由保留，等待用户关闭。
      // 注意：拖拽途中 value 被拖到 1.0 也会同步触发 completed，
      // _onDragUpdate 每次都会重新置回 opening，不影响后续松手处理。
      _mode = _BarDragMode.idle;
      // 播放页已完全展开：重置 _routePushed，使隐藏条件
      // `widget.hidden && !_routePushed` 成立，栏体从渲染树中移除，
      // 播放页得以占满全屏。
      _routePushed = false;
      needsRebuild = true;
    } else if (s == AnimationStatus.dismissed) {
      // 收起补间完成：重置手势状态，允许下次重新 push 播放页。
      // pop 路由的职责已全部收归 NowPlaying 自身（其监听 dismissed 自行
      // pop），此处不再 pop，避免双重 pop 误弹主页。
      _mode = _BarDragMode.idle;
      _routePushed = false;
      needsRebuild = true;
    }
    if (needsRebuild) {
      // 必须触发重建：仅修改 _routePushed 不触发 build，
      // 播放页展开完成后 `widget.hidden && !_routePushed` 无法求值，
      // 栏体将残留不隐藏。
      setState(() {});
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

  /// 统一拖拽跟手：单个 PanGestureRecognizer 同时处理上划开页与左右滑切歌，
  /// 避免 Vertical + Horizontal 两个识别器在竞技场中竞争导致上划卡住。
  void _onPanUpdate(DragUpdateDetails d) {
    if (!_hasSong) return;

    // 方向锁定：首个有意义的增量决定本次手势方向
    if (_dragDirection == null) {
      if (d.delta.dx.abs() < 8 && d.delta.dy.abs() < 8) return;
      _dragDirection = d.delta.dx.abs() >= d.delta.dy.abs();
      // 新手势开始，取消未完成的弹回动画
      if (_dragDirection! && _barSnapController != null) {
        _barSnapController!.stop();
        _barSnapController!.dispose();
        _barSnapController = null;
      }
    }

    if (_dragDirection!) {
      // ── 水平：栏内内容跟手平移 ──
      _barOffset += d.delta.dx;
      _barOffset = _barOffset.clamp(-150.0, 150.0);
      setState(() {});
      return;
    }

    // ── 竖直：上划开页 ──
    if (!_routePushed) {
      if (widget.hidden) return;
      if (d.delta.dy >= 0) return;
      _routePushed = true;
      _screenHeight = MediaQuery.of(context).size.height;
      navigatorKey.currentState?.push(
        _fadeRoute(NowPlaying(_song, nowPlayTap: true)),
      );
    }
    _mode = _BarDragMode.opening;
    nowPlayingController.value =
        (nowPlayingController.value - d.delta.dy / _effectiveDragDistance)
            .clamp(0.002, 1.0);
  }

  /// 杌手：水平方向按偏移/速度触发切歌并弹回；竖直方向按速度与进度收尾。
  void _onPanEnd(DragEndDetails d) {
    if (_dragDirection == true) {
      // ── 水平滑动：判断是否触发切歌 ──
      final vx = d.velocity.pixelsPerSecond.dx;
      if (_barOffset < -60 || vx < -600) {
        audioHandler?.skipToNext();
      } else if (_barOffset > 60 || vx > 600) {
        audioHandler?.skipToPrevious();
      }
      // 弹回动画
      _animateBarBack();
      return;
    }

    _dragDirection = null;

    if (!_routePushed) {
      // 未 push 路由 → 纯横划（旧逻辑兜底）
      if (!_hasSong) return;
      final vx = d.velocity.pixelsPerSecond.dx;
      if (vx < -300) {
        audioHandler?.skipToNext();
      } else if (vx > 300) {
        audioHandler?.skipToPrevious();
      }
      return;
    }
    // 已 push 路由 → 上划开页，按竖向速度与进度收尾
    final vy = d.velocity.pixelsPerSecond.dy;
    final p = nowPlayingController.value;
    if (vy < -150) {
      _mode = _BarDragMode.opening;
      nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
    } else if (vy > 150) {
      _mode = _BarDragMode.closing;
      nowPlayingController.value = 0.0;
    } else {
      if (p >= 0.2) {
        _mode = _BarDragMode.opening;
        nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
      } else {
        _mode = _BarDragMode.closing;
        nowPlayingController.value = 0.0;
      }
    }
  }

  /// 栏内内容水平偏移弹回动画
  void _animateBarBack() {
    // 取消未完成的弹回动画
    _barSnapController?.stop();
    _barSnapController?.dispose();

    final start = _barOffset;
    final controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 250),
    );
    _barSnapController = controller;
    final anim = CurvedAnimation(parent: controller, curve: Curves.easeOutCubic);
    anim.addListener(() {
      _barOffset = start * (1.0 - anim.value);
      if (mounted) setState(() {});
    });
    anim.addStatusListener((s) {
      if (s == AnimationStatus.completed ||
          s == AnimationStatus.dismissed) {
        _barOffset = 0.0;
        _dragDirection = null;
        _barSnapController = null;
        // 不能在 status listener 被同步遍历期间 dispose 自身：
        // AnimationController.notifyStatusListeners 正在遍历监听器，
        // 此时销毁可能引发 "used after being disposed"。推迟到
        // 本帧结束后释放，安全且不产生额外动画帧。
        controller.stop();
        WidgetsBinding.instance
            .addPostFrameCallback((_) => controller.dispose());
      }
    });
    controller.forward();
  }

  @override
  Widget build(BuildContext context) {
    final h = audioHandler;
    if (h == null) return const SizedBox.shrink();

    // 播放页已完全打开且无进行中的手势：隐藏栏体
    if (widget.hidden && !_routePushed) {
      return const SizedBox.shrink();
    }

    // 栏体本体：手势检测 + 内容展示
    // 使用单个 Pan 识别器同时处理上划开页与左右滑切歌，
    // 避免 Vertical + Horizontal 两个识别器竞技场竞争导致上划卡住。
    final bar = GestureDetector(
      onTap: () => _openNowPlaying(),
      onPanUpdate: _onPanUpdate,
      onPanEnd: _onPanEnd,
      child: _Bar(
        h.currentSong.value,
        h,
        onOpenPlaylist: _openPlaylist,
        onOpenNowPlaying: _openNowPlaying,
        offset: _barOffset,
      ),
    );

    // 栏体固定在底部不随播放页上移，仅跟随进度淡出。
    // 始终用 AnimatedBuilder → Opacity 包裹 bar，确保组件树结构在手势
    // 全程保持不变。若在 value 穿过 0.001 时从「直接返回 bar」切换为
    // 「AnimatedBuilder 包裹 bar」，Flutter 会销毁旧 GestureDetector
    // element 并重建，导致正在进行的上划手势被取消（_onPanEnd 永远
    // 不触发），页面卡在半途。
    return AnimatedBuilder(
      animation: nowPlayingController,
      builder: (context, child) {
        final p = nowPlayingController.value;
        // 播放页从底部开始上移，栏体快速淡出：
        // 当页面滑到距底部约 2 个播放栏高度（160px）时栏体完全透明，
        // 对应 p ≈ 160/screenH（800px 屏 ≈ 0.2）。
        final barH = 80.0;
        final screenH = MediaQuery.of(context).size.height;
        final fadeDist = (barH * 2 / screenH).clamp(0.05, 0.5);
        final contentOpacity =
            ((fadeDist - p) / fadeDist).clamp(0.0, 1.0);
        // 两阶段隐藏：先淡出，透明后再折叠高度。
        // 因为高度折叠时栏体已完全透明，肉眼看不到折叠动画。
        final h = contentOpacity > 0.0 ? barH : 0.0;
        return IgnorePointer(
          ignoring: p > 0.001,
          child: SizedBox(
            height: h,
            child: Opacity(
              opacity: contentOpacity,
              child: child,
            ),
          ),
        );
      },
      child: bar,
    );
  }
}

class _Bar extends StatelessWidget {
  final Song? song;
  final MpAudioHandler h;
  final VoidCallback onOpenPlaylist;
  final VoidCallback onOpenNowPlaying;

  /// 栏内内容水平偏移（左划为负、右划为正）
  final double offset;

  const _Bar(
    this.song,
    this.h, {
    required this.onOpenPlaylist,
    required this.onOpenNowPlaying,
    this.offset = 0.0,
  });

  bool get hasSong => song != null;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final artwork = hasSong
        ? MpArtwork(
            song!.path,
            cachedArtworkPath: song?.cachedArtworkPath,
            songId: song?.id,
            coverArtId: song?.coverArtId,
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
              Row(
                children: [
                  Flexible(
                    child: Text(
                      song!.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.titleMedium
                          ?.copyWith(fontWeight: FontWeight.w600),
                    ),
                  ),
                  // 已缓存的远程歌曲显示缓存标识
                  if (song!.isRemote && song!.isCached)
                    Padding(
                      padding: const EdgeInsets.only(left: 4.0),
                      child: Icon(
                        Icons.offline_pin,
                        size: 15.0,
                        color: theme.colorScheme.primary,
                      ),
                    ),
                ],
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

    // 左右滑动时的「下一曲 / 上一曲」提示，
    // 透明度随偏移量线性增长，最大 0.85。
    final tipOpacity = (offset.abs() / 80.0).clamp(0.0, 0.85);

    // 栏体主内容（封面 + 歌名 + 按钮），左右滑动时跟手平移。
    final mainRow = Row(
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
    );

    return ClipRect(
      child: Container(
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
        child: Stack(
          clipBehavior: Clip.hardEdge,
          children: [
            // 右侧背景：左划时显示「下一曲」
            Positioned.fill(
              child: Align(
                alignment: Alignment.centerRight,
                child: Padding(
                  padding: const EdgeInsets.only(right: 24.0),
                  child: Opacity(
                    opacity: offset < 0 ? tipOpacity : 0.0,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text('下一曲',
                            style: TextStyle(
                                color: theme.colorScheme.primary,
                                fontSize: 15.0,
                                fontWeight: FontWeight.w600)),
                        const SizedBox(width: 6.0),
                        Icon(Icons.skip_next,
                            color: theme.colorScheme.primary, size: 22.0),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            // 左侧背景：右划时显示「上一曲」
            Positioned.fill(
              child: Align(
                alignment: Alignment.centerLeft,
                child: Padding(
                  padding: const EdgeInsets.only(left: 24.0),
                  child: Opacity(
                    opacity: offset > 0 ? tipOpacity : 0.0,
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(Icons.skip_previous,
                            color: theme.colorScheme.primary, size: 22.0),
                        const SizedBox(width: 6.0),
                        Text('上一曲',
                            style: TextStyle(
                                color: theme.colorScheme.primary,
                                fontSize: 15.0,
                                fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                ),
              ),
            ),
            // 主内容：跟手水平平移
            Transform.translate(
              offset: Offset(offset, 0),
              child: mainRow,
            ),
          ],
        ),
      ),
    );
  }
}
