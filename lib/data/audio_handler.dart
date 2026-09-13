import 'dart:async';
import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:flute_example/data/lyrics_overlay_manager.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/utils/lrc.dart';
import 'package:flute_example/data/playlist_data.dart';
import 'package:flute_example/data/song_data.dart';
import 'package:flute_example/data/subsonic_service.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/data/widget_service.dart';
import 'package:flutter/widgets.dart';
import 'package:path_provider/path_provider.dart';

/// 全局播放控制器实例（AudioService.init 后赋值）
MpAudioHandler? audioHandler;

/// 是否正在「正在播放」页（用于隐藏全局底部栏）
final ValueNotifier<bool> nowPlayingOpen = ValueNotifier(false);

/// 全局 Navigator key：供底部栏/通知等脱离 BuildContext 进行跳转
final GlobalKey<NavigatorState> navigatorKey = GlobalKey<NavigatorState>();

/// 播放页展开进度控制器（0 = 隐藏在屏幕底部，1 = 完全展开）。
///
/// 由 [MyMaterialAppState] 在 initState 创建（提供 vsync）。手势期间直接
/// 赋值 `.value` 实现跟手（手指停即停）；松手后 `animateTo` 补间完成展开
/// 或收回，且补间可被下一次 `.value =` 即时打断接管。播放页偏移、播放栏
/// 淡化、主页上移淡出均绑定到此单一进度，天然同步。
late AnimationController nowPlayingController;

/// 集中式音频处理器
///
/// 统一管理播放/暂停/上下首/进度/静音/播放模式，并维护当前歌曲索引；
/// 同时通过 [MediaItem] 与 [PlaybackState] 驱动安卓通知栏与媒体控制器，
/// 实现通知栏上一曲/下一曲/播放暂停的控制。
class MpAudioHandler extends BaseAudioHandler with SeekHandler {
  final AudioPlayer player;
  final PlaylistData playlistData;
  SongData? songData; // 可选的歌曲数据引用，用于更新歌曲列表

  final ValueNotifier<Song?> currentSong = ValueNotifier(null);
  final ValueNotifier<bool> isPlaying = ValueNotifier(false);
  final ValueNotifier<bool> isMuted = ValueNotifier(false);
  final ValueNotifier<Duration?> durationN = ValueNotifier(null);
  final ValueNotifier<Duration> positionN = ValueNotifier(Duration.zero);

  int _index = -1;
  List<int> _shuffleOrder = [];
  int _shufflePos = 0;

  /// 上次向平台（媒体通知/悬浮歌词）发布位置的时间（毫秒）。
  ///
  /// 播放器位置流约每 200ms 触发一次，直接每次都走
  /// `_publishState()` + `_updateFloatingLyrics()` 会造成高频平台通道
  /// 通信（通知栏 PlaybackState 刷新）与电量浪费。这里节流到 >=500ms
  /// 才推送一次；`positionN.value` 照常更新，仅供 UI（Slider/时间文本）
  /// 经 ValueListenableBuilder 局部消费。
  int _lastPublishedPosMs = -1;

  /// 位置到达平台的节流间隔（毫秒）
  static const int _platformPosIntervalMs = 500;

  /// 播放令牌：每次 [playSong] 自增。快速连续点"下一曲"或换歌时，
  /// 早一步发起的 playSong 的异步段（DB 查询、缓存确认、play 完成
  /// 后的收尾）完成时会发现自己的令牌已过期，直接放弃收尾，避免
  /// 旧歌的状态回写覆盖新歌（通知栏闪跳、`_index` 跳两首等竞态）。
  int _playSeq = 0;

  MpAudioHandler(this.player, this.playlistData) {
    _init();
  }

