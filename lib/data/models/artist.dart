/// 艺术家聚合实体：从 songs 表 GROUP BY artist 查询构造，不单独入库
class Artist {
  final String name;
  final int songCount;
  final int albumCount;

  /// 用于取封面的歌曲路径（任一歌曲）
  final String? coverSongPath;

  const Artist({
    required this.name,
    this.songCount = 0,
    this.albumCount = 0,
    this.coverSongPath,
  });
}
