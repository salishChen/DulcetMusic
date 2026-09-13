import 'package:flutter/material.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_song_list_item.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/music_search_delegate.dart';
import 'package:flute_example/pages/now_playing.dart';

/// 排序方式
enum SongSortType {
  title,
  artist,
  album,
  dateAdded,
  playCount,
  duration,
}

/// 排序方向
enum SortDirection {
  ascending,
  descending,
}

/// 歌曲一级页面（首页）：列出数据库中的所有音乐
class SongsPage extends StatefulWidget {
  const SongsPage({Key? key}) : super(key: key);

  @override
  State<SongsPage> createState() => _SongsPageState();
}

class _SongsPageState extends State<SongsPage> {
  // 多选模式状态
  bool _selectionMode = false;
  final Set<String> _selectedPaths = {};
  
  // 排序状态
  SongSortType _sortType = SongSortType.title;
  SortDirection _sortDirection = SortDirection.ascending;

  @override
  Widget build(BuildContext context) {
    final rootIW = MPInheritedWidget.of(context);
    final songData = rootIW.songData;

    return Scaffold(
      appBar: _selectionMode ? _buildSelectionAppBar(context) : _buildNormalAppBar(context, songData),
      body: rootIW.isLoading
          ? const Center(child: CircularProgressIndicator())
          : songData == null
              ? Container()
              : ValueListenableBuilder<List<Song>>(
                  valueListenable: songData.notifier,
                  builder: (context, songs, _) {
                    if (songs.isEmpty) {
                      return _emptyView(context);
                    }
                    // 应用排序
                    final sortedSongs = _sortSongs(songs);
                    return _buildSongList(sortedSongs);
                  },
                ),
    );
  }

  /// 普通模式的 AppBar
  PreferredSizeWidget _buildNormalAppBar(BuildContext context, dynamic songData) {
    return AppBar(
      leading: IconButton(
        icon: const Icon(Icons.toc),
        tooltip: '打开侧边栏',
        onPressed: () => MPNavScaffold.of(context)?.toggleSidebar(),
      ),
      title: const Text('歌曲'),
      centerTitle: false,
      titleSpacing: 0,
      actions: [
        // 搜索按钮
        IconButton(
          icon: const Icon(Icons.search),
          tooltip: '搜索',
          onPressed: () {
            showSearch(
              context: context,
              delegate: MusicSearchDelegate(),
            );
          },
        ),
        // 排序按钮
        PopupMenuButton<SongSortType>(
          icon: const Icon(Icons.sort),
          tooltip: '排序',
          onSelected: (type) {
            setState(() {
              if (_sortType == type) {
                // 切换排序方向
                _sortDirection = _sortDirection == SortDirection.ascending
                    ? SortDirection.descending
                    : SortDirection.ascending;
              } else {
                _sortType = type;
                _sortDirection = SortDirection.ascending;
              }
            });
          },
          itemBuilder: (context) => [
            _buildSortMenuItem(SongSortType.title, '按标题', Icons.text_fields),
            _buildSortMenuItem(SongSortType.artist, '按艺术家', Icons.person),
            _buildSortMenuItem(SongSortType.album, '按专辑', Icons.album),
            _buildSortMenuItem(SongSortType.dateAdded, '按添加时间', Icons.access_time),
            _buildSortMenuItem(SongSortType.playCount, '按播放次数', Icons.play_circle),
            _buildSortMenuItem(SongSortType.duration, '按时长', Icons.timer),
          ],
        ),
        // 多选模式按钮
        IconButton(
          icon: const Icon(Icons.checklist),
          tooltip: '多选',
          onPressed: () {
            setState(() {
              _selectionMode = true;
              _selectedPaths.clear();
            });
          },
        ),
      ],
    );
  }

