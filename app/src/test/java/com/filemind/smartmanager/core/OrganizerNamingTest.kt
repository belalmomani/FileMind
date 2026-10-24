package com.filemind.smartmanager.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OrganizerNamingTest {
    private fun fe(name: String, ms: Long?) = FileEntry("/x/$name", name, 1, ms)
    private fun utc(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun monthFoldersAreReadableAndSorted() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.YEAR_MONTH, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("a.jpg", utc(2025, 1, 10)), fe("b.jpg", utc(2025, 12, 3))), rule)
        assertEquals(setOf("/r/2025/شهر 01", "/r/2025/شهر 12"), plan.newFolders)
    }

    @Test fun numericNamesShareOneFolder_notOnePerFile() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.NAME, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("1785982504049.jpg", null), fe("1788161559316.jpg", null)), rule)
        assertEquals(setOf("/r/أسماء رقمية"), plan.newFolders)
        assertEquals(2, plan.moves.size)
    }

    @Test fun namesWithLettersStillGroupByName() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.NAME, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("Trip_1.jpg", null), fe("trip_2.jpg", null)), rule)
        assertEquals(setOf("/r/trip"), plan.newFolders)
    }
}
