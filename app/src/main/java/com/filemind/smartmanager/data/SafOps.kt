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
}
