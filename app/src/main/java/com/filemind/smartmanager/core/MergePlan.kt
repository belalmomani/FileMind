package com.filemind.smartmanager.core

/**
 * Pure planning for "copy what is missing" between two folders. Never plans overwrite or delete:
 * only files present on one side and absent on the other (ONLY_A / ONLY_B) with a readable source URI.
 * DIFFERENT / UNREADABLE rows are never touched (they need the user's own decision).
 */
object MergePlan {
    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    /** fromA = copy files found only in A into B; otherwise files found only in B into A. */
    fun missing(rows: List<CompareRow>, fromA: Boolean): List<CompareRow> {
        val wanted = if (fromA) CompareStatus.ONLY_A else CompareStatus.ONLY_B
        return rows.filter { r -> r.status == wanted && (if (fromA) r.a else r.b)?.uri != null }
    }
}
