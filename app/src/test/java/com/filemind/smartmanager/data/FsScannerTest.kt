package com.filemind.smartmanager.data

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FsScannerTest {
    private fun tree(): File {
        val root = Files.createTempDirectory("fsscan").toFile()
        fun file(rel: String, text: String) { val f = File(root, rel); f.parentFile.mkdirs(); f.writeText(text) }
        file("root.txt", "r")
        file("a/b/doc.txt", "hello")
        file("a/b/.hidden", "xyz")
        file(".h/x.bin", "12")
        file("Android/data/skip.txt", "no")
        file(".FileMind-Trash/t/t.txt", "no")
        File(root, "a/empty").mkdirs()
        File(root, "a/onlyempty/sub").mkdirs()
        return root
    }

    @Test fun findsEverythingIncludingHiddenFiles_andSkipsAndroidAndTheTrash() {
        val root = tree()
        try {
            val r = FsScanner.scan(root)
            assertEquals(setOf("root.txt", "doc.txt", ".hidden", "x.bin"), r.files.map { it.name }.toSet())
            assertTrue(r.files.all { it.path.startsWith("file:") })
            val parents = r.files.associate { it.name to it.parent }
            assertEquals("", parents["root.txt"]); assertEquals("a/b/", parents["doc.txt"]); assertEquals(".h/", parents["x.bin"])
            assertEquals(5L, r.files.first { it.name == "doc.txt" }.size)
            assertTrue(r.files.first { it.name == ".hidden" }.isHidden)
        } finally { root.deleteRecursively() }
    }

    @Test fun reportsFoldersThatHoldNoFileAnywhere() {
        val root = tree()
        try {
            val e = FsScanner.scan(root).emptyDirs.toSet()
            assertEquals(setOf("a/empty", "a/onlyempty", "a/onlyempty/sub"), e)
            assertFalse("a/b" in e); assertFalse("" in e)
        } finally { root.deleteRecursively() }
    }

    @Test fun unreadableOrMissingRootGivesNothingAndNoCrash() {
        val r = FsScanner.scan(File("/definitely/not/here"))
        assertTrue(r.files.isEmpty() && r.emptyDirs.isEmpty())
    }
}
