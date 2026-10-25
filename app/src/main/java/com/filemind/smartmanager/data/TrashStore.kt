package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.filemind.smartmanager.core.FileEntry
import com.filemind.smartmanager.core.TrashIndex
import com.filemind.smartmanager.core.TrashRecord

/**
 * In-app trash on top of folders the user granted (SAF). Each deleted item is MOVED (DocumentsContract.moveDocument: a rename on the same volume, no copy)
 * into "<granted folder>/.FileMind-Trash/<batch id>/" with its original name, plus a line in index.txt that remembers where it came from.
 * Nothing here ever deletes a file that is not already inside a .FileMind-Trash folder, except [deleteForever]/[purge] on trashed batches only.
 * If a provider cannot move, the file is left untouched and the caller reports it (never a silent permanent delete).
 */
object TrashStore {
    const val TRASH_DIR = ".FileMind-Trash"
    private const val INDEX = "index.txt"
    private const val RESTORE_DIR = "FileMind-Restored"

    class Item(val tree: Uri, val trashDocId: String, val batchDocId: String, val itemDocId: String, val record: TrashRecord)
    enum class Restore { OK, EXISTS, FAILED }

    private fun docUri(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Document id of an entry on the primary volume, or null when it can't be mapped reliably. */
    fun docIdOf(e: FileEntry): String? {
        val u = Uri.parse(e.path)
        return if (u.authority == "media") {
            if (e.parent.startsWith("/")) null
            else "primary:" + e.parent.trim('/').let { if (it.isEmpty()) "" else "$it/" } + e.name
        } else runCatching { DocumentsContract.getDocumentId(u) }.getOrNull()
    }

    fun parentDocId(docId: String): String {
        val i = docId.lastIndexOf('/')
        return if (i >= 0) docId.substring(0, i) else docId.substringBefore(':') + ":"
    }

    /** The granted folder (deepest match) that covers [docId]; never one inside the trash itself. */
    fun rootFor(roots: Collection<String>, docId: String): Uri? {
        if (docId.contains(TRASH_DIR)) return null
        var best: Uri? = null; var bestLen = -1
        for (r in roots) {
            val tree = Uri.parse(r)
            val rid = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val covers = if (rid.endsWith(":")) docId.startsWith(rid) else docId == rid || docId.startsWith("$rid/")
            if (covers && rid.length > bestLen) { best = tree; bestLen = rid.length }
        }
        return best
    }

    private fun findChild(cr: ContentResolver, tree: Uri, dirId: String, name: String): String? {
        var found: String? = null
        cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId), arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) { if (c.getString(1) == name) { found = c.getString(0); break } }
        }
        return found
    }

    private fun readIndex(cr: ContentResolver, tree: Uri, trashId: String): List<TrashRecord> {
        val f = findChild(cr, tree, trashId, INDEX) ?: return emptyList()
        return runCatching { cr.openInputStream(docUri(tree, f))?.bufferedReader()?.use { TrashIndex.decode(it.readText()) } ?: emptyList() }.getOrDefault(emptyList())
    }

    private fun writeIndex(cr: ContentResolver, tree: Uri, trashId: String, list: List<TrashRecord>): Boolean {
        val existing = findChild(cr, tree, trashId, INDEX)
        val uri = existing?.let { docUri(tree, it) } ?: SafOps.createFile(cr, tree, trashId, INDEX) ?: return false
        return runCatching { cr.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(TrashIndex.encode(list)); true } == true }.getOrDefault(false)
    }

    /** Moves one item into the trash. [expectSize] guards against moving a different file that merely has the same path. */
    fun trash(cr: ContentResolver, tree: Uri, docId: String, parentDocId: String, name: String, expectSize: Long?, isDir: Boolean): Boolean {
        if (docId.contains(TRASH_DIR)) return false
        return try {
            val src = docUri(tree, docId)
            val same = cr.query(src, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE), null, null, null)?.use { c ->
                c.moveToFirst() && c.getString(0) == name && (expectSize == null || expectSize <= 0 || (!c.isNull(1) && c.getLong(1) == expectSize))
            } ?: false
            if (!same) return false
            val now = System.currentTimeMillis()
            val trashId = SafOps.ensureFolder(cr, tree, listOf(TRASH_DIR))
            if (findChild(cr, tree, trashId, ".nomedia") == null) SafOps.createFile(cr, tree, trashId, ".nomedia")
            val records = readIndex(cr, tree, trashId)
            val id = TrashIndex.newId(now, records.map { it.id }.toSet())
            val batchId = SafOps.ensureFolder(cr, tree, listOf(TRASH_DIR, id))
            val moved = DocumentsContract.moveDocument(cr, src, docUri(tree, parentDocId), docUri(tree, batchId))
            if (moved == null) { runCatching { DocumentsContract.deleteDocument(cr, docUri(tree, batchId)) }; return false }
            writeIndex(cr, tree, trashId, records + TrashRecord(id, now, parentDocId, name, expectSize ?: 0L, isDir))
            true
        } catch (e: Exception) { false }
    }

    /** All trashed items under the granted folders. Items with a missing index line are still listed (deletedAt unknown, never auto-purged). */
    fun list(cr: ContentResolver, roots: Collection<String>): List<Item> {
        val out = ArrayList<Item>()
        for (r in roots) {
            val tree = Uri.parse(r)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: continue
            val trashId = runCatching { findChild(cr, tree, rootId, TRASH_DIR) }.getOrNull() ?: continue
            val records = readIndex(cr, tree, trashId).associateBy { it.id }
            for (b in FolderLister.list(cr, tree, trashId).items) {
                if (!b.isDir) continue
                val inner = FolderLister.list(cr, tree, b.docId).items.firstOrNull() ?: continue
                val rec = records[b.name] ?: TrashRecord(b.name, 0L, "", inner.name, inner.size, inner.isDir)
                out += Item(tree, trashId, b.docId, inner.docId, rec)
            }
        }
        return out.sortedByDescending { it.record.deletedAtMs }
    }

    fun restore(cr: ContentResolver, item: Item): Restore = try {
        val tree = item.tree; val rec = item.record
        val parentOk = rec.originalParentDocId.isNotEmpty() &&
            (runCatching { cr.query(docUri(tree, rec.originalParentDocId), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false }.getOrDefault(false))
        val targetId = if (parentOk) rec.originalParentDocId else SafOps.ensureFolder(cr, tree, listOf(RESTORE_DIR))
        if (SafOps.childExists(cr, tree, targetId, rec.name)) Restore.EXISTS
        else {
            val moved = DocumentsContract.moveDocument(cr, docUri(tree, item.itemDocId), docUri(tree, item.batchDocId), docUri(tree, targetId))
            if (moved == null) Restore.FAILED
            else {
                runCatching { DocumentsContract.deleteDocument(cr, docUri(tree, item.batchDocId)) }
                writeIndex(cr, tree, item.trashDocId, readIndex(cr, tree, item.trashDocId).filter { it.id != rec.id })
                Restore.OK
            }
        }
    } catch (e: Exception) { Restore.FAILED }

    /** Permanently deletes ONE trashed batch (a folder inside .FileMind-Trash) and its index line. */
    fun deleteForever(cr: ContentResolver, item: Item): Boolean = try {
        val ok = DocumentsContract.deleteDocument(cr, docUri(item.tree, item.batchDocId))
        if (ok) writeIndex(cr, item.tree, item.trashDocId, readIndex(cr, item.tree, item.trashDocId).filter { it.id != item.record.id })
        ok
    } catch (e: Exception) { false }

    /** Auto-purge: only trashed items with a KNOWN deletion time older than [days]. Returns how many were removed. */
    fun purge(cr: ContentResolver, roots: Collection<String>, days: Int): Int {
        if (days <= 0) return 0
        val now = System.currentTimeMillis()
        return list(cr, roots).filter { TrashIndex.isExpired(it.record, now, days) }.count { deleteForever(cr, it) }
    }
}
