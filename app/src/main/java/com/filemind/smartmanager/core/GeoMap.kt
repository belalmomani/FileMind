package com.filemind.smartmanager.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.floor

/** Result of reading a photo's location metadata. has == false means the photo carries no (usable) location. (0,0) and out-of-range values are treated as "none". */
class GeoResult(val has: Boolean, val lat: Double, val lon: Double) {
    val valid: Boolean get() = has && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
    fun encode(): String = if (valid) "$lat,$lon" else "-"

    companion object {
        fun decode(s: String): GeoResult? {
            if (s == "-") return GeoResult(false, 0.0, 0.0)
            val p = s.split(',')
            if (p.size != 2) return null
            val la = p[0].toDoubleOrNull() ?: return null
            val lo = p[1].toDoubleOrNull() ?: return null
            return GeoResult(true, la, lo)
        }
    }
}

class GeoCluster(val lat: Double, val lon: Double, val photos: List<WallPhoto>)

/** Local-only photo map helpers: nobody is contacted, nothing is geocoded. Clusters are approximate grid cells (a cell edge may split two nearby photos). */
object GeoMap {
    /** Cluster cell size in degrees for a view scale of [pxPerDeg] pixels per degree: about 48 px per cell, clamped to [0.0002, 20]. */
    fun cellFor(pxPerDeg: Double): Double = (48.0 / pxPerDeg).coerceIn(0.0002, 20.0)

    fun cluster(points: List<Pair<WallPhoto, GeoResult>>, cellDeg: Double): List<GeoCluster> {
        val cells = LinkedHashMap<Pair<Long, Long>, MutableList<Pair<WallPhoto, GeoResult>>>()
        for (pt in points) cells.getOrPut(Pair(floor(pt.second.lat / cellDeg).toLong(), floor(pt.second.lon / cellDeg).toLong())) { ArrayList() }.add(pt)
        return cells.values
            .map { g -> GeoCluster(g.sumOf { it.second.lat } / g.size, g.sumOf { it.second.lon } / g.size, g.map { it.first }) }
            .sortedByDescending { it.photos.size }
    }

    /** [minLat, maxLat, minLon, maxLon], or null for no points. */
    fun bounds(points: List<GeoResult>): DoubleArray? {
        if (points.isEmpty()) return null
        return doubleArrayOf(points.minOf { it.lat }, points.maxOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lon })
    }

    fun yearOf(ms: Long?, zone: TimeZone = TimeZone.getDefault()): Int? =
        ms?.let { Calendar.getInstance(zone).apply { timeInMillis = it }.get(Calendar.YEAR) }

    fun years(photos: List<WallPhoto>, zone: TimeZone = TimeZone.getDefault()): List<Int> =
        photos.mapNotNull { yearOf(it.dateMs, zone) }.distinct().sortedDescending()

    /** Deliberately coarse (2 decimals, about 1 km) so the screen never shows an exact home address. */
    fun fmt(lat: Double, lon: Double): String = String.format(Locale.US, "%.2f, %.2f", lat, lon)
}
