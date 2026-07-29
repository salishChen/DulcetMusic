import 'dart:convert';
import 'dart:math';
import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import 'database_helper.dart';
import 'models/subsonic_config.dart';
import 'models/song.dart';
import 'models/playlist.dart';

/// Subsonic API 客户端
///
/// 实现 Subsonic REST API 封装，支持内网优先连接策略。
/// API 版本：1.16.1
class SubsonicService {
  static final SubsonicService instance = SubsonicService._();
  SubsonicService._();

  SubsonicConfig? _config;
  String? _activeBaseUrl;

  /// 加载配置
  Future<SubsonicConfig?> loadConfig() async {
    _config = await DatabaseHelper.instance.querySubsonicConfig();
    _activeBaseUrl = null; // 重置缓存的 base URL
    return _config;
  }

  /// 保存配置
  Future<void> saveConfig(SubsonicConfig config) async {
    await DatabaseHelper.instance.saveSubsonicConfig(config);
    _config = config;
    _activeBaseUrl = null;
  }

  /// 是否已配置
  bool get isConfigured => _config != null;

  /// 解析活跃的 Base URL（内网优先，超时 5 秒回退公网）
  Future<String> _resolveBaseUrl() async {
    if (_activeBaseUrl != null) return _activeBaseUrl!;
    if (_config == null) throw Exception('Subsonic 未配置');

    // 尝试内网
    try {
      final url = _buildUrl(_config!.intranetUrl, 'ping');
      final response =
          await http.get(Uri.parse(url)).timeout(const Duration(seconds: 5));
      if (response.statusCode == 200) {
        final json = jsonDecode(response.body);
        if (json['subsonic-response']?['status'] == 'ok') {
          _activeBaseUrl = _config!.intranetUrl;
          return _activeBaseUrl!;
        }
      }
    } catch (_) {
      // 内网不可达，继续尝试公网
    }

    // 尝试公网
    try {
      final url = _buildUrl(_config!.publicUrl, 'ping');
      final response =
          await http.get(Uri.parse(url)).timeout(const Duration(seconds: 10));
      if (response.statusCode == 200) {
        final json = jsonDecode(response.body);
        if (json['subsonic-response']?['status'] == 'ok') {
          _activeBaseUrl = _config!.publicUrl;
          return _activeBaseUrl!;
        }
      }
    } catch (_) {}

    throw Exception('无法连接到 Subsonic 服务器（内网和公网均不可达）');
  }

  /// 构建 API URL
  ///
  /// [endpoint] 可以是纯端点名（如 `'getArtists'`）或带参数的（如 `'getArtist&id=123'`）。
  /// 当包含 `&` 时，第一段作为端点名，其余作为查询参数与认证参数合并。
  String _buildUrl(String baseUrl, String endpoint) {
    final base = baseUrl.endsWith('/')
        ? baseUrl.substring(0, baseUrl.length - 1)
        : baseUrl;
    final params = _buildAuthParams();

    // 处理 endpoint 内嵌参数的情况，例如 'getArtist&id=123'
    final parts = endpoint.split('&');
    final endpointName = parts[0];
    final extraParams = parts.length > 1 ? parts.sublist(1).join('&') : null;

    if (extraParams != null) {
      return '$base/rest/$endpointName?$extraParams&$params';
    }
    return '$base/rest/$endpointName?$params';
  }

  /// 构建认证参数
  String _buildAuthParams() {
    if (_config == null) throw Exception('Subsonic 未配置');
    final salt = _generateSalt(16);
    final token = md5.convert(utf8.encode('${_config!.password}$salt')).toString();
    return 'u=${Uri.encodeComponent(_config!.username)}'
        '&s=$salt'
        '&t=$token'
        '&v=1.16.1'
        '&c=MusicPlayer'
        '&f=json';
  }

  /// 生成随机盐值
  String _generateSalt(int length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    final random = Random.secure();
    return List.generate(length, (_) => chars[random.nextInt(chars.length)])
        .join();
  }

  /// 发送 API 请求
  Future<Map<String, dynamic>> _request(String endpoint) async {
    final baseUrl = await _resolveBaseUrl();
    final url = _buildUrl(baseUrl, endpoint);
    final response =
        await http.get(Uri.parse(url)).timeout(const Duration(seconds: 30));
    if (response.statusCode != 200) {
      throw Exception('HTTP ${response.statusCode}');
    }
    final json = jsonDecode(response.body) as Map<String, dynamic>;
    final subsonicResponse = json['subsonic-response'] as Map<String, dynamic>;
    if (subsonicResponse['status'] != 'ok') {
      final error = subsonicResponse['error'];
      final code = error?['code'] ?? 'unknown';
      final message = error?['message'] ?? '未知错误';
      throw Exception('Subsonic 错误 ($code): $message');
    }
    return subsonicResponse;
  }

