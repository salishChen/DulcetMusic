/// 歌单实体：映射 playlists 表
class Playlist {
  final int? id;
  final String name;

  /// 歌单内歌曲数量（列表页展示用，查询时 JOIN 统计）
  final int songCount;

  const Playlist({this.id, required this.name, this.songCount = 0});

  factory Playlist.fromMap(Map<String, dynamic> m) => Playlist(
        id: m['id'] as int?,
        name: m['name'] as String,
        songCount: (m['songCount'] as int?) ?? 0,
      );

  Map<String, dynamic> toMap() => {
        if (id != null) 'id': id,
        'name': name,
      };
}

/// 歌单-歌曲绑定记录：映射 playlist_songs 子表
class PlaylistSong {
  final int playlistId;
  final int songId;

  /// 歌单内排序位置
  final int position;

  const PlaylistSong({
    required this.playlistId,
    required this.songId,
    required this.position,
  });

  factory PlaylistSong.fromMap(Map<String, dynamic> m) => PlaylistSong(
        playlistId: m['playlistId'] as int,
        songId: m['songId'] as int,
        position: m['position'] as int,
      );

  Map<String, dynamic> toMap() => {
        'playlistId': playlistId,
        'songId': songId,
        'position': position,
      };
}
