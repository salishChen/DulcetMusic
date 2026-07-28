import 'package:flutter/material.dart';
import 'package:flute_example/utils/themes.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';

/// 设置一级页面：提供主题切换（跟随系统 / 浅色 / 深色）
class SettingsPage extends StatelessWidget {
  const SettingsPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: buildPrimaryAppBar(context, '设置'),
      body: ValueListenableBuilder<ThemeMode>(
        valueListenable: themeModeNotifier,
        builder: (context, mode, _) {
          return ListView(
            padding: const EdgeInsets.all(8.0),
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
              const AboutListTile(
                icon: Icon(Icons.info_outline),
                applicationName: '愉乐',
                applicationVersion: '1.0.0',
                child: Text('关于'),
              ),
            ],
          );
        },
      ),
    );
  }
}