  /// 测试连接
  Future<bool> ping() async {
    try {
      final baseUrl = await _resolveBaseUrl();
      final url = _buildUrl(baseUrl, 'ping');
      final response =
          await http.get(Uri.parse(url)).timeout(const Duration(seconds: 10));
      if (response.statusCode == 200) {
        final json = jsonDecode(response.body);
        return json['subsonic-response']?['status'] == 'ok';
      }
    } catch (_) {}
    return false;
  }

  /// 获取所有歌曲列表（通过 getArtists -> getAlbum -> getSong 三级遍历）
  Future<List<Song>> getAllSongs() async {
    final List<Song> allSongs = [];

    // 1. 获取艺术家列表
    final artistsResponse = await _request('getArtists');
    final artistsIndex =
        artistsResponse['artists']?['index'] as List<dynamic>? ?? [];

    for (final index in artistsIndex) {
      final artists = index['artist'] as List<dynamic>? ?? [];
      for (final artist in artists) {
        final artistId = artist['id'].toString();
        final artistName = artist['name'] as String? ?? '未知艺术家';

        // 2. 获取艺术家的专辑
        try {
          final albumResponse =
              await _request('getArtist&id=$artistId');
          final albums =
              albumResponse['artist']?['album'] as List<dynamic>? ?? [];

          for (final album in albums) {
            final albumId = album['id'].toString();
            final albumName = album['title'] as String? ?? '未知专辑';
            final albumCoverArt = album['coverArt'] as String?;

            // 3. 获取专辑内的歌曲
            try {
              final songResponse =
                  await _request('getAlbum&id=$albumId');
              final songs =
                  songResponse['album']?['song'] as List<dynamic>? ?? [];

              for (final song in songs) {
                allSongs.add(_parseSong(song, artistName, albumName,
                    albumId: albumCoverArt ?? albumId));
              }
            } catch (e) {
              print('SubsonicService: 获取专辑 $albumName 歌曲失败: $e');
            }
          }
        } catch (e) {
          print('SubsonicService: 获取艺术家 $artistName 专辑失败: $e');
        }
      }
    }

    return allSongs;
  }

  /// 解析歌曲 JSON 为 Song 对象
  ///
  /// [albumId] 专辑 ID，用于当歌曲没有 coverArt 时作为备选。
  Song _parseSong(
      Map<String, dynamic> song, String artistName, String albumName,
      {String? albumId}) {
    final id = song['id'].toString();
    final title = song['title'] as String? ?? '未知歌曲';
    final duration = song['duration'] as int?; // 秒
    final track = song['track'] as int?;
    final size = song['size'] as int?;
    final suffix = song['suffix'] as String?;
    final bitRate = song['bitRate'] as int?;
    final contentType = song['contentType'] as String?;
    final coverArt = song['coverArt'] as String?;

    final baseUrl = _activeBaseUrl ?? _config?.publicUrl ?? '';
    final streamUrl = _buildUrl(baseUrl, 'stream&id=$id');

    // 优先使用歌曲的 coverArt，其次使用专辑 ID
    final effectiveCoverArt = coverArt ?? albumId;

    return Song(
      title: title,
      path: streamUrl, // path 存储流媒体 URL
      artist: artistName,
      album: albumName,
      trackNumber: track,
      duration: duration != null ? duration * 1000 : null, // 转为毫秒
      size: size,
      format: suffix,
      codec: contentType,
      sourceType: 'subsonic',
      remoteId: id,
      remoteStreamUrl: streamUrl,
      coverArtId: effectiveCoverArt,
      dateAdded: DateTime.now().millisecondsSinceEpoch,
    );
  }

  /// 获取流媒体 URL
  String getStreamUrl(String songId) {
    if (_config == null) throw Exception('Subsonic 未配置');
    final baseUrl = _activeBaseUrl ?? _config!.publicUrl;
    return _buildUrl(baseUrl, 'stream&id=$songId');
  }

  /// 获取歌单列表
  Future<List<Map<String, dynamic>>> getPlaylists() async {
    final response = await _request('getPlaylists');
    final playlists =
        response['playlists']?['playlist'] as List<dynamic>? ?? [];
    return playlists.cast<Map<String, dynamic>>();
  }

  /// 获取歌单详情（包含歌曲列表）
  Future<Map<String, dynamic>> getPlaylistDetail(String playlistId) async {
    final response = await _request('getPlaylist&id=$playlistId');
    return response['playlist'] as Map<String, dynamic>? ?? {};
  }

  /// 创建远程歌单
  /// [name] 歌单名称
  /// [songIds] Subsonic 歌曲 ID 列表
  Future<String?> createPlaylist(String name, List<String> songIds) async {
    final songIdParams = songIds.map((id) => 'songId=$id').join('&');
    final endpoint = 'createPlaylist&name=${Uri.encodeComponent(name)}'
        '${songIdParams.isNotEmpty ? '&$songIdParams' : ''}';
    try {
      final response = await _request(endpoint);
      return response['playlist']?['id']?.toString();
    } catch (e) {
      print('SubsonicService: 创建歌单失败: $e');
      return null;
    }
  }

