package com.mtechviral.musicfinderexample.core.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.mtechviral.musicfinderexample.core.database.DatabaseHelper
import com.mtechviral.musicfinderexample.core.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 扫描进度回调：processed 已处理数，total 总数，failed 失败数。
 * 对应原 Flutter 端 `typedef ScanProgress`。
 */
typealias ScanProgress = (processed: Int, total: Int, failed: Int) -> Unit

/** 扫描结果，对应原 Flutter 端 `ScanResult` */
data class ScanResult(
    val added: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
)

/**
 * 音乐扫描与元数据入库服务。
 *
 * 对应原 Flutter 工程 `lib/data/metadata_service.dart`：
 * 两种扫描来源（安卓媒体库 / 指定文件夹），元数据统一从文件本体读取后批量写库；
 * 分批大小 30（`batchSize = 30`），每批完成后回调进度。
 */
class MetadataScanService(
    private val context: Context,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {

    /**
     * 扫描安卓媒体库：仅取文件路径列表，元数据仍从文件读取。
     * 对应 Dart 端 `_audioQuery.querySongs(uriType: EXTERNAL)`。
     */
    suspend fun scanMediaLibrary(onProgress: ScanProgress? = null): ScanResult {
        val paths = withContext(Dispatchers.IO) { queryMediaStorePaths() }
        if (paths.isEmpty()) {
            Log.w(TAG, "媒体库未查询到受支持的音频文件")
            return ScanResult()
        }
        // 与 Dart 端一致：以 'media_library' 作为来源标识
        return scanPaths(paths, Song.SOURCE_MEDIA_LIBRARY, onProgress)
    }

    /** 扫描指定文件夹（递归遍历所有子目录，不跟随符号链接） */
    suspend fun scanFolder(folderPath: String, onProgress: ScanProgress? = null): ScanResult {
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) {
            Log.w(TAG, "文件夹不存在: $folderPath")
            return ScanResult()
        }
        val paths = withContext(Dispatchers.IO) {
            try {
                dir.walkTopDown()
                    .onFail { _, e -> Log.w(TAG, "遍历失败: ${e.message}") }
                    .filter { it.isFile }
                    .map { it.absolutePath }
                    .filter { AudioFormats.isSupported(it) }
                    .toList()
            } catch (e: Exception) {
                Log.w(TAG, "遍历文件夹失败: ${e.message}")
                emptyList()
            }
        }
        // 以文件夹路径作为来源标识：同一文件夹再次扫描视为同源
        return scanPaths(paths, folderPath, onProgress)
    }

    /**
     * 分批解析文件元数据并事务批量入库。
     */
    private suspend fun scanPaths(
        paths: List<String>,
        source: String?,
        onProgress: ScanProgress?,
    ): ScanResult {
        if (paths.isEmpty()) return ScanResult()

        var processed = 0
        var failed = 0
        var added = 0
        val total = paths.size

        var i = 0
        while (i < paths.size) {
            val end = minOf(i + batchSize, paths.size)
            val chunk = paths.subList(i, end)

            // 元数据解析放到 IO 线程，避免 UI 卡顿（对应 Dart 端的 compute isolate）
            val songs = withContext(Dispatchers.IO) {
                val parsed = ArrayList<Song>(chunk.size)
                var failCount = 0
                for (p in chunk) {
                    val song = MetadataParser.parse(p)
                    if (song == null) failCount++ else parsed.add(song)
                }
                parsed to failCount
            }
            failed += songs.second
            // 第二十六轮需求 3：扫描入库前先剔除排除列表中的音乐
            // （被排除的单曲，或歌手被整位排除的歌曲），其余照常入库。
            val accepted = ExclusionFilter.filter(songs.first)
            added += DatabaseHelper.insertSongs(accepted, source)

            processed += chunk.size
            onProgress?.invoke(processed, total, failed)
            Log.d(TAG, "扫描进度 $processed/$total，失败 $failed")
            i = end
        }

        return ScanResult(added = added, failed = failed, total = total)
    }

    /** 查询 MediaStore 中的音频文件绝对路径（过滤支持的扩展名） */
    private fun queryMediaStorePaths(): List<String> {
        val out = ArrayList<String>()
        try {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (cursor.moveToNext()) {
                    val data = cursor.getString(dataIdx)
                    if (!data.isNullOrEmpty() && AudioFormats.isSupported(data)) {
                        out.add(data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询媒体库失败: ${e.message}")
        }
        return out
    }

    companion object {
        private const val TAG = "MetadataScanService"
        private const val DEFAULT_BATCH_SIZE = 30
    }
}
