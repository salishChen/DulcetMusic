import 'package:flutter/material.dart';
import 'package:flute_example/data/database_helper.dart';
import 'package:flute_example/data/song_data.dart';
import 'package:flute_example/data/playlist_data.dart';

class MPInheritedWidget extends InheritedWidget {
  final SongData? songData;
  final PlaylistData? playlistData;
  final DatabaseHelper dbHelper;
  final bool isLoading;
  final Widget child;

  const MPInheritedWidget(this.songData, this.playlistData, this.dbHelper,
      this.isLoading, this.child)
      : super(child: child);

  static MPInheritedWidget of(BuildContext context) {
    return context.dependOnInheritedWidgetOfExactType<MPInheritedWidget>()!;
  }

  @override
  bool updateShouldNotify(MPInheritedWidget oldWidget) =>
      songData != oldWidget.songData ||
      playlistData != oldWidget.playlistData ||
      isLoading != oldWidget.isLoading;
}
