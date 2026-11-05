package com.filemind.smartmanager.data

import android.os.Environment
import com.filemind.smartmanager.core.FileEntry
import java.io.File
import java.nio.file.Files

class FsScanResult(val files: List<FileEntry>, val emptyDirs: List<String>)

/**
 * Walks the REAL shared storage like a normal file manager. Needs "all files access". It finds what MediaStore hides from apps on Android 11+:
 * documents, APK, archives, hidden files and folders that hold no file at all. Entries use file: URIs (opened through a FileProvider).
 * Skipped: the top-level Android folder (app-private, blocked by the system), our own trash, symbolic links.
 */
object FsScanner {
    const val MAX_FILES = 400_000
    private const val TRASH_DIR = ".FileMind-Trash"   // same name as TrashStore.TRASH_DIR (kept literal so this scanner stays a plain JVM class)
    private val SKIP_TOP = setOf("Android")

    fun scan(root: File = Environment.getExternalStorageDirectory()): FsScanResult {
        val files = ArrayList<FileEntry>(); val empties = ArrayList<String>()
        /** True when at least one file lives somewhere below [dir]. An unreadable folder is never claimed to be empty. */
        fun walk(dir: File, rel: String): Boolean {
            val children = dir.listFiles() ?: return true
            var any = false
            for (c in children) {
                if (files.size >= MAX_FILES) return true
                val name = c.name
                if (c.isDirectory) {
                    if ((rel.isEmpty() && name in SKIP_TOP) || name == TRASH_DIR) { any = true; continue }
                    if (runCatching { Files.isSymbolicLink(c.toPath()) }.getOrDefault(false)) continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (walk(c, childRel)) any = true else empties += childRel
                } else if (c.isFile) {
                    any = true
                    files += FileEntry(c.toURI().toString(), name, c.length(), c.lastModified().takeIf { it > 0 }, parent = if (rel.isEmpty()) "" else "$rel/")
                }
            }
            return any
        }
        walk(root, "")
        return FsScanResult(files, empties)
    }
}
