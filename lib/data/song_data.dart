import 'dart:math';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';

import 'audio_player_instance.dart';
import 'database_helper.dart';
import 'models/song.dart';

/// 歌曲数据管理：持有数据库歌曲快照与全局唯一的 AudioPlayer
///
/// 歌曲列表来自 SQLite（扫描入库），通过 [reload] 从数据库刷新，
/// [notifier] 对外提供响应式更新（扫描完成后刷新各页面）。
class SongData {
  List<Song> _songs;
  int _currentSongIndex = -1;
  final AudioPlayer audioPlayer;
  final DatabaseHelper dbHelper;

  /// 歌曲列表变更通知
  final ValueNotifier<List<Song>> notifier;

  SongData(this._songs, {DatabaseHelper? dbHelper})
      : audioPlayer = sharedAudioPlayer,
        dbHelper = dbHelper ?? DatabaseHelper.instance,
        notifier = ValueNotifier<List<Song>>(List.unmodifiable(_songs));

  List<Song> get songs => _songs;
  int get length => _songs.length;
  int get songNumber => _currentSongIndex + 1;

  /// 从数据库重新加载歌曲列表（扫描完成后调用）
  Future<void> reload() async {
    _songs = await dbHelper.queryAllSongs();
    if (_currentSongIndex >= _songs.length) _currentSongIndex = -1;
    notifier.value = List.unmodifiable(_songs);
  }

  void setCurrentIndex(int index) {
    _currentSongIndex = index;
  }

  int get currentIndex => _currentSongIndex;

  Song? get nextSong {
    if (_currentSongIndex < length) {
      _currentSongIndex++;
    }
    if (_currentSongIndex >= length) return null;
    return _songs[_currentSongIndex];
  }

  Song? get randomSong {
    if (_songs.isEmpty) return null;
    final Random r = Random();
    return _songs[r.nextInt(_songs.length)];
  }

  Song? get prevSong {
    if (_currentSongIndex > 0) {
      _currentSongIndex--;
    }
    if (_currentSongIndex < 0 || _songs.isEmpty) return null;
    return _songs[_currentSongIndex];
  }
}
