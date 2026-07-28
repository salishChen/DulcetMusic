import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/playlist.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/pages/album_detail_page.dart';
import 'package:flute_example/pages/artist_detail_page.dart';
import 'package:flute_example/widgets/mp_artwork.dart';

/// 底部功能弹框：点击 more_vert 或长按歌曲时弹出
///
/// 弹框分两个卡片：
/// 1. 歌曲信息卡：封面、歌名、艺术家、专辑（内容行高 40px）
/// 2. 功能卡：添加到歌单、艺术家、专辑、歌曲信息；
///    若在歌单详情页中打开（[playlistId] 非空），额外显示"从本歌单删除"
///
/// 颜色跟随主题（浅色/深色自动适配）。
Future<void> showSongBottomSheet(
  BuildContext context,
  Song song, {
  int? playlistId,
  VoidCallback? onRemovedFromPlaylist,
}) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.transparent,
    isScrollControlled: false,
    builder: (sheetContext) => _SongSheetContent(
      song: song,
      parentContext: context,
      playlistId: playlistId,
      onRemovedFromPlaylist: onRemovedFromPlaylist,
    ),
  );
}

class _SongSheetContent extends StatelessWidget {
  final Song song;

  /// 弹框宿主页面的 context（弹框关闭后用于导航/再弹窗）
  final BuildContext parentContext;
  final int? playlistId;
  final VoidCallback? onRemovedFromPlaylist;

  const _SongSheetContent({
    required this.song,
    required this.parentContext,
    this.playlistId,
    this.onRemovedFromPlaylist,
  });

  /// 卡片 1：封面 + 歌名 + 艺术家 + 专辑（高度 40px）
  Widget _infoCard(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.cardColor,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16.0)),
      margin: const EdgeInsets.fromLTRB(12.0, 0, 12.0, 8.0),
      child: Padding(
        padding: const EdgeInsets.all(12.0),
        child: SizedBox(
          height: 40.0,
          child: Row(
            children: [
              MpArtwork(
                song.path,
                width: 40.0,
                height: 40.0,
                borderRadius: BorderRadius.circular(8.0),
              ),
              const SizedBox(width: 10.0),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      song.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 14.0,
                        fontWeight: FontWeight.w600,
                        color: theme.colorScheme.onSurface,
                      ),
                    ),
                    Text(
                      '${song.displayArtist} · ${song.displayAlbum}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _actionTile(BuildContext context, IconData icon, String label,
      VoidCallback onTap,
      {Color? color}) {
    final theme = Theme.of(context);
    final fg = color ?? theme.colorScheme.onSurface;
    return ListTile(
      dense: true,
      leading: Icon(icon, color: fg, size: 22.0),
      title: Text(label, style: TextStyle(color: fg, fontSize: 14.0)),
      onTap: onTap,
    );
  }

  /// 卡片 2：功能卡
  Widget _actionCard(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: theme.cardColor,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16.0)),
      margin: const EdgeInsets.fromLTRB(12.0, 0, 12.0, 12.0),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _actionTile(context, Icons.playlist_add, '添加到歌单', () {
            Navigator.pop(context);
            _showAddToPlaylistDialog(parentContext, song);
          }),
          _actionTile(context, Icons.person, '艺术家', () {
            Navigator.pop(context);
            Navigator.push(
              parentContext,
              MaterialPageRoute(
                builder: (_) => ArtistDetailPage(artistName: song.displayArtist),
              ),
            );
          }),
          _actionTile(context, Icons.album, '专辑', () {
            Navigator.pop(context);
            Navigator.push(
              parentContext,
              MaterialPageRoute(
                builder: (_) => AlbumDetailPage(albumTitle: song.displayAlbum),
              ),
            );
          }),
          _actionTile(context, Icons.info_outline, '歌曲信息', () {
            Navigator.pop(context);
            _showSongInfoDialog(parentContext, song);
          }),
          // 歌单详情页中额外显示"从本歌单删除"
          if (playlistId != null)
            _actionTile(context, Icons.delete_outline, '从本歌单删除', () async {
              final navigator = Navigator.of(context);
              if (song.id != null) {
                await DatabaseHelper.instance
                    .removeSongFromPlaylist(playlistId!, song.id!);
              }
              navigator.pop();
              onRemovedFromPlaylist?.call();
            }, color: const Color(0xFFFF5252)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 顶部拖拽指示条
          Container(
            width: 40.0,
            height: 4.0,
            margin: const EdgeInsets.only(bottom: 10.0),
            decoration: BoxDecoration(
              color: theme.dividerColor,
              borderRadius: BorderRadius.circular(2.0),
            ),
          ),
          _infoCard(context),
          _actionCard(context),
        ],
      ),
    );
  }
}

