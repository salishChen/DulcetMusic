import 'dart:io';
import 'dart:typed_data';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_inherited.dart';

/// 封面字节内存缓存：以歌曲文件路径为 key
///
/// 首次读取文件内嵌封面（后台 isolate），之后走内存缓存，
/// 避免 ListView 滚动时重复读文件解码导致卡顿。
class ArtworkCache {
  ArtworkCache._();

  static final Map<String, Uint8List?> _cache = {};
  static final Map<String, Future<Uint8List?>> _pending = {};

  /// 同步取缓存（未加载过返回 null）
  static Uint8List? peek(String path) => _cache[path];

  static bool has(String path) => _cache.containsKey(path);

  /// 清除指定 key 的缓存（封面缓存路径变更时使用）
  static void invalidate(String key) {
    _cache.remove(key);
    _pending.remove(key);
  }

  /// 清除所有以 path 为 key 的 null 缓存（远程歌曲封面缓存完成后使用）
  static void invalidateByPathPrefix(String path) {
    _cache.remove(path);
    _pending.remove(path);
  }

  /// 异步加载封面字节（自动去重并发请求）
  ///
  /// [cachedArtworkPath] 远程歌曲的本地缓存封面路径（优先使用）。
  static Future<Uint8List?> load(String path, {String? cachedArtworkPath}) {
    final cacheKey = cachedArtworkPath ?? path;
    if (_cache.containsKey(cacheKey)) return Future.value(_cache[cacheKey]);
    return _pending.putIfAbsent(cacheKey, () async {
      Uint8List? bytes;
      try {
        // 优先使用缓存的封面文件
        if (cachedArtworkPath != null && cachedArtworkPath.isNotEmpty) {
          final file = File(cachedArtworkPath);
          if (await file.exists()) {
            bytes = await file.readAsBytes();
          }
        }
        // 回退到从音频文件读取内嵌封面
        if (bytes == null) {
          bytes = await compute(readArtworkBytes, path);
        }
      } catch (e) {
        print('ArtworkCache: 读取封面失败 $path: $e');
      }
      _cache[cacheKey] = bytes;
      _pending.remove(cacheKey);
      return bytes;
    });
  }
}

/// isolate 入口：读取音频文件内嵌封面字节（无封面返回 null）
Uint8List? readArtworkBytes(String path) {
  try {
    final file = File(path);
    if (!file.existsSync()) return null;
    final meta = readMetadata(file, getImage: true);
    if (meta.pictures.isEmpty) return null;
    return meta.pictures.first.bytes;
  } catch (_) {
    return null;
  }
}

/// 通用封面组件：替代原 QueryArtworkWidget
///
/// 从音频文件内嵌封面读取并做内存缓存；无封面时显示渐变占位。
/// 支持远程歌曲的缓存封面文件。
/// 对于远程歌曲，若无内嵌封面且无缓存封面，会自动触发后台缓存并在完成后刷新。
class MpArtwork extends StatefulWidget {
  /// 歌曲文件路径（null 或文件不存在时显示占位）
  final String? path;

  /// 远程歌曲的本地缓存封面路径（优先于 path 读取）
  final String? cachedArtworkPath;

  /// 歌曲数据库 id（远程歌曲后台缓存封面时需要）
  final int? songId;

  /// 远程歌曲的 coverArtId（用于后台缓存封面）
  final String? coverArtId;

  final double? width;
  final double? height;
  final BorderRadius borderRadius;
  final BoxFit fit;

  /// 占位图标大小
  final double? placeholderIconSize;

  const MpArtwork(
    this.path, {
    Key? key,
    this.cachedArtworkPath,
    this.songId,
    this.coverArtId,
    this.width,
    this.height,
    this.borderRadius = BorderRadius.zero,
    this.fit = BoxFit.cover,
    this.placeholderIconSize,
  }) : super(key: key);

  @override
  State<MpArtwork> createState() => _MpArtworkState();
}

class _MpArtworkState extends State<MpArtwork> {
  Future<Uint8List?>? _cacheFuture;
  bool _backgroundCached = false;

  /// 后台缓存完成后保存的封面路径（用于无 songId 的封面，如艺术家）
  String? _artworkOverride;

  @override
  void initState() {
    super.initState();
    _initLoad();
  }