  void _init() {
    player.onDurationChanged.listen((d) {
      durationN.value = d;
      _publishMediaItem(currentSong.value);
    });
    player.onPositionChanged.listen((p) {
      positionN.value = p;
      _publishStateThrottled(p);
    });
    player.onPlayerComplete.listen((_) => _onComplete());
    player.onPlayerStateChanged.listen((s) {
      isPlaying.value = s == PlayerState.playing;
      _publishState();
    });
    playlistData.modeNotifier.addListener(_onModeChanged);
    playlistData.notifier.addListener(() {
      // 播放列表内容变化：打乱顺序失效，下次按需重建
      _shuffleOrder = [];
    });

    // 监听当前歌曲变化，更新小组件和悬浮窗歌词
    currentSong.addListener(_onCurrentSongChanged);
    isPlaying.addListener(_updateWidget);

    // 监听悬浮窗歌词状态变化，刷新通知栏按钮和歌词
    final lyricsMgr = LyricsOverlayManager.instance;
    lyricsMgr.isVisible.addListener(_onOverlayVisibilityChanged);
    lyricsMgr.isLocked.addListener(_publishState);

    _publishState();
  }

  /// 悬浮窗可见性变化时，立即推送歌词
  void _onOverlayVisibilityChanged() {
    _publishState();
    if (LyricsOverlayManager.instance.isVisible.value) {
      _updateFloatingLyrics();
    }
  }

  /// 当前歌曲变化时更新小组件和悬浮窗歌词
  void _onCurrentSongChanged() {
    _updateWidget();
    _updateFloatingLyrics();
  }

  /// 更新桌面小组件显示
  void _updateWidget() {
    WidgetService.updateWidget(
      song: currentSong.value,
      isPlaying: isPlaying.value,
    );
  }

  // ===================== 悬浮窗歌词 =====================

  /// 节流推送位置相关的平台状态（通知栏 + 悬浮歌词）
  void _publishStateThrottled(Duration p) {
    final ms = p.inMilliseconds;
    if (_lastPublishedPosMs >= 0 &&
        (ms - _lastPublishedPosMs).abs() < _platformPosIntervalMs) {
      return;
    }
    _lastPublishedPosMs = ms;
    _publishState();
    _updateFloatingLyrics();
  }

  /// 位置发生跳变（seek / 起播）时立即推送，绕过节流
  void _publishStateImmediate(Duration p) {
    _lastPublishedPosMs = p.inMilliseconds;
    _publishState();
    _updateFloatingLyrics();
  }

  /// 更新悬浮窗歌词内容
  ///
  /// 将原始歌词和播放位置传递给 Android 端，由 Android 端自行解析和同步。
  /// 这样即使 Flutter 引擎在后台暂停，Android 端也能根据缓存的数据继续显示歌词。
  void _updateFloatingLyrics() {
    final lyricsMgr = LyricsOverlayManager.instance;
    if (!lyricsMgr.isVisible.value) {
      return;
    }

    final song = currentSong.value;
    final lyrics = song?.lyrics;
    final positionMs = positionN.value.inMilliseconds;
    final playing = isPlaying.value;

    // 将原始歌词和位置写入 MethodChannel，Android 端可独立处理
    lyricsMgr.updateLyrics(
      lyrics: lyrics,
      positionMs: positionMs,
      isPlaying: playing,
    );
  }

  // ===================== 对外控制 =====================

