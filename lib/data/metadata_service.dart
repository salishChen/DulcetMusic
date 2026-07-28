import 'dart:io';

import 'package:audio_metadata_reader/audio_metadata_reader.dart';
import 'package:flutter/foundation.dart';
import 'package:on_audio_query/on_audio_query.dart';
import 'package:path/path.dart' as p;

import 'database_helper.dart';
import 'models/song.dart';

/// 扫描进度回调：processed 已处理数，total 总数，failed 失败数
typedef ScanProgress = void Function(int processed, int total, int failed);

/// 扫描结果
class ScanResult {
  final int added;
  final int failed;
  final int total;
  const ScanResult({this.added = 0, this.failed = 0, this.total = 0});
}

/// 支持的音频扩展名（与 audio_metadata_reader 支持格式一致）
const kAudioExtensions = {
  '.mp3', '.m4a', '.mp4', '.flac', '.ogg', '.opus', '.wav', '.aiff',
  '.aifc', '.ape',
};

/// 音乐扫描与元数据入库服务
///
/// 两种扫描来源：
/// 1. 安卓媒体库（on_audio_query 仅用于获取文件路径列表）
/// 2. 指定文件夹（递归遍历）
/// 元数据统一用 audio_metadata_reader 从文件本体读取，批量写入 SQLite。
class MetadataService {
  final DatabaseHelper _dbHelper;
  final OnAudioQuery _audioQuery = OnAudioQuery();

  MetadataService({DatabaseHelper? dbHelper})
      : _dbHelper = dbHelper ?? DatabaseHelper.instance;

  /// 扫描安卓媒体库：仅取路径列表，元数据仍从文件读取
  Future<ScanResult> scanMediaLibrary({ScanProgress? onProgress}) async {
    List<String> paths = [];
    try {
      final hasPermission = await _audioQuery.permissionsRequest();
      if (!hasPermission) {
        print('MetadataService: 媒体库权限被拒绝');
        return const ScanResult();
      }
      final models = await _audioQuery.querySongs(
        sortType: SongSortType.TITLE,
        orderType: OrderType.ASC_OR_SMALLER,
        uriType: UriType.EXTERNAL,
        ignoreCase: true,
      );
      paths = models
          .map((m) => m.data)
          .where((d) => d.isNotEmpty &&
              kAudioExtensions.contains(p.extension(d).toLowerCase()))
          .toList();
    } catch (e) {
      print('MetadataService: 查询媒体库失败: $e');
      return const ScanResult();
    }
    return _scanPaths(paths, source: 'media_library', onProgress: onProgress);
  }

  /// 扫描指定文件夹（递归遍历所有子目录）
  Future<ScanResult> scanFolder(String folderPath,
      {ScanProgress? onProgress}) async {
    final dir = Directory(folderPath);
    if (!await dir.exists()) {
      print('MetadataService: 文件夹不存在: $folderPath');
      return const ScanResult();
    }
    final paths = <String>[];
    try {
      await for (final entity
          in dir.list(recursive: true, followLinks: false)) {
        if (entity is File &&
            kAudioExtensions.contains(p.extension(entity.path).toLowerCase())) {
          paths.add(entity.path);
        }
      }
    } catch (e) {
      print('MetadataService: 遍历文件夹失败: $e');
    }
    // 以文件夹路径作为来源标识：同一文件夹再次扫描视为同源
    return _scanPaths(paths, source: folderPath, onProgress: onProgress);
  }

  /// 分批解析文件元数据（后台 isolate）并事务批量入库
  Future<ScanResult> _scanPaths(List<String> paths,
      {String? source, ScanProgress? onProgress}) async {
    if (paths.isEmpty) return const ScanResult();
    const batchSize = 30;
    int processed = 0, failed = 0, added = 0;
    final total = paths.length;

    for (var i = 0; i < paths.length; i += batchSize) {
      final chunk = paths.sublist(
          i, (i + batchSize > paths.length) ? paths.length : i + batchSize);
      // 元数据解析放到后台 isolate，避免 UI 卡顿
      final maps = await compute(parseAudioFiles, chunk);
      final songs = <Song>[];
      for (final m in maps) {
        if (m == null) {
          failed++;
        } else {
          songs.add(Song.fromMap(m));
        }
      }
      added += await _dbHelper.insertSongs(songs, source: source);
      processed += chunk.length;
      onProgress?.call(processed, total, failed);
      // 采样打印，避免刷屏
      print('MetadataService: 扫描进度 $processed/$total，失败 $failed');
    }
    return ScanResult(added: added, failed: failed, total: total);
  }
}

/// isolate 入口：解析一批音频文件，返回 Song.toMap 列表（失败项为 null）
///
/// 必须是顶层函数才能被 compute 调用。
List<Map<String, dynamic>?> parseAudioFiles(List<String> paths) {
  return paths.map((path) {
    try {
      final file = File(path);
      if (!file.existsSync()) return null;
      final stat = file.statSync();
      // getImage: true 仅用于判断是否含内嵌封面，不落库图片字节
      final meta = readMetadata(file, getImage: true);
      final ext = p.extension(path).toLowerCase().replaceFirst('.', '');
      final title = (meta.title == null || meta.title!.trim().isEmpty)
          ? p.basenameWithoutExtension(path)
          : meta.title!.trim();
      return Song(
        title: title,
        path: path,
        artist: meta.artist?.trim(),
        album: meta.album?.trim(),
        // audio_metadata_reader 无独立 albumArtist 字段，
        // 以 performers 首项回退，再回退 artist
        albumArtist: meta.performers.isNotEmpty
            ? meta.performers.first
            : meta.artist?.trim(),
        trackNumber: meta.trackNumber,
        duration: meta.duration?.inMilliseconds,
        bitrate: meta.bitrate,
        sampleRate: meta.sampleRate,
        // 解析库不提供位深，存 null
        bitDepth: null,
        size: stat.size,
        format: ext,
        codec: _codecOf(ext),
        dateAdded: DateTime.now().millisecondsSinceEpoch,
        dateModified: stat.modified.millisecondsSinceEpoch,
        hasArtwork: meta.pictures.isNotEmpty,
        lyrics: meta.lyrics?.trim(),
      ).toMap();
    } catch (e) {
      print('parseAudioFiles: 解析失败 $path: $e');
      return null;
    }
  }).toList();
}

/// 根据扩展名推断编码
String _codecOf(String ext) {
  switch (ext) {
    case 'mp3':
      return 'MPEG Layer III';
    case 'flac':
      return 'FLAC';
    case 'm4a':
    case 'mp4':
      return 'AAC/ALAC';
    case 'ogg':
      return 'Vorbis';
    case 'opus':
      return 'Opus';
    case 'wav':
      return 'PCM';
    case 'aiff':
    case 'aifc':
      return 'AIFF';
    case 'ape':
      return 'APE';
    default:
      return ext.toUpperCase();
  }
}
