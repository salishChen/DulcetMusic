import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' as p;

import 'models/song.dart';
import 'models/album.dart';
import 'models/artist.dart';
import 'models/playlist.dart';

/// SQLite 数据库单例
///
/// 三张表：
/// - songs：歌曲表（扫描入库的全部元数据）
/// - playlists：歌单表（歌单名）
/// - playlist_songs：歌单-歌曲绑定子表（含歌单内排序）
class DatabaseHelper {
  static final DatabaseHelper instance = DatabaseHelper._();
  DatabaseHelper._();

  static const _dbName = 'music_player.db';
  static const _dbVersion = 3;

  Database? _db;

  /// 轻量内存缓存：一级菜单查询结果，扫描/增删后失效，提升页面切换响应速度
  List<Song>? _cachedAllSongs;
  List<Album>? _cachedAlbums;
  List<Artist>? _cachedArtists;

  Future<Database> get database async {
    if (_db != null) return _db!;
    _db = await _open();
    return _db!;
  }

  Future<Database> _open() async {
    final dbPath = await getDatabasesPath();
    return openDatabase(
      p.join(dbPath, _dbName),
      version: _dbVersion,
      onConfigure: (db) async {
        // 启用外键，保证删除歌单/歌曲时级联清理绑定子表
        await db.execute('PRAGMA foreign_keys = ON');
      },
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE songs (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            title TEXT NOT NULL,
            path TEXT NOT NULL UNIQUE,
            artist TEXT,
            album TEXT,
            albumArtist TEXT,
            trackNumber INTEGER,
            duration INTEGER,
            bitrate INTEGER,
            sampleRate INTEGER,
            bitDepth INTEGER,
            size INTEGER,
            format TEXT,
            codec TEXT,
            dateAdded INTEGER,
            dateModified INTEGER,
            hasArtwork INTEGER DEFAULT 0,
            lyrics TEXT,
            source TEXT
          )
        ''');
        await db.execute('''
          CREATE TABLE playlists (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
          )
        ''');
        await db.execute('''
          CREATE TABLE playlist_songs (
            playlistId INTEGER NOT NULL,
            songId INTEGER NOT NULL,
            position INTEGER NOT NULL,
            PRIMARY KEY (playlistId, songId),
            FOREIGN KEY (playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
            FOREIGN KEY (songId) REFERENCES songs(id) ON DELETE CASCADE
          )
        ''');
        await db.execute('CREATE INDEX idx_songs_album ON songs(album)');
        await db.execute('CREATE INDEX idx_songs_artist ON songs(artist)');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          await db.execute('ALTER TABLE songs ADD COLUMN lyrics TEXT');
        }
        if (oldVersion < 3) {
          await db.execute('ALTER TABLE songs ADD COLUMN source TEXT');
        }
      },
    );
  }

  /// 失效全部缓存（扫描/增删歌曲后调用）
  void _invalidateCache() {
    _cachedAllSongs = null;
    _cachedAlbums = null;
    _cachedArtists = null;
  }

  // ======================== 歌曲 ========================

  /// 查询全部歌曲（按标题排序），带内存缓存
  Future<List<Song>> queryAllSongs() async {
    if (_cachedAllSongs != null) return _cachedAllSongs!;
    final db = await database;
    final rows = await db.query('songs', orderBy: 'title COLLATE NOCASE ASC');
    _cachedAllSongs = rows.map(Song.fromMap).toList();
    return _cachedAllSongs!;
  }

  /// 按 id 查询单曲
  Future<Song?> querySongById(int id) async {
    final db = await database;
    final rows = await db.query('songs', where: 'id = ?', whereArgs: [id]);
    return rows.isEmpty ? null : Song.fromMap(rows.first);
  }

  /// 事务批量插入/更新歌曲，返回实际新增/覆盖的数量。
  ///
  /// 去重规则（以 [source] 标识音乐来源）：
  /// - 已存在「同一首歌」（标题|艺术家|专辑相同）且**来源相同** -> 跳过，不重复添加；
  /// - 已存在「同一首歌」但**来源不同** -> 用后扫描到的覆盖旧记录（更新元数据、路径与来源）；
  /// - 不存在 -> 新增。
  Future<int> insertSongs(List<Song> songs, {String? source}) async {
    if (songs.isEmpty) return 0;
    final db = await database;

    // 预读现有歌曲的身份信息，按 identityKey 建立索引
    final existingRows = await db.query('songs',
        columns: [
          'id',
          'title',
          'artist',
          'album',
          'path',
          'source',
        ]);
    final byIdentity = <String, Map<String, Object?>>{};
    for (final row in existingRows) {
      final key = _identityKeyOf(
        row['title'] as String?,
        row['artist'] as String?,
        row['album'] as String?,
      );
      byIdentity[key] = row;
    }

    int affected = 0;
    await db.transaction((txn) async {
      final batch = txn.batch();
      for (final s in songs) {
        final key = s.identityKey;
        final existing = byIdentity[key];
        final map = s.toMap()..['source'] = source;

        if (existing == null) {
          // 全新歌曲：插入（path 冲突时以路径替换，容错重复路径）
          batch.insert('songs', map,
              conflictAlgorithm: ConflictAlgorithm.replace);
          affected++;
          // 记录到索引，避免同一批内相同来源的重复文件再次入库
          byIdentity[key] = {
            'source': source,
            'path': s.path,
          };
        } else if (existing['source'] == source) {
          // 同一来源的相同音乐：跳过
          continue;
        } else {
          // 不同来源的同一首歌：后来者覆盖旧记录
          final id = existing['id'] as int?;
          final updateMap = Map<String, Object?>.from(map)..remove('id');
          if (id != null) {
            batch.update('songs', updateMap,
                where: 'id = ?', whereArgs: [id]);
          } else {
            batch.insert('songs', map,
                conflictAlgorithm: ConflictAlgorithm.replace);
          }
          affected++;
          byIdentity[key] = {
            'source': source,
            'path': s.path,
          };
        }
      }
      await batch.commit(noResult: true);
    });
    _invalidateCache();
    return affected;
  }

  /// 归一化歌曲身份键（与 [Song.identityKey] 保持一致）
  static String _identityKeyOf(String? title, String? artist, String? album) =>
      '${(title ?? '').trim().toLowerCase()}|${(artist ?? '').trim().toLowerCase()}|${(album ?? '').trim().toLowerCase()}';

  /// 删除歌曲（级联清理歌单绑定）
  Future<void> deleteSong(int id) async {
    final db = await database;
    await db.delete('songs', where: 'id = ?', whereArgs: [id]);
    _invalidateCache();
  }

  /// 清空歌曲表
  Future<void> clearSongs() async {
    final db = await database;
    await db.delete('songs');
    _invalidateCache();
  }

  // ======================== 专辑（聚合） ========================

  /// 查询全部专辑（GROUP BY album，在 DB 层聚合），带内存缓存
  Future<List<Album>> queryAlbums() async {
    if (_cachedAlbums != null) return _cachedAlbums!;
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT album AS title,
             COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
             MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE album IS NOT NULL AND album != ''
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
    ''');
    _cachedAlbums = rows
        .map((m) => Album(
              title: m['title'] as String,
              artist: m['artist'] as String?,
              coverSongId: m['coverSongId'] as int?,
              coverSongPath: m['coverSongPath'] as String?,
              songCount: (m['songCount'] as int?) ?? 0,
            ))
        .toList();
    return _cachedAlbums!;
  }

  /// 查询专辑内全部歌曲（按音轨号排序）
  Future<List<Song>> querySongsByAlbum(String album) async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'album = ?',
        whereArgs: [album],
        orderBy: 'trackNumber ASC, title COLLATE NOCASE ASC');
    return rows.map(Song.fromMap).toList();
  }

  // ======================== 艺术家（聚合） ========================

  /// 查询全部艺术家（GROUP BY artist），带内存缓存
  Future<List<Artist>> queryArtists() async {
    if (_cachedArtists != null) return _cachedArtists!;
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT artist AS name,
             COUNT(*) AS songCount,
             COUNT(DISTINCT album) AS albumCount,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath
      FROM songs
      WHERE artist IS NOT NULL AND artist != ''
      GROUP BY artist
      ORDER BY artist COLLATE NOCASE ASC
    ''');
    _cachedArtists = rows
        .map((m) => Artist(
              name: m['name'] as String,
              songCount: (m['songCount'] as int?) ?? 0,
              albumCount: (m['albumCount'] as int?) ?? 0,
              coverSongPath: m['coverSongPath'] as String?,
            ))
        .toList();
    return _cachedArtists!;
  }

  /// 查询艺术家全部歌曲
  Future<List<Song>> querySongsByArtist(String artist) async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'artist = ?',
        whereArgs: [artist],
        orderBy: 'album COLLATE NOCASE ASC, trackNumber ASC');
    return rows.map(Song.fromMap).toList();
  }

  /// 查询艺术家的专辑列表
  Future<List<Album>> queryAlbumsByArtist(String artist) async {
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT album AS title,
             COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
             MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE artist = ? AND album IS NOT NULL AND album != ''
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
    ''', [artist]);
    return rows
        .map((m) => Album(
              title: m['title'] as String,
              artist: m['artist'] as String?,
              coverSongId: m['coverSongId'] as int?,
              coverSongPath: m['coverSongPath'] as String?,
              songCount: (m['songCount'] as int?) ?? 0,
            ))
        .toList();
  }

  // ======================== 歌单 ========================

  /// 查询全部歌单（含歌曲数）
  Future<List<Playlist>> queryPlaylists() async {
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT p.id, p.name, COUNT(ps.songId) AS songCount
      FROM playlists p
      LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
      GROUP BY p.id
      ORDER BY p.id ASC
    ''');
    return rows.map(Playlist.fromMap).toList();
  }

  /// 新建歌单，重名返回 null
  Future<int?> createPlaylist(String name) async {
    final db = await database;
    try {
      return await db.insert('playlists', {'name': name},
          conflictAlgorithm: ConflictAlgorithm.abort);
    } catch (e) {
      print('createPlaylist failed: $e');
      return null;
    }
  }

  /// 删除歌单（级联删除绑定记录）
  Future<void> deletePlaylist(int id) async {
    final db = await database;
    await db.delete('playlists', where: 'id = ?', whereArgs: [id]);
  }

  /// 查询歌单内全部歌曲（按 position 排序）
  Future<List<Song>> querySongsInPlaylist(int playlistId) async {
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT s.* FROM songs s
      INNER JOIN playlist_songs ps ON ps.songId = s.id
      WHERE ps.playlistId = ?
      ORDER BY ps.position ASC
    ''', [playlistId]);
    return rows.map(Song.fromMap).toList();
  }

  /// 添加歌曲到歌单（已存在返回 false）
  Future<bool> addSongToPlaylist(int playlistId, int songId) async {
    final db = await database;
    final exists = await db.query('playlist_songs',
        where: 'playlistId = ? AND songId = ?',
        whereArgs: [playlistId, songId]);
    if (exists.isNotEmpty) return false;
    final maxRow = await db.rawQuery(
        'SELECT COALESCE(MAX(position), -1) + 1 AS next FROM playlist_songs WHERE playlistId = ?',
        [playlistId]);
    final next = (maxRow.first['next'] as int?) ?? 0;
    await db.insert('playlist_songs', {
      'playlistId': playlistId,
      'songId': songId,
      'position': next,
    });
    return true;
  }

  /// 从歌单移除歌曲
  Future<void> removeSongFromPlaylist(int playlistId, int songId) async {
    final db = await database;
    await db.delete('playlist_songs',
        where: 'playlistId = ? AND songId = ?',
        whereArgs: [playlistId, songId]);
  }
}