  /// 多选模式的 AppBar
  PreferredSizeWidget _buildSelectionAppBar(BuildContext context) {
    return AppBar(
      leading: IconButton(
        icon: const Icon(Icons.close),
        onPressed: () {
          setState(() {
            _selectionMode = false;
            _selectedPaths.clear();
          });
        },
      ),
      title: Text('已选择 ${_selectedPaths.length} 首'),
      actions: [
        // 全选/取消全选
        IconButton(
          icon: const Icon(Icons.select_all),
          tooltip: '全选',
          onPressed: () {
            final songData = MPInheritedWidget.of(context).songData;
            if (songData == null) return;
            final songs = songData.songs;
            setState(() {
              if (_selectedPaths.length == songs.length) {
                _selectedPaths.clear();
              } else {
                _selectedPaths.clear();
                _selectedPaths.addAll(songs.map((s) => s.path));
              }
            });
          },
        ),
        // 添加到播放队列
        IconButton(
          icon: const Icon(Icons.playlist_add),
          tooltip: '添加到播放队列',
          onPressed: () => _addToQueue(context),
        ),
        // 删除选中
        IconButton(
          icon: const Icon(Icons.delete),
          tooltip: '删除选中',
          onPressed: () => _deleteSelected(context),
        ),
      ],
    );
  }

  PopupMenuItem<SongSortType> _buildSortMenuItem(
      SongSortType type, String label, IconData icon) {
    final isSelected = _sortType == type;
    return PopupMenuItem(
      value: type,
      child: Row(
        children: [
          Icon(icon, size: 20, color: isSelected ? Theme.of(context).colorScheme.primary : null),
          const SizedBox(width: 12),
          Text(label),
          if (isSelected) ...[
            const Spacer(),
            Icon(
              _sortDirection == SortDirection.ascending
                  ? Icons.arrow_upward
                  : Icons.arrow_downward,
              size: 16,
              color: Theme.of(context).colorScheme.primary,
            ),
          ],
        ],
      ),
    );
  }

  /// 对歌曲列表进行排序
  List<Song> _sortSongs(List<Song> songs) {
    final sorted = List<Song>.from(songs);
    
    int Function(Song, Song) comparator;
    switch (_sortType) {
      case SongSortType.title:
        comparator = (a, b) => a.title.toLowerCase().compareTo(b.title.toLowerCase());
        break;
      case SongSortType.artist:
        comparator = (a, b) => (a.artist ?? '').toLowerCase().compareTo((b.artist ?? '').toLowerCase());
        break;
      case SongSortType.album:
        comparator = (a, b) => (a.album ?? '').toLowerCase().compareTo((b.album ?? '').toLowerCase());
        break;
      case SongSortType.dateAdded:
        comparator = (a, b) => (a.dateAdded ?? 0).compareTo(b.dateAdded ?? 0);
        break;
      case SongSortType.playCount:
        comparator = (a, b) => a.playCount.compareTo(b.playCount);
        break;
      case SongSortType.duration:
        comparator = (a, b) => (a.duration ?? 0).compareTo(b.duration ?? 0);
        break;
    }

    sorted.sort(comparator);
    
    if (_sortDirection == SortDirection.descending) {
      return sorted.reversed.toList();
    }
    return sorted;
  }

  /// 构建歌曲列表
  Widget _buildSongList(List<Song> songs) {
    return Scrollbar(
      child: ListView.builder(
        itemCount: songs.length,
        itemBuilder: (context, index) {
          final song = songs[index];
          if (_selectionMode) {
            return _buildSelectableSongItem(song, songs);
          }
          return MpSongListItem(
            song: song,
            queue: songs,
          );
        },
      ),
    );
  }

