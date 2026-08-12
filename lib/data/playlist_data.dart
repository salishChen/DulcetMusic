import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'models/song.dart';
import 'database_helper.dart';

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
///
/// 同时支持将播放列表持久化到 [SharedPreferences]，退出软件重新进入后可恢复
/// 上次关闭前的播放列表（歌曲记录以数据库 id / 路径为标识，启动时回查）。
class PlaylistData {
  final List<Song> _playlist = [];
  PlayMode _playMode = PlayMode.sequential;

  static const String _prefsKey = 'last_playlist_songs';
  static const String _prefsModeKey = 'last_playlist_mode';

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

  /// 更新指定路径的歌曲对象（用于异步获取歌词/封面后刷新）
  void updateSong(Song updatedSong) {
    final idx = _playlist.indexWhere((s) => s.path == updatedSong.path);
    if (idx >= 0) {
      _playlist[idx] = updatedSong;
      _notify();
    }
  }

  /// 清空播放列表
  void clear() {
    _playlist.clear();
    _notify();
  }

  /// 切换播放模式（顺序 -> 随机 -> 单曲 -> 顺序 ...），返回切换后的模式
  ///
  /// 切换后立即持久化，确保退出后再次进入能恢复上次的播放模式。
  PlayMode togglePlayMode() {
    _playMode = PlayMode.values[(_playMode.index + 1) % PlayMode.values.length];
    modeNotifier.value = _playMode;
    _persist();
    return _playMode;
  }

  /// 直接设置播放模式
  ///
  /// 切换后立即持久化，确保退出后再次进入能恢复上次的播放模式。
  void setPlayMode(PlayMode mode) {
    _playMode = mode;
    modeNotifier.value = _playMode;
    _persist();
  }

  /// 内部通知：将不可变快照推送给监听者
  void _notify() {
    notifier.value = List.unmodifiable(_playlist);
    _persist();
  }

  // ===================== 播放列表持久化 =====================

  /// 将当前播放列表及播放模式异步写入 SharedPreferences
  void _persist() {
    try {
      // 保存每首歌的标识：优先数据库 id，其次 path（可能未入库的线上歌曲）
      final data = _playlist
          .map((s) => {
                'id': s.id,
                'path': s.path,
              })
          .toList();
      SharedPreferences.getInstance().then((prefs) async {
        await prefs.setString(_prefsKey, jsonEncode(data));
        await prefs.setInt(_prefsModeKey, _playMode.index);
      }).catchError((_) {});
    } catch (_) {
      // 持久化失败不影响播放
    }
  }

  /// 从 SharedPreferences 恢复上次关闭前的播放列表与播放模式
  ///
  /// 需要传入 [dbHelper] 用于把存储的标识回查为完整的 [Song] 对象。
  /// 若某歌曲已从库中删除则跳过；返回实际恢复的歌曲数。
  Future<int> restoreFromPrefs(DatabaseHelper dbHelper) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_prefsKey);
      final savedMode = prefs.getInt(_prefsModeKey);
      if (savedMode != null && savedMode >= 0 && savedMode < PlayMode.values.length) {
        _playMode = PlayMode.values[savedMode];
        modeNotifier.value = _playMode;
      }
      if (raw == null || raw.isEmpty) return 0;

      final items = jsonDecode(raw) as List<dynamic>;
      final restored = <Song>[];
      for (final item in items) {
        final map = (item as Map).cast<String, dynamic>();
        final id = map['id'] as int?;
        final path = map['path'] as String?;
        Song? song;
        if (id != null) {
          song = await dbHelper.querySongById(id);
        }
        if (song == null && path != null) {
          song = await dbHelper.querySongByPath(path);
        }
        if (song != null) restored.add(song);
      }

      _playlist
        ..clear()
        ..addAll(restored);
      _notify();
      return restored.length;
    } catch (e) {
      print('PlaylistData: 恢复播放列表失败: $e');
      return 0;
    }
  }
}
