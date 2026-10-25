package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

data class BItem(val docId: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long?, val uri: String, val mime: String?) {
    val ext: String? get() = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
    val isHidden: Boolean get() = name.startsWith(".")
}

/** Lists ONE folder of a granted SAF tree. A refused/failed query is reported as an error — never as an empty folder. */
object FolderLister {
    const val ACCESS_ERROR = "لا يمكن الوصول إلى هذا المجلد بهذه الصلاحيات"
    class Result(val items: List<BItem>, val error: String?)

    fun list(cr: ContentResolver, tree: Uri, docId: String): Result {
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        return try {
            val c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)
                ?: return Result(emptyList(), ACCESS_ERROR)
            val out = ArrayList<BItem>()
            c.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2)
                    out += BItem(id, name, mime == Document.MIME_TYPE_DIR, if (it.isNull(3)) 0 else it.getLong(3),
                        if (it.isNull(4)) null else it.getLong(4).takeIf { m -> m > 0 },
                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), mime)
                }
            }
            Result(out, null)
        } catch (e: Exception) { Result(emptyList(), ACCESS_ERROR) }
    }

    fun createFolder(cr: ContentResolver, tree: Uri, parentDocId: String, name: String): Uri? =
        try { DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, parentDocId), Document.MIME_TYPE_DIR, name) } catch (e: Exception) { null }

    class WalkResult(val files: List<Pair<BItem, String>>, val emptyDirs: List<String>, val failed: List<String>)

    /** Recursively lists a folder: files with their relative directory (starting at [rootName]), empty dirs, and folders that failed to list. */
    fun walk(cr: ContentResolver, tree: Uri, rootDocId: String, rootName: String): WalkResult {
        val files = ArrayList<Pair<BItem, String>>(); val empties = ArrayList<String>(); val failed = ArrayList<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { add(rootDocId to rootName) }
        while (stack.isNotEmpty()) {
            val (id, rel) = stack.removeLast()
            val r = list(cr, tree, id)
            if (r.error != null) { failed += rel; continue }
            if (r.items.isEmpty()) empties += rel
            for (i in r.items) { if (i.name == TrashStore.TRASH_DIR) continue; if (i.isDir) stack.add(i.docId to "$rel/${i.name}") else files += i to rel }
        }
        return WalkResult(files, empties, failed)
    }
}
