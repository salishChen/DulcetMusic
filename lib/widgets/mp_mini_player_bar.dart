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

class _MiniPlayerBarState extends State<MiniPlayerBar> {
  _BarDragMode _mode = _BarDragMode.idle;

  /// 本次拖拽是否已 push 播放页路由（避免重复 push）
  bool _routePushed = false;

  /// 缓存屏幕高度，用于把拖拽增量映射为 0~1 进度
  double _screenHeight = 0.0;

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
  /// 避免 Vertical + Horizontal 两个识别器在手势竞技场中竞争导致上划卡住。
  void _onPanUpdate(DragUpdateDetails d) {
    if (!_hasSong) return;
    if (!_routePushed) {
      if (widget.hidden) return;
      // 仅向上划才触发打开；向下划或横划忽略
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

  /// 松手：根据已 push 路由与否分流——未 push 说明是纯横划（切歌），
  /// 已 push 说明是上划开页，按速度与进度收尾。
  void _onPanEnd(DragEndDetails d) {
    if (!_routePushed) {
      // 未 push 路由 → 本次为横划手势，判断左右滑切歌
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
        // 播放页上移过程中栏体逐渐淡出：
        // 当页面滑到距底部约 2 个播放栏高度时完全透明，
        // 而非等到页面完全到顶（p=1）才消失。
        // fadeDistance ≈ 0.8（800px 屏、80px 栏高），
        // 在此之前线性淡出至 0，之后保持 0。
        final barH = 80.0;
        final screenH = MediaQuery.of(context).size.height;
        final fadeDist =
            ((screenH - barH * 2) / screenH).clamp(0.1, 0.95);
        final contentOpacity =
            ((fadeDist - p) / fadeDist).clamp(0.0, 1.0);
        return Opacity(
          opacity: contentOpacity,
          child: child,
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
