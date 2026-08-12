import 'dart:io';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'database_helper.dart';
import 'models/song.dart';
import 'subsonic_service.dart';

/// 缓存池管理服务
///
/// 负责 Subsonic 音乐的本地缓存、LRU 淘汰和缓存池大小管理。
class CacheService {
  static final CacheService instance = CacheService._();
  CacheService._();

  static const String _cacheSizeKey = 'subsonic_cache_size_mb';
  static const int _defaultCacheSizeMB = 2048; // 默认 2GB
  static const int _minCacheSizeMB = 500; // 最小 500MB
  static const int _maxCacheSizeMB = 51200; // 最大 50GB

  Directory? _cacheDir;

  /// 获取缓存目录
  Future<Directory> getCacheDir() async {
    if (_cacheDir != null) return _cacheDir!;
    final appDir = await getApplicationDocumentsDirectory();
    _cacheDir = Directory('${appDir.path}/subsonic_cache');
    if (!await _cacheDir!.exists()) {
      await _cacheDir!.create(recursive: true);
    }
    return _cacheDir!;
  }

  /// 获取用户设置的缓存池大小（MB）
  Future<int> getCacheSizeMB() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_cacheSizeKey) ?? _defaultCacheSizeMB;
  }

  /// 设置缓存池大小（MB）
  Future<void> setCacheSizeMB(int sizeMB) async {
    final clamped = sizeMB.clamp(_minCacheSizeMB, _maxCacheSizeMB);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_cacheSizeKey, clamped);
    // 设置后立即检查是否需要淘汰
    await evictIfNeeded(0);
  }

  /// 获取当前缓存总大小（字节）
  Future<int> getCacheSizeBytes() async {
    final dir = await getCacheDir();
    int totalSize = 0;
    await for (final entity in dir.list(recursive: true)) {
      if (entity is File) {
        totalSize += await entity.length();
      }
    }
    return totalSize;
  }

  /// 获取当前缓存总大小（MB）
  Future<int> getCacheSizeMBActual() async {
    final bytes = await getCacheSizeBytes();
    return (bytes / (1024 * 1024)).ceil();
  }

  /// 检查并执行 LRU 淘汰
  /// [requiredBytes] 需要腾出的空间（字节）
  Future<void> evictIfNeeded(int requiredBytes) async {
    final maxSizeMB = await getCacheSizeMB();
    final maxSizeBytes = maxSizeMB * 1024 * 1024;
    final currentSize = await getCacheSizeBytes();

    if (currentSize + requiredBytes <= maxSizeBytes) return;

    // 需要淘汰的空间
    int needEvict = (currentSize + requiredBytes) - maxSizeBytes;

    while (needEvict > 0) {
      final oldest = await DatabaseHelper.instance.queryOldestCachedSong();
      if (oldest == null || oldest.cachedPath == null) break;

      // 删除缓存文件
      final file = File(oldest.cachedPath!);
      if (await file.exists()) {
        final fileSize = await file.length();
        await file.delete();
        needEvict -= fileSize;
      }

      // 清除数据库中的缓存记录
      await DatabaseHelper.instance.clearSongCache(oldest.id!);
    }
  }

  /// 缓存歌曲到本地
  /// 返回缓存后的本地文件路径，失败返回 null
  Future<String?> cacheSong(Song song) async {
    if (song.remoteId == null) return null;

    try {
      final dir = await getCacheDir();
      final cachePath = '${dir.path}/${song.remoteId}.cache';

      // 检查是否已缓存
      if (File(cachePath).existsSync()) {
        if (song.id != null) {
          await DatabaseHelper.instance.updateSongCache(song.id!, cachePath);
        }
        return cachePath;
      }

      // 检查缓存池空间
      final songSize = song.size ?? 0;
      await evictIfNeeded(songSize);

      // 下载歌曲
      final streamUrl = await SubsonicService.instance.getStreamUrl(song.remoteId!);
      final response = await http.get(Uri.parse(streamUrl));
      if (response.statusCode != 200) return null;

      // 写入缓存文件
      final file = File(cachePath);
      await file.writeAsBytes(response.bodyBytes);

      // 更新数据库
      if (song.id != null) {
        await DatabaseHelper.instance.updateSongCache(song.id!, cachePath);
      }

      return cachePath;
    } catch (e) {
      print('CacheService: 缓存歌曲失败: $e');
      return null;
    }
  }

  /// 后台缓存歌曲（不阻塞播放）
  ///
  /// 同时缓存音频和封面，缓存完成后通过 [onCached] 回调通知调用方。
  void startCaching(Song song, {void Function(Song updated)? onCached}) {
    Future.microtask(() async {
      // 缓存音频
      final cachedPath = await cacheSong(song);
      // 缓存封面
      String? artworkPath;
      if (song.coverArtId != null && song.id != null) {
        artworkPath = await cacheArtwork(song);
      }
      // 回调通知：合并更新后的 Song 对象
      if (onCached != null && (cachedPath != null || artworkPath != null)) {
        final updated = song.copyWith(
          cachedPath: cachedPath ?? song.cachedPath,
          cachedArtworkPath: artworkPath ?? song.cachedArtworkPath,
        );
        onCached(updated);
      }
    });
  }

  /// 缓存歌曲封面到本地
  /// 返回缓存后的本地文件路径，失败返回 null
  Future<String?> cacheArtwork(Song song) async {
    if (song.coverArtId == null || song.id == null) return null;

    try {
      final dir = await getCacheDir();
      final artworkDir = Directory('${dir.path}/artwork');
      if (!await artworkDir.exists()) {
        await artworkDir.create(recursive: true);
      }
      final cachePath = '${artworkDir.path}/${song.coverArtId}.jpg';

      // 检查是否已缓存
      if (File(cachePath).existsSync()) {
        await DatabaseHelper.instance.updateArtworkCache(song.id!, cachePath);
        return cachePath;
      }

      // 下载封面
      final bytes =
          await SubsonicService.instance.getCoverArt(song.coverArtId!);
      if (bytes == null || bytes.isEmpty) return null;

      // 写入缓存文件
      final file = File(cachePath);
      await file.writeAsBytes(bytes);

      // 更新数据库
      await DatabaseHelper.instance.updateArtworkCache(song.id!, cachePath);

      return cachePath;
    } catch (e) {
      print('CacheService: 缓存封面失败: $e');
      return null;
    }
  }

  /// 批量缓存歌曲封面（扫描后调用）
  Future<void> cacheArtworkBatch(List<Song> songs) async {
    for (final song in songs) {
      if (song.coverArtId != null && song.id != null) {
        await cacheArtwork(song);
      }
    }
  }

  /// 删除指定歌曲的缓存
  Future<void> deleteCache(List<int> songIds) async {
    for (final songId in songIds) {
      final song = await DatabaseHelper.instance.querySongById(songId);
      if (song == null || song.cachedPath == null) continue;

      final file = File(song.cachedPath!);
      if (await file.exists()) {
        await file.delete();
      }
      await DatabaseHelper.instance.clearSongCache(songId);
    }
  }

  /// 删除所有缓存
  Future<void> clearAllCache() async {
    final dir = await getCacheDir();
    if (await dir.exists()) {
      await dir.delete(recursive: true);
      _cacheDir = null;
    }

    // 清除数据库中的缓存记录
    final cachedSongs = await DatabaseHelper.instance.queryCachedSongs();
    for (final song in cachedSongs) {
      if (song.id != null) {
        await DatabaseHelper.instance.clearSongCache(song.id!);
      }
    }
  }

  /// 获取所有已缓存歌曲
  Future<List<Song>> getCachedSongs() async {
    return await DatabaseHelper.instance.queryCachedSongs();
  }

  /// 格式化文件大小
  static String formatSize(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  /// 格式化 MB 大小
  static String formatSizeMB(int mb) {
    if (mb < 1024) return '$mb MB';
    return '${(mb / 1024).toStringAsFixed(1)} GB';
  }
}