  @override
  void didUpdateWidget(MpArtwork oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.path != widget.path ||
        oldWidget.cachedArtworkPath != widget.cachedArtworkPath ||
        oldWidget.coverArtId != widget.coverArtId) {
      // path / 封面标识变化时重置后台缓存状态
      _artworkOverride = null;
      _backgroundCached = false;
      _initLoad();
    }
  }

  void _initLoad() {
    final path = widget.path;
    if (path == null || path.isEmpty) {
      _cacheFuture = null;
      // 无本地封面路径但有 coverArtId（如云端专辑/艺术家封面）时，
      // 仍按 coverArtId 触发后台缓存封面文件，缓存完成后刷新展示。
      if (widget.coverArtId != null && !_backgroundCached) {
        _tryBackgroundCache();
      }
      return;
    }
    final effectiveArtworkPath = widget.cachedArtworkPath ?? _artworkOverride;
    final cacheKey = effectiveArtworkPath ?? path;

    // 已有内存缓存，不需要 FutureBuilder
    if (ArtworkCache.has(cacheKey)) {
      _cacheFuture = null;
      // 如果内存缓存为 null 且是远程歌曲，尝试后台缓存
      if (ArtworkCache.peek(cacheKey) == null && !_backgroundCached) {
        _tryBackgroundCache();
      }
      return;
    }

    _cacheFuture =
        ArtworkCache.load(path, cachedArtworkPath: effectiveArtworkPath);
    // 如果加载结果为 null 且是远程歌曲，FutureBuilder 结束后尝试后台缓存
  }

  /// 远程歌曲无封面时，后台从 Subsonic 缓存封面文件并刷新 UI
  void _tryBackgroundCache() {
    if (_backgroundCached) return;
    if (widget.coverArtId == null) return;
    _backgroundCached = true;

    Future.microtask(() async {
      try {
        String? artworkPath;
        if (widget.songId != null) {
          // 有 songId：从数据库获取完整 Song 对象再缓存（会写库）
          final song =
              await DatabaseHelper.instance.querySongById(widget.songId!);
          if (song == null) return;
          artworkPath = await CacheService.instance.cacheArtwork(song);
        } else {
          // 无 songId（如艺术家封面）：直接按 coverArtId 缓存封面文件
          final placeholder = Song(
            title: '',
            path: widget.path ?? '',
            coverArtId: widget.coverArtId,
          );
          artworkPath = await CacheService.instance.cacheArtwork(placeholder);
        }

        if (artworkPath != null && mounted) {
          // 保存封面路径，使后续 build 能直接读取缓存的封面文件
          _artworkOverride = artworkPath;
          // 同步更新歌曲列表/播放列表中的对象，使 cachedArtworkPath 生效，
          // 否则 MpArtwork 的 cachedArtworkPath 参数仍为 null，会再次走占位。
          final oldKey = widget.cachedArtworkPath ?? widget.path!;
          ArtworkCache.invalidate(oldKey);
          if (mounted) setState(() {});
        }
      } catch (e) {
        print('MpArtwork: 后台缓存封面失败: $e');
      }
    });
  }

  Widget _placeholder() => Container(
        width: widget.width,
        height: widget.height,
        decoration: BoxDecoration(
          borderRadius: widget.borderRadius,
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF7C4DFF), Color(0xFF18D2C7)],
          ),
        ),
        child: Icon(
          Icons.music_note,
          color: Colors.white70,
          size: widget.placeholderIconSize ??
              ((widget.width != null && widget.width! < 60) ? 24.0 : 48.0),
        ),
      );

  Widget _image(Uint8List bytes) => ClipRRect(
        borderRadius: widget.borderRadius,
        child: Image.memory(
          bytes,
          width: widget.width,
          height: widget.height,
          fit: widget.fit,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _placeholder(),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final path = widget.path;
    final effectiveArtworkPath = widget.cachedArtworkPath ?? _artworkOverride;

    // 无本地封面路径：
    // - 若已有缓存封面路径（_artworkOverride / cachedArtworkPath），直接读取缓存文件展示；
    // - 若有 coverArtId（云端专辑/艺术家封面），后台缓存完成后刷新展示。
    if (path == null || path.isEmpty) {
      if (effectiveArtworkPath != null && effectiveArtworkPath.isNotEmpty) {
        final cacheKey = effectiveArtworkPath;
        if (ArtworkCache.has(cacheKey)) {
          final bytes = ArtworkCache.peek(cacheKey);
          if (bytes != null) return _image(bytes);
        }
        return FutureBuilder<Uint8List?>(
          future:
              ArtworkCache.load('', cachedArtworkPath: effectiveArtworkPath),
          builder: (context, snapshot) {
            final bytes = snapshot.data;
            if (bytes != null) return _image(bytes);
            if (widget.coverArtId != null && !_backgroundCached) {
              _tryBackgroundCache();
            }
            return _placeholder();
          },
        );
      }
      // 无缓存封面路径但存在 coverArtId → 触发后台缓存（完成后 setState 刷新）
      if (widget.coverArtId != null && !_backgroundCached) {
        _tryBackgroundCache();
      }
      return _placeholder();
    }

    final cacheKey = effectiveArtworkPath ?? path;

    // 命中内存缓存：直接同步渲染，避免闪烁
    if (ArtworkCache.has(cacheKey)) {
      final bytes = ArtworkCache.peek(cacheKey);
      if (bytes == null) {
        // 内存缓存为 null（无封面），但可能后台正在缓存
        // 如果后台缓存完成会 setState 触发重建
        return _placeholder();
      }
      return _image(bytes);
    }

    // 尚未加载：用 FutureBuilder 异步加载
    final future = _cacheFuture ??
        ArtworkCache.load(path, cachedArtworkPath: effectiveArtworkPath);

    return FutureBuilder<Uint8List?>(
      future: future,
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.done) {
          final bytes = snapshot.data;
          if (bytes == null) {
            // 加载无果 → 尝试后台缓存（仅一次）
            if (!_backgroundCached) _tryBackgroundCache();
            return _placeholder();
          }
          return _image(bytes);
        }
        // 加载中：先尝试同步缓存，有的话直接显示
        final peek = ArtworkCache.peek(cacheKey);
        if (peek != null) return _image(peek);
        return _placeholder();
      },
    );
  }
}
