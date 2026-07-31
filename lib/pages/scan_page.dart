import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flute_example/data/audio_handler.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/data/metadata_service.dart';
import 'package:flute_example/data/subsonic_service.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 扫描音乐一级页面
///
/// 支持两种扫描来源：
/// 1. 安卓媒体库（on_audio_query 获取路径，元数据从文件读取）
/// 2. 指定文件夹（file_picker 选择目录后递归扫描）
/// 扫描结果批量写入 SQLite，完成后刷新全局歌曲列表。
class ScanPage extends StatefulWidget {
  const ScanPage({Key? key}) : super(key: key);

  @override
  State<ScanPage> createState() => _ScanPageState();
}

class _ScanPageState extends State<ScanPage> {
  final MetadataService _service = MetadataService();

  bool _scanning = false;
  int _processed = 0;
  int _total = 0;
  int _failed = 0;
  ScanResult? _lastResult;

  void _onProgress(int processed, int total, int failed) {
    if (!mounted) return;
    setState(() {
      _processed = processed;
      _total = total;
      _failed = failed;
    });
  }

  Future<void> _run(Future<ScanResult> Function() scan) async {
    setState(() {
      _scanning = true;
      _processed = 0;
      _total = 0;
      _failed = 0;
      _lastResult = null;
    });
    ScanResult result = const ScanResult();
    try {
      result = await scan();
    } catch (e) {
      print('ScanPage: 扫描失败: $e');
    }
    // 扫描完成后刷新全局歌曲快照（通知各页面）
    if (mounted) {
      await MPInheritedWidget.of(context).songData?.reload();
    }
    if (!mounted) return;
    setState(() {
      _scanning = false;
      _lastResult = result;
    });
  }

  Future<void> _scanMediaLibrary() =>
      _run(() => _service.scanMediaLibrary(onProgress: _onProgress));

  Future<void> _scanFolder() async {
    final dir = await FilePicker.platform.getDirectoryPath(
      dialogTitle: '选择要扫描的音乐文件夹',
    );
    if (dir == null) return;
    await _run(() => _service.scanFolder(dir, onProgress: _onProgress));
  }

