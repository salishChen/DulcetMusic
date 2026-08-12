import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/models/album.dart';
import 'package:flute_example/data/models/artist.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/pages/album_detail_page.dart';
import 'package:flute_example/pages/artist_detail_page.dart';
import 'package:flute_example/pages/now_playing.dart';

/// 音乐搜索代理：支持搜索歌曲、专辑、艺术家
class MusicSearchDelegate extends SearchDelegate<String> {
  final dbHelper = DatabaseHelper.instance;

  MusicSearchDelegate() : super(searchFieldLabel: '搜索歌曲、歌手、专辑...');

  @override
  ThemeData appBarTheme(BuildContext context) {
    final theme = Theme.of(context);
    return theme.copyWith(
      inputDecorationTheme: InputDecorationTheme(
        hintStyle: TextStyle(color: theme.textTheme.bodySmall?.color),
      ),
    );
  }

  @override
  List<Widget>? buildActions(BuildContext context) {
    return [
      if (query.isNotEmpty)
        IconButton(
          icon: const Icon(Icons.clear),
          onPressed: () => query = '',
        ),
    ];
  }

  @override
  Widget? buildLeading(BuildContext context) {
    return IconButton(
      icon: const Icon(Icons.arrow_back),
      onPressed: () => close(context, ''),
    );
  }

  @override
  Widget buildResults(BuildContext context) {
    return _buildSearchResults(context);
  }

  @override
  Widget buildSuggestions(BuildContext context) {
    if (query.isEmpty) {
      return _buildRecentSearches(context);
    }
    return _buildSearchResults(context);
  }

