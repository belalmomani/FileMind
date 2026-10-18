package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import com.filemind.smartmanager.core.FileEntry

data class ScanResult(val files: List<FileEntry>, val failedFolders: List<String>)

/** Walks a user-granted SAF tree. Folders that fail are REPORTED, never treated as empty. */
class SafScanner(private val cr: ContentResolver) {
    fun scan(tree: Uri): ScanResult {
        val out = ArrayList<FileEntry>(); val failed = ArrayList<String>()
        val stack = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        while (stack.isNotEmpty()) {
            val pid = stack.removeLast()
            try {
                cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, pid), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == Document.MIME_TYPE_DIR) { stack.add(id); continue }
                        val mod = if (c.isNull(4)) null else c.getLong(4).takeIf { it > 0 }
                        out += FileEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, if (c.isNull(3)) 0 else c.getLong(3), mod, parent = pid)
                    }
                } ?: failed.add(pid)
            } catch (e: Exception) { failed.add(pid) }
        }
        return ScanResult(out, failed)
    }
}

/** Media files visible through READ_MEDIA_* (images/video/audio). EXIF-like date comes from MediaStore DATE_TAKEN only if present. */
class MediaScanner(private val cr: ContentResolver) {
    fun scan(): ScanResult {
        val base = MediaStore.Files.getContentUri("external")
        val rel = if (Build.VERSION.SDK_INT >= 29) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN, rel)
        val out = ArrayList<FileEntry>()
        return try {
            cr.query(base, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2); if (size <= 0) continue
                    val modS = c.getLong(3)
                    val taken = if (c.isNull(4)) 0L else c.getLong(4)
                    out += FileEntry(ContentUris.withAppendedId(base, c.getLong(0)).toString(), name, size,
                        if (modS > 0) modS * 1000 else null, exifDateMs = taken.takeIf { it > 0 }, parent = c.getString(5).orEmpty())
                }
            }
            ScanResult(out, emptyList())
        } catch (e: SecurityException) { ScanResult(emptyList(), listOf("MediaStore")) }
    }
}
