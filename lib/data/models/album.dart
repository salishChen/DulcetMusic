/// 专辑聚合实体：从 songs 表 GROUP BY album 查询构造，不单独入库
class Album {
  /// 专辑名
  final String title;

  /// 专辑艺术家（优先 albumArtist，回退 artist）
  final String? artist;

  /// 用于取封面的歌曲 id（专辑内任一含封面歌曲）
  final int? coverSongId;

  /// 用于取封面的歌曲路径
  final String? coverSongPath;

  /// 缓存的封面路径
  final String? coverArtworkPath;

  /// 远程歌曲的 coverArtId（用于按需从 Subsonic 缓存封面）
  final String? coverArtId;

  /// 专辑内歌曲数
  final int songCount;

  const Album({
    required this.title,
    this.artist,
    this.coverSongId,
    this.coverSongPath,
    this.coverArtworkPath,
    this.coverArtId,
    this.songCount = 0,
  });

  factory Album.fromMap(Map<String, dynamic> m) => Album(
        title: (m['title'] as String?) ?? '未知专辑',
        artist: m['artist'] as String?,
        coverSongId: m['coverSongId'] as int?,
        coverSongPath: m['coverSongPath'] as String?,
        coverArtworkPath: m['coverArtworkPath'] as String?,
        coverArtId: m['coverArtId'] as String?,
        songCount: (m['songCount'] as int?) ?? 0,
      );

  String get displayArtist =>
      (artist == null || artist!.trim().isEmpty) ? '未知艺术家' : artist!;
}
