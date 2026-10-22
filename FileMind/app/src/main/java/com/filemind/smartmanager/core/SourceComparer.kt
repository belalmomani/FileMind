package com.filemind.smartmanager.core

data class RelFile(val path: String, val size: Long, val modifiedMs: Long?, val uri: String? = null)
enum class CompareStatus { ONLY_A, ONLY_B, IDENTICAL, DIFFERENT, UNREADABLE }
data class CompareRow(val path: String, val status: CompareStatus, val a: RelFile?, val b: RelFile?)

/**
 * Read-only comparison of two file sets keyed by relative path (works for folders and archives).
 * - Different size ⇒ DIFFERENT without hashing. Same size ⇒ compared by content hash.
 * - A file absent from one side is NOT reported missing if its folder could not be scanned there (⇒ UNREADABLE).
 * - failedDirs: relative folder paths that failed to scan ("" = the whole source failed).
 */
object SourceComparer {
    private fun inFailed(path: String, failed: List<String>) =
        failed.any { it.isEmpty() || path == it || path.startsWith("$it/") }

    fun compare(
        a: List<RelFile>, b: List<RelFile>,
        hashA: (RelFile) -> String?, hashB: (RelFile) -> String?,
        failedDirsA: List<String> = emptyList(), failedDirsB: List<String> = emptyList(),
    ): List<CompareRow> {
        val ma = a.associateBy { it.path }; val mb = b.associateBy { it.path }
        return (ma.keys + mb.keys).sorted().map { p ->
            val x = ma[p]; val y = mb[p]
            val st = when {
                x != null && y == null -> if (inFailed(p, failedDirsB)) CompareStatus.UNREADABLE else CompareStatus.ONLY_A
                x == null && y != null -> if (inFailed(p, failedDirsA)) CompareStatus.UNREADABLE else CompareStatus.ONLY_B
                x!!.size != y!!.size -> CompareStatus.DIFFERENT
                else -> {
                    val ha = hashA(x); val hb = hashB(y)
                    if (ha == null || hb == null) CompareStatus.UNREADABLE else if (ha == hb) CompareStatus.IDENTICAL else CompareStatus.DIFFERENT
                }
            }
            CompareRow(p, st, x, y)
        }
    }
}
