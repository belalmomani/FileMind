package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class FilterDateSourceTest {
    private fun ms(y: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, 5, 15) }.timeInMillis
    private val e = FileEntry("/a", "a.jpg", 1L, ms(2025), ms(2020))

    @Test fun exifSourceUsesCaptureDate() {
        assertTrue(FileFilter(year = 2020, dateSource = DateSource.EXIF).matches(e))
        assertFalse(FileFilter(year = 2025, dateSource = DateSource.EXIF).matches(e))
        assertTrue(FileFilter(year = 2025).matches(e))
    }
    @Test fun missingExifNeverMatchesInExifMode() {
        assertFalse(FileFilter(year = 2025, dateSource = DateSource.EXIF).matches(FileEntry("/b", "b.jpg", 1L, ms(2025), null)))
    }
    @Test fun jsonKeepsDateSource() {
        val f = FileFilter(year = 2020, dateSource = DateSource.EXIF)
        assertEquals(f, FileFilter.fromJson(f.toJson()))
    }
}
