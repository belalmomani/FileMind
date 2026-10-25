package com.filemind.smartmanager.core

/** One trashed item. deletedAtMs == 0 means "unknown" (index missing): such items are never auto-purged. */
data class TrashRecord(val id: String, val deletedAtMs: Long, val originalParentDocId: String, val name: String, val size: Long, val isDir: Boolean)

/** Plain-text index kept inside the trash folder (no database schema change). One tab-separated line per item; unknown/bad lines are ignored. */
object TrashIndex {
    private const val HEADER = "FM-TRASH-1"

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); 'r' -> sb.append('\r'); '\\' -> sb.append('\\'); else -> sb.append(s[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(list: List<TrashRecord>): String =
        (listOf(HEADER) + list.map { listOf(it.id, it.deletedAtMs, esc(it.originalParentDocId), esc(it.name), it.size, if (it.isDir) 1 else 0).joinToString("\t") }).joinToString("\n") + "\n"

    fun decode(text: String?): List<TrashRecord> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        return lines.drop(1).mapNotNull { l ->
            val p = l.split('\t')
            if (p.size != 6) return@mapNotNull null
            val at = p[1].toLongOrNull() ?: return@mapNotNull null
            val size = p[4].toLongOrNull() ?: return@mapNotNull null
            if (p[0].isBlank()) return@mapNotNull null
            TrashRecord(p[0], at, unesc(p[2]), unesc(p[3]), size, p[5] == "1")
        }
    }

    fun isExpired(r: TrashRecord, nowMs: Long, days: Int): Boolean = days > 0 && r.deletedAtMs > 0 && nowMs - r.deletedAtMs > days * 86_400_000L

    /** Unique batch id: time + counter, never reusing an id already present. */
    fun newId(nowMs: Long, taken: Set<String>): String {
        var n = 1
        while ("$nowMs-$n" in taken) n++
        return "$nowMs-$n"
    }
}
