import 'package:flutter/material.dart';
import 'package:flute_example/data/cache_service.dart';
import 'package:flute_example/pages/cache_manage_page.dart';
import 'package:flute_example/utils/themes.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 设置一级页面：提供主题切换（跟随系统 / 浅色 / 深色）和缓存设置
class SettingsPage extends StatefulWidget {
  const SettingsPage({Key? key}) : super(key: key);

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  int _cacheSizeMB = 2048;
  int _actualCacheMB = 0;
  bool _loadingCache = true;

  @override
  void initState() {
    super.initState();
    _loadCacheSettings();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // 每次页面显示时刷新实际缓存大小
    _loadActualCacheSize();
  }

  Future<void> _loadCacheSettings() async {
    final sizeMB = await CacheService.instance.getCacheSizeMB();
    final actualMB = await CacheService.instance.getCacheSizeMBActual();
    if (mounted) {
      setState(() {
        _cacheSizeMB = sizeMB;
        _actualCacheMB = actualMB;
        _loadingCache = false;
      });
    }
  }

  Future<void> _loadActualCacheSize() async {
    final actualMB = await CacheService.instance.getCacheSizeMBActual();
    if (mounted) {
      setState(() => _actualCacheMB = actualMB);
    }
  }

  Future<void> _updateCacheSize(int sizeMB) async {
    await CacheService.instance.setCacheSizeMB(sizeMB);
    if (mounted) {
      setState(() => _cacheSizeMB = sizeMB);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '设置'),
      body: ListView(
        padding: const EdgeInsets.all(8.0),
        children: [
          // 主题设置
          ValueListenableBuilder<ThemeMode>(
            valueListenable: themeModeNotifier,
            builder: (context, mode, _) {
              return Column(
                children: [
                  RadioListTile<ThemeMode>(
                    secondary: const Icon(Icons.brightness_auto),
                    title: const Text('跟随系统'),
                    subtitle: const Text('系统为深色时使用深色，否则使用浅色',
                        style: TextStyle(fontSize: 12.0, color: Color(0xFF8A8A99))),
                    value: ThemeMode.system,
                    groupValue: mode,
                    onChanged: (m) => setThemeMode(m!),
                  ),
                  RadioListTile<ThemeMode>(
                    secondary: const Icon(Icons.light_mode),
                    title: const Text('浅色'),
                    value: ThemeMode.light,
                    groupValue: mode,
                    onChanged: (m) => setThemeMode(m!),
                  ),
                  RadioListTile<ThemeMode>(
                    secondary: const Icon(Icons.dark_mode),
                    title: const Text('深色'),
                    value: ThemeMode.dark,
                    groupValue: mode,
                    onChanged: (m) => setThemeMode(m!),
                  ),
                ],
              );
            },
          ),

          const Divider(),

          // 缓存设置
          ListTile(
            leading: const Icon(Icons.storage),
            title: const Text('缓存池大小'),
            subtitle: _loadingCache
                ? const Text('加载中...')
                : Text('已用 ${CacheService.formatSizeMB(_actualCacheMB)} / 上限 ${CacheService.formatSizeMB(_cacheSizeMB)}'),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Row(
              children: [
                Text(
                  '500 MB',
                  style: TextStyle(
                    fontSize: 12.0,
                    color: Theme.of(context).textTheme.bodySmall?.color,
                  ),
                ),
                Expanded(
                  child: Slider(
                    value: _cacheSizeMB.toDouble(),
                    min: 500,
                    max: 51200,
                    divisions: 102,
                    label: CacheService.formatSizeMB(_cacheSizeMB),
                    onChanged: _loadingCache
                        ? null
                        : (value) {
                            setState(() => _cacheSizeMB = value.round());
                          },
                    onChangeEnd: _loadingCache
                        ? null
                        : (value) => _updateCacheSize(value.round()),
                  ),
                ),
                Text(
                  '50 GB',
                  style: TextStyle(
                    fontSize: 12.0,
                    color: Theme.of(context).textTheme.bodySmall?.color,
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16.0),
            child: Text(
              '超出缓存池大小时，自动删除最早缓存的音乐',
              style: TextStyle(
                fontSize: 12.0,
                color: Theme.of(context).textTheme.bodySmall?.color?.withOpacity(0.6),
              ),
            ),
          ),

          const SizedBox(height: 8.0),

          // 缓存管理入口
          ListTile(
            leading: const Icon(Icons.manage_search),
            title: const Text('缓存管理'),
            subtitle: const Text('查看和管理已缓存的远程音乐'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (_) => const CacheManagePage(),
                ),
              );
            },
          ),

          const Divider(),

          const AboutListTile(
            icon: Icon(Icons.info_outline),
            applicationName: '愉乐',
            applicationVersion: '1.0.0',
            child: Text('关于'),
          ),
        ],
      ),
    );
  }
}
