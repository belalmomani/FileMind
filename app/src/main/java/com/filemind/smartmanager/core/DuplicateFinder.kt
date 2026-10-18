package com.filemind.smartmanager.core

import java.io.File

data class DuplicateGroup(val files: List<File>, val sizeEach: Long)

/** Exact duplicates only: group by size, then by SHA-256. Read-only — never deletes anything. */
object DuplicateFinder {
    fun find(files: List<File>, excludedDirs: Set<File> = emptySet(), onUnreadable: (File) -> Unit = {}): List<DuplicateGroup> =
        files.filter { f -> f.isFile && excludedDirs.none { f.canonicalPath.startsWith(it.canonicalPath + File.separator) } }
            .groupBy { it.length() }
            .filter { it.value.size > 1 && it.key > 0 }
            .flatMap { (size, same) ->
                same.mapNotNull { f -> try { Hashing.sha256(f) to f } catch (e: Exception) { onUnreadable(f); null } }
                    .groupBy({ it.first }, { it.second })
                    .filter { it.value.size > 1 }
                    .map { DuplicateGroup(it.value, size) }
            }
}
