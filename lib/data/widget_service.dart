import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flute_example/data/models/song.dart';

/// 桌面小组件通信服务
///
/// 通过 MethodChannel 与 Android 原生小组件通信，
/// 实现小组件数据更新和播放/暂停控制。
class WidgetService {
  static const MethodChannel _channel =
      MethodChannel('com.mtechviral.musicfinderexample/widget');

  static Timer? _pendingCheckTimer;
  static Function? _onPlayPauseFromWidget;

  /// 初始化服务，开始监听来自小组件的播放/暂停请求
  static void init({Function? onPlayPause}) {
    _onPlayPauseFromWidget = onPlayPause;
    _startPendingCheck();
  }

  /// 定期检查是否有来自小组件的播放/暂停请求
  static void _startPendingCheck() {
    _pendingCheckTimer?.cancel();
    _pendingCheckTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      _checkPendingPlayPause();
    });
  }

  /// 检查并处理待处理的播放/暂停请求
  static Future<void> _checkPendingPlayPause() async {
    if (!Platform.isAndroid) return;
    try {
      final bool hasPending =
          await _channel.invokeMethod('checkPendingPlayPause') ?? false;
      if (hasPending && _onPlayPauseFromWidget != null) {
        _onPlayPauseFromWidget!();
      }
    } catch (e) {
      // 忽略错误，可能是 MethodChannel 还未初始化
    }
  }

  /// 更新小组件显示内容
  ///
  /// [song] 当前播放的歌曲，null 表示未在播放
  /// [isPlaying] 是否正在播放
  static Future<void> updateWidget({Song? song, required bool isPlaying}) async {
    if (!Platform.isAndroid) return;

    try {
      String title = '未在播放';
      String? artworkPath;

      if (song != null) {
        title = song.title;
        // 优先使用缓存的封面路径
        if (song.cachedArtworkPath != null && song.cachedArtworkPath!.isNotEmpty) {
          artworkPath = song.cachedArtworkPath;
        }
      }

      await _channel.invokeMethod('updateWidget', {
        'title': title,
        'isPlaying': isPlaying,
        'artworkPath': artworkPath,
      });
    } catch (e) {
      print('WidgetService: 更新小组件失败 - $e');
    }
  }

  /// 停止服务
  static void dispose() {
    _pendingCheckTimer?.cancel();
    _pendingCheckTimer = null;
  }
}