  /// 播放指定歌曲（重新定位到列表中的索引后播放）
  ///
  /// 支持三种播放源：
  /// 1. 已缓存的远程歌曲 -> 本地文件播放
  /// 2. 未缓存的远程歌曲 -> 流式播放 + 后台缓存
  /// 3. 本地歌曲 -> 本地文件播放
  Future<bool> playSong(Song song) async {
    // 自增令牌：本次调用的身份；后续每个 await 之后校验，
    // 过期即放弃（新一轮 playSong 已接管）。
    final seq = ++_playSeq;
    bool stale() => seq != _playSeq;

    final songs = playlistData.songs;
    final idx = songs.indexWhere((s) => s.path == song.path);
    if (idx < 0) return false;
    _index = idx;
    if (playlistData.playMode == PlayMode.random) _syncShufflePos();

    // 立即使用传入的 song 对象，不等待数据库查询（避免延迟）。
    // 注意：不在此处异步重查数据库，否则会与后台歌词获取产生竞态，
    // 用无歌词的旧数据覆盖已更新的 currentSong，导致歌词不显示。
    currentSong.value = song;
    durationN.value = null;
    positionN.value = Duration.zero;
    // 换歌/起播位置归零：立即推送一次，重置位置节流基线
    _publishStateImmediate(Duration.zero);
    _publishMediaItem(song);

    try {
      // 0. 若是远程歌曲，先尝试从数据库确认是否已缓存，避免重复触发缓存：
      //    播放列表/歌曲列表中的 song 对象可能因未及时刷新而 isCached=false，
      //    但数据库里其实已经有缓存路径了。此时应直接本地播放。
      if (song.isRemote && !song.isCached && song.remoteId != null) {
        final dbSong = await DatabaseHelper.instance.querySongByRemoteId(song.remoteId!);
        if (stale()) return true; // 新的播放请求已接管，本次静默让位
        if (dbSong != null && dbSong.isCached) {
          final cachedFile = File(dbSong.cachedPath!);
          if (await cachedFile.exists()) {
            if (stale()) return true;
            song = song.copyWith(cachedPath: dbSong.cachedPath);
            // 同步更新 currentSong，使播放页/播放栏能即时反映已缓存状态
            currentSong.value = song;
          }
        }
      }
      // 1. 已缓存的远程歌曲：优先本地播放
      if (song.isRemote && song.isCached) {
        final cachedFile = File(song.cachedPath!);
        if (await cachedFile.exists()) {
          await player.play(DeviceFileSource(song.cachedPath!));
          if (stale()) return true;
          // 检查并获取歌词（如果歌曲没有歌词）
          _checkAndFetchLyricsIfNeeded(song);
        } else {
          // 缓存文件丢失，走流式播放
          await _playRemoteAndCache(song);
          if (stale()) return true;
        }
      }
      // 2. 未缓存的远程歌曲：流式播放 + 后台缓存
      else if (song.isRemote) {
        await _playRemoteAndCache(song);
        if (stale()) return true;
      }
      // 3. 本地歌曲
      else {
        await player.play(DeviceFileSource(song.path));
        if (stale()) return true;
      }
      // player.play 完成后的收尾（状态/统计/预缓存）：
      // 若期间用户已发起新的 playSong，不再覆盖其状态。
      if (stale()) return true;
      isPlaying.value = true;
      // 递增播放次数（后台执行，不阻塞播放；unawaited 显式声明 fire-and-forget）
      if (song.id != null) {
        unawaited(DatabaseHelper.instance.incrementPlayCount(song.id!));
      }
      // 预缓存下一首
      _precacheNext();
      return true;
    } catch (e) {
      // 只有"最新的这次播放"才允许写失败状态，旧请求的失败不影响新一轮
      if (stale()) return false;
      print('MpAudioHandler: 播放失败 $e');
      // 连接可能已失效（内网/公网切换）：重置，下次播放重新解析
      SubsonicService.instance.resetConnection();
      isPlaying.value = false;
      currentSong.value = null;
      return false;
    }
  }

  /// 流式播放远程歌曲并后台缓存
  Future<void> _playRemoteAndCache(Song song) async {
    // 不再每次播放都强制 resetConnection()：旧实现每换一首远程歌都
    // 重新 ping 内网（最长 5s 超时），换歌会有明显卡顿感。连接复用
    // 已缓存的 _activeBaseUrl；播放失败时由 playSong 的 catch 侧重置，
    // 下次播放会重新解析连接。
    final streamUrl = await SubsonicService.instance.getStreamUrl(song.remoteId!);
    print('MpAudioHandler: 开始流式播放 - ${song.title} (${song.artist})');
    await player.play(UrlSource(streamUrl));
    // 后台缓存音频 + 封面（已缓存则跳过，避免同一首歌重复触发缓存下载），
    // 缓存完成后刷新 currentSong 和播放列表中的对象。
    if (!song.isCached) {
      CacheService.instance.startCaching(song, onCached: (updated) {
        _mergeCurrentSong(updated, mergeArtwork: true, mergeLyrics: true);
      });
    } else {
      // 音频已缓存但封面可能未缓存：仅补充封面缓存
      if (song.cachedArtworkPath == null && song.coverArtId != null && song.id != null) {
        CacheService.instance.startCaching(song, onCached: (updated) {
          _mergeCurrentSong(updated, mergeArtwork: true);
        });
      }
    }
    // 后台获取歌词（仅当歌曲尚无歌词时）
    if (song.lyrics == null || song.lyrics!.isEmpty) {
      print('MpAudioHandler: 后台获取歌词 - ${song.title}');
      _fetchLyricsInBackground(song);
    }
  }
  
