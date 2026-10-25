package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class WallLayoutTest {
    private val day = 86_400_000L
    private val now = 1_000 * day
    private fun p(path: String, folder: String, daysAgo: Int?) = WallPhoto(path, folder, daysAgo?.let { now - it * day })

    @Test fun oneIslandPerFolder_titleIsLastSegment() {
        val w = WallLayout.build(listOf(p("a", "Pictures/Screenshots/", 1), p("b", "DCIM/Camera/", 2), p("c", "Pictures/Screenshots/", 3), p("d", "primary:Pictures/Trips", 4)), now)
        assertEquals(3, w.islands.size)
        assertEquals(setOf("Screenshots", "Camera", "Trips"), w.islands.map { it.title }.toSet())
        assertEquals(4, w.tiles.size)
    }

    @Test fun newestFirstInsideIsland_andNewestIslandFirst() {
        val w = WallLayout.build(listOf(p("old", "A/", 400), p("new", "A/", 1), p("other", "B/", 0)), now)
        assertEquals("B", w.islands[0].title)                         // B holds the newest photo overall
        assertEquals("new", w.tiles.first { it.island == 1 }.path)     // newest first inside A
    }

    @Test fun unknownDatesGoLast_neverInvented() {
        val w = WallLayout.build(listOf(p("none", "A/", null), p("dated", "A/", 100)), now)
        assertEquals(listOf("dated", "none"), w.tiles.map { it.path })
    }

    @Test fun olderPhotosAreSmallerAndFainter() {
        val (fNew, aNew) = WallLayout.depth(now - 5 * day, now)
        val (fMid, aMid) = WallLayout.depth(now - 90 * day, now)
        val (fOld, aOld) = WallLayout.depth(now - 400 * day, now)
        assertTrue(fNew > fMid && fMid > fOld)
        assertTrue(aNew > aMid && aMid > aOld)
        assertEquals(0.72f, WallLayout.depth(null, now).second)
    }

    @Test fun islandsNeverOverlap_andTilesStayInsideTheirIsland() {
        val photos = (1..300).map { p("p$it", "F${it % 7}/", it) }
        val w = WallLayout.build(photos, now)
        for (i in w.islands.indices) for (j in i + 1 until w.islands.size) {
            val a = w.islands[i]; val b = w.islands[j]
            val disjoint = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y
            assertTrue("islands $i and $j overlap", disjoint)
        }
        for (t in w.tiles) {
            val isl = w.islands[t.island]
            assertTrue(t.x >= isl.x && t.x + t.size <= isl.x + isl.w + 0.01f)
            assertTrue(t.y >= isl.y + WallLayout.HEADER - 0.01f && t.y + t.size <= isl.y + isl.h + 0.01f)
        }
    }

    @Test fun emptyInput_givesEmptyWall() {
        val w = WallLayout.build(emptyList(), now)
        assertTrue(w.tiles.isEmpty() && w.islands.isEmpty())
    }
}
