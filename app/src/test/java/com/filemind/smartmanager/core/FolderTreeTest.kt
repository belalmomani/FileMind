package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class FolderTreeTest {
    private fun f(parent: String, size: Long, mod: Long? = 1) = FileEntry("/x/$parent/$size", "f$size", size, mod, parent = parent)

    @Test fun normalize_allFormsOfTheSameFolder() {
        assertEquals("Download/x", FolderTree.normalize("Download/x/"))
        assertEquals("Download/x", FolderTree.normalize("/Download/x"))
        assertEquals("Download/x", FolderTree.normalize("primary:Download/x"))
        assertEquals("Download/x", FolderTree.normalize("storage/emulated/0/Download/x"))
        assertEquals("", FolderTree.normalize("primary:")); assertEquals("", FolderTree.normalize("")); assertEquals("", FolderTree.normalize("/"))
        assertEquals("1234-ABCD/Pics", FolderTree.normalize("1234-ABCD:Pics"))
    }

    @Test fun treeTotalsIncludeEverythingBelow() {
        val root = FolderTree.build(listOf(f("Download/Film/", 10), f("primary:Download/Film", 20), f("/DCIM/Camera", 5), f("DCIM/Camera/", 7), f("primary:", 3)))
        assertEquals(5, root.totalFiles); assertEquals(45L, root.totalSize)
        assertEquals(listOf("Download", "DCIM"), root.children.keys.toList())
        assertEquals(1, root.files.size)                                            // the file at the storage root
        val film = FolderTree.find(root, "Download/Film")!!
        assertEquals(2, film.files.size); assertEquals(30L, film.totalSize)
        val download = root.children.getValue("Download")
        assertTrue(download.files.isEmpty()); assertEquals(30L, download.totalSize); assertEquals(2, download.totalFiles)
        assertEquals(12L, FolderTree.find(root, "DCIM/Camera")!!.totalSize)
        assertNull(FolderTree.find(root, "Nope/Here"))
        assertSame(root, FolderTree.find(root, ""))
    }

    @Test fun newestDateIsTheNewestBelow() {
        val root = FolderTree.build(listOf(f("A/B", 1, 100), f("A", 1, 300), f("A/B/C", 1, 200)))
        assertEquals(300L, root.children.getValue("A").newestMs)
        assertEquals(200L, FolderTree.find(root, "A/B")!!.newestMs)
    }

    @Test fun sizeClasses_redGreenYellowBlack() {
        assertEquals(FolderSize.EMPTY, FolderTree.sizeClass(0, 0)); assertEquals(FolderSize.EMPTY, FolderTree.sizeClass(3, 0))
        assertEquals(FolderSize.SMALL, FolderTree.sizeClass(1, 1))
        assertEquals(FolderSize.SMALL, FolderTree.sizeClass(1, FolderTree.SMALL_MAX - 1))
        assertEquals(FolderSize.MEDIUM, FolderTree.sizeClass(1, FolderTree.SMALL_MAX))
        assertEquals(FolderSize.MEDIUM, FolderTree.sizeClass(1, FolderTree.MEDIUM_MAX - 1))
        assertEquals(FolderSize.LARGE, FolderTree.sizeClass(1, FolderTree.MEDIUM_MAX))
        assertEquals(0xFFE53935, FolderSize.LARGE.argb); assertEquals(0xFF43A047, FolderSize.MEDIUM.argb)
        assertEquals(0xFFFBC02D, FolderSize.SMALL.argb); assertEquals(0xFF212121, FolderSize.EMPTY.argb)
    }

    @Test fun emptyInputGivesAnEmptyRoot() {
        val root = FolderTree.build(emptyList())
        assertEquals(0, root.totalFiles); assertTrue(root.children.isEmpty()); assertEquals(FolderSize.EMPTY, root.size)
    }
}
