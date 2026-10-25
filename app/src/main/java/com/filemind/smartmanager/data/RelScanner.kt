package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.filemind.smartmanager.core.RelFile

/** Like SafScanner but keeps RELATIVE paths (needed for comparing two trees). Failed folders are returned, never treated as empty. */
object RelScanner {
    fun scan(cr: ContentResolver, tree: Uri): Pair<List<RelFile>, List<String>> {
        val out = ArrayList<RelFile>(); val failed = ArrayList<String>()
        val stack = ArrayDeque<Pair<String, String>>().apply { add(DocumentsContract.getTreeDocumentId(tree) to "") }
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        while (stack.isNotEmpty()) {
            val (id, rel) = stack.removeLast()
            try {
                cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val cid = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (name == TrashStore.TRASH_DIR) continue   // never compare/copy our own trash
                        val path = if (rel.isEmpty()) name else "$rel/$name"
                        if (c.getString(2) == Document.MIME_TYPE_DIR) stack.add(cid to path)
                        else out += RelFile(path, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) null else c.getLong(4),
                            DocumentsContract.buildDocumentUriUsingTree(tree, cid).toString())
                    }
                } ?: failed.add(rel)
            } catch (e: Exception) { failed.add(rel) }
        }
        return out to failed
    }
}
