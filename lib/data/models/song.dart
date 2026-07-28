/// 歌曲实体：映射数据库 songs 表的全部字段
///
/// 所有元数据均在扫描时从音频文件中读取（audio_metadata_reader），
/// 播放时直接使用 [path] 指向的本地文件。
class Song {
  final int? id;

  /// 歌名（无标签时回退为文件名）
  final String title;

  /// 文件绝对路径（唯一）
  final String path;

  final String? artist;
  final String? album;

  /// 专辑艺术家
  final String? albumArtist;

  /// 专辑内排序（音轨号）
  final int? trackNumber;

  /// 时长（毫秒）
  final int? duration;

  /// 比特率（bps）
  final int? bitrate;

  /// 采样率（Hz）
  final int? sampleRate;

  /// 位深（bit），解析库不支持时为 null
  final int? bitDepth;

  /// 文件大小（字节）
  final int? size;

  /// 文件格式（扩展名，如 mp3 / flac）
  final String? format;

  /// 编码（如 ID3v2 / Vorbis 等，取自元数据格式）
  final String? codec;

  /// 添加时间（毫秒时间戳，入库时间）
  final int? dateAdded;

  /// 文件修改时间（毫秒时间戳）
  final int? dateModified;

  /// 是否含内嵌封面
  final bool hasArtwork;

  /// 内嵌歌词文本（通常为 LRC 格式，含 [mm:ss.xx] 时间戳）
  final String? lyrics;

  /// 音乐来源标识（媒体库为 'media_library'，文件夹扫描为文件夹路径）
  final String? source;

  const Song({
    this.id,
    required this.title,
    required this.path,
    this.artist,
    this.album,
    this.albumArtist,
    this.trackNumber,
    this.duration,
    this.bitrate,
    this.sampleRate,
    this.bitDepth,
    this.size,
    this.format,
    this.codec,
    this.dateAdded,
    this.dateModified,
    this.hasArtwork = false,
    this.lyrics,
    this.source,
  });

  factory Song.fromMap(Map<String, dynamic> m) => Song(
        id: m['id'] as int?,
        title: (m['title'] as String?) ?? '未知歌曲',
        path: m['path'] as String,
        artist: m['artist'] as String?,
        album: m['album'] as String?,
        albumArtist: m['albumArtist'] as String?,
        trackNumber: m['trackNumber'] as int?,
        duration: m['duration'] as int?,
        bitrate: m['bitrate'] as int?,
        sampleRate: m['sampleRate'] as int?,
        bitDepth: m['bitDepth'] as int?,
        size: m['size'] as int?,
        format: m['format'] as String?,
        codec: m['codec'] as String?,
        dateAdded: m['dateAdded'] as int?,
        dateModified: m['dateModified'] as int?,
        hasArtwork: (m['hasArtwork'] as int? ?? 0) == 1,
        lyrics: m['lyrics'] as String?,
        source: m['source'] as String?,
      );

  Map<String, dynamic> toMap() => {
        if (id != null) 'id': id,
        'title': title,
        'path': path,
        'artist': artist,
        'album': album,
        'albumArtist': albumArtist,
        'trackNumber': trackNumber,
        'duration': duration,
        'bitrate': bitrate,
        'sampleRate': sampleRate,
        'bitDepth': bitDepth,
        'size': size,
        'format': format,
        'codec': codec,
        'dateAdded': dateAdded,
        'dateModified': dateModified,
        'hasArtwork': hasArtwork ? 1 : 0,
        'lyrics': lyrics,
        'source': source,
      };

  Song copyWith({int? id, String? source}) => Song(
        id: id ?? this.id,
        title: title,
        path: path,
        artist: artist,
        album: album,
        albumArtist: albumArtist,
        trackNumber: trackNumber,
        duration: duration,
        bitrate: bitrate,
        sampleRate: sampleRate,
        bitDepth: bitDepth,
        size: size,
        format: format,
        codec: codec,
        dateAdded: dateAdded,
        dateModified: dateModified,
        hasArtwork: hasArtwork,
        lyrics: lyrics,
        source: source ?? this.source,
      );

  /// 归一化的歌曲身份键（用于跨来源判定"同一首歌"）：标题|艺术家|专辑
  String get identityKey =>
      '${title.trim().toLowerCase()}|${(artist ?? '').trim().toLowerCase()}|${(album ?? '').trim().toLowerCase()}';

  /// 展示用艺术家（空值回退）
  String get displayArtist =>
      (artist == null || artist!.trim().isEmpty) ? '未知艺术家' : artist!;

  /// 展示用专辑（空值回退）
  String get displayAlbum =>
      (album == null || album!.trim().isEmpty) ? '未知专辑' : album!;

  /// 时长格式化 mm:ss
  String get durationText {
    if (duration == null || duration! <= 0) return '--:--';
    final d = Duration(milliseconds: duration!);
    final m = d.inMinutes;
    final s = d.inSeconds % 60;
    return '$m:${s.toString().padLeft(2, '0')}';
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Song && runtimeType == other.runtimeType && path == other.path;

  @override
  int get hashCode => path.hashCode;
}
