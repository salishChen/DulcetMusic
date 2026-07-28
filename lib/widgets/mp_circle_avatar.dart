import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_artwork.dart';

Widget avatar(Song song, MaterialColor color) {
  return Material(
    borderRadius: BorderRadius.circular(20.0),
    elevation: 3.0,
    color: Colors.transparent,
    child: MpArtwork(
      song.path,
      width: 50.0,
      height: 50.0,
      borderRadius: BorderRadius.circular(20.0),
      fit: BoxFit.cover,
    ),
  );
}
