import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/playlist_data.dart';
import 'package:flute_example/utils/lrc.dart';
import 'package:flute_example/widgets/mp_album_ui.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/mp_blur_filter.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

/// 由全局 [nowPlayingController] 驱动的播放页滑出路由。
///
/// `transitionDuration` 置零以禁用路由自动动画，偏移与透明度完全由进度跟手 / 补间。
/// 供底部播放栏上滑 / 点击入口与歌曲列表点击入口共用，确保两边打开的是同一个播放页
/// （同样的转场与下滑关闭跟手行为）。
Route<void> nowPlayingSlideRoute(Widget page) {
  return PageRouteBuilder<void>(
    opaque: false,
    transitionDuration: Duration.zero,
    reverseTransitionDuration: Duration.zero,
    pageBuilder: (_, __, ___) => page,
    transitionsBuilder: (context, anim, __, child) {
        return AnimatedBuilder(
          animation: nowPlayingController,
          builder: (context, c) {
            final screenH = MediaQuery.of(context).size.height;
            final p = nowPlayingController.value;
            // 与底部播放栏动画同步：栏体上移 80px 并渐隐收起，
            // 播放页起始位置跟随栏体上移，确保页面与栏体同步向上滑动。
            const barH = 80.0; // 与 mp_mini_player_bar 的 _barHeight 一致
            const barMoveUpDistance = 80.0; // 与 mp_mini_player_bar 一致
            // 栏体上移后的视觉顶部位置
            final barVisualTop = screenH - barH - barMoveUpDistance * p;
            // 播放页偏移：从栏体视觉顶部开始，随进度向上移动
            final offset = barVisualTop * (1.0 - p);
            // 透明度：进度从 0 到 1，播放页逐渐完全不透明
            final opacity = p.clamp(0.0, 1.0);
            return Transform.translate(
              offset: Offset(0.0, offset),
              child: Opacity(opacity: opacity, child: c),
            );
          },
          child: child,
        );
    },
  );
}

/// 统一打开播放页入口：push 滑出路由并补间展开。
///
/// 供底部播放栏点击入口与歌曲列表点击入口共用，确保是同一个播放页。[nowPlayTap]
/// 为 false 时（歌曲列表点歌）会在 [NowPlaying.initState] 中真正起播；为 true 时
/// （底部栏入口）仅展示当前在播歌曲。已打开时忽略，避免重复 push。
void openNowPlayingPage(Song song,
    {bool nowPlayTap = false, bool showPlaylist = false}) {
  final nav = navigatorKey.currentState;
  if (nav == null) return;
  if (nowPlayingOpen.value) return;
  nav.push(nowPlayingSlideRoute(
    NowPlaying(song, nowPlayTap: nowPlayTap, showPlaylist: showPlaylist),
  ));
  nowPlayingController.animateTo(1.0, curve: Curves.easeOutCubic);
}

/// 正在播放页面（Now Playing）
///
/// - 无标题栏、无返回按钮（依赖系统返回）
/// - 左上角显示歌名与艺术家
/// - 封面放大展示，其下显示最近 3 行歌词
/// - 进度条与主控（上一首 / 播放暂停 / 下一首）、底部功能按钮均为白色
/// - 右滑封面显示详细歌词（可滚动、点击跳转到对应时间）
/// - 上滑或点击「播放列表」按钮打开内置播放列表；在播放列表下滑返回播放页
/// - 可在本页切换播放模式（顺序 / 随机 / 单曲，实时刷新）
///
/// 所有播放控制经由全局 [audioHandler] 完成，本页仅负责展示与转发。
/// 播放页下滑关闭专用的竖向拖拽识别器。
///
/// 仅在「正在播放」页（第 0 页）顶部、且手指向下划时才赢得手势竞技场
/// （接管下拉关闭）；向上划则主动退出竞技场，交给 PageView 翻到播放列表。
/// 接管后**双向**驱动 [nowPlayingController]（下划收起、上划取消），松手按速度
/// 趋势收尾——以此彻底替代基于 OverscrollNotification 的不可靠方案。
class _CloseDragRecognizer extends VerticalDragGestureRecognizer {
  _CloseDragRecognizer({
    required this.canClose,
    required void Function(double dy) onMove,
    required void Function(double velocity) onFinish,
    Object? debugOwner,
  }) : super(debugOwner: debugOwner) {
    // 复用基类的拖拽机制（含 VelocityTracker 速度追踪），
    // 竞技场裁决由下方 handleEvent 提前完成。
    onStart = (_) {};
    onUpdate = (d) => onMove(d.delta.dy);
    onEnd = (d) => onFinish(d.velocity.pixelsPerSecond.dy);
    onCancel = () => onFinish(0.0);
  }

