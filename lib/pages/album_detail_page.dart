import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/mp_song_list_item.dart';

/// 专辑详情页（二级页面）：显示本专辑所有的歌
class AlbumDetailPage extends StatefulWidget {
  final String albumTitle;
  const AlbumDetailPage({Key? key, required this.albumTitle}) : super(key: key);

  @override
  State<AlbumDetailPage> createState() => _AlbumDetailPageState();
}

class _AlbumDetailPageState extends State<AlbumDetailPage> {
  List<Song> _songs = [];
  bool _loading = true;

  /// 跨实例缓存：同一专辑再次进入时直接使用，不再查询数据库
  static final Map<String, List<Song>> _cache = {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  void _load() {
    final cached = _cache[widget.albumTitle];
    if (cached != null) {
      if (mounted) {
        setState(() {
          _songs = cached;
          _loading = false;
        });
      }
      return;
    }
    _query();
  }

  Future<void> _query() async {
    final songs =
        await DatabaseHelper.instance.querySongsByAlbum(widget.albumTitle);
    _cache[widget.albumTitle] = songs;
    if (!mounted) return;
    setState(() {
      _songs = songs;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final coverPath = _songs.isEmpty ? null : _songs.first.path;
    final artist = _songs.isEmpty ? '' : _songs.first.displayArtist;

    return Scaffold(
      appBar: AppBar(title: Text(widget.albumTitle), centerTitle: true),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // 顶部专辑头图
                Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Row(
                    children: [
                      MpArtwork(
                        coverPath,
                        width: 110.0,
                        height: 110.0,
                        borderRadius: BorderRadius.circular(16.0),
                      ),
                      const SizedBox(width: 16.0),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(widget.albumTitle,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                    fontSize: 18.0,
                                    fontWeight: FontWeight.w700)),
                            const SizedBox(height: 6.0),
                            Text(artist,
                                style: const TextStyle(
                                    color: Color(0xFFB3B3C2))),
                            const SizedBox(height: 4.0),
                            Text('${_songs.length} 首歌曲',
                                style: const TextStyle(
                                    fontSize: 12.0,
                                    color: Color(0xFF8A8A99))),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
                const Divider(height: 1.0),
                Expanded(
                  child: _songs.isEmpty
                      ? const Center(
                          child: Text('本专辑暂无歌曲',
                              style: TextStyle(color: Color(0xFF8A8A99))))
                      : ListView.builder(
                          itemCount: _songs.length,
                          itemBuilder: (context, index) => MpSongListItem(
                            song: _songs[index],
                            queue: _songs,
                          ),
                        ),
                ),
              ],
            ),
    );
  }
}
