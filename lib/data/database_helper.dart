import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart' as p;

import 'models/song.dart';
import 'models/album.dart';
import 'models/artist.dart';
import 'models/playlist.dart';
import 'models/subsonic_config.dart';

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
  static const _dbVersion = 6;

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
        // Subsonic 远程音乐支持字段
        await db.execute('ALTER TABLE songs ADD COLUMN sourceType TEXT DEFAULT "local"');
        await db.execute('ALTER TABLE songs ADD COLUMN remoteId TEXT');
        await db.execute('ALTER TABLE songs ADD COLUMN remoteStreamUrl TEXT');
        await db.execute('ALTER TABLE songs ADD COLUMN cachedPath TEXT');
        await db.execute('ALTER TABLE songs ADD COLUMN cacheTimestamp INTEGER');
        await db.execute('ALTER TABLE songs ADD COLUMN cachedArtworkPath TEXT');
        await db.execute('ALTER TABLE songs ADD COLUMN coverArtId TEXT');
        await db.execute('ALTER TABLE songs ADD COLUMN playCount INTEGER DEFAULT 0');
        await db.execute('ALTER TABLE songs ADD COLUMN isLiked INTEGER DEFAULT 0');
        await db.execute('ALTER TABLE songs ADD COLUMN lastPlayed INTEGER');
        // 艺术家元数据表
        await db.execute('''
          CREATE TABLE IF NOT EXISTS artists_meta (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE,
            artistId TEXT,
            coverArtId TEXT,
            cachedArtworkPath TEXT,
            isLiked INTEGER DEFAULT 0
          )
        ''');
        // Subsonic 服务器配置表
        await db.execute('''
          CREATE TABLE subsonic_config (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            intranetUrl TEXT NOT NULL,
            publicUrl TEXT NOT NULL,
            username TEXT NOT NULL,
            password TEXT NOT NULL,
            serverName TEXT,
            isActive INTEGER DEFAULT 1
          )
        ''');
      },
      onUpgrade: (db, oldVersion, newVersion) async {
        if (oldVersion < 2) {
          await db.execute('ALTER TABLE songs ADD COLUMN lyrics TEXT');
        }
        if (oldVersion < 3) {
          await db.execute('ALTER TABLE songs ADD COLUMN source TEXT');
        }
        if (oldVersion < 4) {
          // Subsonic 远程音乐支持
          await db.execute('ALTER TABLE songs ADD COLUMN sourceType TEXT DEFAULT "local"');
          await db.execute('ALTER TABLE songs ADD COLUMN remoteId TEXT');
          await db.execute('ALTER TABLE songs ADD COLUMN remoteStreamUrl TEXT');
          await db.execute('ALTER TABLE songs ADD COLUMN cachedPath TEXT');
          await db.execute('ALTER TABLE songs ADD COLUMN cacheTimestamp INTEGER');
          await db.execute('''
            CREATE TABLE subsonic_config (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              intranetUrl TEXT NOT NULL,
              publicUrl TEXT NOT NULL,
              username TEXT NOT NULL,
              password TEXT NOT NULL,
              serverName TEXT,
              isActive INTEGER DEFAULT 1
            )
          ''');
        }
        if (oldVersion < 5) {
          // 远程封面缓存支持
          await db.execute('ALTER TABLE songs ADD COLUMN cachedArtworkPath TEXT');
          await db.execute('ALTER TABLE songs ADD COLUMN coverArtId TEXT');
        }
        if (oldVersion < 6) {
          // 播放统计和喜欢功能
          await db.execute('ALTER TABLE songs ADD COLUMN playCount INTEGER DEFAULT 0');
          await db.execute('ALTER TABLE songs ADD COLUMN isLiked INTEGER DEFAULT 0');
          await db.execute('ALTER TABLE songs ADD COLUMN lastPlayed INTEGER');
          // 艺术家元数据表
          await db.execute('''
            CREATE TABLE IF NOT EXISTS artists_meta (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL UNIQUE,
              artistId TEXT,
              coverArtId TEXT,
              cachedArtworkPath TEXT,
              isLiked INTEGER DEFAULT 0
            )
          ''');
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

  /// 按 path 查询单曲（用于播放列表持久化恢复）
  Future<Song?> querySongByPath(String path) async {
    final db = await database;
    final rows = await db.query('songs', where: 'path = ?', whereArgs: [path]);
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
             MAX(cachedArtworkPath) AS coverArtworkPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE album IS NOT NULL AND album != ''
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
    ''');
    _cachedAlbums = rows.map(Album.fromMap).toList();
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
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             MAX(cachedArtworkPath) AS coverArtworkPath
      FROM songs
      WHERE artist IS NOT NULL AND artist != ''
      GROUP BY artist
      ORDER BY artist COLLATE NOCASE ASC
    ''');
    _cachedArtists = rows.map(Artist.fromMap).toList();
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
             MAX(cachedArtworkPath) AS coverArtworkPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE artist = ? AND album IS NOT NULL AND album != ''
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
    ''', [artist]);
    return rows.map(Album.fromMap).toList();
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

  // ======================== Subsonic 配置 ========================

  /// 查询当前活跃的 Subsonic 配置
  Future<SubsonicConfig?> querySubsonicConfig() async {
    final db = await database;
    final rows = await db.query('subsonic_config',
        where: 'isActive = ?', whereArgs: [1], limit: 1);
    return rows.isEmpty ? null : SubsonicConfig.fromMap(rows.first);
  }

  /// 保存 Subsonic 配置（存在则更新，不存在则插入）
  Future<void> saveSubsonicConfig(SubsonicConfig config) async {
    final db = await database;
    final existing = await querySubsonicConfig();
    if (existing != null) {
      await db.update('subsonic_config', config.toMap(),
          where: 'id = ?', whereArgs: [existing.id]);
    } else {
      await db.insert('subsonic_config', config.toMap());
    }
  }

  /// 删除 Subsonic 配置
  Future<void> deleteSubsonicConfig(int id) async {
    final db = await database;
    await db.delete('subsonic_config', where: 'id = ?', whereArgs: [id]);
  }

  // ======================== 远程歌曲缓存 ========================

  /// 更新歌曲的缓存路径和时间戳
  Future<void> updateSongCache(int songId, String cachedPath) async {
    final db = await database;
    await db.update(
      'songs',
      {
        'cachedPath': cachedPath,
        'cacheTimestamp': DateTime.now().millisecondsSinceEpoch,
      },
      where: 'id = ?',
      whereArgs: [songId],
    );
    _invalidateCache();
  }

  /// 查询所有已缓存的远程歌曲
  Future<List<Song>> queryCachedSongs() async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'cachedPath IS NOT NULL AND sourceType = ?',
        whereArgs: ['subsonic'],
        orderBy: 'cacheTimestamp DESC');
    return rows.map(Song.fromMap).toList();
  }

  /// 清除歌曲的缓存
  Future<void> clearSongCache(int songId) async {
    final db = await database;
    await db.update(
      'songs',
      {'cachedPath': null, 'cacheTimestamp': null},
      where: 'id = ?',
      whereArgs: [songId],
    );
    _invalidateCache();
  }

  /// 查询缓存时间最早的歌曲（用于 LRU 淘汰）
  Future<Song?> queryOldestCachedSong() async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'cachedPath IS NOT NULL AND sourceType = ?',
        whereArgs: ['subsonic'],
        orderBy: 'cacheTimestamp ASC',
        limit: 1);
    return rows.isEmpty ? null : Song.fromMap(rows.first);
  }

  /// 查询远程歌曲（按 remoteId 查找）
  Future<Song?> querySongByRemoteId(String remoteId) async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'remoteId = ?', whereArgs: [remoteId], limit: 1);
    return rows.isEmpty ? null : Song.fromMap(rows.first);
  }

  /// 更新歌曲的封面缓存路径
  Future<void> updateArtworkCache(int songId, String artworkPath) async {
    final db = await database;
    await db.update(
      'songs',
      {'cachedArtworkPath': artworkPath},
      where: 'id = ?',
      whereArgs: [songId],
    );
    _invalidateCache();
  }

  /// 更新歌曲的歌词内容
  Future<void> updateSongLyrics(int songId, String lyrics) async {
    final db = await database;
    await db.update(
      'songs',
      {'lyrics': lyrics},
      where: 'id = ?',
      whereArgs: [songId],
    );
    _invalidateCache();
  }

  /// 查询封面尚未缓存的远程歌曲（coverArtId 有值但 cachedArtworkPath 为空）
  Future<List<Song>> querySongsNeedingArtworkCache() async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'sourceType = ? AND coverArtId IS NOT NULL AND (cachedArtworkPath IS NULL OR cachedArtworkPath = ?)',
        whereArgs: ['subsonic', ''],
        orderBy: 'id ASC');
    return rows.map(Song.fromMap).toList();
  }

  // ======================== 播放统计 ========================

  /// 递增歌曲播放次数并更新最后播放时间
  Future<void> incrementPlayCount(int songId) async {
    final db = await database;
    await db.rawUpdate(
      'UPDATE songs SET playCount = playCount + 1, lastPlayed = ? WHERE id = ?',
      [DateTime.now().millisecondsSinceEpoch, songId],
    );
    _invalidateCache();
  }

  /// 查询最常播放的歌曲（Top N）
  Future<List<Song>> queryTopPlayed({int limit = 20}) async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'playCount > 0',
        orderBy: 'playCount DESC',
        limit: limit);
    return rows.map(Song.fromMap).toList();
  }

  /// 查询最近播放的歌曲
  Future<List<Song>> queryRecentlyPlayed({int limit = 50}) async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'lastPlayed IS NOT NULL',
        orderBy: 'lastPlayed DESC',
        limit: limit);
    return rows.map(Song.fromMap).toList();
  }

  // ======================== 喜欢功能 ========================

  /// 切换歌曲喜欢状态
  Future<void> toggleLikeSong(int songId) async {
    final db = await database;
    await db.rawUpdate(
      'UPDATE songs SET isLiked = CASE WHEN isLiked = 1 THEN 0 ELSE 1 END WHERE id = ?',
      [songId],
    );
    _invalidateCache();
  }

  /// 查询喜欢的歌曲
  Future<List<Song>> queryLikedSongs() async {
    final db = await database;
    final rows = await db.query('songs',
        where: 'isLiked = 1',
        orderBy: 'title COLLATE NOCASE ASC');
    return rows.map(Song.fromMap).toList();
  }

  /// 查询喜欢的专辑（通过歌曲聚合）
  Future<List<Album>> queryLikedAlbums() async {
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT album AS title,
             COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
             MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             MAX(cachedArtworkPath) AS coverArtworkPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE album IS NOT NULL AND album != '' AND isLiked = 1
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
    ''');
    return rows.map(Album.fromMap).toList();
  }

  /// 查询喜欢的艺术家
  Future<List<Artist>> queryLikedArtists() async {
    final db = await database;
    final rows = await db.rawQuery('''
      SELECT artist AS name,
             COUNT(*) AS songCount,
             COUNT(DISTINCT album) AS albumCount,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             MAX(cachedArtworkPath) AS coverArtworkPath
      FROM songs
      WHERE artist IS NOT NULL AND artist != '' AND isLiked = 1
      GROUP BY artist
      ORDER BY artist COLLATE NOCASE ASC
    ''');
    return rows.map(Artist.fromMap).toList();
  }

  // ======================== 艺术家元数据 ========================

  /// 插入或更新艺术家元数据
  Future<void> upsertArtistMeta({
    required String name,
    String? artistId,
    String? coverArtId,
  }) async {
    final db = await database;
    await db.rawInsert(
      'INSERT OR REPLACE INTO artists_meta (name, artistId, coverArtId) VALUES (?, ?, ?)',
      [name, artistId, coverArtId],
    );
  }

  /// 批量插入艺术家元数据
  Future<void> upsertArtistMetaBatch(List<Map<String, String?>> artists) async {
    final db = await database;
    final batch = db.batch();
    for (final a in artists) {
      batch.rawInsert(
        'INSERT OR REPLACE INTO artists_meta (name, artistId, coverArtId) VALUES (?, ?, ?)',
        [a['name'], a['artistId'], a['coverArtId']],
      );
    }
    await batch.commit(noResult: true);
  }

  /// 更新艺术家封面缓存路径
  Future<void> updateArtistArtworkCache(String artistName, String artworkPath) async {
    final db = await database;
    await db.update(
      'artists_meta',
      {'cachedArtworkPath': artworkPath},
      where: 'name = ?',
      whereArgs: [artistName],
    );
  }

  /// 切换艺术家喜欢状态
  Future<void> toggleLikeArtist(String artistName) async {
    final db = await database;
    await db.rawUpdate(
      'UPDATE artists_meta SET isLiked = CASE WHEN isLiked = 1 THEN 0 ELSE 1 END WHERE name = ?',
      [artistName],
    );
  }

  /// 查询艺术家元数据
  Future<Map<String, dynamic>?> queryArtistMeta(String name) async {
    final db = await database;
    final rows = await db.query('artists_meta',
        where: 'name = ?', whereArgs: [name], limit: 1);
    return rows.isEmpty ? null : rows.first;
  }

  /// 查询所有艺术家元数据
  Future<List<Map<String, dynamic>>> queryAllArtistMeta() async {
    final db = await database;
    return db.query('artists_meta', orderBy: 'name COLLATE NOCASE ASC');
  }
}
