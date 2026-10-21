package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.security.MessageDigest

object SafOps {
    fun sha256(cr: ContentResolver, uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        (cr.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")).use { s ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Works only for documents inside a granted SAF tree. Returns null on refusal. */
    fun rename(cr: ContentResolver, uri: Uri, newName: String): Uri? =
        try { DocumentsContract.renameDocument(cr, uri, newName) } catch (e: Exception) { null }

    /** Finds or creates nested folders under the tree root; returns the final folder's document id. */
    fun ensureFolder(cr: ContentResolver, tree: Uri, segments: List<String>): String {
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (seg in segments) {
            var found: String? = null
            cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == seg && c.getString(2) == Document.MIME_TYPE_DIR) { found = c.getString(0); break }
                }
            }
            id = found ?: DocumentsContract.getDocumentId(
                DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, id), Document.MIME_TYPE_DIR, seg)
                    ?: throw IOException("تعذر إنشاء المجلد $seg"))
        }
        return id
    }

    fun childExists(cr: ContentResolver, tree: Uri, dirId: String, name: String): Boolean {
        var found = false
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { if (c.getString(0) == name) { found = true; break } }
        }
        return found
    }

    fun createFile(cr: ContentResolver, tree: Uri, dirId: String, name: String): Uri? {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        return try { DocumentsContract.createDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, dirId), mime, name) } catch (e: Exception) { null }
    }

    /** Deletes a folder only if it (and every subfolder) is completely empty. Never deletes a folder that still holds files. */
    fun removeEmptyTree(cr: ContentResolver, tree: Uri, docId: String): Boolean {
        val r = FolderLister.list(cr, tree, docId)
        if (r.error != null) return false
        var remaining = r.items.size
        for (i in r.items) { if (i.isDir && removeEmptyTree(cr, tree, i.docId)) remaining-- }
        return if (remaining == 0) runCatching { DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, docId)) }.getOrDefault(false) else false
    }
}
