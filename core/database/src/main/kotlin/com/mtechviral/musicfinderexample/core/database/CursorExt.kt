package com.mtechviral.musicfinderexample.core.database

import android.database.Cursor

/**
 * Cursor 通用扩展：把 `while (moveToNext())` 样板收敛为 `mapAll { }`，
 * 并保证游标一定被关闭（对应 Dart 端 sqflite 返回 List<Map> 的语义）。
 */
internal inline fun <T> Cursor.mapAll(mapper: (Cursor) -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) {
        out.add(mapper(this))
    }
    return out
}

/** 取指定列，未找到或为 NULL 时返回 null */
internal fun Cursor.stringOrNull(column: String): String? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getString(i)
}

internal fun Cursor.longOrNull(column: String): Long? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getLong(i)
}

internal fun Cursor.intOrNull(column: String): Int? {
    val i = getColumnIndex(column)
    return if (i < 0 || isNull(i)) null else getInt(i)
}
