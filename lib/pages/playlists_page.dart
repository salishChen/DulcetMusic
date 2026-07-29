import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/playlist.dart';
import 'package:flute_example/data/subsonic_service.dart';
import 'package:flute_example/pages/playlist_detail_page.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_song_bottom_sheet.dart';

/// 歌单一级页面：列出所有歌单，支持新建/删除
class PlaylistsPage extends StatefulWidget {
  const PlaylistsPage({Key? key}) : super(key: key);

  @override
  State<PlaylistsPage> createState() => _PlaylistsPageState();
}

class _PlaylistsPageState extends State<PlaylistsPage> {
  List<Playlist> _playlists = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final playlists = await DatabaseHelper.instance.queryPlaylists();
    if (!mounted) return;
    setState(() {
      _playlists = playlists;
      _loading = false;
    });
  }

  Future<void> _create() async {
    final name = await promptPlaylistName(context);
    if (name == null || name.trim().isEmpty) return;
    final id = await DatabaseHelper.instance.createPlaylist(name.trim());
    if (id == null && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
          content: Text('新建歌单失败（可能重名）'),
          duration: Duration(seconds: 1)));
    }
    _load();
  }

  Future<void> _delete(Playlist pl) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: Theme.of(context).dialogBackgroundColor,
        title: const Text('删除歌单'),
        content: Text('确定删除歌单「${pl.name}」吗？'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('取消')),
          TextButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('删除',
                  style: TextStyle(color: Color(0xFFFF5252)))),
        ],
      ),
    );
    if (confirmed == true && pl.id != null) {
      await DatabaseHelper.instance.deletePlaylist(pl.id!);
      _load();
    }
  }

  Future<void> _syncFromSubsonic() async {
    if (!SubsonicService.instance.isConfigured) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('请先在"远程配置"页面配置 Subsonic 服务器'),
            duration: Duration(seconds: 2),
          ),
        );
      }
      return;
    }

    // 显示加载对话框
    if (mounted) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (_) => const AlertDialog(
          content: Row(
            children: [
              CircularProgressIndicator(),
              SizedBox(width: 16),
              Text('正在同步歌单...'),
            ],
          ),
        ),
      );
    }

    try {
      final synced = await SubsonicService.instance.syncPlaylistsToLocalStorage();
      if (mounted) {
        Navigator.pop(context); // 关闭加载对话框
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('同步完成，共同步 $synced 个歌单'),
            duration: const Duration(seconds: 2),
          ),
        );
        _load(); // 刷新列表
      }
    } catch (e) {
      if (mounted) {
        Navigator.pop(context); // 关闭加载对话框
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('同步失败: $e'),
            duration: const Duration(seconds: 3),
          ),
        );
      }
    }
  }

  Future<void> _pushToRemote(Playlist pl) async {
    if (!SubsonicService.instance.isConfigured) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('请先在"远程配置"页面配置 Subsonic 服务器'),
            duration: Duration(seconds: 2),
          ),
        );
      }
      return;
    }

    // 获取歌单内的歌曲
    final songs = await DatabaseHelper.instance.querySongsInPlaylist(pl.id!);
    final remoteSongIds = songs
        .where((s) => s.remoteId != null)
        .map((s) => s.remoteId!)
        .toList();

    if (remoteSongIds.isEmpty) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('歌单内没有远程歌曲，无法推送到 Subsonic'),
            duration: Duration(seconds: 2),
          ),
        );
      }
      return;
    }

    // 显示加载对话框
    if (mounted) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (_) => const AlertDialog(
          content: Row(
            children: [
              CircularProgressIndicator(),
              SizedBox(width: 16),
              Text('正在推送到远程...'),
            ],
          ),
        ),
      );
    }

    try {
      final playlistId = await SubsonicService.instance
          .createPlaylist(pl.name, remoteSongIds);
      if (mounted) {
        Navigator.pop(context); // 关闭加载对话框
        if (playlistId != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('歌单「${pl.name}」已推送到 Subsonic'),
              duration: const Duration(seconds: 2),
            ),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('推送失败'),
              duration: Duration(seconds: 2),
            ),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        Navigator.pop(context); // 关闭加载对话框
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('推送失败: $e'),
            duration: const Duration(seconds: 3),
          ),
        );
      }
    }
  }

  void _showPlaylistActions(Playlist pl) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              margin: const EdgeInsets.only(top: 8),
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: Colors.grey.withOpacity(0.3),
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: Text(
                pl.name,
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.play_circle_outline),
              title: const Text('播放歌单'),
              onTap: () {
                Navigator.pop(context);
                Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (_) => PlaylistDetailPage(playlist: pl),
                  ),
                ).then((_) => _load());
              },
            ),
            if (SubsonicService.instance.isConfigured)
              ListTile(
                leading: const Icon(Icons.cloud_upload, color: Color(0xFF7C4DFF)),
                title: const Text('推送到远程'),
                subtitle: const Text('将歌单推送到 Subsonic 服务器'),
                onTap: () {
                  Navigator.pop(context);
                  _pushToRemote(pl);
                },
              ),
            ListTile(
              leading: const Icon(Icons.delete_outline, color: Color(0xFFFF5252)),
              title: const Text('删除歌单', style: TextStyle(color: Color(0xFFFF5252))),
              onTap: () {
                Navigator.pop(context);
                _delete(pl);
              },
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(
        context,
        '歌单',
        actions: [
          IconButton(
            icon: const Icon(Icons.cloud_sync),
            tooltip: '从 Subsonic 同步歌单',
            onPressed: _syncFromSubsonic,
          ),
          IconButton(
            icon: const Icon(Icons.add),
            tooltip: '新建歌单',
            onPressed: _create,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _playlists.isEmpty
              ? Center(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Text('暂无歌单',
                          style: TextStyle(color: Color(0xFF8A8A99))),
                      const SizedBox(height: 12.0),
                      FilledButton.icon(
                        icon: const Icon(Icons.add),
                        label: const Text('新建歌单'),
                        onPressed: _create,
                      ),
                    ],
                  ),
                )
              : ListView.builder(
                  itemCount: _playlists.length,
                  itemBuilder: (context, index) {
                    final pl = _playlists[index];
                    return GestureDetector(
                      onLongPress: () => _showPlaylistActions(pl),
                      child: ListTile(
                        leading: Container(
                          width: 48.0,
                          height: 48.0,
                          decoration: BoxDecoration(
                            borderRadius: BorderRadius.circular(12.0),
                            gradient: LinearGradient(colors: [
                              const Color(0xFF7C4DFF).withOpacity(0.6),
                              const Color(0xFF18D2C7).withOpacity(0.6),
                            ]),
                          ),
                          child: const Icon(Icons.queue_music,
                              color: Colors.white),
                        ),
                        title: Text(pl.name),
                        subtitle: Text('${pl.songCount} 首歌曲',
                            style: const TextStyle(
                                fontSize: 12.0, color: Color(0xFF8A8A99))),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline,
                              color: Color(0xFFFF5252)),
                          tooltip: '删除歌单',
                          onPressed: () => _delete(pl),
                        ),
                        onTap: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (_) => PlaylistDetailPage(playlist: pl),
                            ),
                          ).then((_) => _load());
                        },
                      ),
                    );
                  },
                ),
    );
  }
}
