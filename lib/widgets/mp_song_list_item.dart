import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/pages/now_playing.dart';
import 'package:flute_example/widgets/mp_artwork.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_song_bottom_sheet.dart';

/// 通用歌曲列表项
///
/// 右侧两个按钮：
/// - 加号：将当前歌曲添加到当前播放列表
/// - more_vert：点击（或长按整行）从底部弹出功能弹框
///
/// 点击整行：将 [queue]（当前页面的歌曲列表）整体替换为播放列表，
/// 并从本首开始播放。
class MpSongListItem extends StatelessWidget {
  final Song song;

  /// 点击播放时作为播放列表的歌曲队列（默认仅本首）
  final List<Song>? queue;

  /// 所处歌单 id（歌单详情页传入，弹框中显示"从本歌单删除"）
  final int? playlistId;

  /// 从歌单删除后的回调（刷新歌单详情页）
  final VoidCallback? onRemovedFromPlaylist;

  const MpSongListItem({
    Key? key,
    required this.song,
    this.queue,
    this.playlistId,
    this.onRemovedFromPlaylist,
  }) : super(key: key);

  void _openSheet(BuildContext context) {
    showSongBottomSheet(
      context,
      song,
      playlistId: playlistId,
      onRemovedFromPlaylist: onRemovedFromPlaylist,
    );
  }

  @override
  Widget build(BuildContext context) {
    final rootIW = MPInheritedWidget.of(context);
    final songData = rootIW.songData;
    final playlistData = rootIW.playlistData;
    final theme = Theme.of(context);

    return ListTile(
      contentPadding: const EdgeInsets.only(left: 16.0, right: 4.0),
      leading: Hero(
        tag: song.path,
        child: MpArtwork(
          song.path,
          width: 50.0,
          height: 50.0,
          borderRadius: BorderRadius.circular(12.0),
        ),
      ),
      title: Text(song.title, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        song.displayArtist,
        style: theme.textTheme.bodySmall,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 加号：添加到当前播放列表
          IconButton(
            icon: const Icon(Icons.add, color: Color(0xFF333333)),
            tooltip: '添加到播放列表',
            onPressed: () {
              if (playlistData == null) return;
              final added = playlistData.addSong(song);
              ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                content: Text(added ? '已添加到播放列表' : '该歌曲已在播放列表中'),
                duration: const Duration(seconds: 1),
              ));
            },
          ),
          // more_vert：底部功能弹框
          IconButton(
            icon: const Icon(Icons.more_vert, color: Color(0xFF333333)),
            tooltip: '更多操作',
            onPressed: () => _openSheet(context),
          ),
        ],
      ),
      onTap: () {
        if (songData == null) return;
        final q = queue ?? [song];
        final index = q.indexWhere((s) => s.path == song.path);
        // 将本页歌曲队列整体替换为播放列表，再从选中歌曲开始播放
        playlistData?.setSongs(q);
        songData.setCurrentIndex(index < 0 ? 0 : index);
        // 走与底部播放栏相同的滑出路由，确保是同一个播放页（nowPlayTap=false 起播）
        openNowPlayingPage(song);
      },
      onLongPress: () => _openSheet(context),
    );
  }
}
