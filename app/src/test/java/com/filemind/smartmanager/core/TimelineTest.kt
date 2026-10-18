package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TimelineTest {
    private fun ms(y: Int, m: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, 10) }.timeInMillis
    private fun e(n: String, mod: Long?, exif: Long? = null) = FileEntry("/$n", n, 5, mod, exif)

    @Test fun onlyExistingMonthsSortedDescendingAndUnknownSeparate() {
        val r = Timeline.build(listOf(e("a.jpg", ms(2024, 3)), e("b.jpg", ms(2025, 1)), e("c.jpg", ms(2025, 1)), e("d.jpg", null)), DateSource.MODIFIED)
        assertEquals(listOf(2025 to 1, 2024 to 3), r.months.map { it.year to it.month })
        assertEquals(2, r.months[0].count); assertEquals(1, r.unknown.size)
    }
    @Test fun exifSourceNeverFallsBackToModified() {
        val r = Timeline.build(listOf(e("a.jpg", ms(2024, 3), exif = null), e("b.jpg", ms(2024, 3), exif = ms(2020, 7))), DateSource.EXIF)
        assertEquals(listOf(2020 to 7), r.months.map { it.year to it.month }); assertEquals(1, r.unknown.size)
    }
    @Test fun monthRangeCoversDecemberToJanuary() {
        val (f, t) = Timeline.monthRange(2025, 12)
        assertTrue(ms(2025, 12) in f until t); assertFalse(ms(2026, 1) in f until t)
    }
}
