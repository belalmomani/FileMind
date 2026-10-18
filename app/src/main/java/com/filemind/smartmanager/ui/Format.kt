package com.filemind.smartmanager.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(Locale.ROOT, b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(Locale.ROOT, b / 1024.0)
    else -> "$b B"
}
fun fmtDate(ms: Long?): String = if (ms == null) "غير معروف" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(ms))