  /// 多选模式下的歌曲列表项
  Widget _buildSelectableSongItem(Song song, List<Song> queue) {
    final theme = Theme.of(context);
    final isSelected = _selectedPaths.contains(song.path);

    return ListTile(
      leading: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Checkbox(
            value: isSelected,
            onChanged: (value) {
              setState(() {
                if (value == true) {
                  _selectedPaths.add(song.path);
                } else {
                  _selectedPaths.remove(song.path);
                }
              });
            },
          ),
          MpArtwork(
            song.path,
            cachedArtworkPath: song.cachedArtworkPath,
            songId: song.id,
            coverArtId: song.coverArtId,
            width: 40,
            height: 40,
            borderRadius: BorderRadius.circular(8),
          ),
        ],
      ),
      title: Text(song.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        '${song.artist ?? "未知艺术家"} · ${song.album ?? "未知专辑"}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
      ),
      onTap: () {
        setState(() {
          if (isSelected) {
            _selectedPaths.remove(song.path);
          } else {
            _selectedPaths.add(song.path);
          }
        });
      },
    );
  }

  /// 添加选中歌曲到播放队列
  void _addToQueue(BuildContext context) {
    final songData = MPInheritedWidget.of(context).songData;
    final playlistData = MPInheritedWidget.of(context).playlistData;
    if (songData == null || playlistData == null) return;

    final songs = songData.songs;
    final selectedSongs = songs.where((s) => _selectedPaths.contains(s.path)).toList();
    
    int addedCount = 0;
    for (final song in selectedSongs) {
      if (playlistData.addSong(song)) {
        addedCount++;
      }
    }

    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('已添加 $addedCount 首歌曲到播放队列'),
      duration: const Duration(seconds: 2),
    ));

    setState(() {
      _selectionMode = false;
      _selectedPaths.clear();
    });
  }

  /// 删除选中歌曲
  Future<void> _deleteSelected(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('确认删除'),
        content: Text('确定要删除选中的 ${_selectedPaths.length} 首歌曲吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );

    // 弹窗期间用户可能已离开本页：inherited 查找/后续 setState 前先校验
    if (confirmed != true || !mounted) return;

    final dbHelper = DatabaseHelper.instance;
    final rootIW = MPInheritedWidget.of(context);
    final songData = rootIW.songData;
    if (songData == null) return;

    final songs = songData.songs;
    final selectedSongs =
        songs.where((s) => _selectedPaths.contains(s.path)).toList();

    for (final song in selectedSongs) {
      if (song.id == null) continue;
      // 先清理远程缓存文件与缓存记录（需要 songs 行仍存在才能查到路径），
      // 否则删除歌曲记录后会留下孤儿缓存文件。
      if (song.isRemote && song.isCached) {
        await CacheService.instance.deleteCache([song.id!]);
      }
      await dbHelper.deleteSong(song.id!);
    }

    if (!mounted) return;

    // 刷新歌曲列表
    final refreshed = await dbHelper.queryAllSongs();
    songData.updateSongs(refreshed);

    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('已删除 ${selectedSongs.length} 首歌曲'),
      duration: const Duration(seconds: 2),
    ));

    setState(() {
      _selectionMode = false;
      _selectedPaths.clear();
    });
  }

  /// 空状态：引导用户去扫描音乐
  Widget _emptyView(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 96.0,
            height: 96.0,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(colors: [
                const Color(0xFF7C4DFF).withOpacity(0.3),
                const Color(0xFF18D2C7).withOpacity(0.3),
              ]),
            ),
            child:
                const Icon(Icons.library_music, size: 44.0, color: Colors.white54),
          ),
          const SizedBox(height: 16.0),
          const Text('曲库为空', style: TextStyle(fontSize: 18.0)),
          const SizedBox(height: 8.0),
          const Text('去「扫描音乐」页面扫描媒体库或文件夹',
              style: TextStyle(color: Color(0xFF8A8A99))),
          const SizedBox(height: 16.0),
          FilledButton.icon(
            icon: const Icon(Icons.manage_search),
            label: const Text('去扫描'),
            onPressed: () => MPNavScaffold.of(context)?.selectPage(5),
          ),
        ],
      ),
    );
  }
}