/// "添加到歌单"对话框：列出全部歌单，支持快捷新建
Future<void> _showAddToPlaylistDialog(BuildContext context, Song song) async {
  final theme = Theme.of(context);
  final db = DatabaseHelper.instance;
  final playlists = await db.queryPlaylists();
  if (!context.mounted) return;

  showModalBottomSheet(
    context: context,
    backgroundColor: theme.cardColor,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20.0)),
    ),
    builder: (sheetContext) {
      return SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Text('添加到歌单',
                  style: TextStyle(
                      fontSize: 16.0,
                      fontWeight: FontWeight.w600,
                      color: theme.colorScheme.onSurface)),
            ),
            // 新建歌单入口
            ListTile(
              leading: const Icon(Icons.add, color: Color(0xFF18D2C7)),
              title: const Text('新建歌单',
                  style: TextStyle(color: Color(0xFF18D2C7))),
              onTap: () async {
                Navigator.pop(sheetContext);
                final name = await _promptPlaylistName(context);
                if (name == null || name.trim().isEmpty) return;
                final id = await db.createPlaylist(name.trim());
                if (id != null && song.id != null) {
                  await db.addSongToPlaylist(id, song.id!);
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                        content: Text('已添加到歌单「${name.trim()}」'),
                        duration: const Duration(seconds: 1)));
                  }
                } else if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(const SnackBar(
                      content: Text('新建歌单失败（可能重名）'),
                      duration: Duration(seconds: 1)));
                }
              },
            ),
            if (playlists.isEmpty)
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Text('暂无歌单', style: theme.textTheme.bodySmall),
              )
            else
              Flexible(
                child: ListView.builder(
                  shrinkWrap: true,
                  itemCount: playlists.length,
                  itemBuilder: (context, index) {
                    final Playlist pl = playlists[index];
                    return ListTile(
                      leading: Icon(Icons.queue_music,
                          color: theme.colorScheme.onSurface.withOpacity(0.7)),
                      title: Text(pl.name,
                          style:
                              TextStyle(color: theme.colorScheme.onSurface)),
                      subtitle: Text('${pl.songCount} 首',
                          style: theme.textTheme.bodySmall),
                      onTap: () async {
                        Navigator.pop(sheetContext);
                        if (song.id == null || pl.id == null) return;
                        final added =
                            await db.addSongToPlaylist(pl.id!, song.id!);
                        if (context.mounted) {
                          ScaffoldMessenger.of(context)
                              .showSnackBar(SnackBar(
                            content: Text(added
                                ? '已添加到歌单「${pl.name}」'
                                : '歌曲已在歌单「${pl.name}」中'),
                            duration: const Duration(seconds: 1),
                          ));
                        }
                      },
                    );
                  },
                ),
              ),
            const SizedBox(height: 8.0),
          ],
        ),
      );
    },
  );
}

/// 输入歌单名对话框（新建歌单公用）
Future<String?> _promptPlaylistName(BuildContext context) {
  final controller = TextEditingController();
  final theme = Theme.of(context);
  return showDialog<String>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      backgroundColor: theme.dialogBackgroundColor,
      title: Text('新建歌单', style: TextStyle(color: theme.colorScheme.onSurface)),
      content: TextField(
        controller: controller,
        autofocus: true,
        style: TextStyle(color: theme.colorScheme.onSurface),
        decoration: InputDecoration(
          hintText: '请输入歌单名',
          hintStyle: theme.textTheme.bodySmall,
        ),
        onSubmitted: (v) => Navigator.pop(dialogContext, v),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('取消')),
        TextButton(
            onPressed: () => Navigator.pop(dialogContext, controller.text),
            child: const Text('确定')),
      ],
    ),
  );
}

/// 对外暴露：新建歌单名称输入（歌单页复用）
Future<String?> promptPlaylistName(BuildContext context) =>
    _promptPlaylistName(context);

/// 歌曲信息对话框：展示数据库中的全部元数据
void _showSongInfoDialog(BuildContext context, Song song) {
  final theme = Theme.of(context);
  String fmtTime(int? ms) {
    if (ms == null || ms <= 0) return '未知';
    final dt = DateTime.fromMillisecondsSinceEpoch(ms);
    return '${dt.year}-${dt.month.toString().padLeft(2, '0')}-${dt.day.toString().padLeft(2, '0')} '
        '${dt.hour.toString().padLeft(2, '0')}:${dt.minute.toString().padLeft(2, '0')}';
  }

  String fmtSize(int? bytes) {
    if (bytes == null || bytes <= 0) return '未知';
    if (bytes >= 1024 * 1024) {
      return '${(bytes / 1024 / 1024).toStringAsFixed(2)} MB';
    }
    return '${(bytes / 1024).toStringAsFixed(1)} KB';
  }

  final rows = <MapEntry<String, String>>[
    MapEntry('歌名', song.title),
    MapEntry('艺术家', song.displayArtist),
    MapEntry('专辑', song.displayAlbum),
    MapEntry('专辑艺术家', song.albumArtist ?? '未知'),
    MapEntry('专辑内排序', song.trackNumber?.toString() ?? '未知'),
    MapEntry('时长', song.durationText),
    MapEntry('比特率',
        song.bitrate != null ? '${(song.bitrate! / 1000).round()} kbps' : '未知'),
    MapEntry('采样率',
        song.sampleRate != null ? '${song.sampleRate} Hz' : '未知'),
    MapEntry('位深', song.bitDepth != null ? '${song.bitDepth} bit' : '未知'),
    MapEntry('大小', fmtSize(song.size)),
    MapEntry('格式', song.format?.toUpperCase() ?? '未知'),
    MapEntry('编码', song.codec ?? '未知'),
    MapEntry('添加时间', fmtTime(song.dateAdded)),
    MapEntry('修改时间', fmtTime(song.dateModified)),
    MapEntry('路径', song.path),
  ];

  showDialog(
    context: context,
    builder: (dialogContext) => AlertDialog(
      backgroundColor: theme.dialogBackgroundColor,
      title: Text('歌曲信息',
          style: TextStyle(color: theme.colorScheme.onSurface)),
      content: SizedBox(
        width: double.maxFinite,
        child: ListView(
          shrinkWrap: true,
          children: rows
              .map((e) => Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4.0),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        SizedBox(
                          width: 80.0,
                          child: Text(e.key,
                              style: theme.textTheme.bodySmall
                                  ?.copyWith(fontSize: 13.0)),
                        ),
                        Expanded(
                          child: Text(e.value,
                              style: TextStyle(
                                  color: theme.colorScheme.onSurface,
                                  fontSize: 13.0)),
                        ),
                      ],
                    ),
                  ))
              .toList(),
        ),
      ),
      actions: [
        TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('关闭')),
      ],
    ),
  );
}