  final bool Function() canClose;

  bool _decided = false;
  double _accumulatedDy = 0.0;

  /// 方向锁定阈值：只有竖向位移累积超过该值才锁定为竖向手势。
  /// 与全局 40px touch slop 保持一致，避免斜向/微小幅度的滑动
  /// 在未达到阈值时就提前抢占方向，误触下滑关闭或翻页。
  static const double _lockThreshold = 40.0;

  /// 覆写命中阈值：让基类在未达到 40px 前不主动在竞技场中胜出。
  ///
  /// 若不覆写，基类 `VerticalDragGestureRecognizer` 会在默认 touch slop
  /// （约 18px）时就 resolve 胜出，导致：
  /// 1) 播放列表（第 1 页）内下滑在 18px 即被本识别器接管，从而串入播放页
  ///    的收起逻辑（F1 问题）；
  /// 2) 播放页（第 0 页）任意区域上滑被本识别器吞掉，使 PageView 无法翻到
  ///    播放列表（F3 问题）。
  /// 统一锁定到 40px 后，竖向手势需累积超过 40px 才锁定方向（F2 验收）。
  @override
  double computeHitSlop(PointerEvent event, Matrix4? transform) {
    return _lockThreshold;
  }

  @override
  void addPointer(PointerDownEvent event) {
    _decided = false;
    _accumulatedDy = 0.0;
    super.addPointer(event);
  }

  @override
  void handleEvent(PointerEvent event) {
    // 只有累积竖向位移超过 40px 阈值才裁决方向：
    // - 下划且允许关闭 → 宣布胜出（先于 PageView 越过 touch slop），
    //   接管后基类会双向派发 onUpdate（上划取消也跟手）；
    // - 否则（上划翻播放列表 / 不在 page0）→ 主动退出竞技场，交还 PageView。
    // 未达到阈值前不 resolve，让横向手势（如歌词切换）有机会在竞技场中竞争。
    if (!_decided && event is PointerMoveEvent && event.delta.dy != 0.0) {
      _accumulatedDy += event.delta.dy;
      if (_accumulatedDy.abs() < _lockThreshold) {
        super.handleEvent(event);
        return;
      }
      _decided = true;
      if (_accumulatedDy > 0 && canClose()) {
        // 宣布胜出。resolve 是异步生效的，此时基类状态仍是 ready，
        // 直接再调用 super.handleEvent 会触发 'ready' 断言，
        // 因此本 move 事件不再派发给基类，后续事件由基类正常接管。
        resolve(GestureDisposition.accepted);
        return;
      } else {
        // 主动退出竞技场，交还 PageView。本 move 事件同样不再派发。
        resolve(GestureDisposition.rejected);
        return;
      }
    }
    super.handleEvent(event);
  }
}

class NowPlaying extends StatefulWidget {
  final Song? song;
  final bool nowPlayTap;

  /// 进入时是否直接展开播放列表
  final bool showPlaylist;

  const NowPlaying(this.song,
      {this.nowPlayTap = false, this.showPlaylist = false});

  @override
  _NowPlayingState createState() => _NowPlayingState();
}

class _NowPlayingState extends State<NowPlaying> with TickerProviderStateMixin {
  late Song _song;
  late final bool _empty;
  bool _playing = false;
  bool _muted = false;
  Duration? _duration;
  Duration _position = Duration.zero;

  List<LrcLine> _lyricLines = const [];
  int _activeLine = -1;
  final ScrollController _lyricScroll = ScrollController();
  late PageController _pageController;
  bool _dragClosing = false;
  bool _isClosing = false;
  bool _popped = false;

  /// 歌词面板展开进度控制器（0 关闭 -> 1 全开）
  late final AnimationController _lyricsController;