  Widget _buildRecentSearches(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.search, size: 48, color: Colors.grey[400]),
          const SizedBox(height: 16),
          Text(
            '输入关键词搜索',
            style: TextStyle(color: Colors.grey[500], fontSize: 16),
          ),
          const SizedBox(height: 8),
          Text(
            '支持搜索歌曲名、歌手名、专辑名',
            style: TextStyle(color: Colors.grey[400], fontSize: 13),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchResults(BuildContext context) {
    if (query.length < 2) {
      return Center(
        child: Text(
          '请输入至少2个字符',
          style: TextStyle(color: Colors.grey[500]),
        ),
      );
    }

    return FutureBuilder<SearchResults>(
      future: _performSearch(query),
      builder: (context, snapshot) {
        if (snapshot.connectionState == ConnectionState.waiting) {
          return const Center(child: CircularProgressIndicator());
        }

        if (!snapshot.hasData || snapshot.data!.isEmpty) {
          return Center(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(Icons.search_off, size: 48, color: Colors.grey[400]),
                const SizedBox(height: 16),
                Text(
                  '未找到匹配结果',
                  style: TextStyle(color: Colors.grey[500], fontSize: 16),
                ),
              ],
            ),
          );
        }

        final results = snapshot.data!;
        final theme = Theme.of(context);

        return DefaultTabController(
          length: 3,
          child: Column(
            children: [
              Container(
                decoration: BoxDecoration(
                  border: Border(bottom: BorderSide(color: theme.dividerColor)),
                ),
                child: TabBar(
                  labelColor: theme.colorScheme.primary,
                  unselectedLabelColor: theme.textTheme.bodySmall?.color,
                  indicatorColor: theme.colorScheme.primary,
                  tabs: [
                    Tab(text: '歌曲 (${results.songs.length})'),
                    Tab(text: '专辑 (${results.albums.length})'),
                    Tab(text: '艺术家 (${results.artists.length})'),
                  ],
                ),
              ),
              Expanded(
                child: TabBarView(
                  children: [
                    _buildSongsList(context, results.songs),
                    _buildAlbumsList(context, results.albums),
                    _buildArtistsList(context, results.artists),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildSongsList(BuildContext context, List<Song> songs) {
    if (songs.isEmpty) {
      return Center(child: Text('未找到歌曲', style: TextStyle(color: Colors.grey[500])));
    }

    final theme = Theme.of(context);
    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: songs.length,
      itemBuilder: (context, index) {
        final song = songs[index];
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
          onTap: () {
            close(context, 'song:${song.path}');
            // 设置播放队列并播放
            final playlistData = MPInheritedWidget.of(context).playlistData;
            playlistData?.setSongs(songs);
            MPInheritedWidget.of(context).songData?.setCurrentIndex(index);
            openNowPlayingPage(song);
          },
        );
      },
    );
  }

  Widget _buildAlbumsList(BuildContext context, List<Album> albums) {
    if (albums.isEmpty) {
      return Center(child: Text('未找到专辑', style: TextStyle(color: Colors.grey[500])));
    }

    final theme = Theme.of(context);

    return GridView.builder(
      padding: const EdgeInsets.all(16),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 2,
        childAspectRatio: 0.85,
        crossAxisSpacing: 12,
        mainAxisSpacing: 12,
      ),
      itemCount: albums.length,
      itemBuilder: (context, index) {
        final album = albums[index];
        return GestureDetector(
          onTap: () {
            close(context, 'album:${album.title}');
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
    );
  }

  Widget _buildArtistsList(BuildContext context, List<Artist> artists) {
    if (artists.isEmpty) {
      return Center(child: Text('未找到艺术家', style: TextStyle(color: Colors.grey[500])));
    }

    final theme = Theme.of(context);

    return ListView.builder(
      padding: const EdgeInsets.symmetric(vertical: 8),
      itemCount: artists.length,
      itemBuilder: (context, index) {
        final artist = artists[index];
        return ListTile(
          leading: CircleAvatar(
            radius: 24,
            backgroundColor: theme.colorScheme.primary.withOpacity(0.15),
            child: artist.coverArtworkPath != null
                ? ClipOval(
                    child: Image.file(
                      File(artist.coverArtworkPath!),
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
            style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
          ),
          onTap: () {
            close(context, 'artist:${artist.name}');
            Navigator.push(
              context,
              MaterialPageRoute(
                builder: (_) => ArtistDetailPage(artistName: artist.name),
              ),
            );
          },
        );
      },
    );
  }

  Future<SearchResults> _performSearch(String query) async {
    final pattern = '%${query.toLowerCase()}%';
    
    // 并行搜索歌曲、专辑、艺术家
    final results = await Future.wait([
      _searchSongs(pattern),
      _searchAlbums(pattern),
      _searchArtists(pattern),
    ]);

    return SearchResults(
      songs: results[0] as List<Song>,
      albums: results[1] as List<Album>,
      artists: results[2] as List<Artist>,
    );
  }

  Future<List<Song>> _searchSongs(String pattern) async {
    final db = await dbHelper.database;
    final rows = await db.query(
      'songs',
      where: 'LOWER(title) LIKE ? OR LOWER(artist) LIKE ? OR LOWER(album) LIKE ?',
      whereArgs: [pattern, pattern, pattern],
      orderBy: 'title COLLATE NOCASE ASC',
      limit: 100,
    );
    return rows.map(Song.fromMap).toList();
  }

  Future<List<Album>> _searchAlbums(String pattern) async {
    final db = await dbHelper.database;
    final rows = await db.rawQuery('''
      SELECT album AS title,
             COALESCE(MAX(albumArtist), MAX(artist)) AS artist,
             MAX(CASE WHEN hasArtwork = 1 THEN id END) AS coverSongId,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             MAX(cachedArtworkPath) AS coverArtworkPath,
             COUNT(*) AS songCount
      FROM songs
      WHERE album IS NOT NULL AND album != '' AND LOWER(album) LIKE ?
      GROUP BY album
      ORDER BY album COLLATE NOCASE ASC
      LIMIT 50
    ''', [pattern]);
    return rows.map(Album.fromMap).toList();
  }

  Future<List<Artist>> _searchArtists(String pattern) async {
    final db = await dbHelper.database;
    final rows = await db.rawQuery('''
      SELECT artist AS name,
             COUNT(*) AS songCount,
             COUNT(DISTINCT album) AS albumCount,
             MAX(CASE WHEN hasArtwork = 1 THEN path END) AS coverSongPath,
             MAX(cachedArtworkPath) AS coverArtworkPath
      FROM songs
      WHERE artist IS NOT NULL AND artist != '' AND LOWER(artist) LIKE ?
      GROUP BY artist
      ORDER BY artist COLLATE NOCASE ASC
      LIMIT 50
    ''', [pattern]);
    return rows.map(Artist.fromMap).toList();
  }
}

/// 搜索结果容器
class SearchResults {
  final List<Song> songs;
  final List<Album> albums;
  final List<Artist> artists;

  SearchResults({
    required this.songs,
    required this.albums,
    required this.artists,
  });

  bool get isEmpty => songs.isEmpty && albums.isEmpty && artists.isEmpty;
}
