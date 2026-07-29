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

  /// 来源类型：'local'（本地）或 'subsonic'（远程）
  final String? sourceType;

  /// Subsonic 远程歌曲 ID
  final String? remoteId;

  /// 远程流媒体 URL（未缓存时用于流式播放）
  final String? remoteStreamUrl;

  /// 本地缓存文件路径（已缓存时非 null）
  final String? cachedPath;

  /// 缓存时间戳（毫秒，用于 LRU 淘汰）
  final int? cacheTimestamp;

  /// 本地缓存的封面文件路径（远程歌曲扫描时下载）
  final String? cachedArtworkPath;

  /// Subsonic 封面 ID（如 "al-123"，用于按需获取封面）
  final String? coverArtId;

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
    this.sourceType,
    this.remoteId,
    this.remoteStreamUrl,
    this.cachedPath,
    this.cacheTimestamp,
    this.cachedArtworkPath,
    this.coverArtId,
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
        sourceType: m['sourceType'] as String?,
        remoteId: m['remoteId'] as String?,
        remoteStreamUrl: m['remoteStreamUrl'] as String?,
        cachedPath: m['cachedPath'] as String?,
        cacheTimestamp: m['cacheTimestamp'] as int?,
        cachedArtworkPath: m['cachedArtworkPath'] as String?,
        coverArtId: m['coverArtId'] as String?,
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
        'sourceType': sourceType,
        'remoteId': remoteId,
        'remoteStreamUrl': remoteStreamUrl,
        'cachedPath': cachedPath,
        'cacheTimestamp': cacheTimestamp,
        'cachedArtworkPath': cachedArtworkPath,
        'coverArtId': coverArtId,
      };

  Song copyWith({
    int? id,
    String? source,
    String? sourceType,
    String? remoteId,
    String? remoteStreamUrl,
    String? cachedPath,
    int? cacheTimestamp,
    String? cachedArtworkPath,
    String? coverArtId,
    String? lyrics,
  }) =>
      Song(
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
        lyrics: lyrics ?? this.lyrics,
        source: source ?? this.source,
        sourceType: sourceType ?? this.sourceType,
        remoteId: remoteId ?? this.remoteId,
        remoteStreamUrl: remoteStreamUrl ?? this.remoteStreamUrl,
        cachedPath: cachedPath ?? this.cachedPath,
        cacheTimestamp: cacheTimestamp ?? this.cacheTimestamp,
        cachedArtworkPath: cachedArtworkPath ?? this.cachedArtworkPath,
        coverArtId: coverArtId ?? this.coverArtId,
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

  /// 是否为远程 Subsonic 歌曲
  bool get isRemote => sourceType == 'subsonic';

  /// 是否已缓存到本地
  bool get isCached => cachedPath != null && cachedPath!.isNotEmpty;

  /// 获取可播放的路径：已缓存返回本地路径，否则返回 path（远程 URL 由播放器处理）
  String get playablePath => isCached ? cachedPath! : path;

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
