import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flute_example/data/metadata_service.dart';
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
                backgroundColor: const Color(0xFF1F1F2E),
                valueColor:
                    const AlwaysStoppedAnimation<Color>(Color(0xFF7C4DFF)),
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
            style: const TextStyle(color: Color(0xFFB3B3C2))),
      ],
    );
  }

  Widget _resultView(ScanResult r) {
    return Container(
      margin: const EdgeInsets.only(top: 24.0),
      padding: const EdgeInsets.all(16.0),
      decoration: BoxDecoration(
        color: const Color(0xFF1F1F2E),
        borderRadius: BorderRadius.circular(16.0),
      ),
      child: Row(
        children: [
          const Icon(Icons.check_circle, color: Color(0xFF1ED760)),
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
          const Text(
            '选择扫描来源',
            style: TextStyle(fontSize: 14.0, color: Color(0xFFB3B3C2)),
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
          if (_scanning) _progressView(),
          if (_lastResult != null) _resultView(_lastResult!),
          const SizedBox(height: 24.0),
          const Text(
            '说明：歌曲的歌名、艺术家、专辑、时长、比特率、采样率等信息'
            '均直接从音频文件中读取并存入本地数据库，播放时使用数据库内的路径。',
            style: TextStyle(fontSize: 12.0, color: Color(0xFF8A8A99)),
          ),
        ],
      ),
    );
  }
}
