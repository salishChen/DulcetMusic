import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_inherited.dart';

/// 统计页面：播放次数排行 + 最近播放
class StatsPage extends StatefulWidget {
  const StatsPage({Key? key}) : super(key: key);

  @override
  State<StatsPage> createState() => _StatsPageState();
}

class _StatsPageState extends State<StatsPage> with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  final dbHelper = DatabaseHelper.instance;

  List<Song> _topPlayed = [];
  List<Song> _recentlyPlayed = [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    _loadData();
  }

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    final top = await dbHelper.queryTopPlayed(limit: 50);
    final recent = await dbHelper.queryRecentlyPlayed(limit: 50);
    if (mounted) {
      setState(() {
        _topPlayed = top;
        _recentlyPlayed = recent;
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: buildPrimaryAppBar(context, '统计'),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // 概览卡片
                _buildOverviewCard(theme),
                // Tab 栏
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
                    tabs: const [
                      Tab(text: '最常播放'),
                      Tab(text: '最近播放'),
                    ],
                  ),
                ),
                // Tab 内容
                Expanded(
                  child: TabBarView(
                    controller: _tabController,
                    children: [
                      _buildSongList(_topPlayed, showRank: true),
                      _buildSongList(_recentlyPlayed),
                    ],
                  ),
                ),
              ],
            ),
    );
  }

  Widget _buildOverviewCard(ThemeData theme) {
    final totalPlays = _topPlayed.fold<int>(0, (sum, s) => sum + s.playCount);
    final uniquePlayed = _topPlayed.length;

    return Container(
      margin: const EdgeInsets.all(16.0),
      padding: const EdgeInsets.all(20.0),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16.0),
        gradient: LinearGradient(
          colors: [
            theme.colorScheme.primary.withOpacity(0.15),
            theme.colorScheme.primary.withOpacity(0.05),
          ],
        ),
        border: Border.all(
          color: theme.colorScheme.primary.withOpacity(0.3),
        ),
      ),
      child: Row(
        children: [
          Expanded(
            child: _statItem(theme, '总播放次数', '$totalPlays'),
          ),
          Container(
            width: 1,
            height: 40,
            color: theme.dividerColor,
          ),
          Expanded(
            child: _statItem(theme, '已听歌曲', '$uniquePlayed'),
          ),
          Container(
            width: 1,
            height: 40,
            color: theme.dividerColor,
          ),
          Expanded(
            child: _statItem(theme, '最近播放', '${_recentlyPlayed.length}'),
          ),
        ],
      ),
    );
  }

  Widget _statItem(ThemeData theme, String label, String value) {
    return Column(
      children: [
        Text(
          value,
          style: TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.w700,
            color: theme.colorScheme.primary,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: TextStyle(
            fontSize: 12,
            color: theme.textTheme.bodySmall?.color,
          ),
        ),
      ],
    );
  }

  Widget _buildSongList(List<Song> songs, {bool showRank = false}) {
    if (songs.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.music_note, size: 48, color: Colors.grey[400]),
            const SizedBox(height: 12),
            Text(
              '暂无播放记录',
              style: TextStyle(color: Colors.grey[500], fontSize: 16),
            ),
          ],
        ),
      );
    }

    final rootIW = MPInheritedWidget.of(context);
    final allSongs = rootIW.songData?.songs ?? songs;

    return RefreshIndicator(
      onRefresh: _loadData,
      child: ListView.builder(
        padding: const EdgeInsets.symmetric(vertical: 8),
        itemCount: songs.length,
        itemBuilder: (context, index) {
          final song = songs[index];
          return _buildSongTile(song, index, allSongs, showRank: showRank);
        },
      ),
    );
  }

  Widget _buildSongTile(Song song, int index, List<Song> queue, {bool showRank = false}) {
    final theme = Theme.of(context);
    final h = audioHandler;

    return ListTile(
      leading: showRank
          ? Container(
              width: 32,
              alignment: Alignment.center,
              child: Text(
                '${index + 1}',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: index < 3 ? theme.colorScheme.primary : theme.textTheme.bodySmall?.color,
                ),
              ),
            )
          : null,
      title: Text(
        song.title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 15),
      ),
      subtitle: Text(
        '${song.artist ?? "未知艺术家"} · ${song.album ?? "未知专辑"}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          fontSize: 12,
          color: theme.textTheme.bodySmall?.color,
        ),
      ),
      trailing: showRank
          ? Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: theme.colorScheme.primary.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                '${song.playCount}次',
                style: TextStyle(
                  fontSize: 12,
                  color: theme.colorScheme.primary,
                  fontWeight: FontWeight.w500,
                ),
              ),
            )
          : Text(
              _formatLastPlayed(song.lastPlayed),
              style: TextStyle(
                fontSize: 12,
                color: theme.textTheme.bodySmall?.color,
              ),
            ),
      onTap: () {
        MPInheritedWidget.of(context).playlistData?.setSongs(queue);
        h?.playSong(song);
      },
    );
  }

  String _formatLastPlayed(int? timestamp) {
    if (timestamp == null) return '';
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
    final now = DateTime.now();
    final diff = now.difference(date);

    if (diff.inMinutes < 1) return '刚刚';
    if (diff.inHours < 1) return '${diff.inMinutes}分钟前';
    if (diff.inDays < 1) return '${diff.inHours}小时前';
    if (diff.inDays < 7) return '${diff.inDays}天前';
    return '${date.month}/${date.day}';
  }
}
