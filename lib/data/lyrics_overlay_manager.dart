import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 悬浮窗歌词管理器（单例）
///
/// 通过 SharedPreferences 和 MethodChannel 与 Android 原生悬浮窗服务通信。
/// 歌词数据写入 SharedPreferences，确保在后台也能正常工作。
class LyricsOverlayManager {
  LyricsOverlayManager._() {
    _loadPrefs();
  }

  static final LyricsOverlayManager instance = LyricsOverlayManager._();

  static const MethodChannel _channel =
      MethodChannel('com.mtechviral.musicfinderexample/lyrics_overlay');

  // SharedPreferences 键名（与 Android 端保持一致）
  static const String _prefsName = 'lyrics_overlay_prefs';
  static const String _keyLocked = 'locked';
  static const String _keyColor = 'color';
  static const String _keyFontSize = 'font_size';
  static const String _keyLinesCount = 'lines_count';
  static const String _keyLyricsRaw = 'lyrics_raw';
  static const String _keyPositionMs = 'position_ms';
  static const String _keyIsPlaying = 'is_playing';

  // ===================== 状态 =====================

  /// 悬浮窗是否可见
  final ValueNotifier<bool> isVisible = ValueNotifier(false);

  /// 悬浮窗是否锁定
  final ValueNotifier<bool> isLocked = ValueNotifier(false);

  /// 歌词行数（1或2）
  final ValueNotifier<int> linesCount = ValueNotifier(2);

  // ===================== 初始化 =====================

  Future<void> _loadPrefs() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      isLocked.value = prefs.getBool('$_prefsName.$_keyLocked') ?? false;
      linesCount.value = prefs.getInt('$_prefsName.$_keyLinesCount') ?? 2;
    } catch (e) {
      debugPrint('加载悬浮窗设置失败: $e');
    }
  }

  // ===================== 操作 =====================

  /// 检查悬浮窗权限
  Future<bool> checkPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>('checkOverlayPermission');
      return result ?? false;
    } catch (e) {
      debugPrint('检查权限失败: $e');
      return false;
    }
  }

  /// 请求悬浮窗权限
  Future<bool> requestPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>('requestOverlayPermission');
      return result ?? false;
    } catch (e) {
      debugPrint('请求权限失败: $e');
      return false;
    }
  }

  /// 通知栏/应用内按钮点击：切换显示/隐藏
  Future<void> onNotificationToggle() async {
    if (!isVisible.value) {
      await showOverlay();
    } else if (isLocked.value) {
      await toggleLock();
    } else {
      await hideOverlay();
    }
  }

  /// 显示悬浮窗
  Future<void> showOverlay() async {
    try {
      bool hasPermission = await checkPermission();
      if (!hasPermission) {
        hasPermission = await requestPermission();
        if (!hasPermission) {
          debugPrint('没有悬浮窗权限');
          return;
        }
      }
      await _channel.invokeMethod('showOverlay');
      isVisible.value = true;
    } catch (e) {
      debugPrint('显示悬浮窗失败: $e');
      // 即使调用失败，也标记为可见（Service可能已在运行）
      isVisible.value = true;
    }
  }

  /// 隐藏悬浮窗
  Future<void> hideOverlay() async {
    try {
      await _channel.invokeMethod('hideOverlay');
    } catch (e) {
      debugPrint('隐藏悬浮窗失败: $e');
    }
    // 无论成功与否都标记为不可见
    isVisible.value = false;
  }

  /// 更新歌词内容
  Future<void> updateLyrics({
    required String? lyrics,
    required int positionMs,
    required bool isPlaying,
  }) async {
    if (!isVisible.value) {
      print('LyricsOverlayManager: 悬浮窗不可见，跳过更新');
      return;
    }
    try {
      print('LyricsOverlayManager: 调用 updateLyrics, lyrics长度=${lyrics?.length ?? 0}');
      await _channel.invokeMethod('updateLyrics', {
        'lyrics': lyrics ?? '',
        'position': positionMs,
        'is_playing': isPlaying,
      });
      print('LyricsOverlayManager: updateLyrics 调用成功');
    } catch (e) {
      print('LyricsOverlayManager: 更新歌词失败: $e');
    }
  }

  /// 更新播放位置
  Future<void> updatePosition(int positionMs) async {
    if (!isVisible.value) return;
    try {
      await _channel.invokeMethod('updatePosition', {
        'position': positionMs,
      });
    } catch (e) {
      // ignore
    }
  }

  /// 切换锁定状态
  Future<void> toggleLock() async {
    try {
      await _channel.invokeMethod('toggleLock');
      isLocked.value = !isLocked.value;
    } catch (e) {
      debugPrint('切换锁定失败: $e');
      // 即使调用失败，也切换本地状态
      isLocked.value = !isLocked.value;
    }
  }

  /// 获取歌词行数配置
  Future<int> getLinesCount() async {
    try {
      final result = await _channel.invokeMethod<int>('getLinesCount');
      return result ?? 2;
    } catch (e) {
      return 2;
    }
  }

  /// 设置歌词行数
  Future<void> setLinesCount(int count) async {
    try {
      await _channel.invokeMethod('setLinesCount', {'count': count});
      linesCount.value = count;
    } catch (e) {
      debugPrint('设置行数失败: $e');
      linesCount.value = count;
    }
  }
}
