import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/album.dart';
import 'package:flute_example/data/song_data.dart';
import 'package:flute_example/pages/album_detail_page.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 专辑一级页面：分列出所有专辑（网格布局）
class AlbumsPage extends StatefulWidget {
  const AlbumsPage({Key? key}) : super(key: key);

  @override
  State<AlbumsPage> createState() => _AlbumsPageState();
}

class _AlbumsPageState extends State<AlbumsPage> {
  List<Album> _albums = [];
  bool _loading = true;
  bool _loaded = false;
  SongData? _songData;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 监听曲库变化，扫描完成后自动刷新
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
    final albums = await DatabaseHelper.instance.queryAlbums();
    if (!mounted) return;
    setState(() {
      _albums = albums;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '专辑'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _albums.isEmpty
              ? Center(
                  child: Text('暂无专辑',
                      style: theme.textTheme.bodySmall))
              : GridView.builder(
                  padding: const EdgeInsets.all(12.0),
                  gridDelegate:
                      const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 2,
                    mainAxisSpacing: 12.0,
                    crossAxisSpacing: 12.0,
                    childAspectRatio: 0.78,
                  ),
                  itemCount: _albums.length,
                  itemBuilder: (context, index) {
                    final album = _albums[index];
                    return _AlbumCard(album: album, onChanged: _load);
                  },
                ),
    );
  }
}

class _AlbumCard extends StatelessWidget {
  final Album album;
  final VoidCallback onChanged;
  const _AlbumCard({required this.album, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return InkWell(
      borderRadius: BorderRadius.circular(16.0),
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => AlbumDetailPage(albumTitle: album.title),
          ),
        ).then((_) => onChanged());
      },
      child: Container(
        decoration: BoxDecoration(
          color: theme.cardColor,
          borderRadius: BorderRadius.circular(16.0),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: SizedBox(
                width: double.infinity,
                child: MpArtwork(
                  album.coverSongPath,
                  fit: BoxFit.cover,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(10.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(album.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 14.0, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2.0),
                  Text('${album.displayArtist} · ${album.songCount} 首',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                          fontSize: 12.0, color: Color(0xFF8A8A99))),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 供其他页面复用的专辑横向卡片（艺术家详情页用）
class AlbumHorizontalCard extends StatelessWidget {
  final Album album;
  const AlbumHorizontalCard({Key? key, required this.album}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 120.0,
      child: InkWell(
        borderRadius: BorderRadius.circular(12.0),
        onTap: () {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (_) => AlbumDetailPage(albumTitle: album.title),
            ),
          );
        },
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            MpArtwork(
              album.coverSongPath,
              width: 120.0,
              height: 120.0,
              borderRadius: BorderRadius.circular(12.0),
            ),
            const SizedBox(height: 6.0),
            Text(album.title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13.0)),
            Text('${album.songCount} 首',
                style:
                    const TextStyle(fontSize: 11.0, color: Color(0xFF8A8A99))),
          ],
        ),
      ),
    );
  }
}