  /// 检查并获取远程歌曲的歌词（如果歌曲没有歌词）
  void _checkAndFetchLyricsIfNeeded(Song song) {
    if (song.isRemote && (song.lyrics == null || song.lyrics!.isEmpty)) {
      print('MpAudioHandler: 歌曲无歌词，后台获取 - ${song.title}');
      _fetchLyricsInBackground(song);
    }
  }

  /// 将异步更新的 Song 字段合并到 currentSong，避免互相覆盖
  void _mergeCurrentSong(Song updated,
      {bool mergeArtwork = false, bool mergeLyrics = false}) {
    final cur = currentSong.value;
    // 若当前无播放歌曲或 path 不匹配（可能是正在播放另一首歌），
    // 仍更新播放列表和歌曲列表中的对象，使缓存状态能反映到列表 UI。
    if (cur == null || cur.path != updated.path) {
      playlistData.updateSong(updated);
      songData?.updateSong(updated);
      return;
    }
    // 只合并指定字段，保留其他字段的最新状态
    final merged = cur.copyWith(
      cachedPath: mergeArtwork ? (updated.cachedPath ?? cur.cachedPath) : null,
      cachedArtworkPath:
          mergeArtwork ? (updated.cachedArtworkPath ?? cur.cachedArtworkPath) : null,
      lyrics: mergeLyrics ? (updated.lyrics ?? cur.lyrics) : null,
    );
    // 必须始终更新 currentSong.value（即使字段未变化也赋值新对象），
    // 确保 ValueNotifier 触发所有监听者（NowPlaying/MiniPlayerBar 等）。
    currentSong.value = merged;
    // 清除 ArtworkCache 中旧 key，让 UI 重新加载新封面
    if (mergeArtwork && updated.cachedArtworkPath != null &&
        updated.cachedArtworkPath != cur.cachedArtworkPath) {
      ArtworkCache.invalidate(cur.path);
      ArtworkCache.invalidateByPathPrefix(updated.cachedArtworkPath!);
    }
    // 更新播放列表和歌曲列表中的歌曲对象
    playlistData.updateSong(merged);
    songData?.updateSong(merged);
  }

  /// 后台获取远程歌曲歌词
  ///
  /// 注意：歌词获取仅依赖 artist 与 title，不依赖 song.id。
  /// 初次从在线（Subsonic）列表播放的歌曲尚未入库，id 为 null，
  /// 若在此处强校验 id 会导致歌词永远无法获取。
  Future<void> _fetchLyricsInBackground(Song song) async {
    final artist = song.artist;
    if (artist == null || artist.trim().isEmpty || song.title.trim().isEmpty) {
      print('MpAudioHandler: 无法获取歌词 - 缺少 artist 或 title');
      return;
    }
    try {
      print('MpAudioHandler: 正在获取歌词 - $artist - ${song.title}');
      final lyrics = await SubsonicService.instance.getLyrics(
        artist,
        song.title,
      );
      if (lyrics != null && lyrics.isNotEmpty) {
        print('MpAudioHandler: 歌词获取成功，长度: ${lyrics.length}');
        // 有数据库 id 才写库；否则仅更新 currentSong（初次播放的在线歌曲）
        if (song.id != null) {
          await DatabaseHelper.instance.updateSongLyrics(song.id!, lyrics);
        }
        // 基于当前实际播放的歌曲对象合并歌词（避免用旧的 song 覆盖
        // currentSong 中已被封面/缓存回调更新的字段）。
        final cur = currentSong.value;
        final base = (cur != null && cur.path == song.path) ? cur : song;
        final updatedSong = base.copyWith(lyrics: lyrics);
        if (cur != null && cur.path == song.path) {
          // 先置 null 再赋新值：Song.== 仅比较 path，直接赋同 path 的新对象
          // 不会被 ValueNotifier 视为"值变化"，监听者不会触发。
          // 置 null 使比较变为 null → 非 null，强制触发一次通知。
          currentSong.value = null;
          currentSong.value = updatedSong;
        }
        _mergeCurrentSong(updatedSong, mergeArtwork: false, mergeLyrics: true);
      } else {
        print('MpAudioHandler: 歌词获取失败或为空');
      }
    } catch (e) {
      print('MpAudioHandler: 歌词获取异常: $e');
    }
  }

