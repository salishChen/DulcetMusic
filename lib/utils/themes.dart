import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum CurrentTheme { dark, light }

/// 品牌色：紫 / 青
const Color kBrandPurple = Color(0xFF7C4DFF);
const Color kBrandCyan = Color(0xFF18D2C7);

/// 文字次要色（浅色下灰、深色下浅灰）
const Color kTextSecondaryLight = Color(0xFF8A8A99);
const Color kTextSecondaryDark = Color(0xFFB3B3C2);

/// 全局主题切换通知（三态：跟随系统 / 浅色 / 深色），默认浅色
final ValueNotifier<ThemeMode> themeModeNotifier =
    ValueNotifier<ThemeMode>(ThemeMode.light);

const String _kThemePrefKey = 'theme_mode';

/// 启动时从 shared_preferences 读取并恢复主题偏好
Future<void> loadThemeMode() async {
  try {
    final prefs = await SharedPreferences.getInstance();
    final v = prefs.getString(_kThemePrefKey) ?? 'light';
    themeModeNotifier.value = _modeFromString(v);
  } catch (e) {
    // 读取失败则保持默认浅色
    themeModeNotifier.value = ThemeMode.light;
  }
}

/// 写入主题偏好并即时生效
Future<void> setThemeMode(ThemeMode mode) async {
  themeModeNotifier.value = mode;
  try {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kThemePrefKey, _modeToString(mode));
  } catch (_) {
    // 持久化失败不影响本次生效
  }
}

ThemeMode _modeFromString(String s) {
  switch (s) {
    case 'dark':
      return ThemeMode.dark;
    case 'system':
      return ThemeMode.system;
    default:
      return ThemeMode.light;
  }
}

String _modeToString(ThemeMode m) {
  switch (m) {
    case ThemeMode.dark:
      return 'dark';
    case ThemeMode.system:
      return 'system';
    default:
      return 'light';
  }
}

/// 浅色主题（默认风格）：浅灰背景 + 白色卡片 + 紫青强调色
final ThemeData lightTheme = ThemeData(
  useMaterial3: true,
  brightness: Brightness.light,
  colorScheme: ColorScheme.light(
    primary: kBrandPurple,
    secondary: kBrandCyan,
    surface: Colors.white,
    onSurface: const Color(0xFF333333),
    background: const Color(0xFFF7F7FA),
  ),
  scaffoldBackgroundColor: const Color(0xFFF7F7FA),
  cardColor: Colors.white,
  dividerColor: const Color(0xFFECECF1),
  appBarTheme: const AppBarTheme(
    backgroundColor: Color(0xFFF7F7FA),
    foregroundColor: Color(0xFF333333),
    elevation: 0,
    centerTitle: false,
  ),
  listTileTheme: const ListTileThemeData(
    textColor: Color(0xFF333333),
    iconColor: Color(0xFF333333),
  ),
  primaryTextTheme: const TextTheme(
    bodySmall: TextStyle(color: kTextSecondaryLight),
  ),
  textTheme: const TextTheme(
    bodySmall: TextStyle(color: kTextSecondaryLight),
  ),
  bottomSheetTheme: const BottomSheetThemeData(
    backgroundColor: Colors.white,
  ),
);

/// 深色主题：保留原有深色配色，紫/青强调色不变
final ThemeData darkTheme = ThemeData(
  useMaterial3: true,
  brightness: Brightness.dark,
  colorScheme: ColorScheme.dark(
    primary: const Color(0xFFB388FF),
    secondary: kBrandCyan,
    surface: const Color(0xFF16161F),
    onSurface: Colors.white,
    background: const Color(0xFF0E0E14),
  ),
  scaffoldBackgroundColor: const Color(0xFF0E0E14),
  cardColor: const Color(0xFF1F1F2E),
  dividerColor: const Color(0xFF2A2A3A),
  appBarTheme: const AppBarTheme(
    backgroundColor: Color(0xFF0E0E14),
    foregroundColor: Colors.white,
    elevation: 0,
    centerTitle: false,
  ),
  listTileTheme: const ListTileThemeData(
    textColor: Colors.white,
    iconColor: Colors.white70,
  ),
  primaryTextTheme: const TextTheme(
    bodySmall: TextStyle(color: kTextSecondaryDark),
  ),
  textTheme: const TextTheme(
    bodySmall: TextStyle(color: kTextSecondaryDark),
  ),
  bottomSheetTheme: const BottomSheetThemeData(
    backgroundColor: Color(0xFF1F1F2E),
  ),
);
