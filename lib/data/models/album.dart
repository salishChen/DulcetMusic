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

  /// 专辑内歌曲数
  final int songCount;

  const Album({
    required this.title,
    this.artist,
    this.coverSongId,
    this.coverSongPath,
    this.songCount = 0,
  });

  String get displayArtist =>
      (artist == null || artist!.trim().isEmpty) ? '未知艺术家' : artist!;
}
