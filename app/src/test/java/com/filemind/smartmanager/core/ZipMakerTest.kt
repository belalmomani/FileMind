package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ZipMakerTest {
    private fun file(n: String, text: String) = ZipMaker.Entry(n, false, 1_700_000_000_000L) { ByteArrayInputStream(text.toByteArray()) }

    @Test fun uniqueNames_caseInsensitive_keepsExtension() =
        assertEquals(listOf("a.txt", "A (2).txt", "a (3).txt", "d/", "D (2)/"), ZipMaker.uniqueNames(listOf("a.txt", "A.txt", "a.txt", "d/", "D/")))

    @Test fun roundTrip_listAndContent() {
        val out = ByteArrayOutputStream()
        val r = ZipMaker.write(listOf(ZipMaker.Entry("dir/", true, null) { null }, file("dir/a.txt", "hello"), file("b.txt", "xyz")), out)
        assertNull(r.error); assertFalse(r.stopped); assertEquals(2, r.files)
        val l = ZipLab.list(ByteArrayInputStream(out.toByteArray()))
        assertNull(l.error)
        assertEquals(listOf("dir/", "dir/a.txt", "b.txt"), l.items.map { it.name })
        assertEquals(listOf(0L, 5L, 3L), l.items.map { it.size })
    }

    @Test fun stop_returnsStopped() {
        val r = ZipMaker.write(listOf(file("a.txt", "1"), file("b.txt", "2")), ByteArrayOutputStream(), shouldStop = { true })
        assertTrue(r.stopped); assertEquals(0, r.files)
    }

    @Test fun unreadableFile_reportsError() {
        val r = ZipMaker.write(listOf(file("a.txt", "1"), ZipMaker.Entry("bad.txt", false, null) { null }), ByteArrayOutputStream())
        assertNotNull(r.error); assertEquals(1, r.files)
    }
}
