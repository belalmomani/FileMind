package com.filemind.smartmanager.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Color groups shown in the explorer. Hue groups own 15-degree bins (24 bins around the wheel); the last two are not hue based. */
enum class ColorGroup(val titleAr: String, val bins: IntArray?) {
    RED("أحمر", intArrayOf(23, 0)),
    ORANGE("برتقالي", intArrayOf(1, 2)),
    GOLD("ذهبي", intArrayOf(3, 4)),
    GREEN("أخضر", intArrayOf(5, 6, 7, 8, 9, 10)),
    CYAN("سماوي", intArrayOf(11, 12)),
    BLUE("أزرق", intArrayOf(13, 14, 15, 16)),
    PURPLE("بنفسجي", intArrayOf(17, 18, 19)),
    PINK("وردي", intArrayOf(20, 21, 22)),
    BLACK_WHITE("أبيض وأسود", null),
    MULTI("ألوان متعددة", null),
}

/**
 * Approximate color summary of one photo (computed from a tiny copy of it).
 * [bins]: per-mille share of the VIVID pixels in each 15-degree hue bin. The four percentages describe all counted pixels.
 */
class ColorProfile(val bins: IntArray, val blackPct: Int, val whitePct: Int, val grayPct: Int, val satPct: Int) {
    fun encode(): String = (listOf(blackPct, whitePct, grayPct, satPct) + bins.toList()).joinToString(",")

    companion object {
        const val BINS = 24
        fun decode(s: String): ColorProfile? {
            val p = s.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (p.size != 4 + BINS) return null
            return ColorProfile(p.drop(4).toIntArray(), p[0], p[1], p[2], p[3])
        }
    }
}

object ColorAnalyzer {
    /** [pixels]: ARGB ints (as from Bitmap.getPixels). Mostly transparent pixels are ignored. Pure Kotlin so it can be unit-tested. */
    fun analyze(pixels: IntArray): ColorProfile {
        var black = 0; var white = 0; var gray = 0; var sat = 0; var total = 0
        val w = DoubleArray(ColorProfile.BINS)
        for (px in pixels) {
            if ((px ushr 24) < 128) continue
            total++
            val r = ((px shr 16) and 0xFF) / 255f; val g = ((px shr 8) and 0xFF) / 255f; val b = (px and 0xFF) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val v = mx; val s = if (mx == 0f) 0f else d / mx
            when {
                v < 0.18f -> black++
                s < 0.12f && v > 0.85f -> white++
                s < 0.15f -> gray++
                else -> {
                    sat++
                    var h = when {
                        d == 0f -> 0f
                        mx == r -> 60f * (((g - b) / d + 6f) % 6f)
                        mx == g -> 60f * ((b - r) / d + 2f)
                        else -> 60f * ((r - g) / d + 4f)
                    }
                    if (h >= 360f) h -= 360f
                    w[(h / 15f).toInt().coerceIn(0, ColorProfile.BINS - 1)] += (s * v).toDouble()
                }
            }
        }
        if (total == 0) return ColorProfile(IntArray(ColorProfile.BINS), 0, 0, 0, 0)
        val sum = w.sum()
        val bins = IntArray(ColorProfile.BINS) { if (sum > 0) (w[it] / sum * 1000).roundToInt() else 0 }
        fun pct(x: Int) = (x * 100f / total).roundToInt()
        return ColorProfile(bins, pct(black), pct(white), pct(gray), pct(sat))
    }
}

object ColorMatch {
    private fun circDist(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return min(d, 360f - d) }

    /** Group of a photo: mostly black/white/gray => BLACK_WHITE; one hue group with >= 55% of the vivid color => that group; else MULTI. */
    fun group(p: ColorProfile): ColorGroup {
        val neutral = p.blackPct + p.whitePct + p.grayPct
        if (neutral == 0 && p.satPct == 0) return ColorGroup.MULTI   // nothing measurable
        if (neutral >= 70 || p.satPct == 0) return ColorGroup.BLACK_WHITE
        val total = p.bins.sum().coerceAtLeast(1)
        var best: ColorGroup? = null; var bestMass = 0
        for (g in ColorGroup.entries) {
            val bins = g.bins ?: continue
            val mass = bins.sumOf { p.bins[it] }
            if (mass > bestMass) { best = g; bestMass = mass }
        }
        return if (best != null && bestMass * 100 >= total * 55) best else ColorGroup.MULTI
    }

    /** 0..1 approximate share of this photo that lies within +-[tolDeg] of [hue] (0..360), scaled down for photos that are mostly neutral. */
    fun hueScore(p: ColorProfile, hue: Float, tolDeg: Float): Float {
        val total = p.bins.sum()
        if (total == 0) return 0f
        var mass = 0
        for (i in 0 until ColorProfile.BINS) if (circDist(i * 15f + 7.5f, hue) <= tolDeg + 7.5f) mass += p.bins[i]
        return mass / total.toFloat() * (p.satPct / 100f)
    }
}
