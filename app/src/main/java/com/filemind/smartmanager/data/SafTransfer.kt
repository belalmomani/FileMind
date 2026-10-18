package com.filemind.smartmanager.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import com.filemind.smartmanager.core.ConflictDecision
import com.filemind.smartmanager.core.ConflictRelation
import com.filemind.smartmanager.core.FileEntry
import com.filemind.smartmanager.core.TransferStatus
import java.io.IOException
import java.security.MessageDigest

data class UiConflict(
    val name: String, val oldSize: Long, val newSize: Long, val oldModified: Long?, val newModified: Long?,
    val oldLocation: String, val newLocation: String, val relation: ConflictRelation,
)
data class OpResult(val entry: FileEntry, val status: TransferStatus, val reason: String?, val newPath: String)

private data class Existing(val uri: Uri, val size: Long, val modified: Long?)

/**
 * Copy/move into a SAF folder. Replace = write temp doc → verify size+SHA-256 → delete old → rename temp.
 * NOT atomic (SAF gives no guarantee). If the final rename fails, the new copy is kept under a temp name — no data is lost.
 */
class SafTransfer(ctx: Context, private val ask: suspend (UiConflict) -> ConflictDecision) {
    private val cr = ctx.contentResolver

    suspend fun run(sources: List<FileEntry>, destTree: Uri, move: Boolean): List<OpResult> =
        runInDir(sources, destTree, DocumentsContract.getTreeDocumentId(destTree), move)

    suspend fun runInDir(sources: List<FileEntry>, destTree: Uri, destId: String, move: Boolean): List<OpResult> {
        val destDir = DocumentsContract.buildDocumentUriUsingTree(destTree, destId)
        val out = ArrayList<OpResult>()
        for ((i, s) in sources.withIndex()) {
            val r = one(s, destTree, destId, destDir, move)
            out += r
            if (r.status == TransferStatus.CANCELLED) {
                sources.drop(i + 1).forEach { out += OpResult(it, TransferStatus.CANCELLED, "أُلغيت العملية", "") }
                break
            }
        }
        return out
    }

    private fun children(tree: Uri, id: String): Map<String, Existing> {
        val m = HashMap<String, Existing>()
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                m[c.getString(1) ?: continue] = Existing(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                    if (c.isNull(2)) 0 else c.getLong(2), if (c.isNull(3)) null else c.getLong(3))
            }
        }
        return m
    }

    private fun sha(uri: Uri): String {
        val md = MessageDigest.getInstance("SHA-256")
        (cr.openInputStream(uri) ?: throw IOException("تعذر فتح الملف")).use { s ->
            val b = ByteArray(64 * 1024)
            while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeOf(uri: Uri): Long =
        cr.query(uri, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else -1 } ?: -1

    private fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun deleteAny(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(cr, uri)
    } catch (e: Exception) { try { cr.delete(uri, null, null) > 0 } catch (e2: Exception) { false } }

    private suspend fun one(s: FileEntry, tree: Uri, destId: String, destDir: Uri, move: Boolean): OpResult {
        val src = Uri.parse(s.path)
        val existing = children(tree, destId)[s.name]
        if (existing != null && existing.uri == src) return OpResult(s, TransferStatus.SKIPPED, "المصدر والوجهة هما الملف نفسه", s.path)
        if (existing != null) {
            val rel = try {
                if (existing.size != s.size) ConflictRelation.DIFFERENT_CONTENT
                else if (sha(existing.uri) == sha(src)) ConflictRelation.IDENTICAL_CONTENT else ConflictRelation.DIFFERENT_CONTENT
            } catch (e: Exception) { ConflictRelation.UNREADABLE }
            val d = ask(UiConflict(s.name, existing.size, s.size, existing.modified, s.modifiedMs, destId, s.parent, rel))
            if (d == ConflictDecision.KEEP_EXISTING) return OpResult(s, TransferStatus.SKIPPED, "تم الاحتفاظ بالملف الموجود", existing.uri.toString())
            if (d == ConflictDecision.CANCEL) return OpResult(s, TransferStatus.CANCELLED, null, "")
        }
        var tmpDoc: Uri? = null; var oldDeleted = false
        return try {
            val tmpName = if (existing != null) ".${s.name}.filemind.tmp" else s.name
            val doc = DocumentsContract.createDocument(cr, destDir, mime(s.name), tmpName) ?: throw IOException("تعذر إنشاء الملف في الوجهة (صلاحيات؟)")
            tmpDoc = doc
            (cr.openInputStream(src) ?: throw IOException("تعذر قراءة المصدر")).use { i ->
                (cr.openOutputStream(doc, "w") ?: throw IOException("تعذر الكتابة في الوجهة")).use { o -> i.copyTo(o) }
            }
            if (sizeOf(doc) != s.size || sha(doc) != sha(src)) throw IOException("فشل التحقق من الملف المكتوب")
            var finalUri = doc
            if (existing != null) {
                oldDeleted = deleteAny(existing.uri)
                if (!oldDeleted) throw IOException("تعذر حذف الملف القديم؛ لم يتم الاستبدال")
                finalUri = DocumentsContract.renameDocument(cr, doc, s.name)
                    ?: return OpResult(s, TransferStatus.FAILED, "اكتمل التحقق لكن تعذرت إعادة التسمية؛ النسخة الجديدة محفوظة باسم مؤقت", doc.toString())
            }
            val note = if (move && !deleteAny(src)) "تم النسخ لكن تعذر حذف المصدر" else if (existing != null) "تم الاستبدال" else null
            OpResult(s, TransferStatus.DONE, note, finalUri.toString())
        } catch (e: Exception) {
            if (tmpDoc != null && !oldDeleted) deleteAny(tmpDoc) // old file untouched
            OpResult(s, TransferStatus.FAILED, e.message ?: "خطأ غير معروف", "")
        }
    }
}