  /// 恢复播放（暂停态）或重播当前歌曲；无当前歌曲则空操作
  Future<void> resumeOrPlay() async {
    final song = currentSong.value;
    if (song == null) return;
    if (player.state == PlayerState.paused) {
      await player.resume();
    } else if (player.state != PlayerState.playing) {
      await playSong(song);
    }
    isPlaying.value = player.state == PlayerState.playing;
    _publishState();
  }

  Future<void> pause() async {
    await player.pause();
    isPlaying.value = false;
    _publishState();
  }

  @override
  Future<void> onPlay() => resumeOrPlay();

  @override
  Future<void> onPause() => pause();

  @override
  Future<void> onStop() async {
    await player.stop();
    isPlaying.value = false;
    _publishState();
  }

  Future<void> skipToNext() async {
    final s = _resolveNext(true);
    if (s != null) await playSong(s);
  }

  Future<void> skipToPrevious() async {
    final s = _resolveNext(false);
    if (s != null) await playSong(s);
  }

  @override
  Future<void> onSkipToNext() => skipToNext();

  @override
  Future<void> onSkipToPrevious() => skipToPrevious();

  @override
  Future<void> onSeek(Duration position) async {
    await player.seek(position);
    positionN.value = position;
    _publishStateImmediate(position);
  }

  Future<void> seek(Duration position) => onSeek(position);

  /// 处理通知栏自定义按钮事件
  @override
  Future<dynamic> customAction(String name, [Map<String, dynamic>? extras]) async {
    if (name == 'toggle_lyrics') {
      LyricsOverlayManager.instance.onNotificationToggle();
    }
  }

  Future<void> setMuted(bool muted) async {
    isMuted.value = muted;
    await player.setVolume(muted ? 0.0 : 1.0);
  }

  /// 切换播放模式（顺序 / 随机 / 单曲）
  void setPlayMode(PlayMode mode) {
    playlistData.setPlayMode(mode);
    _onModeChanged();
  }

  // ===================== 内部导航 =====================

  void _onModeChanged() {
    if (playlistData.playMode == PlayMode.random) {
      _ensureShuffle();
    }
    _publishState();
  }

  /// 确保随机顺序已生成：长度与列表不一致时重建，
  /// 并把当前歌曲放在队首、其余打乱，使“随机”按新顺序连续播放。
  void _ensureShuffle() {
    final songs = playlistData.songs;
    if (songs.isEmpty) {
      _shuffleOrder = [];
      _shufflePos = 0;
      _index = -1;
      return;
    }
    // 当前歌曲索引越界时回退到 0（列表可能因删除/替换而缩短）
    final cur = (_index >= 0 && _index < songs.length) ? _index : 0;
    if (_shuffleOrder.length != songs.length) {
      final order = List<int>.generate(songs.length, (i) => i);
      order.remove(cur);
      order.shuffle();
      order.insert(0, cur);
      _shuffleOrder = order;
      _shufflePos = 0;
    }
  }

  /// 把当前自然索引同步到打乱顺序中的位置
  void _syncShufflePos() {
    _ensureShuffle();
    if (_shuffleOrder.isNotEmpty) {
      _shufflePos = _shuffleOrder.indexOf(_index);
      if (_shufflePos < 0) _shufflePos = 0;
    }
  }

  /// 根据当前播放模式计算下一首
  Song? _resolveNext(bool forward) {
    final songs = playlistData.songs;
    if (songs.isEmpty) return null;
    final mode = playlistData.playMode;

    if (songs.length == 1) {
      _index = 0;
      return songs[0];
    }

    switch (mode) {
      case PlayMode.sequential:
        _index = forward
            ? (_index + 1) % songs.length
            : (_index - 1 + songs.length) % songs.length;
        return songs[_index];
      case PlayMode.random:
        _ensureShuffle();
        _shufflePos = forward
            ? (_shufflePos + 1) % songs.length
            : (_shufflePos - 1 + songs.length) % songs.length;
        _index = _shuffleOrder[_shufflePos];
        return songs[_index];
      case PlayMode.single:
        final idx = _index >= 0 ? _index : 0;
        _index = idx;
        return songs[_index];
    }
  }

