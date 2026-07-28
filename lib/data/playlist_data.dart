import 'package:flutter/material.dart';

import 'models/song.dart';

/// 播放模式枚举
enum PlayMode {
  sequential, // 列表循环：依次播放，越过末尾回到开头
  random, // 随机播放：打乱列表顺序后按新顺序播放
  single, // 单曲循环：始终重复当前歌曲
}

extension PlayModeExtension on PlayMode {
  /// 对应的展示文案
  String get label {
    switch (this) {
      case PlayMode.sequential:
        return '列表循环';
      case PlayMode.random:
        return '随机播放';
      case PlayMode.single:
        return '单曲循环';
    }
  }

  /// 对应的 Material 图标名（使用 Icons 常量）
  IconData get icon {
    switch (this) {
      case PlayMode.sequential:
        return Icons.repeat;
      case PlayMode.random:
        return Icons.shuffle;
      case PlayMode.single:
        return Icons.repeat_one;
    }
  }
}

/// 播放列表数据管理类
///
/// 以内存方式维护一个 [Song] 列表（当前播放列表），提供增删、模式切换等能力，
/// 并通过 [ValueNotifier] 对外暴露响应式更新。
class PlaylistData {
  final List<Song> _playlist = [];
  PlayMode _playMode = PlayMode.sequential;

  /// 列表内容变更通知（增删时触发）
  final ValueNotifier<List<Song>> notifier = ValueNotifier<List<Song>>([]);

  /// 播放模式变更通知（顺序 / 随机 / 单曲）
  final ValueNotifier<PlayMode> modeNotifier = ValueNotifier<PlayMode>(PlayMode.sequential);

  List<Song> get songs => List.unmodifiable(_playlist);
  int get length => _playlist.length;
  PlayMode get playMode => _playMode;

  /// 判断歌曲是否已在播放列表中（以数据库 id / 路径唯一标识）
  bool contains(Song song) => _playlist.any((s) => s.path == song.path);

  /// 添加歌曲；已存在则返回 false，否则返回 true
  bool addSong(Song song) {
    if (contains(song)) return false;
    _playlist.add(song);
    _notify();
    return true;
  }

  /// 用给定列表整体替换播放列表（用于"点击歌曲整列播放"场景）
  void setSongs(List<Song> songs) {
    _playlist
      ..clear()
      ..addAll(songs);
    _notify();
  }

  /// 移除指定歌曲
  void removeSong(Song song) {
    _playlist.removeWhere((s) => s.path == song.path);
    _notify();
  }

  /// 按索引移除
  void removeAt(int index) {
    if (index >= 0 && index < _playlist.length) {
      _playlist.removeAt(index);
      _notify();
    }
  }

  /// 清空播放列表
  void clear() {
    _playlist.clear();
    _notify();
  }

  /// 切换播放模式（顺序 -> 随机 -> 单曲 -> 顺序 ...），返回切换后的模式
  PlayMode togglePlayMode() {
    _playMode = PlayMode.values[(_playMode.index + 1) % PlayMode.values.length];
    modeNotifier.value = _playMode;
    return _playMode;
  }

  /// 直接设置播放模式
  void setPlayMode(PlayMode mode) {
    _playMode = mode;
    modeNotifier.value = _playMode;
  }

  /// 内部通知：将不可变快照推送给监听者
  void _notify() {
    notifier.value = List.unmodifiable(_playlist);
  }
}
