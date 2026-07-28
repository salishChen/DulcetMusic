import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/playlist.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_song_list_item.dart';

/// 歌单详情页（二级页面）：显示本歌单内的所有歌
///
/// 点击歌曲后将本歌单作为播放列表，并开始播放选中歌曲
/// （由 [MpSongListItem] 的 queue 机制实现）。
/// more_vert 弹框中额外显示"从本歌单删除"。
class PlaylistDetailPage extends StatefulWidget {
  final Playlist playlist;
  const PlaylistDetailPage({Key? key, required this.playlist})
      : super(key: key);

  @override
  State<PlaylistDetailPage> createState() => _PlaylistDetailPageState();
}

class _PlaylistDetailPageState extends State<PlaylistDetailPage> {
  List<Song> _songs = [];
  bool _loading = true;

  /// 跨实例缓存：同一歌单再次进入时直接使用，不再查询数据库
  static final Map<int, List<Song>> _cache = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final id = widget.playlist.id;
    if (id == null) return;
    final cached = _cache[id];
    if (cached != null) {
      if (mounted) {
        setState(() {
          _songs = cached;
          _loading = false;
        });
      }
      return;
    }
    _query(id);
  }

  Future<void> _query(int id) async {
    final songs = await DatabaseHelper.instance.querySongsInPlaylist(id);
    if (!mounted) return;
    _cache[id] = songs;
    setState(() {
      _songs = songs;
      _loading = false;
    });
  }

  /// 从歌单删除歌曲：同步更新缓存与本地列表，避免重新查询
  void _removeSong(Song song) {
    final id = widget.playlist.id;
    if (id != null && _cache.containsKey(id)) {
      _cache[id]!.removeWhere((s) => s.path == song.path);
    }
    if (mounted) {
      setState(() => _songs.removeWhere((s) => s.path == song.path));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.playlist.name),
        centerTitle: true,
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _songs.isEmpty
              ? const Center(
                  child: Text('歌单为空，去歌曲列表添加吧',
                      style: TextStyle(color: Color(0xFF8A8A99))))
              : ListView.builder(
                  itemCount: _songs.length,
                  itemBuilder: (context, index) => MpSongListItem(
                    song: _songs[index],
                    // 点击歌曲：将本歌单作为播放列表并播放选中歌曲
                    queue: _songs,
                    playlistId: widget.playlist.id,
                    onRemovedFromPlaylist: () => _removeSong(_songs[index]),
                  ),
                ),
    );
  }
}
