import 'dart:io';
import 'dart:typed_data';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

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

  /// 异步加载封面字节（自动去重并发请求）
  static Future<Uint8List?> load(String path) {
    if (_cache.containsKey(path)) return Future.value(_cache[path]);
    return _pending.putIfAbsent(path, () async {
      Uint8List? bytes;
      try {
        bytes = await compute(readArtworkBytes, path);
      } catch (e) {
        print('ArtworkCache: 读取封面失败 $path: $e');
      }
      _cache[path] = bytes;
      _pending.remove(path);
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
class MpArtwork extends StatelessWidget {
  /// 歌曲文件路径（null 或文件不存在时显示占位）
  final String? path;
  final double? width;
  final double? height;
  final BorderRadius borderRadius;
  final BoxFit fit;

  /// 占位图标大小
  final double? placeholderIconSize;

  const MpArtwork(
    this.path, {
    Key? key,
    this.width,
    this.height,
    this.borderRadius = BorderRadius.zero,
    this.fit = BoxFit.cover,
    this.placeholderIconSize,
  }) : super(key: key);

  Widget _placeholder() => Container(
        width: width,
        height: height,
        decoration: BoxDecoration(
          borderRadius: borderRadius,
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xFF7C4DFF), Color(0xFF18D2C7)],
          ),
        ),
        child: Icon(
          Icons.music_note,
          color: Colors.white70,
          size: placeholderIconSize ??
              ((width != null && width! < 60) ? 24.0 : 48.0),
        ),
      );

  Widget _image(Uint8List bytes) => ClipRRect(
        borderRadius: borderRadius,
        child: Image.memory(
          bytes,
          width: width,
          height: height,
          fit: fit,
          gaplessPlayback: true,
          errorBuilder: (_, __, ___) => _placeholder(),
        ),
      );

  @override
  Widget build(BuildContext context) {
    final path = this.path;
    if (path == null || path.isEmpty) return _placeholder();

    // 命中内存缓存：直接同步渲染，避免闪烁
    if (ArtworkCache.has(path)) {
      final bytes = ArtworkCache.peek(path);
      return bytes == null ? _placeholder() : _image(bytes);
    }

    return FutureBuilder<Uint8List?>(
      future: ArtworkCache.load(path),
      builder: (context, snapshot) {
        final bytes = snapshot.data;
        if (bytes == null) return _placeholder();
        return _image(bytes);
      },
    );
  }
}
