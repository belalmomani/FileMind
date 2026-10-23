package com.filemind.smartmanager.data

import android.content.ContentResolver
import android.net.Uri
import com.filemind.smartmanager.core.ZipItem
import com.filemind.smartmanager.core.ZipLab
import com.github.junrar.Archive
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.FileInputStream

/**
 * READ-ONLY listing of 7z and RAR archives (no extraction, no writing).
 * 7z: Apache Commons Compress (needs a seekable file descriptor, which local files provide).
 * RAR: Junrar (RAR4 and older only; RAR5, encrypted headers and password-protected archives are reported, never guessed).
 * Names go through the same zip-slip safe-name filter as ZIP.
 */
object ArchiveReaders {
    private fun fail(msg: String) = ZipLab.Listing(emptyList(), 0, msg)
    private fun generic(e: Exception) = "الأرشيف تالف أو غير قابل للقراءة: ${e.message}"

    fun list7z(cr: ContentResolver, uri: Uri): ZipLab.Listing {
        val items = ArrayList<ZipItem>(); var unsafe = 0
        return try {
            val pfd = cr.openFileDescriptor(uri, "r") ?: return fail("تعذر فتح الملف")
            pfd.use {
                FileInputStream(it.fileDescriptor).use { fis ->
                    @Suppress("DEPRECATION")
                    SevenZFile(fis.channel).use { sz ->
                        for (e in sz.entries) {
                            val raw = (e.name ?: "") + if (e.isDirectory) "/" else ""
                            val n = ZipLab.safeName(raw)
                            if (n == null) { unsafe++; continue }
                            items += ZipItem(n, if (e.isDirectory) 0 else e.size.coerceAtLeast(0), e.isDirectory)
                        }
                    }
                }
            }
            ZipLab.Listing(items, unsafe, null)
        } catch (e: PasswordRequiredException) {
            fail("الأرشيف محمي بكلمة مرور (غير مدعوم)")
        } catch (e: Exception) {
            fail(generic(e))
        }
    }

    fun listRar(cr: ContentResolver, uri: Uri): ZipLab.Listing {
        val items = ArrayList<ZipItem>(); var unsafe = 0
        return try {
            val ins = cr.openInputStream(uri) ?: return fail("تعذر فتح الملف")
            ins.use {
                Archive(it).use { a ->
                    while (true) {
                        val h = a.nextFileHeader() ?: break
                        val raw = (h.fileName ?: "") + if (h.isDirectory) "/" else ""
                        val n = ZipLab.safeName(raw)
                        if (n == null) { unsafe++; continue }
                        items += ZipItem(n, if (h.isDirectory) 0 else h.fullUnpackSize.coerceAtLeast(0), h.isDirectory)
                    }
                }
            }
            ZipLab.Listing(items, unsafe, null)
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            fail(when {
                e.javaClass.simpleName.contains("V5", ignoreCase = true) || msg.contains("rar5") || msg.contains("rar 5") -> "صيغة RAR5 غير مدعومة في العرض"
                msg.contains("password") || msg.contains("encrypt") -> "الأرشيف محمي بكلمة مرور (غير مدعوم)"
                else -> generic(e)
            })
        }
    }
}
