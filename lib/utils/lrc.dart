/// 单句歌词（含时间轴）
class LrcLine {
  final Duration time;
  final String text;
  const LrcLine(this.time, this.text);
}

/// 解析 LRC 文本为带时间轴的歌词行
///
/// 支持 `[mm:ss.xx]` / `[mm:ss.xxx]` 时间戳（可一行多时间戳）；
/// 无时间戳的纯文本作为无时间歌词返回（time 为 0），供整体展示。
List<LrcLine> parseLrc(String? raw) {
  if (raw == null || raw.trim().isEmpty) return const [];
  final reg = RegExp(r'\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]');
  final out = <LrcLine>[];
  for (final line in raw.split('\n')) {
    final matches = reg.allMatches(line);
    final text = line.replaceAll(reg, '').trim();
    if (matches.isEmpty) {
      if (text.isNotEmpty) out.add(LrcLine(Duration.zero, text));
      continue;
    }
    if (text.isEmpty) continue;
    for (final m in matches) {
      final min = int.parse(m.group(1)!);
      final sec = int.parse(m.group(2)!);
      final msStr = m.group(3) ?? '0';
      final ms = int.parse(msStr.padRight(3, '0').substring(0, 3));
      out.add(LrcLine(
        Duration(minutes: min, seconds: sec, milliseconds: ms),
        text,
      ));
    }
  }
  out.sort((a, b) => a.time.compareTo(b.time));
  return out;
}
