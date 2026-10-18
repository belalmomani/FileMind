package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveAndTaskTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> e.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return bo.toByteArray()
    }

    @Test fun listsArabicNamesAndSizes() {
        val l = ZipLab.list(ByteArrayInputStream(zip("مجلد/ملف.txt" to "hello", "b.txt" to "xy")))
        assertNull(l.error); assertEquals(setOf("مجلد/ملف.txt", "b.txt"), l.items.map { it.name }.toSet())
        assertEquals(5L, l.items.first { it.name.endsWith("ملف.txt") }.size)
    }
    @Test fun zipSlipIsRejected() {
        val l = ZipLab.list(ByteArrayInputStream(zip("../evil.txt" to "x", "ok.txt" to "y")))
        assertEquals(1, l.unsafeSkipped); assertEquals(listOf("ok.txt"), l.items.map { it.name })
        assertNull(ZipLab.safeName("/abs/x")); assertNull(ZipLab.safeName("a/../../b")); assertNull(ZipLab.safeName("C:/x"))
    }
    @Test fun corruptArchiveReportsErrorNotCrash() {
        val l = ZipLab.list(ByteArrayInputStream(zip("a.txt" to "hello").copyOf(20)))
        assertTrue(l.error != null || l.items.isEmpty())
    }
    @Test fun extractCallsActionPerFile() {
        val got = mutableMapOf<String, String>()
        ZipLab.forEachFile(ByteArrayInputStream(zip("a.txt" to "AAA", "d/b.txt" to "BB"))) { n, s -> got[n] = s.readBytes().decodeToString() }
        assertEquals(mapOf("a.txt" to "AAA", "d/b.txt" to "BB"), got)
    }
    @Test fun organizeRuleRoundTrip() {
        val r = OrganizeRule(setOf(FileType.ARCHIVE, FileType.PDF), GroupBy.NAME, GroupBy.YEAR, DateSource.EXIF, "", "تاريخ غير معروف")
        val (back, dest) = OrganizeRuleJson.fromJson(OrganizeRuleJson.toJson(r, "content://x"))!!
        assertEquals(r, back); assertEquals("content://x", dest)
    }
    @Test fun badTaskJsonReturnsNull() { assertNull(OrganizeRuleJson.fromJson("nope")) }
}
