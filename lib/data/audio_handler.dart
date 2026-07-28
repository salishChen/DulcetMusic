import 'dart:io';

import 'package:audio_service/audio_service.dart';
import 'package:audioplayers/audioplayers.dart';
import 'package:flute_example/data/audio_player_instance.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/playlist_data.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
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

  final ValueNotifier<Song?> currentSong = ValueNotifier(null);
  final ValueNotifier<bool> isPlaying = ValueNotifier(false);
  final ValueNotifier<bool> isMuted = ValueNotifier(false);
  final ValueNotifier<Duration?> durationN = ValueNotifier(null);
  final ValueNotifier<Duration> positionN = ValueNotifier(Duration.zero);

  int _index = -1;
  List<int> _shuffleOrder = [];
  int _shufflePos = 0;

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
      _publishState();
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
    _publishState();
  }

  // ===================== 对外控制 =====================

  /// 播放指定歌曲（重新定位到列表中的索引后播放）
  Future<bool> playSong(Song song) async {
    final songs = playlistData.songs;
    final idx = songs.indexWhere((s) => s.path == song.path);
    if (idx < 0) return false;
    _index = idx;
    if (playlistData.playMode == PlayMode.random) _syncShufflePos();
    currentSong.value = song;
    durationN.value = null;
    positionN.value = Duration.zero;
    _publishMediaItem(song);
    try {
      await player.play(DeviceFileSource(song.path));
      isPlaying.value = true;
      return true;
    } catch (e) {
      print('MpAudioHandler: 播放失败 $e');
      isPlaying.value = false;
      currentSong.value = null;
      return false;
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
    _publishState();
  }

  Future<void> seek(Duration position) => onSeek(position);

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
      return;
    }
    if (_shuffleOrder.length != songs.length) {
      final order = List<int>.generate(songs.length, (i) => i);
      final cur = _index >= 0 ? _index : 0;
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

  // ===================== 通知栏 / 媒体会话 =====================

  void _publishState() {
    final playing = isPlaying.value;
    final controls = <MediaControl>[
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
      final bytes = await ArtworkCache.load(song.path);
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
