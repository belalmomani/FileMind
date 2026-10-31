package com.filemind.smartmanager.core

class UsageEntry(val path: String, val count: Int, val lastMs: Long)

/** "Most used files": how many times each file was opened from inside FileMind. Local counters only; nothing leaves the device. */
object UsageStats {
    const val MAX = 300

    private fun order() = compareByDescending<UsageEntry> { it.count }.thenByDescending { it.lastMs }.thenBy { it.path }

    fun record(list: List<UsageEntry>, path: String, now: Long, max: Int = MAX): List<UsageEntry> {
        val old = list.firstOrNull { it.path == path }
        val updated = list.filter { it.path != path } + UsageEntry(path, (old?.count ?: 0) + 1, now)
        return if (updated.size <= max) updated else updated.sortedWith(order()).take(max)
    }

    fun top(list: List<UsageEntry>, n: Int): List<UsageEntry> = list.sortedWith(order()).take(n)
    fun remove(list: List<UsageEntry>, path: String): List<UsageEntry> = list.filter { it.path != path }

    fun encode(list: List<UsageEntry>): String =
        list.joinToString("") { "${it.path.replace('\t', ' ').replace('\n', ' ')}\t${it.count}\t${it.lastMs}\n" }

    fun decode(text: String?): List<UsageEntry> = text.orEmpty().lines().mapNotNull { l ->
        val p = l.split('\t')
        if (p.size != 3 || p[0].isBlank()) return@mapNotNull null
        val c = p[1].toIntOrNull(); val t = p[2].toLongOrNull()
        if (c == null || t == null || c < 1) null else UsageEntry(p[0], c, t)
    }
}
