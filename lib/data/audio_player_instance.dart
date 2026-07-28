import 'package:audioplayers/audioplayers.dart';

/// 全局唯一的 AudioPlayer 实例
///
/// 由 [SongData] 与 [MpAudioHandler] 共用，避免重复创建播放器
/// 并天然保证「列表/播放页/通知栏」控制的是同一路音频。
final AudioPlayer sharedAudioPlayer = AudioPlayer();
