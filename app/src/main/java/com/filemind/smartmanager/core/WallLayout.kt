package com.filemind.smartmanager.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/** dateMs: EXIF taken date if known, else modified date, else null (unknown dates are placed last and treated as old; never invented). */
data class WallPhoto(val path: String, val folder: String, val dateMs: Long?)
data class WallRequest(val title: String, val photos: List<WallPhoto>)

class WallTile(val path: String, val x: Float, val y: Float, val size: Float, val alpha: Float, val island: Int)
class WallIsland(val title: String, val x: Float, val y: Float, val w: Float, val h: Float, val count: Int)
class Wall(val islands: List<WallIsland>, val tiles: List<WallTile>, val width: Float, val height: Float)

/**
 * "Infinite photo wall" layout in WORLD units. Every folder becomes an island; islands are packed in rows, newest island first.
 * Inside an island photos run newest-first. Older photos are slightly smaller and fainter ("recede to the back").
 */
object WallLayout {
    const val TILE = 160f
    const val GAP = 14f
    const val HEADER = 70f
    const val ISLAND_GAP = 160f
    const val ROW_MAX = 3200f
    private const val DAY = 86_400_000L

    fun islandTitle(folder: String): String =
        folder.trim('/').substringAfterLast('/').substringAfterLast(':').ifBlank { "الجذر" }

    /** (size factor, alpha) by age: recent photos are in front. */
    fun depth(dateMs: Long?, nowMs: Long): Pair<Float, Float> {
        if (dateMs == null) return 0.88f to 0.72f
        val days = (nowMs - dateMs) / DAY
        return when {
            days <= 30 -> 1.0f to 1.0f
            days <= 180 -> 0.94f to 0.88f
            else -> 0.88f to 0.72f
        }
    }

    fun build(photos: List<WallPhoto>, nowMs: Long): Wall {
        val groups = photos.groupBy { it.folder }.map { (folder, list) ->
            folder to list.sortedWith(compareByDescending<WallPhoto> { it.dateMs ?: Long.MIN_VALUE }.thenBy { it.path })
        }.sortedWith(compareByDescending<Pair<String, List<WallPhoto>>> { g -> g.second.firstOrNull()?.dateMs ?: Long.MIN_VALUE }.thenBy { it.first })

        val tiles = ArrayList<WallTile>(); val islands = ArrayList<WallIsland>()
        var cx = 0f; var cy = 0f; var rowH = 0f; var maxX = 0f
        for ((folder, list) in groups) {
            val n = list.size
            val cols = ceil(sqrt(n.toDouble())).toInt().coerceIn(1, 12)
            val rows = ceil(n / cols.toDouble()).toInt()
            val w = cols * (TILE + GAP) - GAP
            val h = HEADER + rows * (TILE + GAP) - GAP
            if (cx > 0f && cx + w > ROW_MAX) { cx = 0f; cy += rowH + ISLAND_GAP; rowH = 0f }
            val idx = islands.size
            islands += WallIsland(islandTitle(folder), cx, cy, w, h, n)
            list.forEachIndexed { i, p ->
                val (f, a) = depth(p.dateMs, nowMs)
                val size = TILE * f
                val off = (TILE - size) / 2f
                tiles += WallTile(p.path, cx + (i % cols) * (TILE + GAP) + off, cy + HEADER + (i / cols) * (TILE + GAP) + off, size, a, idx)
            }
            cx += w + ISLAND_GAP
            rowH = max(rowH, h)
            maxX = max(maxX, cx - ISLAND_GAP)
        }
        return Wall(islands, tiles, maxX, cy + rowH)
    }
}
