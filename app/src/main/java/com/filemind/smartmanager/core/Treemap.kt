package com.filemind.smartmanager.core

data class TRect(val label: String, val value: Long, val x: Float, val y: Float, val w: Float, val h: Float)

/** Squarified treemap (Bruls et al.). Area of each rectangle is proportional to its value. */
object Treemap {
    fun layout(items: List<Pair<String, Long>>, width: Float, height: Float): List<TRect> {
        val data = items.filter { it.second > 0 }.sortedByDescending { it.second }
        val total = data.sumOf { it.second }.toDouble()
        if (data.isEmpty() || total == 0.0 || width <= 0f || height <= 0f) return emptyList()
        val scale = width.toDouble() * height / total
        val areas = data.map { it.second * scale }
        val out = ArrayList<TRect>()
        var x = 0.0; var y = 0.0; var w = width.toDouble(); var h = height.toDouble()
        var i = 0
        while (i < data.size) {
            val side = minOf(w, h)
            var j = i + 1
            var best = worst(areas.subList(i, j), side)
            while (j < data.size) {
                val cand = worst(areas.subList(i, j + 1), side)
                if (cand <= best) { best = cand; j++ } else break
            }
            val rowSum = areas.subList(i, j).sum()
            if (w >= h) {
                val cw = rowSum / h; var cy = y
                for (k in i until j) { val ch = areas[k] / cw; out += TRect(data[k].first, data[k].second, x.toFloat(), cy.toFloat(), cw.toFloat(), ch.toFloat()); cy += ch }
                x += cw; w -= cw
            } else {
                val rh = rowSum / w; var cx = x
                for (k in i until j) { val cw = areas[k] / rh; out += TRect(data[k].first, data[k].second, cx.toFloat(), y.toFloat(), cw.toFloat(), rh.toFloat()); cx += cw }
                y += rh; h -= rh
            }
            i = j
        }
        return out
    }

    private fun worst(row: List<Double>, side: Double): Double {
        val s = row.sum(); val mx = row.max(); val mn = row.min()
        return maxOf(side * side * mx / (s * s), s * s / (side * side * mn))
    }
}
