import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/models/album.dart';
import 'package:flute_example/data/models/artist.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/pages/album_detail_page.dart';
import 'package:flute_example/pages/artist_detail_page.dart';
import 'package:flute_example/pages/now_playing.dart';

/// 喜欢页面：三个 Tab — 喜欢的音乐、喜欢的专辑、喜欢的艺术家
class FavoritesPage extends StatefulWidget {
  const FavoritesPage({Key? key}) : super(key: key);

  @override
  State<FavoritesPage> createState() => _FavoritesPageState();
}

class _FavoritesPageState extends State<FavoritesPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  final dbHelper = DatabaseHelper.instance;

  List<Song> _likedSongs = [];
  List<Album> _likedAlbums = [];
  List<Artist> _likedArtists = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 3, vsync: this);
    _loadData();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final songs = await dbHelper.queryLikedSongs();
    final albums = await dbHelper.queryLikedAlbums();
    final artists = await dbHelper.queryLikedArtists();
    if (mounted) {
      setState(() {
        _likedSongs = songs;
        _likedAlbums = albums;
        _likedArtists = artists;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: buildPrimaryAppBar(context, '喜欢'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                Container(
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: theme.dividerColor),
                    ),
                  ),
                  child: TabBar(
                    controller: _tabController,
                    labelColor: theme.colorScheme.primary,
                    unselectedLabelColor: theme.textTheme.bodySmall?.color,
                    indicatorColor: theme.colorScheme.primary,
                    tabs: [
                      Tab(text: '音乐 (${_likedSongs.length})'),
                      Tab(text: '专辑 (${_likedAlbums.length})'),
                      Tab(text: '艺术家 (${_likedArtists.length})'),
                    ],
                  ),
                ),
                Expanded(
                  child: TabBarView(
                    controller: _tabController,
                    children: [
                      _buildLikedSongs(),
                      _buildLikedAlbums(),
                      _buildLikedArtists(),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  // ===================== 喜欢的音乐 =====================

  Widget _buildLikedSongs() {
    if (_likedSongs.isEmpty) {
      return _emptyView(Icons.favorite_border, '还没有喜欢的音乐');
    }

    final theme = Theme.of(context);
    return RefreshIndicator(
      onRefresh: _loadData,
      child: ListView.builder(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: _likedSongs.length,
        itemBuilder: (context, index) {
          final song = _likedSongs[index];
          return ListTile(
            leading: MpArtwork(
              song.path,
              cachedArtworkPath: song.cachedArtworkPath,
              songId: song.id,
              coverArtId: song.coverArtId,
              width: 50,
              height: 50,
              borderRadius: BorderRadius.circular(12),
            ),
            title: Text(song.title, maxLines: 1, overflow: TextOverflow.ellipsis),
            subtitle: Text(
              '${song.artist ?? "未知艺术家"} · ${song.album ?? "未知专辑"}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
            ),
            trailing: IconButton(
              icon: Icon(Icons.favorite, color: Colors.red[400], size: 20),
              onPressed: () async {
                await dbHelper.toggleLikeSong(song.id!);
                _loadData();
              },
            ),
            onTap: () {
              final playlistData = MPInheritedWidget.of(context).playlistData;
              playlistData?.setSongs(_likedSongs);
              final idx = _likedSongs.indexWhere((s) => s.path == song.path);
              MPInheritedWidget.of(context).songData?.setCurrentIndex(idx < 0 ? 0 : idx);
              openNowPlayingPage(song);
            },
          );
        },
      ),
    );
  }

  // ===================== 喜欢的专辑 =====================

  Widget _buildLikedAlbums() {
    if (_likedAlbums.isEmpty) {
      return _emptyView(Icons.album, '还没有喜欢的专辑');
    }

    final theme = Theme.of(context);

    return RefreshIndicator(
      onRefresh: _loadData,
      child: GridView.builder(
        padding: const EdgeInsets.all(16),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          childAspectRatio: 0.85,
          crossAxisSpacing: 12,
          mainAxisSpacing: 12,
        ),
        itemCount: _likedAlbums.length,
        itemBuilder: (context, index) {
          final album = _likedAlbums[index];
          return GestureDetector(
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => AlbumDetailPage(albumTitle: album.title),
                ),
              );
            },
            child: Column(
              children: [
                Expanded(
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(12),
                    child: MpArtwork(
                      album.coverSongPath ?? '',
                      cachedArtworkPath: album.coverArtworkPath,
                      width: double.infinity,
                      height: double.infinity,
                      fit: BoxFit.cover,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  album.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w500,
                    color: theme.textTheme.bodyLarge?.color,
                  ),
                ),
                Text(
                  '${album.artist ?? "未知艺术家"} · ${album.songCount}首',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 11,
                    color: theme.textTheme.bodySmall?.color,
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  // ===================== 喜欢的艺术家 =====================

  Widget _buildLikedArtists() {
    if (_likedArtists.isEmpty) {
      return _emptyView(Icons.people_outline, '还没有喜欢的艺术家');
    }

    final theme = Theme.of(context);

    return RefreshIndicator(
      onRefresh: _loadData,
      child: ListView.builder(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: _likedArtists.length,
        itemBuilder: (context, index) {
          final artist = _likedArtists[index];
          return ListTile(
            leading: CircleAvatar(
              radius: 24,
              backgroundColor: theme.colorScheme.primary.withOpacity(0.15),
              child: artist.coverArtworkPath != null
                  ? ClipOval(
                      child: Image.asset(
                        artist.coverArtworkPath!,
                        width: 48,
                        height: 48,
                        fit: BoxFit.cover,
                        errorBuilder: (_, __, ___) => Icon(
                          Icons.person,
                          color: theme.colorScheme.primary,
                        ),
                      ),
                    )
                  : Icon(
                      Icons.person,
                      color: theme.colorScheme.primary,
                    ),
            ),
            title: Text(
              artist.name,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w500),
            ),
            subtitle: Text(
              '${artist.songCount}首歌 · ${artist.albumCount}张专辑',
              style: TextStyle(
                fontSize: 12,
                color: theme.textTheme.bodySmall?.color,
              ),
            ),
            trailing: IconButton(
              icon: Icon(
                Icons.favorite,
                color: Colors.red[400],
                size: 20,
              ),
              onPressed: () async {
                await dbHelper.toggleLikeArtist(artist.name);
                _loadData();
              },
            ),
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => ArtistDetailPage(artistName: artist.name),
                ),
              );
            },
          );
        },
      ),
    );
  }

  Widget _emptyView(IconData icon, String text) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 48, color: Colors.grey[400]),
          const SizedBox(height: 12),
          Text(
            text,
            style: TextStyle(color: Colors.grey[500], fontSize: 16),
          ),
          const SizedBox(height: 8),
          Text(
            '在播放页或歌曲菜单中点击 ❤ 收藏',
            style: TextStyle(color: Colors.grey[400], fontSize: 13),
          ),
        ],
      ),
    );
  }
}