  PlaylistData? _playlistData;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _playlistData = MPInheritedWidget.of(context).playlistData;
  }

  @override
  void initState() {
    super.initState();
    _empty = widget.song == null;
    // 若当前正在播放同一首歌，优先采用 audioHandler.currentSong 的最新状态
    // （可能已异步获取到歌词/封面），避免打开播放页时显示过期数据。
    final cur = audioHandler?.currentSong.value;
    if (cur != null && !_empty && cur.path == widget.song?.path) {
      _song = cur;
    } else {
      _song = widget.song ?? Song(title: '暂无歌曲', path: '');
    }
    if (!_empty) _parseLyrics(_song);
    _pageController =
        PageController(initialPage: widget.showPlaylist ? 1 : 0);
    _lyricsController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 300),
    );

    final h = audioHandler;
    if (h != null) {
      _playing = h.isPlaying.value;
      _muted = h.isMuted.value;
      _duration = h.durationN.value;
      _position = h.positionN.value;
      h.currentSong.addListener(_onSongChanged);
      h.isPlaying.addListener(_onPlayingChanged);
      h.isMuted.addListener(_onMutedChanged);
      h.durationN.addListener(_onDurationChanged);
      h.positionN.addListener(_onPositionChanged);
      // 仅歌曲列表点歌入口（nowPlayTap=false）才真正起播；
      // 底部播放栏入口（nowPlayTap=true）或空状态均不自动播放。
      // 用微任务推迟到本帧构建之后：initState 处于路由构建期，同步起播会
      // 使 currentSong 立即通知 MiniPlayerBar setState，触发
      // “setState() called during build” 异常。
      if (!widget.nowPlayTap && !_empty) {
        Future.microtask(() {
          if (mounted) h.playSong(_song);
        });
      }
    }

    // 进度归零（dismissed）→ 本页自行 pop。
    // pop 职责全部收归本页：无论关闭由哪条路径驱动（本页下滑、系统返回、
    // 播放栏跟手取消），只要进度归零路由必然弹出，杜绝透明路由残留
    // （ModalBarrier 会拦截 Navigator 内所有下层点击）。
    nowPlayingController.addStatusListener(_onProgressStatus);

    // 帧结束后再通知（此刻正处于路由构建期，直接设值会因
    // “setState during build” 被丢弃，导致底部栏不隐藏）
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      // 竞态兜底：push 后极快松手回弹时，收起补间可能在本页注册监听
      // 之前就已归零（dismissed），此时直接自弹出，避免透明路由残留。
      if (nowPlayingController.status == AnimationStatus.dismissed) {
        _popSelf();
        return;
      }
      nowPlayingOpen.value = true;
    });
  }

  @override
  void dispose() {
    final h = audioHandler;
    h?.currentSong.removeListener(_onSongChanged);
    h?.isPlaying.removeListener(_onPlayingChanged);
    h?.isMuted.removeListener(_onMutedChanged);
    h?.durationN.removeListener(_onDurationChanged);
    h?.positionN.removeListener(_onPositionChanged);
    nowPlayingController.removeStatusListener(_onProgressStatus);
    // nowPlayingOpen.value 已在 _popSelf() 中提前置 false，此处不再重复设置，
    // 避免微任务时序不确定导致底部栏恢复滞后。
    _lyricScroll.dispose();
    _pageController.dispose();
    _lyricsController.dispose();
    super.dispose();
  }

  void _parseLyrics(Song song) {
    _lyricLines = parseLrc(song.lyrics);
    _activeLine = -1;
  }

  void _onSongChanged() {
    if (!mounted) return;
    final s = audioHandler?.currentSong.value;
    if (s != null && !_empty) {
      // 歌词内容不同（无论是否 path 相同）时始终刷新歌词，
      // 修复歌词异步下载完成后播放页不显示的问题。
      final lyricChanged = s.lyrics != _song.lyrics;
      final artworkChanged = s.cachedArtworkPath != _song.cachedArtworkPath;
      final songChanged = s.path != _song.path;
      if (songChanged || lyricChanged || artworkChanged) {
        setState(() {
          _song = s;
          _parseLyrics(s);
        });
      }
    }
  }

  void _onPlayingChanged() {
    if (mounted) setState(() => _playing = audioHandler!.isPlaying.value);
  }

  void _onMutedChanged() {
    if (mounted) setState(() => _muted = audioHandler!.isMuted.value);
  }

  void _onDurationChanged() {
    if (mounted) setState(() => _duration = audioHandler!.durationN.value);
  }

  void _onPositionChanged() {
    if (!mounted) return;
    final pos = audioHandler!.positionN.value;
    setState(() => _position = pos);
    _updateActiveLine(pos);
  }

  /// 计算当前激活歌词行（始终计算，供 3 行迷你歌词与详情面板共用）
  void _updateActiveLine(Duration pos) {
    if (_lyricLines.isEmpty) return;
    int idx = -1;
    for (int i = 0; i < _lyricLines.length; i++) {
      if (_lyricLines[i].time <= pos) {
        idx = i;
      } else {
        break;
      }
    }
    if (idx != _activeLine) {
      _activeLine = idx;
      if (_lyricsController.value > 0.5) _scrollToActive();
    }
  }

  void _scrollToActive() {
    if (!_lyricScroll.hasClients || _activeLine < 0) return;
    final target = _activeLine * 56.0 - 200.0;
    _lyricScroll.animateTo(
      target.clamp(0.0, _lyricScroll.position.maxScrollExtent),
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeOutCubic,
    );
  }

  void _goToPlaylist() {
    _pageController.animateToPage(1,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOutCubic);
  }

  Future<void> _goToNowPlaying() {
    return _pageController.animateToPage(0,
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOutCubic);
  }

  /// 打开歌词面板
  void _openLyrics() {
    _lyricsController.animateTo(1.0, curve: Curves.easeOutCubic);
  }

  /// 关闭歌词面板
  void _closeLyrics() {
    _lyricsController.animateTo(0.0, curve: Curves.easeOutCubic);
  }

  /// 切换歌词面板
  void _toggleLyrics() {
    if (_lyricsController.value > 0.5) {
      _closeLyrics();
    } else {
      _openLyrics();
    }
  }

  /// 收起播放页并退出：同步清理状态并 pop 路由。
  ///
  /// 之前用 animateTo(0.0) → dismissed → _popSelf 的链路，但动画回调
  /// 在多种竞态下不可靠（动画被中断、disposed 时监听器已移除等），
  /// 导致 _popSelf 永远不执行，透明路由 ModalBarrier 残留拦截所有输入。
  void _closeAndPop() {
    if (_popped) return;
    _popped = true;
    _isClosing = false;
    nowPlayingController.value = 0.0;
    nowPlayingOpen.value = false;
    if (mounted) {
      Navigator.of(context).pop();
    } else {
      navigatorKey.currentState?.pop();
    }
  }

  /// 进度归零（dismissed）→ 本页自行 pop（pop 职责唯一入口）
  void _onProgressStatus(AnimationStatus s) {
    if (s == AnimationStatus.dismissed) {
      _popSelf();
    }
  }

  /// pop 本路由（幂等）。用 pop() 而非 maybePop()：maybePop 会询问本页
  /// WillPopScope（永远返回 false），路由将永远无法弹出，透明播放页的
  /// ModalBarrier 残留拦截下层点击。
  void _popSelf() {
    if (_popped) return;
    _popped = true;
    _isClosing = false;
    // 立即重置进度控制器，使主页透明度/位移恢复、播放栏高度恢复；
    // 必须在 nav.pop() 之前完成，否则 pop 后 MiniPlayerBar 的
    // didUpdateWidget 重建可能来不及执行，导致 UI 卡在半展开态。
    nowPlayingController.value = 0.0;
    // 立即通知底部栏恢复显示
    nowPlayingOpen.value = false;
    if (mounted) {
      final nav = Navigator.of(context);
      if (nav.canPop()) nav.pop();
    } else {
      final nav = navigatorKey.currentState;
      if (nav != null && nav.canPop()) nav.pop();
    }
  }

  String get _durationText => _duration != null ? _fmt(_duration!) : '';
  String get _positionText => _fmt(_position);

  String _fmt(Duration d) {
    final m = d.inMinutes;
    final s = d.inSeconds % 60;
    return '$m:${s.toString().padLeft(2, '0')}';
  }

  /// 主控按钮（上一首 / 播放暂停 / 下一首），尺寸为原来的 75%
  Widget _control(IconData icon, VoidCallback? onPressed, {double size = 48}) {
    return IconButton(
      icon: Icon(icon, color: Colors.white),
      iconSize: size,
      padding: const EdgeInsets.symmetric(horizontal: 8.0),
      onPressed: onPressed,
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final h = audioHandler;
    final max = _duration?.inMilliseconds.toDouble() ?? 0.0;

    final slider = max <= 0
        ? const SizedBox.shrink()
        : SliderTheme(
            data: SliderTheme.of(context).copyWith(
              trackHeight: 3.0,
              thumbShape:
                  const RoundSliderThumbShape(enabledThumbRadius: 6.0),
              overlayShape:
                  const RoundSliderOverlayShape(overlayRadius: 14.0),
            ),
            child: Slider(
              value: _position.inMilliseconds.toDouble().clamp(0.0, max),
              min: 0.0,
              max: max,
              activeColor: Colors.white,
              inactiveColor: Colors.white38,
              onChanged: (v) => h?.seek(Duration(milliseconds: v.toInt())),
            ),
          );

    final mainPlayer = SafeArea(
        top: true,
        bottom: false,
        child: Column(
          children: [
                // 左上角：歌名 + 艺术家
                Padding(
                  padding:
                      const EdgeInsets.fromLTRB(20.0, 8.0, 20.0, 0.0),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          _song.title,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 20.0,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                        const SizedBox(height: 4.0),
                        Text(
                          _song.displayArtist,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: Colors.white70,
                            fontSize: 14.0,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                // 中间内容：封面+迷你歌词 ↔ 详细歌词（可左右滑动）
                Expanded(
                  child: _buildMiddleContent(theme, h),
                ),
                // 播放条 + 主控整体上移 30px
                Transform.translate(
                  offset: const Offset(0, -30.0),
                  child: Column(
                    children: [
                      Padding(
                        padding:
                            const EdgeInsets.symmetric(horizontal: 16.0),
                        child: slider,
                      ),
                      Text(
                        max <= 0 ? '' : '$_positionText / $_durationText',
                        style: const TextStyle(
                            color: Colors.white70, fontSize: 13.0),
                      ),
                      const SizedBox(height: 6.0),
                      // 主控：上一首 / 播放暂停 / 下一首（原尺寸的 75%）
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          _control(Icons.skip_previous,
                              !_empty ? () => h?.skipToPrevious() : null,
                              size: 39.0),
                          _control(
                            _playing ? Icons.pause : Icons.play_arrow,
                            !_empty
                                ? () => _playing
                                    ? h?.pause()
                                    : h?.resumeOrPlay()
                                : null,
                            size: 54.0,
                          ),
                          _control(Icons.skip_next,
                              !_empty ? () => h?.skipToNext() : null,
                              size: 39.0),
                        ],
                      ),
                      const SizedBox(height: 4.0),
                    ],
                  ),
                ),
                // 底部功能按钮（模式 / 静音 / 播放列表），上移 10px 并加大 20%
                Padding(
                  padding:
                      const EdgeInsets.fromLTRB(20.0, 0.0, 12.0, 2.0),
                  child: Row(
                    children: [
                      // 播放模式切换（顺序 / 随机 / 单曲），实时刷新
                      ValueListenableBuilder<PlayMode>(
                        valueListenable: _playlistData?.modeNotifier ??
                            ValueNotifier(PlayMode.sequential),
                        builder: (_, mode, __) => IconButton(
                          icon: Icon(mode.icon, color: Colors.white),
                          iconSize: 34.8,
                          tooltip: mode.label,
                          onPressed: () {
                            final next = PlayMode.values[
                                (mode.index + 1) % PlayMode.values.length];
                            audioHandler?.setPlayMode(next);
                          },
                        ),
                      ),
                      const Spacer(),
                      // 静音
                      IconButton(
                        icon: Icon(
                          _muted ? Icons.volume_off : Icons.volume_up,
                          color: Colors.white,
                        ),
                        iconSize: 34.8,
                        onPressed: () => h?.setMuted(!_muted),
                      ),
                      // 播放列表（右下角）
                      IconButton(
                        icon: const Icon(Icons.queue_music,
                            color: Colors.white),
                        iconSize: 34.8,
                        tooltip: '播放列表',
                        onPressed: _goToPlaylist,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          );

    return WillPopScope(
      onWillPop: () async {
        // 系统返回：同步清理所有状态并弹出路由，不依赖动画回调。
        // 之前用 _closeAndPop → animateTo → dismissed → _popSelf 的链路，
        // 若动画被中断或 disposed 时监听器已移除，_popSelf 永远不会执行，
        // 透明路由的 ModalBarrier 残留，拦截下层所有点击/滑动。
        if (!_popped) {
          _popped = true;
          _isClosing = false;
          nowPlayingController.value = 0.0;
          nowPlayingOpen.value = false;
        }
        return true; // 允许系统执行默认 pop
      },
      child: RawGestureDetector(
        gestures: <Type, GestureRecognizerFactory>{
          _CloseDragRecognizer: GestureRecognizerFactoryWithHandlers<
              _CloseDragRecognizer>(
            () => _CloseDragRecognizer(
              debugOwner: this,
              // 仅在「正在播放」页（第 0 页）顶部才允许下滑关闭
              canClose: () => (_pageController.page ?? 0) <= 0.01,
              onMove: (dy) {
                if (_popped) return;
                // 防御性兜底：仅「正在播放」页（第 0 页）参与收起跟手，
                // 播放列表（第 1 页）的竖向滑动一律交给 PageView/列表自身。
                if ((_pageController.page ?? 0) > 0.01) return;
                _dragClosing = true;
                // 中断未完成的关闭补间（value 赋值本身会 stop() 动画）
                _isClosing = false;
                final h = MediaQuery.of(context).size.height;
                // 下划（dy>0）收起、上划（dy<0）取消：双向跟手。
                // 下限钳制到 0.002 而非 0：value 被直接拖到 0 会同步触发
                // dismissed，导致拖拽途中就 pop / 播放栏误重置状态。
                // 拖到底时页面已不可见，松手后由趋势判断统一收尾。
                nowPlayingController.value =
                    (nowPlayingController.value - dy / h).clamp(0.002, 1.0);
              },
              onFinish: (velocity) {
                if (!_dragClosing) return;
                _dragClosing = false;
                // 取最后滑动趋势（速度方向）：向下收起、向上取消、停手就近
                if (velocity > 300) {
                  _closeAndPop();
                } else if (velocity < -300) {
                  nowPlayingController.animateTo(1.0,
                      curve: Curves.easeOutCubic);
                } else if (nowPlayingController.value < 0.5) {
                  _closeAndPop();
                } else {
                  nowPlayingController.animateTo(1.0,
                      curve: Curves.easeOutCubic);
                }
              },
            ),
            (instance) {},
          ),
        },
        child: Scaffold(
          backgroundColor: Colors.transparent,
          body: Stack(
            fit: StackFit.expand,
            children: [
              // 静态共享背景：模糊封面 + 滤镜 + 暗化，固定在底层，
              // 正在播放页与播放列表页共用，且不会随竖向翻页而移动。
              MpArtwork(
                _song.path,
                cachedArtworkPath: _song.cachedArtworkPath,
                songId: _song.id,
                coverArtId: _song.coverArtId,
                fit: BoxFit.cover,
              ),
              blurFilter(),
              Container(color: Colors.black.withOpacity(0.35)),
              PageView(
                scrollDirection: Axis.vertical,
                controller: _pageController,
                children: [
                  // 第 0 页：播放页（中间区域可左右滑动切换封面/歌词）
                  mainPlayer,
                  // 第 1 页：播放列表
                  _buildPlaylistPage(),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 最近 3 行歌词（中间为当前行，高亮）
  Widget _miniLyrics() {
    const lineHeight = 24.0;
    if (_lyricLines.isEmpty) {
      return SizedBox(
        height: lineHeight * 3,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 28.0),
            child: Text(
              '暂无歌词',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(color: Colors.white54, fontSize: 14.0),
            ),
          ),
        ),
      );
    }
    final active = _activeLine < 0 ? 0 : _activeLine;
    Widget lineAt(int i) {
      if (i < 0 || i >= _lyricLines.length) {
        return const SizedBox(height: lineHeight);
      }
      final isActive = i == active;
      return SizedBox(
        height: lineHeight,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 28.0),
            child: Text(
              _lyricLines[i].text,
              textAlign: TextAlign.center,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: isActive
                    ? Colors.white
                    : Colors.white.withOpacity(0.45),
                fontSize: isActive ? 16.0 : 13.0,
                fontWeight: isActive ? FontWeight.w600 : FontWeight.w400,
              ),
            ),
          ),
        ),
      );
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        lineAt(active - 1),
        lineAt(active),
        lineAt(active + 1),
      ],
    );
  }



  /// 中间内容区域：封面+迷你歌词 ↔ 详细歌词（可左右滑动）
  ///
  /// 默认(t=0)：封面+迷你歌词显示；
  /// 左滑(t增大)：封面+迷你歌词向左滑出，详细歌词从右侧滑入取代；
  /// 完全打开(t=1)：详细歌词铺满中间区域。
  Widget _buildMiddleContent(ThemeData theme, MpAudioHandler? h) {
    final screenWidth = MediaQuery.of(context).size.width;

    // 默认内容：封面+迷你歌词
    final defaultContent = _empty
        ? const Center(
            child: Text('暂无歌曲',
                style: TextStyle(color: Colors.white54, fontSize: 14.0)),
          )
        : Column(
            children: [
              // 封面（放大 50%）
              Expanded(
                flex: 5,
                child: Center(
                  child:
                      AlbumUI(_song, _duration, _position, size: 375.0),
                ),
              ),
              // 三行迷你歌词
              GestureDetector(
                onTap: _openLyrics,
                child: _miniLyrics(),
              ),
              const Spacer(flex: 1),
            ],
          );

    return AnimatedBuilder(
      animation: _lyricsController,
      builder: (context, child) {
        final t = _lyricsController.value;

        return ClipRect(
          child: Stack(
            children: [
              // 默认内容：封面+迷你歌词向左滑出并淡出
              Positioned.fill(
                child: Transform.translate(
                  offset: Offset(-screenWidth * t, 0),
                  child: Opacity(
                    opacity: (1.0 - t).clamp(0.0, 1.0),
                    child: defaultContent,
                  ),
                ),
              ),
              // 详细歌词：从右侧滑入
              Positioned.fill(
                child: Transform.translate(
                  offset: Offset(screenWidth * (1.0 - t), 0),
                  child: _buildLyricsPage(theme, h),
                ),
              ),
              // 左滑手势检测层（覆盖整个区域）
              Positioned.fill(
                child: GestureDetector(
                  behavior: HitTestBehavior.translucent,
                  onHorizontalDragUpdate: (details) {
                    // 左滑（负值）打开歌词，右滑（正值）关闭歌词
                    _lyricsController.value -=
                        details.delta.dx / screenWidth;
                  },
                  onHorizontalDragEnd: (details) {
                    final vx = details.velocity.pixelsPerSecond.dx;
                    if (vx < -500) {
                      _openLyrics();
                    } else if (vx > 500) {
                      _closeLyrics();
                    } else {
                      _lyricsController.value > 0.5
                          ? _openLyrics()
                          : _closeLyrics();
                    }
                  },
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// 歌词页（右侧）
  Widget _buildLyricsPage(ThemeData theme, MpAudioHandler? h) {
    final hasTimed = _lyricLines.any((l) => l.time > Duration.zero);
    return Column(
      children: [
        Expanded(
          child: _lyricLines.isEmpty
              ? const Center(
                  child: Text('暂无歌词',
                      style: TextStyle(color: Colors.white70)))
              : ListView.builder(
                  controller: _lyricScroll,
                  padding: const EdgeInsets.symmetric(vertical: 40.0),
                  itemCount: _lyricLines.length,
                  itemBuilder: (context, i) {
                    final line = _lyricLines[i];
                    final active = i == _activeLine;
                    return InkWell(
                      onTap: hasTimed ? () => h?.seek(line.time) : null,
                      child: Container(
                        height: 56.0,
                        alignment: Alignment.center,
                        padding:
                            const EdgeInsets.symmetric(horizontal: 24.0),
                        child: Text(
                          line.text,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            color: active
                                ? Colors.white
                                : Colors.white.withOpacity(0.45),
                            fontSize: active ? 17.0 : 15.0,
                            fontWeight: active
                                ? FontWeight.w600
                                : FontWeight.w400,
                          ),
                        ),
                      ),
                    );
                  },
                ),
        ),
        // 底部上移50px
        const SizedBox(height: 50.0),
      ],
    );
  }

  /// 播放列表面板（与正在播放页拼接到一起，作为竖向 PageView 的第 1 页）
  ///
  /// 透明深色蒙版叠加在共享背景之上，使播放列表与正在播放页共用同一模糊背景；
  /// 顶部含「正在播放」当前歌曲卡片并预留状态栏空白；列表滚动到顶部后继续
  /// 下滑时由竖向 PageView 天然接管并平滑翻回正在播放页。
  Widget _buildPlaylistPage() {
    final current = _song;
    return Container(
      // 透明背景：直接透出共享的模糊封面背景
      color: Colors.transparent,
      child: Column(
        children: [
          // 顶部安全区 + 标题栏（避开状态栏）
          SafeArea(
            top: true,
            bottom: false,
            child: Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.keyboard_arrow_down, color: Colors.white),
                  tooltip: '收起',
                  onPressed: _goToNowPlaying,
                ),
                Expanded(
                  child: Text('播放列表',
                      textAlign: TextAlign.center,
                      style: const TextStyle(
                          color: Colors.white,
                          fontSize: 16.0,
                          fontWeight: FontWeight.w600)),
                ),
                IconButton(
                  icon: const Icon(Icons.delete_sweep, color: Colors.white),
                  tooltip: '清空播放列表',
                  onPressed: () async {
                    await audioHandler?.stopAndClear();
                    _closeAndPop();
                  },
                ),
              ],
            ),
          ),
          // 当前播放歌曲卡片
          _currentSongCard(),
          const Divider(height: 1.0, color: Colors.white24),
          Expanded(
            child: ValueListenableBuilder<List<Song>>(
              valueListenable: _playlistData?.notifier ??
                  ValueNotifier<List<Song>>(const []),
              builder: (context, playlist, _) {
                if (playlist.isEmpty) {
                  return const Center(
                    child: Text('播放列表为空',
                        style: TextStyle(color: Colors.white70, fontSize: 16.0)),
                  );
                }
                // 列表已滚到顶部后继续下滑时，竖向 PageView 会天然接管并
                // 平滑地翻回正在播放页（第 0 页），无需再靠 OverscrollNotification
                // 手动触发 animateToPage，避免与 PageView 的跟手滑动冲突、重复触发。
                return ListView.builder(
                    itemCount: playlist.length,
                    itemBuilder: (context, index) {
                      final s = playlist[index];
                      final isCurrent = s.path == current.path;
                      return Dismissible(
                        key: Key(s.path),
                        direction: DismissDirection.endToStart,
                        background: Container(
                          color: const Color(0xFFFF5252),
                          alignment: Alignment.centerRight,
                          padding: const EdgeInsets.only(right: 20.0),
                          child: const Icon(Icons.delete, color: Colors.white),
                        ),
                        onDismissed: (_) {
                          _playlistData?.removeAt(index);
                          setState(() {});
                        },
                        child: ListTile(
                          leading: MpArtwork(
                            s.path,
                            cachedArtworkPath: s.cachedArtworkPath,
                            songId: s.id,
                            coverArtId: s.coverArtId,
                            width: 48.0,
                            height: 48.0,
                            borderRadius: BorderRadius.circular(6.0),
                          ),
                          title: Text(
                            s.title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: isCurrent ? Colors.white : Colors.white,
                              fontWeight:
                                  isCurrent ? FontWeight.w600 : null,
                            ),
                          ),
                          subtitle: Text(
                            s.displayArtist,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(color: Colors.white70),
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              // 已缓存的远程歌曲显示缓存标识
                              if (s.isRemote && s.isCached)
                                Padding(
                                  padding: const EdgeInsets.only(right: 8.0),
                                  child: Icon(
                                    Icons.offline_pin,
                                    size: 18.0,
                                    color: isCurrent
                                        ? Colors.white
                                        : Colors.white54,
                                  ),
                                ),
                              if (isCurrent)
                                const Icon(Icons.equalizer,
                                    color: Colors.white),
                            ],
                          ),
                          onTap: () {
                            audioHandler?.playSong(s);
                            _goToNowPlaying();
                          },
                        ),
                      );
                    },
                  ),
              },
            ),
          ),
        ],
      ),
    );
  }

  /// 播放列表顶部「正在播放」当前歌曲卡片
  Widget _currentSongCard() {
    final s = _song;
    final h = audioHandler;
    return GestureDetector(
      onTap: _goToNowPlaying,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16.0, 10.0, 16.0, 10.0),
        child: Row(
          children: [
            MpArtwork(
              s.path,
              cachedArtworkPath: s.cachedArtworkPath,
              songId: s.id,
              coverArtId: s.coverArtId,
              width: 48.0,
              height: 48.0,
              borderRadius: BorderRadius.circular(8.0),
            ),
            const SizedBox(width: 12.0),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('正在播放',
                      style: TextStyle(color: Colors.white70, fontSize: 12.0)),
                  const SizedBox(height: 2.0),
                  Text(s.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          color: Colors.white,
                          fontSize: 15.0,
                          fontWeight: FontWeight.w600)),
                  Text(s.displayArtist,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          color: Colors.white70, fontSize: 13.0)),
                ],
              ),
            ),
            ValueListenableBuilder<bool>(
              valueListenable: h?.isPlaying ?? ValueNotifier(false),
              builder: (_, playing, __) => Icon(
                playing ? Icons.equalizer : Icons.play_arrow,
                color: Colors.white,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
