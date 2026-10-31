package com.filemind.smartmanager.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class OrganizerNamingTest {
    private fun fe(name: String, ms: Long?) = FileEntry("/x/$name", name, 1, ms)
    private fun utc(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, 12, 0) }.timeInMillis

    @Test fun typeWordComesBeforeTheYear_andMonthsAreNamesNotNumbers() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.YEAR_MONTH, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("a.jpg", utc(2025, 1, 10)), fe("b.jpg", utc(2025, 12, 3))), rule)
        assertEquals(setOf("/r/صور 2025/يناير", "/r/صور 2025/ديسمبر"), plan.newFolders)
    }

    @Test fun levantMonthNames() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.YEAR_MONTH, destRoot = "/r", monthStyle = MonthStyle.LEVANT)
        val plan = Organizer.plan(listOf(fe("a.jpg", utc(2025, 1, 10)), fe("b.jpg", utc(2025, 7, 3)), fe("c.jpg", utc(2025, 12, 31))), rule)
        assertEquals(setOf("/r/صور 2025/كانون الثاني", "/r/صور 2025/تموز", "/r/صور 2025/كانون الأول"), plan.newFolders)
    }

    @Test fun eachKindGetsItsOwnWord_videoAndImagesSeparate() {
        val rule = OrganizeRule(setOf(FileType.IMAGE, FileType.VIDEO), GroupBy.YEAR, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("a.jpg", utc(2025, 3, 1)), fe("b.mp4", utc(2025, 3, 1)), fe("c.jpg", utc(2026, 3, 1))), rule)
        assertEquals(setOf("/r/صور 2025", "/r/فيديو 2025", "/r/صور 2026"), plan.newFolders)
    }

    @Test fun underAnotherGrouping_theYearStaysPlain_andMonthStillANAME() {
        val rule = OrganizeRule(setOf(FileType.IMAGE), GroupBy.NAME, GroupBy.YEAR_MONTH, destRoot = "/r")
        val plan = Organizer.plan(listOf(fe("trip_1.jpg", utc(2025, 3, 5))), rule)
        assertEquals(setOf("/r/trip/2025/مارس"), plan.newFolders)
    }

    @Test fun typeGroupingUsesTheArabicWord() {
        val plan = Organizer.plan(listOf(fe("a.mp4", null), fe("b.jpg", null)), OrganizeRule(setOf(FileType.VIDEO, FileType.IMAGE), GroupBy.TYPE, destRoot = "/r"))
        assertEquals(setOf("/r/فيديو", "/r/صور"), plan.newFolders)
    }

    @Test fun ruleJsonKeepsTheMonthStyle() {
        val r = OrganizeRule(setOf(FileType.VIDEO), GroupBy.YEAR_MONTH, destRoot = "", monthStyle = MonthStyle.LEVANT)
        assertEquals(MonthStyle.LEVANT, OrganizeRuleJson.fromJson(OrganizeRuleJson.toJson(r, null))!!.first.monthStyle)
        assertEquals(MonthStyle.STANDARD, OrganizeRuleJson.fromJson("{\"types\":[\"IMAGE\"],\"primary\":\"YEAR\",\"dateSource\":\"MODIFIED\",\"destRoot\":\"\"}")!!.first.monthStyle)   // old saved tasks
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
