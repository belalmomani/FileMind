package com.filemind.smartmanager.data

import android.content.Context
import android.net.Uri
import com.filemind.smartmanager.core.*
import com.filemind.smartmanager.data.db.*

/**
 * Runs the remaining items of ONE journaled bulk operation. Shared by the in-app runner (interactive conflicts)
 * and the background worker (never replaces: conflicts are answered KEEP_EXISTING). Every item is marked done and logged one by one.
 */
object PendingRunner {
    private fun PendingItem.toEntry() = FileEntry(path, name, size, modifiedMs, parent = parent)

    private suspend fun record(db: AppDatabase, opId: Long, label: String, r: OpResult) {
        if (r.status == TransferStatus.CANCELLED) return
        db.pending().markDone(opId, r.entry.path)
        db.logs().insert(OperationLog(type = label, timeMs = System.currentTimeMillis(), oldName = r.entry.name, newName = r.entry.name,
            oldPath = r.entry.path, newPath = r.newPath, size = r.entry.size, status = r.status.name, reason = r.reason))
    }

    /** Returns [done, skipped, failed]. Stops cooperatively (after the current file) when [shouldStop] turns true. */
    suspend fun run(
        ctx: Context, db: AppDatabase, opId: Long, backup: Boolean,
        ask: suspend (UiConflict) -> ConflictDecision, shouldStop: () -> Boolean, onProgress: (Int, Int) -> Unit,
    ): IntArray {
        val stats = IntArray(3)
        val op = db.pending().op(opId) ?: return stats
        val items = db.pending().remaining(opId)
        val total = db.pending().total(opId)
        var done = total - items.size
        val label = when (op.kind) { "MOVE" -> "نقل"; "COPY" -> "نسخ"; else -> "تنظيم" }
        val tree = Uri.parse(op.destTree)
        val cr = ctx.contentResolver
        val t = SafTransfer(ctx, ask, backup)
        outer@ for ((dir, group) in items.groupBy { it.destDir }) {
            if (shouldStop()) break@outer
            val folderId = try { SafOps.ensureFolder(cr, tree, dir.split('/').filter { it.isNotBlank() }) }
            catch (e: Exception) {
                for (g in group) { record(db, opId, label, OpResult(g.toEntry(), TransferStatus.FAILED, e.message, "")); stats[2]++; done++; onProgress(done, total) }
                continue@outer
            }
            val results = t.runInDir(group.map { it.toEntry() }, tree, folderId, op.kind != "COPY", shouldStop = shouldStop) { r ->
                record(db, opId, label, r)
                when (r.status) {
                    TransferStatus.DONE -> stats[0]++
                    TransferStatus.SKIPPED -> stats[1]++
                    TransferStatus.FAILED -> stats[2]++
                    TransferStatus.CANCELLED -> {}
                }
                if (r.status != TransferStatus.CANCELLED) { done++; onProgress(done, total) }
            }
            if (results.any { it.status == TransferStatus.CANCELLED }) break@outer
        }
        return stats
    }
}
