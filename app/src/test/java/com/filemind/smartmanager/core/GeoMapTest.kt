package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class GeoMapTest {
    private fun ph(path: String, date: Long? = null) = WallPhoto(path, "F/", date)
    private fun at(lat: Double, lon: Double) = GeoResult(true, lat, lon)

    @Test fun nearbyPhotosShareACluster_farOnesDont() {
        val c = GeoMap.cluster(listOf(ph("a") to at(30.1, 31.1), ph("b") to at(30.2, 31.2), ph("far") to at(40.7, -74.0)), 0.5)
        assertEquals(2, c.size)
        assertEquals(listOf("a", "b"), c[0].photos.map { it.path })          // biggest cluster first
        assertEquals(30.15, c[0].lat, 1e-9); assertEquals(31.15, c[0].lon, 1e-9)
        assertEquals(listOf("far"), c[1].photos.map { it.path })
    }

    @Test fun tinyCellsSeparateWhatLargeCellsMerge() {
        val pts = listOf(ph("a") to at(30.1, 31.1), ph("b") to at(30.2, 31.2))
        assertEquals(1, GeoMap.cluster(pts, 5.0).size)
        assertEquals(2, GeoMap.cluster(pts, 0.01).size)
    }

    @Test fun cellSizeFollowsZoomAndIsClamped() {
        assertEquals(0.0002, GeoMap.cellFor(1e9), 1e-12)
        assertEquals(20.0, GeoMap.cellFor(0.001), 1e-12)
        assertTrue(GeoMap.cellFor(100.0) > GeoMap.cellFor(1000.0))
    }

    @Test fun invalidLocationsAreNone() {
        assertFalse(GeoResult(false, 10.0, 10.0).valid)
        assertFalse(at(0.0, 0.0).valid)             // "null island": junk GPS
        assertFalse(at(95.0, 10.0).valid)
        assertFalse(at(10.0, 200.0).valid)
        assertTrue(at(-33.9, 151.2).valid)
    }

    @Test fun encodeDecode_roundTrip() {
        val r = GeoResult.decode(at(30.0444, 31.2357).encode())!!
        assertTrue(r.has); assertEquals(30.0444, r.lat, 1e-9); assertEquals(31.2357, r.lon, 1e-9)
        assertFalse(GeoResult.decode("-")!!.has)
        assertEquals("-", GeoResult(false, 1.0, 1.0).encode())
        assertNull(GeoResult.decode("x,y")); assertNull(GeoResult.decode("1,2,3"))
    }

    @Test fun yearsAreDistinctNewestFirst_andUnknownDatesIgnored() {
        val utc = TimeZone.getTimeZone("UTC")
        fun ms(y: Int) = Calendar.getInstance(utc).apply { clear(); set(y, 5, 15, 12, 0) }.timeInMillis
        assertEquals(listOf(2026, 2024, 2019), GeoMap.years(listOf(ph("a", ms(2024)), ph("b", ms(2019)), ph("c", ms(2026)), ph("d", ms(2024)), ph("e", null)), utc))
        assertEquals(2024, GeoMap.yearOf(ms(2024), utc))
        assertNull(GeoMap.yearOf(null, utc))
    }

    @Test fun boundsCoverAllPoints() {
        val b = GeoMap.bounds(listOf(at(10.0, 20.0), at(-5.0, 40.0), at(7.0, -3.0)))!!
        assertArrayEquals(doubleArrayOf(-5.0, 10.0, -3.0, 40.0), b, 1e-9)
        assertNull(GeoMap.bounds(emptyList()))
    }

    @Test fun displayedCoordinatesAreCoarse() {
        assertEquals("30.05, 31.23", GeoMap.fmt(30.04567, 31.23456))
        assertEquals("-33.87, 151.21", GeoMap.fmt(-33.8688, 151.2093))
    }
}
