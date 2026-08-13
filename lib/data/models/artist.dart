/// 艺术家聚合实体：从 songs 表 GROUP BY artist 查询构造，不单独入库
class Artist {
  final String name;
  final int songCount;
  final int albumCount;

  /// 用于取封面的歌曲路径（任一歌曲）
  final String? coverSongPath;

  /// 缓存的封面路径
  final String? coverArtworkPath;

  /// 远程歌曲的 coverArtId（用于按需从 Subsonic 缓存封面）
  final String? coverArtId;

  const Artist({
    required this.name,
    this.songCount = 0,
    this.albumCount = 0,
    this.coverSongPath,
    this.coverArtworkPath,
    this.coverArtId,
  });

  factory Artist.fromMap(Map<String, dynamic> m) => Artist(
        name: (m['name'] as String?) ?? '未知艺术家',
        songCount: (m['songCount'] as int?) ?? 0,
        albumCount: (m['albumCount'] as int?) ?? 0,
        coverSongPath: m['coverSongPath'] as String?,
        coverArtworkPath: m['coverArtworkPath'] as String?,
        coverArtId: m['coverArtId'] as String?,
      );
}
