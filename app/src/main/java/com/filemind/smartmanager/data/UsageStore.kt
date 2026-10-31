package com.filemind.smartmanager.data

import android.content.Context
import com.filemind.smartmanager.core.UsageEntry
import com.filemind.smartmanager.core.UsageStats
import java.io.File

/** Private on-device file with the open counters behind "most used files". Cleared from Settings. */
object UsageStore {
    private const val FILE = "usage_v1.tsv"

    fun load(ctx: Context): List<UsageEntry> =
        runCatching { File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.let { UsageStats.decode(it) } ?: emptyList() }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<UsageEntry>) {
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(UsageStats.encode(list))
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
    }

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, FILE).delete() }; runCatching { File(ctx.filesDir, "$FILE.tmp").delete() } }
}
