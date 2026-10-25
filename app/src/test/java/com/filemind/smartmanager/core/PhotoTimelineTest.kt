package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PhotoTimelineTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = Calendar.getInstance(utc).apply { clear(); set(y, m - 1, d, h, 0) }.timeInMillis
    private val now = ms(2026, 10, 25)
    private fun p(path: String, date: Long?) = WallPhoto(path, "F/", date)

    @Test fun sectionsRunFromTodayBackToTheOldestPhoto() {
        val photos = listOf(
            p("today", ms(2026, 10, 25, 8)), p("yest", ms(2026, 10, 24, 23)), p("week", ms(2026, 10, 21)), p("lastweek", ms(2026, 10, 15)),
            p("thismonth", ms(2026, 10, 5)), p("lastmonth", ms(2026, 9, 10)), p("old", ms(2025, 3, 1)), p("none", null), p("future", ms(2026, 12, 1)),
        )
        val s = PhotoTimeline.build(photos, now, utc)
        assertEquals(listOf("future", "d0", "d1", "w0", "w1", "m${2026 * 12 + 9}", "m${2026 * 12 + 8}", "m${2025 * 12 + 2}", "none"), s.map { it.key })
        assertEquals("اليوم", s[1].label); assertEquals("أمس", s[2].label)
        assertEquals("هذا الشهر — أكتوبر 2026", s[5].label)
        assertEquals("الشهر الماضي — سبتمبر 2026", s[6].label)
        assertEquals("مارس 2025", s[7].label)
        assertEquals("بدون تاريخ معروف", s[8].label)
    }

    @Test fun insideASection_newestFirst_andEverythingIsKept() {
        val photos = listOf(p("a", ms(2026, 10, 25, 6)), p("b", ms(2026, 10, 25, 20)), p("c", ms(2026, 10, 25, 10)))
        val s = PhotoTimeline.build(photos, now, utc)
        assertEquals(1, s.size)
        assertEquals(listOf("b", "c", "a"), s[0].photos.map { it.path })
        assertEquals(3, PhotoTimeline.build(photos + p("x", null), now, utc).sumOf { it.photos.size } - 1)
    }

    @Test fun dayBoundaryUsesLocalCalendarDays() {
        val s = PhotoTimeline.build(listOf(p("late", ms(2026, 10, 24, 23)), p("early", ms(2026, 10, 25, 0))), now, utc)
        assertEquals(listOf("d0", "d1"), s.map { it.key })
    }

    @Test fun emptySectionsAreOmitted_andEmptyInputGivesNothing() {
        assertTrue(PhotoTimeline.build(emptyList(), now, utc).isEmpty())
        assertEquals(listOf("d0"), PhotoTimeline.build(listOf(p("t", now)), now, utc).map { it.key })
    }
}
