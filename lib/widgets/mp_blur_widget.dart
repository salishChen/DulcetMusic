import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_artwork.dart';

Widget blurWidget(Song song) {
  return Hero(
    tag: song.artist ?? '',
    child: MpArtwork(
      song.path,
      fit: BoxFit.cover,
    ),
  );
}
