package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CompareTreemapTest {
    private fun f(p: String, s: Long) = RelFile(p, s, null)
    private val h: (RelFile) -> String? = { it.path.substringAfterLast('#', "H") }  // path "x#hash" → hash

    @Test fun statusesAreDistinguished() {
        val a = listOf(f("only.txt", 1), f("same.txt", 5), f("diff.txt", 5), f("size.txt", 5))
        val b = listOf(f("new.txt", 1), f("same.txt", 5), f("diff.txt", 5), f("size.txt", 9))
        val rows = SourceComparer.compare(a, b, { if (it.path == "diff.txt") "A" else "S" }, { if (it.path == "diff.txt") "B" else "S" }).associate { it.path to it.status }
        assertEquals(CompareStatus.ONLY_A, rows["only.txt"]); assertEquals(CompareStatus.ONLY_B, rows["new.txt"])
        assertEquals(CompareStatus.IDENTICAL, rows["same.txt"]); assertEquals(CompareStatus.DIFFERENT, rows["diff.txt"]); assertEquals(CompareStatus.DIFFERENT, rows["size.txt"])
    }
    @Test fun unscannedFolderIsNotReportedMissing() {
        val rows = SourceComparer.compare(listOf(f("docs/a.txt", 1), f("pics/b.jpg", 1)), listOf(f("pics/b.jpg", 1)), { "x" }, { "x" }, failedDirsB = listOf("docs"))
        assertEquals(CompareStatus.UNREADABLE, rows.first { it.path == "docs/a.txt" }.status)
        assertEquals(CompareStatus.IDENTICAL, rows.first { it.path == "pics/b.jpg" }.status)
    }
    @Test fun hashFailureIsUnreadableNotDifferent() {
        val rows = SourceComparer.compare(listOf(f("a", 1)), listOf(f("a", 1)), { null }, { "x" })
        assertEquals(CompareStatus.UNREADABLE, rows[0].status)
    }
    @Test fun wholeSourceFailureMarksEverythingUnreadable() {
        val rows = SourceComparer.compare(listOf(f("a", 1)), emptyList(), { "x" }, { "x" }, failedDirsB = listOf(""))
        assertEquals(CompareStatus.UNREADABLE, rows[0].status)
    }
    @Test fun zipDigestMatchesAcrossSources() {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> z.putNextEntry(ZipEntry("d/a.txt")); z.write("hello".toByteArray()); z.closeEntry() }
        val d = ZipLab.digest(ByteArrayInputStream(bo.toByteArray()))
        assertEquals("d/a.txt", d[0].first); assertEquals(5L, d[0].second)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", d[0].third)
    }
    @Test fun treemapAreasProportionalAndInsideBounds() {
        val items = listOf("a" to 50L, "b" to 25L, "c" to 15L, "d" to 10L)
        val r = Treemap.layout(items, 400f, 200f)
        assertEquals(4, r.size)
        assertEquals(400f * 200f, r.sumOf { (it.w * it.h).toDouble() }.toFloat(), 5f)
        r.forEach { assertTrue(it.x >= -0.01f && it.y >= -0.01f && it.x + it.w <= 400.1f && it.y + it.h <= 200.1f) }
        val a = r.first { it.label == "a" }; assertEquals(0.5f, a.w * a.h / (400f * 200f), 0.01f)
    }
    @Test fun treemapIgnoresZeroAndEmpty() {
        assertTrue(Treemap.layout(emptyList(), 100f, 100f).isEmpty())
        assertEquals(1, Treemap.layout(listOf("a" to 5L, "z" to 0L), 100f, 100f).size)
    }
}
