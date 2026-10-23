package com.filemind.smartmanager.core

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure ZIP writer (java.util.zip). The caller owns the destination file and must delete it when [Outcome.error] != null or stopped. */
object ZipMaker {
    class Entry(val name: String, val isDir: Boolean, val modifiedMs: Long?, val open: () -> InputStream?)
    class Outcome(val files: Int, val error: String?, val stopped: Boolean)

    /** Makes names unique (case-insensitive, like most file systems) by inserting " (2)", " (3)"… before the extension. Order is kept. */
    fun uniqueNames(names: List<String>): List<String> {
        val seen = HashSet<String>()
        return names.map { n ->
            var cand = n; var k = 2
            while (!seen.add(cand.lowercase())) cand = withSuffix(n, k++)
            cand
        }
    }

    private fun withSuffix(n: String, k: Int): String {
        val isDir = n.endsWith("/")
        val body = n.trimEnd('/')
        val dot = body.lastIndexOf('.'); val slash = body.lastIndexOf('/')
        val s = if (!isDir && dot > slash + 1) body.substring(0, dot) + " ($k)" + body.substring(dot) else "$body ($k)"
        return if (isDir) "$s/" else s
    }

    fun write(entries: List<Entry>, out: OutputStream, shouldStop: () -> Boolean = { false }, onProgress: (Int, Int) -> Unit = { _, _ -> }): Outcome {
        val total = entries.count { !it.isDir }
        var done = 0
        val z = ZipOutputStream(out)
        try {
            for (e in entries) {
                if (shouldStop()) return Outcome(done, null, true)
                val ze = ZipEntry(if (e.isDir && !e.name.endsWith("/")) e.name + "/" else e.name)
                // Entry time = the file's real modified time when known; otherwise the zip library stamps the creation moment.
                e.modifiedMs?.takeIf { it > 0 }?.let { ze.time = it }
                z.putNextEntry(ze)
                if (!e.isDir) {
                    val input = e.open() ?: return Outcome(done, "تعذر فتح الملف: ${e.name}", false)
                    input.use { it.copyTo(z, 64 * 1024) }
                    done++; onProgress(done, total)
                }
                z.closeEntry()
            }
            z.finish()
            return Outcome(done, null, false)
        } catch (ex: Exception) {
            return Outcome(done, "فشلت الكتابة: ${ex.message}", false)
        } finally {
            runCatching { z.close() }
        }
    }
}
