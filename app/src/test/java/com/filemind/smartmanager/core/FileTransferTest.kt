package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileTransferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(dir: java.io.File, name: String, text: String) = java.io.File(dir, name).also { it.writeText(text) }

    @Test fun moveToEmptyFolder() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b"); val f = file(a, "x.txt", "hi")
        val r = FileTransfer { ConflictDecision.CANCEL }.transfer(listOf(f), b, move = true)
        assertEquals(TransferStatus.DONE, r[0].status); assertFalse(f.exists()); assertEquals("hi", java.io.File(b, "x.txt").readText())
    }
    @Test fun replaceWithDifferentContent() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val f = file(a, "x.txt", "new content"); file(b, "x.txt", "old")
        var seen: ConflictRelation? = null
        val r = FileTransfer { seen = it.relation; ConflictDecision.REPLACE }.transfer(listOf(f), b, move = false)
        assertEquals(ConflictRelation.DIFFERENT_CONTENT, seen)
        assertEquals(TransferStatus.DONE, r[0].status); assertEquals("new content", java.io.File(b, "x.txt").readText())
        assertTrue(f.exists())
    }
    @Test fun sameNameIdenticalContentIsReported() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val f = file(a, "x.txt", "same"); file(b, "x.txt", "same")
        var seen: ConflictRelation? = null
        FileTransfer { seen = it.relation; ConflictDecision.KEEP_EXISTING }.transfer(listOf(f), b, true)
        assertEquals(ConflictRelation.IDENTICAL_CONTENT, seen)
    }
    @Test fun keepExistingDoesNotTouchOldFile() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val f = file(a, "x.txt", "new"); val old = file(b, "x.txt", "old")
        val r = FileTransfer { ConflictDecision.KEEP_EXISTING }.transfer(listOf(f), b, true)
        assertEquals(TransferStatus.SKIPPED, r[0].status); assertEquals("old", old.readText()); assertTrue(f.exists())
    }
    @Test fun cancelStopsRemaining() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b")
        val f1 = file(a, "1.txt", "1"); val f2 = file(a, "2.txt", "2"); file(b, "1.txt", "other")
        val r = FileTransfer { ConflictDecision.CANCEL }.transfer(listOf(f1, f2), b, true)
        assertTrue(r.all { it.status == TransferStatus.CANCELLED }); assertFalse(java.io.File(b, "2.txt").exists())
    }
    @Test fun missingSourceFailsWithoutCrash() {
        val b = tmp.newFolder("b")
        val r = FileTransfer { ConflictDecision.REPLACE }.transfer(listOf(java.io.File("/nonexistent/z.txt")), b, true)
        assertEquals(TransferStatus.FAILED, r[0].status)
    }
    @Test fun duplicateFinderIsReadOnlyAndExact() {
        val d = tmp.newFolder("d")
        val a = file(d, "a.bin", "same"); val b = file(d, "b.bin", "same"); file(d, "c.bin", "diff!")
        val g = DuplicateFinder.find(listOf(a, b, java.io.File(d, "c.bin")))
        assertEquals(1, g.size); assertEquals(2, g[0].files.size); assertTrue(a.exists() && b.exists())
    }
}
