package com.filemind.smartmanager.core

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class ConflictDecision { REPLACE, KEEP_EXISTING, CANCEL }

/** Name equality is NOT content equality; this tells the UI which case it is. */
enum class ConflictRelation { IDENTICAL_CONTENT, DIFFERENT_CONTENT, UNREADABLE }

data class Conflict(val source: File, val existing: File, val relation: ConflictRelation)

enum class TransferStatus { DONE, SKIPPED, FAILED, CANCELLED }
data class TransferResult(val source: File, val target: File, val status: TransferStatus, val reason: String? = null)

object Hashing {
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    fun compare(a: File, b: File): ConflictRelation = try {
        if (a.length() != b.length()) ConflictRelation.DIFFERENT_CONTENT
        else if (sha256(a) == sha256(b)) ConflictRelation.IDENTICAL_CONTENT
        else ConflictRelation.DIFFERENT_CONTENT
    } catch (e: IOException) { ConflictRelation.UNREADABLE }
}

/**
 * Copy/move with safe replace: write to a temp file in the destination, verify size + hash,
 * then rename over the old file. The source is deleted (move) only after verification.
 * Atomicity is attempted (ATOMIC_MOVE) but NOT guaranteed on every storage.
 */
class FileTransfer(
    /** Called per conflict; return null/CANCEL to stop the whole operation. */
    private val resolver: (Conflict) -> ConflictDecision,
) {
    fun transfer(sources: List<File>, destDir: File, move: Boolean): List<TransferResult> {
        val out = ArrayList<TransferResult>()
        if (!destDir.isDirectory) return sources.map { TransferResult(it, destDir, TransferStatus.FAILED, "الوجهة ليست مجلداً") }
        for ((i, src) in sources.withIndex()) {
            val r = one(src, destDir, move)
            out += r
            if (r.status == TransferStatus.CANCELLED) {
                sources.drop(i + 1).forEach { out += TransferResult(it, File(destDir, it.name), TransferStatus.CANCELLED, "أُلغيت العملية") }
                break
            }
        }
        return out
    }

    private fun one(src: File, destDir: File, move: Boolean): TransferResult {
        val target = File(destDir, src.name)
        if (!src.isFile || !src.canRead()) return TransferResult(src, target, TransferStatus.FAILED, "لا يمكن قراءة الملف المصدر")
        if (src.canonicalFile == target.canonicalFile) return TransferResult(src, target, TransferStatus.SKIPPED, "المصدر والوجهة هما الملف نفسه")
        var replacing = false
        if (target.exists()) {
            when (resolver(Conflict(src, target, Hashing.compare(src, target)))) {
                ConflictDecision.KEEP_EXISTING -> return TransferResult(src, target, TransferStatus.SKIPPED, "تم الاحتفاظ بالملف الموجود")
                ConflictDecision.CANCEL -> return TransferResult(src, target, TransferStatus.CANCELLED)
                ConflictDecision.REPLACE -> replacing = true
            }
        }
        if (destDir.usableSpace < src.length()) return TransferResult(src, target, TransferStatus.FAILED, "المساحة غير كافية")
        val tmp = File(destDir, ".${src.name}.filemind.tmp")
        return try {
            Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            if (tmp.length() != src.length() || Hashing.sha256(tmp) != Hashing.sha256(src))
                throw IOException("فشل التحقق من النسخة المكتوبة")
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            if (move && !src.delete()) TransferResult(src, target, TransferStatus.DONE, "تم النسخ لكن تعذر حذف المصدر")
            else TransferResult(src, target, TransferStatus.DONE, if (replacing) "تم الاستبدال" else null)
        } catch (e: Exception) {
            tmp.delete() // old target (if any) is untouched
            TransferResult(src, target, TransferStatus.FAILED, e.message ?: "خطأ غير معروف")
        }
    }
}
