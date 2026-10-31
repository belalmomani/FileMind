package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class UsageStatsTest {
    private fun paths(l: List<UsageEntry>) = l.map { it.path }

    @Test fun openingAgainIncrementsTheCounter() {
        var l = emptyList<UsageEntry>()
        l = UsageStats.record(l, "a", 1); l = UsageStats.record(l, "b", 2); l = UsageStats.record(l, "a", 3)
        assertEquals(2, l.first { it.path == "a" }.count); assertEquals(3L, l.first { it.path == "a" }.lastMs)
        assertEquals(1, l.first { it.path == "b" }.count); assertEquals(2, l.size)
    }

    @Test fun topIsByCount_thenMostRecent() {
        var l = emptyList<UsageEntry>()
        for ((p, t) in listOf("a" to 1L, "b" to 2L, "b" to 3L, "c" to 4L, "c" to 5L, "d" to 6L)) l = UsageStats.record(l, p, t)
        assertEquals(listOf("c", "b", "d"), paths(UsageStats.top(l, 3)))   // c and b tie on 2: c is newer
        assertEquals(1, UsageStats.top(l, 1).size)
    }

    @Test fun pruningKeepsTheMostUsed() {
        var l = emptyList<UsageEntry>()
        repeat(3) { l = UsageStats.record(l, "keep", 10L + it, max = 3) }
        l = UsageStats.record(l, "x", 20, max = 3); l = UsageStats.record(l, "y", 21, max = 3); l = UsageStats.record(l, "z", 22, max = 3)
        assertEquals(3, l.size); assertTrue("keep" in paths(l)); assertTrue("z" in paths(l))
    }

    @Test fun removeDropsOnlyThatPath() {
        val l = UsageStats.record(UsageStats.record(emptyList(), "a", 1), "b", 2)
        assertEquals(listOf("b"), paths(UsageStats.remove(l, "a")))
    }

    @Test fun encodeDecodeRoundTrip_andBadLinesAreIgnored() {
        val l = UsageStats.record(UsageStats.record(UsageStats.record(emptyList(), "content://x/1", 5), "content://x/1", 6), "p\tq", 7)
        val back = UsageStats.decode(UsageStats.encode(l))
        assertEquals(listOf("content://x/1", "p q"), paths(back).sorted().let { s -> s })
        assertEquals(2, back.first { it.path == "content://x/1" }.count)
        assertTrue(UsageStats.decode("garbage\nx\tnotanumber\t5\n\t1\t2\n").isEmpty())
        assertTrue(UsageStats.decode(null).isEmpty())
    }
}