  Future<void> _scanRemote() async {
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

    setState(() {
      _scanning = true;
      _processed = 0;
      _total = 0;
      _failed = 0;
      _lastResult = null;
    });

    try {
      final songs = await SubsonicService.instance.getAllSongs();
      if (!mounted) return;

      setState(() => _total = songs.length);

      int added = 0;
      int failed = 0;
      const batchSize = 50;

      for (int i = 0; i < songs.length; i += batchSize) {
        final end = (i + batchSize).clamp(0, songs.length);
        final batch = songs.sublist(i, end);

        try {
          final songData = MPInheritedWidget.of(context).songData;
          if (songData == null) {
            failed += batch.length;
            continue;
          }
          final affected =
              await songData.dbHelper.insertSongs(batch, source: 'subsonic');
          added += affected;
        } catch (e) {
          failed += batch.length;
          print('ScanPage: 远程扫描批次失败: $e');
        }

        if (mounted) {
          setState(() {
            _processed = end;
            _failed = failed;
          });
        }
      }

      // 刷新全局歌曲列表（此时封面缓存尚未完成）
      if (mounted) {
        await MPInheritedWidget.of(context).songData?.reload();
      }

      // 后台下载封面，完成后再次刷新列表以更新 cachedArtworkPath
      _cacheArtworkInBackground().then((_) async {
        if (mounted) {
          await MPInheritedWidget.of(context).songData?.reload();
        }
      });

      if (mounted) {
        setState(() {
          _scanning = false;
          _lastResult = ScanResult(
            total: songs.length,
            added: added,
            failed: failed,
          );
        });
      }
    } catch (e) {
      print('ScanPage: 远程扫描失败: $e');
      if (mounted) {
        setState(() {
          _scanning = false;
          _lastResult = ScanResult(total: 0, added: 0, failed: 1);
        });
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('扫描失败: $e'),
            duration: const Duration(seconds: 3),
          ),
        );
      }
    }
  }

  /// 后台批量缓存远程歌曲封面
  Future<void> _cacheArtworkInBackground() async {
    try {
      // 查询所有有 coverArtId 但没有缓存封面的远程歌曲
      final db = DatabaseHelper.instance;
      final allSongs = await db.queryAllSongs();
      final remoteSongs = allSongs
          .where((s) =>
              s.sourceType == 'subsonic' &&
              s.coverArtId != null &&
              s.cachedArtworkPath == null)
          .toList();

      if (remoteSongs.isEmpty) return;

      for (final song in remoteSongs) {
        await CacheService.instance.cacheArtwork(song);
      }
    } catch (e) {
      print('ScanPage: 后台缓存封面失败: $e');
    }
  }

  /// 清空所有已入库歌曲
  Future<void> _clearAllSongs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清空音乐库'),
        content: const Text('确定要清空所有已扫描入库的音乐吗？\n\n此操作不可撤销，将删除所有歌曲记录（包括本地和远程歌曲）。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('清空'),
          ),
        ],
      ),
    );

    if (confirmed != true || !mounted) return;

    setState(() => _scanning = true);

    try {
      // 先停止播放并清空当前播放列表（歌曲记录删除后索引会失效）
      await audioHandler?.stopAndClear();
      // 清空歌曲表（统计 playCount/lastPlayed 随歌曲记录一并清除）
      await DatabaseHelper.instance.clearSongs();
      // 同时清除所有缓存文件
      await CacheService.instance.clearAllCache();
      if (mounted) {
        await MPInheritedWidget.of(context).songData?.reload();
      }
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('已清空所有音乐'),
            duration: Duration(seconds: 2),
          ),
        );
      }
    } catch (e) {
      print('ScanPage: 清空失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('清空失败: $e'),
            duration: const Duration(seconds: 3),
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() {
          _scanning = false;
          _lastResult = null;
        });
      }
    }
  }

  Widget _sourceCard({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback? onTap,
    required List<Color> colors,
  }) {
    return InkWell(
      borderRadius: BorderRadius.circular(20.0),
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(20.0),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(20.0),
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: colors,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 52.0,
              height: 52.0,
              decoration: BoxDecoration(
                color: Colors.white.withOpacity(0.18),
                borderRadius: BorderRadius.circular(14.0),
              ),
              child: Icon(icon, color: Colors.white, size: 28.0),
            ),
            const SizedBox(width: 16.0),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: const TextStyle(
                          fontSize: 16.0,
                          fontWeight: FontWeight.w700,
                          color: Colors.white)),
                  const SizedBox(height: 4.0),
                  Text(subtitle,
                      style: TextStyle(
                          fontSize: 12.0,
                          color: Colors.white.withOpacity(0.8))),
                ],
              ),
            ),
            const Icon(Icons.chevron_right, color: Colors.white70),
          ],
        ),
      ),
    );
  }

  Widget _progressView() {
    final theme = Theme.of(context);
    final progress = _total > 0 ? _processed / _total : null;
    return Column(
      children: [
        const SizedBox(height: 24.0),
        SizedBox(
          width: 120.0,
          height: 120.0,
          child: Stack(
            fit: StackFit.expand,
            alignment: Alignment.center,
            children: [
              CircularProgressIndicator(
                value: progress,
                strokeWidth: 8.0,
                backgroundColor: theme.colorScheme.surfaceVariant,
                valueColor: AlwaysStoppedAnimation<Color>(theme.colorScheme.primary),
              ),
              Center(
                child: Text(
                  _total > 0 ? '$_processed/$_total' : '准备中',
                  style: const TextStyle(
                      fontSize: 16.0, fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16.0),
        Text('正在扫描入库…（失败 $_failed）',
            style: TextStyle(color: theme.textTheme.bodySmall?.color)),
      ],
    );
  }

  Widget _resultView(ScanResult r) {
    final theme = Theme.of(context);
    return Container(
      margin: const EdgeInsets.only(top: 24.0),
      padding: const EdgeInsets.all(16.0),
      decoration: BoxDecoration(
        color: theme.cardColor,
        borderRadius: BorderRadius.circular(16.0),
      ),
      child: Row(
        children: [
          Icon(Icons.check_circle, color: theme.colorScheme.primary),
          const SizedBox(width: 12.0),
          Expanded(
            child: Text(
              '扫描完成：共 ${r.total} 个文件，入库 ${r.added} 首，失败 ${r.failed} 个',
              style: const TextStyle(fontSize: 14.0),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '扫描音乐'),
      body: ListView(
        padding: const EdgeInsets.all(16.0),
        children: [
          Text(
            '选择扫描来源',
            style: TextStyle(fontSize: 14.0, color: Theme.of(context).textTheme.bodySmall?.color),
          ),
          const SizedBox(height: 12.0),
          _sourceCard(
            icon: Icons.library_music,
            title: '扫描安卓媒体库',
            subtitle: '扫描系统媒体库中的所有音乐并入库',
            colors: const [Color(0xFF7C4DFF), Color(0xFFB388FF)],
            onTap: _scanning ? null : _scanMediaLibrary,
          ),
          const SizedBox(height: 12.0),
          _sourceCard(
            icon: Icons.folder_open,
            title: '扫描指定文件夹',
            subtitle: '选择文件夹，递归扫描其中所有音乐',
            colors: const [Color(0xFF18D2C7), Color(0xFF7C4DFF)],
            onTap: _scanning ? null : _scanFolder,
          ),
          const SizedBox(height: 12.0),
          _sourceCard(
            icon: Icons.cloud_download,
            title: '扫描远程音乐',
            subtitle: SubsonicService.instance.isConfigured
                ? '从 Subsonic 服务器扫描音乐并入库'
                : '请先在"远程配置"中配置服务器',
            colors: const [Color(0xFFFF6B6B), Color(0xFFFF8E53)],
            onTap: _scanning ? null : _scanRemote,
          ),
          if (_scanning) _progressView(),
          if (_lastResult != null) _resultView(_lastResult!),
          const SizedBox(height: 24.0),
          // 清空按钮
          SizedBox(
            width: double.infinity,
            child: OutlinedButton.icon(
              onPressed: _scanning ? null : _clearAllSongs,
              icon: const Icon(Icons.delete_sweep, color: Colors.red),
              label: const Text(
                '清空音乐库',
                style: TextStyle(color: Colors.red),
              ),
              style: OutlinedButton.styleFrom(
                side: const BorderSide(color: Colors.red),
                padding: const EdgeInsets.symmetric(vertical: 14.0),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12.0),
                ),
              ),
            ),
          ),
          const SizedBox(height: 16.0),
          Text(
            '说明：歌曲的歌名、艺术家、专辑、时长、比特率、采样率等信息'
            '均直接从音频文件中读取并存入本地数据库，播放时使用数据库内的路径。\n\n'
            '远程歌曲的封面会在扫描后自动缓存到本地，播放时优先使用缓存封面。',
            style: TextStyle(fontSize: 12.0, color: Theme.of(context).textTheme.bodySmall?.color?.withOpacity(0.6)),
          ),
        ],
      ),
    );
  }
}
