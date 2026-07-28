import 'package:flutter/material.dart';
import 'package:flute_example/utils/themes.dart';

/// 侧边栏条目定义
class SidebarItem {
  final String title;
  final IconData icon;
  const SidebarItem(this.title, this.icon);
}

/// 六个一级页面入口
const List<SidebarItem> kSidebarItems = [
  SidebarItem('歌曲', Icons.music_note),
  SidebarItem('专辑', Icons.album),
  SidebarItem('艺术家', Icons.people),
  SidebarItem('歌单', Icons.playlist_play),
  SidebarItem('扫描音乐', Icons.manage_search),
  SidebarItem('设置', Icons.settings),
];

/// 分组：音乐相关（索引 0-3）/ 设置相关（索引 4,5）
const List<int> kMusicGroup = [0, 1, 2, 3];
const List<int> kSettingsGroup = [4,5];

/// 侧边栏内容：上方 Logo，其下两张无标题卡片（音乐 / 设置），当前页高亮
class MPSidebar extends StatelessWidget {
  final int currentIndex;
  final ValueChanged<int> onSelect;

  const MPSidebar({
    Key? key,
    required this.currentIndex,
    required this.onSelect,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;
    final unselected =
        isDark ? const Color(0xFFB3B3C2) : const Color(0xFF333333);

    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: isDark
              ? const [Color(0xFF16161F), Color(0xFF0E0E14)]
              : const [Color(0xFFF3F2FB), Color(0xFFF7F7FA)],
        ),
      ),
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // 顶部 Logo 区
            Padding(
              padding: const EdgeInsets.fromLTRB(20.0, 28.0, 20.0, 24.0),
              child: Row(
                children: [
                  Container(
                    width: 36.0,
                    height: 36.0,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(10.0),
                      gradient: const LinearGradient(
                        colors: [kBrandPurple, kBrandCyan],
                      ),
                    ),
                    child: const Icon(Icons.music_note,
                        color: Colors.white, size: 22.0),
                  ),
                  const SizedBox(width: 10.0),
                  Text(
                    '愉乐',
                    style: TextStyle(
                      fontSize: 20.0,
                      fontWeight: FontWeight.w700,
                      color: theme.colorScheme.onSurface,
                    ),
                  ),
                ],
              ),
            ),
            // 两张无标题卡片
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(horizontal: 12.0),
                children: [
                  _groupCard(theme, unselected, kMusicGroup),
                  const SizedBox(height: 12.0),
                  _groupCard(theme, unselected, kSettingsGroup),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _groupCard(
      ThemeData theme, Color unselected, List<int> indexes) {
    return Card(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: theme.cardColor,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16.0),
        side: BorderSide(color: theme.dividerColor),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6.0),
        child: Column(
          children: indexes
              .map((index) => _item(theme, unselected, index))
              .toList(),
        ),
      ),
    );
  }

  Widget _item(ThemeData theme, Color unselected, int index) {
    final item = kSidebarItems[index];
    final selected = index == currentIndex;
    return Padding(
      padding: const EdgeInsets.only(bottom: 4.0),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          borderRadius: BorderRadius.circular(12.0),
          onTap: () => onSelect(index),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            padding: const EdgeInsets.symmetric(
                horizontal: 14.0, vertical: 12.0),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12.0),
              gradient: selected
                  ? const LinearGradient(colors: [
                      Color(0xFF7C4DFF),
                      Color(0xFF18D2C7),
                    ])
                  : null,
            ),
            child: Row(
              children: [
                Icon(
                  item.icon,
                  size: 22.0,
                  color: selected ? Colors.white : unselected,
                ),
                const SizedBox(width: 12.0),
                Text(
                  item.title,
                  style: TextStyle(
                    fontSize: 15.0,
                    fontWeight:
                        selected ? FontWeight.w600 : FontWeight.w400,
                    color: selected ? Colors.white : unselected,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