  Future<void> _onComplete() async {
    positionN.value = durationN.value ?? Duration.zero;
    final s = _resolveNext(true);
    if (s != null) {
      await playSong(s);
    } else {
      isPlaying.value = false;
      _publishState();
    }
  }

  /// 停止播放并清空播放列表（用于"清空播放列表"按钮）
  Future<void> stopAndClear() async {
    await player.stop();
    isPlaying.value = false;
    currentSong.value = null;
    durationN.value = null;
    positionN.value = Duration.zero;
    playlistData.clear();
    _publishState();
  }

  /// 预缓存队列中的下一首远程歌曲
  void _precacheNext() {
    final songs = playlistData.songs;
    if (songs.isEmpty) return;
    final nextIdx = (_index + 1) % songs.length;
    final nextSong = songs[nextIdx];
    if (nextSong.isRemote && !nextSong.isCached) {
      print('MpAudioHandler: 预缓存下一首 - ${nextSong.title}');
      CacheService.instance.startCaching(nextSong);
    }
  }

  // ===================== 通知栏 / 媒体会话 =====================

  void _publishState() {
    final playing = isPlaying.value;
    // 悬浮窗歌词按钮图标：
    // - 未启动：ic_lyrics（简单音乐图标）
    // - 启动+未锁定：ic_lyrics_active（音乐图标+对勾）
    // - 启动+锁定：ic_lyrics_locked（音乐图标+锁）
    final lyricsMgr = LyricsOverlayManager.instance;
    final lyricsVisible = lyricsMgr.isVisible.value;
    final lyricsLocked = lyricsMgr.isLocked.value;

    String icon;
    String label;
    if (!lyricsVisible) {
      icon = 'drawable/ic_lyrics';
      label = '词';
    } else if (lyricsLocked) {
      icon = 'drawable/ic_lyrics_locked';
      label = '解锁';
    } else {
      icon = 'drawable/ic_lyrics_active';
      label = '词';
    }

    final lyricsControl = MediaControl.custom(
      androidIcon: icon,
      label: label,
      name: 'toggle_lyrics',
    );

    final controls = <MediaControl>[
      lyricsControl,
      MediaControl.skipToPrevious,
      playing ? MediaControl.pause : MediaControl.play,
      MediaControl.skipToNext,
    ];
    final repeatMode = playlistData.playMode == PlayMode.single
        ? AudioServiceRepeatMode.one
        : AudioServiceRepeatMode.all;
    final shuffleMode = playlistData.playMode == PlayMode.random
        ? AudioServiceShuffleMode.all
        : AudioServiceShuffleMode.none;
    playbackState.add(PlaybackState(
      controls: controls,
      systemActions: const {MediaAction.seek},
      processingState: AudioProcessingState.ready,
      playing: playing,
      updatePosition: positionN.value,
      bufferedPosition: positionN.value,
      speed: 1.0,
      repeatMode: repeatMode,
      shuffleMode: shuffleMode,
    ));
  }

  void _publishMediaItem(Song? song) {
    if (song == null) return;
    final item = MediaItem(
      id: song.path,
      album: song.album ?? '',
      title: song.title,
      artist: song.displayArtist,
      duration: durationN.value,
      playable: true,
    );
    mediaItem.add(item);
    _loadArt(song);
  }

  Future<void> _loadArt(Song song) async {
    try {
      final bytes = await ArtworkCache.load(song.path, cachedArtworkPath: song.cachedArtworkPath);
      if (bytes == null) return;
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/art_${song.path.hashCode}.png');
      await file.writeAsBytes(bytes);
      final current = mediaItem.value;
      mediaItem.add(current == null
          ? MediaItem(
              id: song.path,
              title: song.title,
              artist: song.displayArtist,
              artUri: Uri.file(file.path),
            )
          : current.copyWith(artUri: Uri.file(file.path)));
    } catch (_) {
      // 封面缺失则不带封面推送
    }
  }
}
