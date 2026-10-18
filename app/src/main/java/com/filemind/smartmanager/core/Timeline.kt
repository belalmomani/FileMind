package com.filemind.smartmanager.core

import java.util.Calendar
import java.util.TimeZone

data class MonthGroup(val year: Int, val month: Int, val count: Int, val size: Long, val colors: List<Long>)
data class TimelineResult(val months: List<MonthGroup>, val unknown: List<FileEntry>)

/** Builds only years/months that really exist in the data; files with no usable date go to [TimelineResult.unknown]. */
object Timeline {
    private fun cal(ms: Long) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }

    fun build(entries: List<FileEntry>, source: DateSource): TimelineResult {
        val unknown = ArrayList<FileEntry>()
        val dated = HashMap<Pair<Int, Int>, MutableList<FileEntry>>()
        for (e in entries) {
            val ms = Organizer.dateOf(e, source)
            if (ms == null) { unknown += e; continue }
            val c = cal(ms)
            dated.getOrPut(c.get(Calendar.YEAR) to (c.get(Calendar.MONTH) + 1)) { ArrayList() } += e
        }
        val months = dated.entries
            .sortedWith(compareByDescending<Map.Entry<Pair<Int, Int>, MutableList<FileEntry>>> { it.key.first }.thenByDescending { it.key.second })
            .map { (k, l) -> MonthGroup(k.first, k.second, l.size, l.sumOf { it.size }, FolderPalette.of(l).colors) }
        return TimelineResult(months, unknown)
    }

    /** [from, to) in UTC millis. */
    fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val from = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, 1) }.timeInMillis
        val to = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(year, month - 1, 1); add(Calendar.MONTH, 1) }.timeInMillis
        return from to to
    }
}
