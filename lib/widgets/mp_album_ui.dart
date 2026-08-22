import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_artwork.dart';

class AlbumUI extends StatelessWidget {
  final Song song;
  final Duration? position;
  final Duration? duration;

  /// 目标封面边长（会按屏幕宽度做上限约束，避免溢出）
  final double size;

  const AlbumUI(this.song, this.duration, this.position, {this.size = 250.0});

  @override
  Widget build(BuildContext context) {
    // 按屏幕宽度约束封面尺寸，避免大封面在窄屏溢出
    final maxSide = MediaQuery.of(context).size.width - 48.0;
    final side = min(size, maxSide);

    return SizedBox(
      width: side,
      height: side,
      child: Material(
        borderRadius: BorderRadius.circular(12.0),
        elevation: 5.0,
        color: Colors.transparent,
        child: MpArtwork(
          // 用 path + cachedArtworkPath 组合 key，封面缓存完成时强制重建
          key: ValueKey('${song.path}_${song.cachedArtworkPath}'),
          song.path,
          cachedArtworkPath: song.cachedArtworkPath,
          songId: song.id,
          coverArtId: song.coverArtId,
          borderRadius: BorderRadius.circular(12.0),
          fit: BoxFit.cover,
        ),
      ),
    );
  }
}
