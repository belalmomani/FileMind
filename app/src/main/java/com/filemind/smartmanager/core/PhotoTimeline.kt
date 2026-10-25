package com.filemind.smartmanager.core

import java.util.Calendar
import java.util.TimeZone

class TimeSection(val key: String, val label: String, val photos: List<WallPhoto>)

/**
 * Photo Time Machine grouping, newest first: today, yesterday, this week, last week, then one section per calendar month back to the oldest photo.
 * Days are LOCAL calendar days. Photos with no known date go to a last section (never invented); future-dated photos get their own first section.
 */
object PhotoTimeline {
    private val MONTHS = listOf("يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو", "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر")
    private const val DAY = 86_400_000L

    private fun dayNumber(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY)

    /** year * 12 + month (0-based), in the given zone. */
    private fun monthIndex(ms: Long, zone: TimeZone): Int {
        val c = Calendar.getInstance(zone); c.timeInMillis = ms
        return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
    }

    private fun monthName(ym: Int) = "${MONTHS[ym % 12]} ${ym / 12}"

    fun build(photos: List<WallPhoto>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<TimeSection> {
        val today = dayNumber(nowMs, zone); val cur = monthIndex(nowMs, zone)
        val future = ArrayList<WallPhoto>(); val d0 = ArrayList<WallPhoto>(); val d1 = ArrayList<WallPhoto>()
        val w0 = ArrayList<WallPhoto>(); val w1 = ArrayList<WallPhoto>(); val none = ArrayList<WallPhoto>()
        val months = HashMap<Int, ArrayList<WallPhoto>>()
        for (p in photos) {
            val ms = p.dateMs
            if (ms == null) { none += p; continue }
            val ago = today - dayNumber(ms, zone)
            when {
                ago < 0 -> future += p
                ago == 0L -> d0 += p
                ago == 1L -> d1 += p
                ago in 2..6 -> w0 += p
                ago in 7..13 -> w1 += p
                else -> months.getOrPut(monthIndex(ms, zone)) { ArrayList() } += p
            }
        }
        val order = compareByDescending<WallPhoto> { it.dateMs ?: Long.MIN_VALUE }.thenBy { it.path }
        val out = ArrayList<TimeSection>()
        fun add(key: String, label: String, list: List<WallPhoto>) { if (list.isNotEmpty()) out += TimeSection(key, label, list.sortedWith(order)) }
        add("future", "تواريخ مستقبلية", future)
        add("d0", "اليوم", d0)
        add("d1", "أمس", d1)
        add("w0", "هذا الأسبوع", w0)
        add("w1", "الأسبوع الماضي", w1)
        for (ym in months.keys.sortedDescending()) {
            val label = when (ym) { cur -> "هذا الشهر — ${monthName(ym)}"; cur - 1 -> "الشهر الماضي — ${monthName(ym)}"; else -> monthName(ym) }
            add("m$ym", label, months.getValue(ym))
        }
        add("none", "بدون تاريخ معروف", none)
        return out
    }
}
