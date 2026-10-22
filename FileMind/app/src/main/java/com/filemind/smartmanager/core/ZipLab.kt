package com.filemind.smartmanager.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class ZipItem(val name: String, val size: Long, val isDir: Boolean)
data class ArchiveState(val entry: FileEntry, val items: List<ZipItem>, val note: String?)

/** ZIP only (java.util.zip). RAR/7Z are NOT supported: no licensed library is bundled. */
object ZipLab {
    class Listing(val items: List<ZipItem>, val unsafeSkipped: Int, val error: String?)

    /** Rejects absolute paths and ".." (zip-slip). Returns null when the entry must be ignored. */
    fun safeName(raw: String): String? {
        val isDir = raw.endsWith("/") || raw.endsWith("\\")
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." } || raw.startsWith("/") || (parts[0].length == 2 && parts[0][1] == ':')) return null
        return parts.joinToString("/") + if (isDir) "/" else ""
    }

    private fun describe(e: Exception) =
        if (e is ZipException && e.message?.contains("encrypted", true) == true) "الأرشيف محمي بكلمة مرور (غير مدعوم)"
        else "الأرشيف تالف أو غير قابل للقراءة: ${e.message}"

    /** Size comes from the entry header when present; encryption is only detected when data is actually read (extraction). */
    fun list(input: InputStream): Listing {
        val items = ArrayList<ZipItem>(); var unsafe = 0; var error: String? = null
        try {
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val n = safeName(e.name)
                    if (n == null) { unsafe++; continue }
                    var size = e.size
                    if (size < 0 && !e.isDirectory) { size = 0; val b = ByteArray(32 * 1024); while (true) { val r = z.read(b); if (r < 0) break; size += r } }
                    items += ZipItem(n, size.coerceAtLeast(0), e.isDirectory)
                }
            }
        } catch (e: ZipException) { error = describe(e) } catch (e: IOException) { error = describe(e) }
        return Listing(items, unsafe, error)
    }

    /** Calls [action] for every safe file entry. Throws ZipException/IOException on corrupt or encrypted data. Returns unsafe-skipped count. */
    fun forEachFile(input: InputStream, action: (name: String, stream: InputStream) -> Unit): Int {
        var unsafe = 0
        ZipInputStream(input).use { z ->
            val shield = object : FilterInputStream(z) { override fun close() {} }
            while (true) {
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val n = safeName(e.name)
                if (n == null) { unsafe++; continue }
                action(n, shield)
            }
        }
        return unsafe
    }

    /** One pass: (safe name, real size, SHA-256) for every file entry. Throws on corrupt/encrypted data. */
    fun digest(input: InputStream): List<Triple<String, Long, String>> {
        val out = ArrayList<Triple<String, Long, String>>()
        forEachFile(input) { name, s ->
            val md = MessageDigest.getInstance("SHA-256"); var n = 0L; val b = ByteArray(64 * 1024)
            while (true) { val r = s.read(b); if (r < 0) break; md.update(b, 0, r); n += r }
            out += Triple(name, n, md.digest().joinToString("") { "%02x".format(it) })
        }
        return out
    }
}
