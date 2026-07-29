import 'package:flutter/material.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 缓存管理页面
///
/// 查看缓存池内的音乐列表，支持批量选中删除。
class CacheManagePage extends StatefulWidget {
  const CacheManagePage({Key? key}) : super(key: key);

  @override
  State<CacheManagePage> createState() => _CacheManagePageState();
}

class _CacheManagePageState extends State<CacheManagePage> {
  List<Song> _cachedSongs = [];
  bool _loading = true;
  bool _selectMode = false;
  final Set<int> _selectedIds = {};
  int _cacheSizeMB = 0;

  @override
  void initState() {
    super.initState();
    _loadCache();
  }

  Future<void> _loadCache() async {
    setState(() => _loading = true);
    final songs = await CacheService.instance.getCachedSongs();
    final sizeMB = await CacheService.instance.getCacheSizeMBActual();
    if (mounted) {
      setState(() {
        _cachedSongs = songs;
        _cacheSizeMB = sizeMB;
        _loading = false;
      });
    }
  }

  void _toggleSelectMode() {
    setState(() {
      _selectMode = !_selectMode;
      if (!_selectMode) _selectedIds.clear();
    });
  }

  void _toggleSelection(int songId) {
    setState(() {
      if (_selectedIds.contains(songId)) {
        _selectedIds.remove(songId);
      } else {
        _selectedIds.add(songId);
      }
    });
  }

  void _selectAll() {
    setState(() {
      if (_selectedIds.length == _cachedSongs.length) {
        _selectedIds.clear();
      } else {
        _selectedIds.addAll(_cachedSongs.where((s) => s.id != null).map((s) => s.id!));
      }
    });
  }

  Future<void> _deleteSelected() async {
    if (_selectedIds.isEmpty) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('删除缓存'),
        content: Text('确定删除选中的 ${_selectedIds.length} 首歌曲的缓存吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除', style: TextStyle(color: Color(0xFFFF5252))),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await CacheService.instance.deleteCache(_selectedIds.toList());
      setState(() {
        _selectedIds.clear();
        _selectMode = false;
      });
      await _loadCache();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('缓存已删除'),
            duration: Duration(seconds: 1),
          ),
        );
      }
    }
  }

  Future<void> _clearAll() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('清空缓存'),
        content: const Text('确定清空所有缓存吗？此操作不可撤销。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('清空', style: TextStyle(color: Color(0xFFFF5252))),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await CacheService.instance.clearAllCache();
      await _loadCache();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('缓存已清空'),
            duration: Duration(seconds: 1),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('缓存管理'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
        actions: [
          if (_cachedSongs.isNotEmpty) ...[
            if (_selectMode) ...[
              IconButton(
                icon: Icon(
                  _selectedIds.length == _cachedSongs.length
                      ? Icons.deselect
                      : Icons.select_all,
                ),
                tooltip: _selectedIds.length == _cachedSongs.length ? '取消全选' : '全选',
                onPressed: _selectAll,
              ),
              IconButton(
                icon: const Icon(Icons.delete, color: Color(0xFFFF5252)),
                tooltip: '删除选中',
                onPressed: _selectedIds.isEmpty ? null : _deleteSelected,
              ),
            ],
            IconButton(
              icon: Icon(_selectMode ? Icons.close : Icons.checklist),
              tooltip: _selectMode ? '取消选择' : '批量选择',
              onPressed: _toggleSelectMode,
            ),
            if (!_selectMode)
              IconButton(
                icon: const Icon(Icons.delete_sweep, color: Color(0xFFFF5252)),
                tooltip: '清空缓存',
                onPressed: _clearAll,
              ),
          ],
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                // 缓存信息卡片
                Container(
                  margin: const EdgeInsets.all(16),
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      colors: [Color(0xFF7C4DFF), Color(0xFF18D2C7)],
                    ),
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Row(
                    children: [
                      Container(
                        width: 48,
                        height: 48,
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.2),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: const Icon(Icons.storage, color: Colors.white),
                      ),
                      const SizedBox(width: 16),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const Text(
                              '缓存池大小',
                              style: TextStyle(
                                color: Colors.white70,
                                fontSize: 12,
                              ),
                            ),
                            Text(
                              CacheService.formatSizeMB(_cacheSizeMB),
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 24,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                      ),
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            '${_cachedSongs.length} 首歌曲',
                            style: const TextStyle(
                              color: Colors.white70,
                              fontSize: 12,
                            ),
                          ),
                          if (_selectMode)
                            Text(
                              '已选 ${_selectedIds.length} 首',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 14,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                        ],
                      ),
                    ],
                  ),
                ),

                // 歌曲列表
                Expanded(
                  child: _cachedSongs.isEmpty
                      ? Center(
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(
                                Icons.music_off,
                                size: 64,
                                color: Theme.of(context)
                                    .textTheme
                                    .bodySmall
                                    ?.color
                                    ?.withOpacity(0.3),
                              ),
                              const SizedBox(height: 16),
                              Text(
                                '暂无缓存',
                                style: TextStyle(
                                  color: Theme.of(context)
                                      .textTheme
                                      .bodySmall
                                      ?.color,
                                ),
                              ),
                            ],
                          ),
                        )
                      : ListView.builder(
                          itemCount: _cachedSongs.length,
                          itemBuilder: (context, index) {
                            final song = _cachedSongs[index];
                            final isSelected =
                                _selectedIds.contains(song.id);
                            return ListTile(
                              leading: _selectMode
                                  ? Checkbox(
                                      value: isSelected,
                                      onChanged: (_) =>
                                          _toggleSelection(song.id!),
                                      activeColor: const Color(0xFF7C4DFF),
                                    )
                                  : Container(
                                      width: 40,
                                      height: 40,
                                      decoration: BoxDecoration(
                                        color: const Color(0xFF7C4DFF)
                                            .withOpacity(0.1),
                                        borderRadius:
                                            BorderRadius.circular(8),
                                      ),
                                      child: const Icon(
                                        Icons.music_note,
                                        color: Color(0xFF7C4DFF),
                                        size: 20,
                                      ),
                                    ),
                              title: Text(
                                song.title,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                              ),
                              subtitle: Text(
                                '${song.displayArtist} · ${CacheService.formatSize(song.size ?? 0)}',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 12,
                                  color: Color(0xFF8A8A99),
                                ),
                              ),
                              onTap: _selectMode && song.id != null
                                  ? () => _toggleSelection(song.id!)
                                  : null,
                            );
                          },
                        ),
                ),
              ],
            ),
    );
  }
}
