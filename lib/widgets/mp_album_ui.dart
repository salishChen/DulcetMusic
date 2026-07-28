import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_artwork.dart';

class AlbumUI extends StatefulWidget {
  final Song song;
  final Duration? position;
  final Duration? duration;

  /// 目标封面边长（会按屏幕宽度做上限约束，避免溢出）
  final double size;

  const AlbumUI(this.song, this.duration, this.position, {this.size = 250.0});
  @override
  AlbumUIState createState() => AlbumUIState();
}

class AlbumUIState extends State<AlbumUI> with SingleTickerProviderStateMixin {
  late Animation<double> animation;
  late AnimationController animationController;

  @override
  void initState() {
    super.initState();
    animationController = AnimationController(
        vsync: this, duration: const Duration(seconds: 1));
    animation =
        CurvedAnimation(parent: animationController, curve: Curves.elasticOut);
    animation.addListener(() => setState(() {}));
    animationController.forward();
  }

  @override
  void dispose() {
    animationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    // 按屏幕宽度约束封面尺寸，避免大封面在窄屏溢出
    final maxSide = MediaQuery.of(context).size.width - 48.0;
    final target = min(widget.size, maxSide);

    // 不再使用 Hero 包裹：避免从「正在播放」页返回主页时封面飞回列表项的动画，
    // 返回时封面随整个播放页下滑淡出即可，不再单独飞行。
    return SizedBox.fromSize(
      size: Size(animation.value * target, animation.value * target),
      child: Material(
        borderRadius: BorderRadius.circular(12.0),
        elevation: 5.0,
        color: Colors.transparent,
        child: MpArtwork(
          // 用歌曲路径作为 key，切歌时强制重建并重新取封面
          key: ValueKey(widget.song.path),
          widget.song.path,
          borderRadius: BorderRadius.circular(12.0),
          fit: BoxFit.cover,
        ),
      ),
    );
  }
}
