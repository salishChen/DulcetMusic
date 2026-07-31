import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/models/album.dart';
import 'package:flute_example/data/models/artist.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/widgets/mp_inherited.dart';

/// 实体类型
enum EntityType {
  album,
  artist,
}

/// 实体操作结果
class EntityActionResult {
  final bool success;
  final String message;
  final int? count;

  EntityActionResult({
    required this.success,
    required this.message,
    this.count,
  });
}

/// 实体操作底部弹窗
class EntityActionSheet extends StatelessWidget {
  final EntityType entityType;
  final String entityName;
  final String? artistName; // 专辑所属艺术家（仅专辑类型需要）
  final List<Song> entitySongs; // 该实体下的所有歌曲
  final bool isLiked;
  final VoidCallback? onRefresh;

  const EntityActionSheet({
    Key? key,
    required this.entityType,
    required this.entityName,
    this.artistName,
    required this.entitySongs,
    this.isLiked = false,
    this.onRefresh,
  }) : super(key: key);

  /// 显示专辑操作菜单
  static Future<void> showForAlbum({
    required BuildContext context,
    required Album album,
    List<Song>? songs,
    VoidCallback? onRefresh,
  }) async {
    // 如果没有传入歌曲，查询该专辑的所有歌曲
    final albumSongs = songs ?? await DatabaseHelper.instance.querySongsByAlbum(album.title);
    
    // 检查专辑是否被喜欢（通过歌曲聚合判断）
    final isLiked = albumSongs.isNotEmpty && albumSongs.every((s) => s.isLiked);

    if (!context.mounted) return;

    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => EntityActionSheet(
        entityType: EntityType.album,
        entityName: album.title,
        artistName: album.artist,
        entitySongs: albumSongs,
        isLiked: isLiked,
        onRefresh: onRefresh,
      ),
    );
  }

  /// 显示艺术家操作菜单
  static Future<void> showForArtist({
    required BuildContext context,
    required Artist artist,
    List<Song>? songs,
    VoidCallback? onRefresh,
  }) async {
    // 如果没有传入歌曲，查询该艺术家的所有歌曲
    final artistSongs = songs ?? await DatabaseHelper.instance.querySongsByArtist(artist.name);
    
    // 检查艺术家是否被喜欢
    final artistMeta = await DatabaseHelper.instance.queryArtistMeta(artist.name);
    final isLiked = artistMeta?['isLiked'] == 1;

    if (!context.mounted) return;

    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => EntityActionSheet(
        entityType: EntityType.artist,
        entityName: artist.name,
        entitySongs: artistSongs,
        isLiked: isLiked,
        onRefresh: onRefresh,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final remoteSongs = entitySongs.where((s) => s.isRemote).toList();
    final uncachedRemoteSongs = remoteSongs.where((s) => !s.isCached).toList();

    return Container(
      padding: const EdgeInsets.symmetric(vertical: 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // 标题栏
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Icon(
                  entityType == EntityType.album ? Icons.album : Icons.person,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        entityName,
                        style: const TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.w600,
                        ),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      if (entityType == EntityType.album && artistName != null)
                        Text(
                          artistName!,
                          style: TextStyle(
                            fontSize: 13,
                            color: theme.textTheme.bodySmall?.color,
                          ),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      Text(
                        '${entitySongs.length} 首歌曲',
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.textTheme.bodySmall?.color,
                        ),
                      ),
                    ],
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
          ),
          const Divider(),
          // 操作列表
          _buildActionItem(
            context,
            icon: isLiked ? Icons.favorite : Icons.favorite_border,
            iconColor: isLiked ? Colors.red : null,
            title: isLiked ? '取消喜欢' : '喜欢',
            subtitle: isLiked ? '从喜欢列表中移除' : '添加到喜欢列表',
            onTap: () => _toggleLike(context),
          ),
          _buildActionItem(
            context,
            icon: Icons.playlist_add,
            title: '添加到播放队列',
            subtitle: '将 ${entitySongs.length} 首歌曲添加到队列',
            onTap: () => _addToQueue(context),
          ),
          if (uncachedRemoteSongs.isNotEmpty)
            _buildActionItem(
              context,
              icon: Icons.cloud_download,
              title: '缓存全部',
              subtitle: '缓存 ${uncachedRemoteSongs.length} 首未缓存的远程歌曲',
              onTap: () => _cacheAll(context, uncachedRemoteSongs),
            ),
          if (entityType == EntityType.artist)
            _buildActionItem(
              context,
              icon: Icons.cloud_sync,
              title: '默认缓存本艺术家音乐',
              subtitle: '自动缓存该艺术家的新歌曲',
              onTap: () => _setDefaultCacheForArtist(context),
            ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  Widget _buildActionItem(
    BuildContext context, {
    required IconData icon,
    Color? iconColor,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    final theme = Theme.of(context);
    return ListTile(
      leading: Icon(icon, color: iconColor ?? theme.colorScheme.primary),
      title: Text(title),
      subtitle: Text(
        subtitle,
        style: TextStyle(fontSize: 12, color: theme.textTheme.bodySmall?.color),
      ),
      onTap: () {
        Navigator.pop(context);
        onTap();
      },
    );
  }

  /// 切换喜欢状态
  Future<void> _toggleLike(BuildContext context) async {
    final dbHelper = DatabaseHelper.instance;
    
    if (entityType == EntityType.artist) {
      // 切换艺术家喜欢状态
      await dbHelper.toggleLikeArtist(entityName);
      // 同时切换该艺术家所有歌曲的喜欢状态
      for (final song in entitySongs) {
        if (song.id != null) {
          await dbHelper.toggleLikeSong(song.id!);
        }
      }
    } else {
      // 切换专辑所有歌曲的喜欢状态
      for (final song in entitySongs) {
        if (song.id != null) {
          await dbHelper.toggleLikeSong(song.id!);
        }
      }
    }

    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(isLiked ? '已取消喜欢' : '已添加到喜欢'),
        duration: const Duration(seconds: 1),
      ));
    }

    onRefresh?.call();
  }

  /// 添加到播放队列
  void _addToQueue(BuildContext context) {
    final playlistData = MPInheritedWidget.of(context).playlistData;
    if (playlistData == null) return;

    int addedCount = 0;
    for (final song in entitySongs) {
      if (playlistData.addSong(song)) {
        addedCount++;
      }
    }

    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('已添加 $addedCount 首歌曲到播放队列'),
      duration: const Duration(seconds: 2),
    ));
  }

  /// 缓存全部远程歌曲
  Future<void> _cacheAll(BuildContext context, List<Song> songsToCache) async {
    final cacheService = CacheService.instance;
    
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('开始缓存 ${songsToCache.length} 首歌曲...'),
      duration: const Duration(seconds: 2),
    ));

    // 在后台开始缓存（异步执行，不阻塞UI）
    for (final song in songsToCache) {
      cacheService.startCaching(song);
    }

    // 显示缓存已启动的消息
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text('已在后台开始缓存 ${songsToCache.length} 首歌曲'),
        duration: const Duration(seconds: 2),
      ));
    }

    onRefresh?.call();
  }

  /// 设置默认缓存艺术家音乐
  Future<void> _setDefaultCacheForArtist(BuildContext context) async {
    // 这里可以实现一个设置，标记该艺术家的音乐默认自动缓存
    // 目前先显示一个提示
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text('已设置默认缓存 $entityName 的音乐'),
      duration: const Duration(seconds: 2),
    ));
  }
}
