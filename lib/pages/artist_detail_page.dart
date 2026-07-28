import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/album.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/pages/albums_page.dart';
import 'package:flute_example/widgets/mp_song_list_item.dart';

/// 艺术家详情页（二级页面）：显示本艺术家的所有单曲和专辑
class ArtistDetailPage extends StatefulWidget {
  final String artistName;
  const ArtistDetailPage({Key? key, required this.artistName})
      : super(key: key);

  @override
  State<ArtistDetailPage> createState() => _ArtistDetailPageState();
}

/// 艺术家详情页缓存条目
class _ArtistCache {
  final List<Song> songs;
  final List<Album> albums;
  _ArtistCache(this.songs, this.albums);
}

class _ArtistDetailPageState extends State<ArtistDetailPage> {
  List<Song> _songs = [];
  List<Album> _albums = [];
  bool _loading = true;

  /// 跨实例缓存：同一艺术家再次进入时直接使用，不再查询数据库
  static final Map<String, _ArtistCache> _cache = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final cached = _cache[widget.artistName];
    if (cached != null) {
      if (mounted) {
        setState(() {
          _songs = cached.songs;
          _albums = cached.albums;
          _loading = false;
        });
      }
      return;
    }
    _query();
  }

  Future<void> _query() async {
    final db = DatabaseHelper.instance;
    final songs = await db.querySongsByArtist(widget.artistName);
    final albums = await db.queryAlbumsByArtist(widget.artistName);
    _cache[widget.artistName] = _ArtistCache(songs, albums);
    if (!mounted) return;
    setState(() {
      _songs = songs;
      _albums = albums;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.artistName), centerTitle: true),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : (_songs.isEmpty && _albums.isEmpty)
              ? const Center(
                  child: Text('该艺术家暂无歌曲',
                      style: TextStyle(color: Color(0xFF8A8A99))))
              : ListView(
                  children: [
                    // 专辑区（横向滚动）
                    if (_albums.isNotEmpty) ...[
                      const Padding(
                        padding: EdgeInsets.fromLTRB(16.0, 16.0, 16.0, 8.0),
                        child: Text('专辑',
                            style: TextStyle(
                                fontSize: 16.0,
                                fontWeight: FontWeight.w600)),
                      ),
                      SizedBox(
                        height: 172.0,
                        child: ListView.separated(
                          scrollDirection: Axis.horizontal,
                          padding:
                              const EdgeInsets.symmetric(horizontal: 16.0),
                          itemCount: _albums.length,
                          separatorBuilder: (_, __) =>
                              const SizedBox(width: 12.0),
                          itemBuilder: (context, index) =>
                              AlbumHorizontalCard(album: _albums[index]),
                        ),
                      ),
                    ],
                    // 单曲区
                    Padding(
                      padding:
                          const EdgeInsets.fromLTRB(16.0, 16.0, 16.0, 8.0),
                      child: Text('单曲（${_songs.length}）',
                          style: const TextStyle(
                              fontSize: 16.0, fontWeight: FontWeight.w600)),
                    ),
                    ..._songs.map((s) => MpSongListItem(
                          song: s,
                          queue: _songs,
                        )),
                    const SizedBox(height: 24.0),
                  ],
                ),
    );
  }
}
