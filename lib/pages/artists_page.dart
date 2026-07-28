import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/artist.dart';
import 'package:flute_example/data/song_data.dart';
import 'package:flute_example/pages/artist_detail_page.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 艺术家一级页面：列出所有艺术家
class ArtistsPage extends StatefulWidget {
  const ArtistsPage({Key? key}) : super(key: key);

  @override
  State<ArtistsPage> createState() => _ArtistsPageState();
}

class _ArtistsPageState extends State<ArtistsPage> {
  List<Artist> _artists = [];
  bool _loading = true;
  bool _loaded = false;
  SongData? _songData;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _songData = MPInheritedWidget.of(context).songData;
    _songData?.notifier.removeListener(_load);
    _songData?.notifier.addListener(_load);
    // 仅首次加载：进入页面用缓存数据，避免重复查询与刷新
    if (!_loaded) {
      _loaded = true;
      _load();
    }
  }

  @override
  void dispose() {
    _songData?.notifier.removeListener(_load);
    super.dispose();
  }

  Future<void> _load() async {
    final artists = await DatabaseHelper.instance.queryArtists();
    if (!mounted) return;
    setState(() {
      _artists = artists;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '艺术家'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _artists.isEmpty
              ? const Center(
                  child: Text('暂无艺术家',
                      style: TextStyle(color: Color(0xFF8A8A99))))
              : ListView.builder(
                  itemCount: _artists.length,
                  itemBuilder: (context, index) {
                    final artist = _artists[index];
                    return ListTile(
                      leading: ClipOval(
                        child: MpArtwork(
                          artist.coverSongPath,
                          width: 48.0,
                          height: 48.0,
                          borderRadius: BorderRadius.circular(24.0),
                        ),
                      ),
                      title: Text(artist.name,
                          maxLines: 1, overflow: TextOverflow.ellipsis),
                      subtitle: Text(
                        '${artist.songCount} 首歌曲 · ${artist.albumCount} 张专辑',
                        style: const TextStyle(
                            fontSize: 12.0, color: Color(0xFF8A8A99)),
                      ),
                      trailing: const Icon(Icons.chevron_right,
                          color: Color(0xFF8A8A99)),
                      onTap: () {
                        Navigator.push(
                          context,
                          MaterialPageRoute(
                            builder: (_) =>
                                ArtistDetailPage(artistName: artist.name),
                          ),
                        );
                      },
                    );
                  },
                ),
    );
  }
}
