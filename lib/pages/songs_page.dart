import 'package:flutter/material.dart';
import 'package:flute_example/data/models/song.dart';
import 'package:flute_example/widgets/mp_inherited.dart';
import 'package:flute_example/widgets/mp_nav_scaffold.dart';
import 'package:flute_example/widgets/mp_song_list_item.dart';

/// 歌曲一级页面（首页）：列出数据库中的所有音乐
class SongsPage extends StatelessWidget {
  const SongsPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final rootIW = MPInheritedWidget.of(context);
    final songData = rootIW.songData;

    return Scaffold(
      appBar: buildPrimaryAppBar(context, '歌曲'),
      body: rootIW.isLoading
          ? const Center(child: CircularProgressIndicator())
          : songData == null
              ? Container()
              : ValueListenableBuilder<List<Song>>(
                  valueListenable: songData.notifier,
                  builder: (context, songs, _) {
                    if (songs.isEmpty) {
                      return _emptyView(context);
                    }
                    return Scrollbar(
                      child: ListView.builder(
                        itemCount: songs.length,
                        itemBuilder: (context, index) => MpSongListItem(
                          song: songs[index],
                          queue: songs,
                        ),
                      ),
                    );
                  },
                ),
    );
  }

  /// 空状态：引导用户去扫描音乐
  Widget _emptyView(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 96.0,
            height: 96.0,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: LinearGradient(colors: [
                const Color(0xFF7C4DFF).withOpacity(0.3),
                const Color(0xFF18D2C7).withOpacity(0.3),
              ]),
            ),
            child:
                const Icon(Icons.library_music, size: 44.0, color: Colors.white54),
          ),
          const SizedBox(height: 16.0),
          const Text('曲库为空', style: TextStyle(fontSize: 18.0)),
          const SizedBox(height: 8.0),
          const Text('去「扫描音乐」页面扫描媒体库或文件夹',
              style: TextStyle(color: Color(0xFF8A8A99))),
          const SizedBox(height: 16.0),
          FilledButton.icon(
            icon: const Icon(Icons.manage_search),
            label: const Text('去扫描'),
            onPressed: () => MPNavScaffold.of(context)?.selectPage(4),
          ),
        ],
      ),
    );
  }
}