  /// 同步远程歌单到本地
  /// 返回同步的歌单数量
  Future<int> syncPlaylistsToLocalStorage() async {
    final remotePlaylists = await getPlaylists();
    int synced = 0;

    for (final remotePl in remotePlaylists) {
      final remoteName = remotePl['name'] as String? ?? '未命名歌单';
      final remoteId = remotePl['id'].toString();

      // 检查本地是否已存在同名歌单
      final db = DatabaseHelper.instance;
      final localPlaylists = await db.queryPlaylists();
      final existing =
          localPlaylists.where((p) => p.name == remoteName).firstOrNull;

      int localPlaylistId;
      if (existing != null) {
        localPlaylistId = existing.id!;
      } else {
        final newId = await db.createPlaylist(remoteName);
        if (newId == null) continue;
        localPlaylistId = newId;
      }

      // 获取远程歌单详情
      try {
        final detail = await getPlaylistDetail(remoteId);
        final songs = detail['entry'] as List<dynamic>? ?? [];

        int position = 0;
        for (final song in songs) {
          final songId = song['id'].toString();
          final title = song['title'] as String? ?? '未知歌曲';
          final artist = song['artist'] as String? ?? '未知艺术家';
          final album = song['album'] as String? ?? '未知专辑';
          final duration = song['duration'] as int?;
          final size = song['size'] as int?;
          final suffix = song['suffix'] as String?;
          final track = song['track'] as int?;

          final baseUrl = _activeBaseUrl ?? _config?.publicUrl ?? '';
          final streamUrl = _buildUrl(baseUrl, 'stream&id=$songId');

          // 检查本地是否已有该远程歌曲
          final existingSong = await db.querySongByRemoteId(songId);
          int localSongId;

          if (existingSong != null) {
            localSongId = existingSong.id!;
          } else {
            // 插入新歌曲记录
            final newSong = Song(
              title: title,
              path: streamUrl,
              artist: artist,
              album: album,
              trackNumber: track,
              duration: duration != null ? duration * 1000 : null,
              size: size,
              format: suffix,
              sourceType: 'subsonic',
              remoteId: songId,
              remoteStreamUrl: streamUrl,
              dateAdded: DateTime.now().millisecondsSinceEpoch,
            );
            final affected =
                await db.insertSongs([newSong], source: 'subsonic');
            if (affected == 0) continue;

            // 重新查询获取 id
            final inserted = await db.querySongByRemoteId(songId);
            if (inserted == null) continue;
            localSongId = inserted.id!;
          }

          // 添加到歌单
          await db.addSongToPlaylist(localPlaylistId, localSongId);
          position++;
        }
        synced++;
      } catch (e) {
        print('SubsonicService: 同步歌单 $remoteName 失败: $e');
      }
    }

    return synced;
  }

  /// 获取封面图片字节数据
  ///
  /// [coverArtId] 来自歌曲/专辑/艺术家的 coverArt 字段（如 "al-123"）。
  /// 返回图片的原始字节，失败返回 null。
  Future<Uint8List?> getCoverArt(String coverArtId) async {
    try {
      final baseUrl = await _resolveBaseUrl();
      final url = _buildUrl(baseUrl, 'getCoverArt&id=$coverArtId');
      final response =
          await http.get(Uri.parse(url)).timeout(const Duration(seconds: 30));
      // 只检查状态码和数据长度，不检查 content-type（某些服务器可能不返回正确的类型）
      if (response.statusCode == 200 && response.bodyBytes.length > 100) {
        return response.bodyBytes;
      }
      print('SubsonicService: 封面响应异常 - 状态码: ${response.statusCode}, '
          '大小: ${response.bodyBytes.length}');
    } catch (e) {
      print('SubsonicService: 获取封面失败 ($coverArtId): $e');
    }
    return null;
  }

  /// 获取歌词文本
  ///
  /// [artist] 艺术家名称
  /// [title] 歌曲标题
  /// 返回 LRC 格式歌词文本，失败返回 null。
  Future<String?> getLyrics(String artist, String title) async {
    try {
      final response = await _request(
        'getLyrics&artist=${Uri.encodeComponent(artist)}'
        '&title=${Uri.encodeComponent(title)}',
      );
      final lyrics = response['lyrics']?['value'] as String?;
      if (lyrics != null && lyrics.trim().isNotEmpty) {
        print('SubsonicService: 成功获取歌词 - $artist - $title');
        return lyrics.trim();
      }
      print('SubsonicService: 歌词为空 - $artist - $title');
    } catch (e) {
      print('SubsonicService: 获取歌词失败 - $artist - $title: $e');
    }
    return null;
  }

  /// 清除缓存的连接（网络变化时调用）
  void resetConnection() {
    _activeBaseUrl = null;
  }
}
