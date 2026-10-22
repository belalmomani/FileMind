package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class FilterTest {
    private val now = 1_800_000_000_000L
    private fun e(n: String, size: Long = 1, mod: Long? = now, parent: String = "Download") = FileEntry("/$n", n, size, mod, parent = parent)

    @Test fun andRequiresAll() {
        val f = FileFilter(types = setOf(FileType.IMAGE), minSize = 5 * MB, folderContains = "Download")
        assertTrue(f.matches(e("a.jpg", 6 * MB))); assertFalse(f.matches(e("a.jpg", 1 * MB))); assertFalse(f.matches(e("a.zip", 6 * MB)))
    }
    @Test fun orRequiresAny() {
        val f = FileFilter(types = setOf(FileType.IMAGE), minSize = 100 * MB, combine = Combine.OR)
        assertTrue(f.matches(e("a.jpg"))); assertTrue(f.matches(e("a.zip", 200 * MB))); assertFalse(f.matches(e("a.zip")))
    }
    @Test fun unknownDateNeverMatchesDateConditions() {
        val f = FileFilter(modifiedBeforeMs = now)
        assertFalse(f.matches(e("a.pdf", mod = null)))
    }
    @Test fun hiddenExcludedRegardlessOfCombine() {
        val f = FileFilter(query = "a", includeHidden = false, combine = Combine.OR)
        assertFalse(f.matches(e(".a.txt")))
    }
    @Test fun sortBySizeDescending() {
        val r = FileFilter(sortBy = SortBy.SIZE).apply(listOf(e("a", 1), e("b", 9), e("c", 5)))
        assertEquals(listOf("b", "c", "a"), r.map { it.name })
    }
    @Test fun jsonRoundTripPreservesFilter() {
        val f = FileFilter(query = "بلال", types = setOf(FileType.ARCHIVE), extensions = setOf("zip"), minSize = 5 * MB, year = 2025, combine = Combine.OR, sortBy = SortBy.NAME, descending = false)
        assertEquals(f, FileFilter.fromJson(f.toJson()))
    }
    @Test fun corruptJsonFallsBackToDefault() { assertEquals(FileFilter(), FileFilter.fromJson("{not json")) }
    @Test fun staleCategoryUsesModifiedDateOnly() {
        assertTrue(Category.STALE.test(e("a", mod = now - 200 * DAY_MS), now))
        assertFalse(Category.STALE.test(e("a", mod = null), now))
    }
    @Test fun folderPaletteTopThree() {
        val list = listOf(e("a.jpg"), e("b.mp4"), e("c.zip"), e("d.pdf"), e("e.jpg"))
        val p = FolderPalette.of(list)
        assertEquals(3, p.colors.size); assertTrue(p.hasMore); assertEquals(FileType.IMAGE.argb, p.colors[0])
    }
    @Test fun parsedQueryBecomesFilter() {
        val f = ArabicQueryParser.parse("اعرض كل ملفات ZIP من سنة 2025").toFileFilter(now)
        assertEquals(setOf("zip"), f.extensions); assertEquals(2025, f.year)
    }
}
