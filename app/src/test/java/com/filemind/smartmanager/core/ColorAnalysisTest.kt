package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class ColorAnalysisTest {
    private fun solid(argb: Long, n: Int = 100) = IntArray(n) { argb.toInt() }
    private fun mix(a: IntArray, b: IntArray) = a + b
    private val red = 0xFFFF0000L; private val blue = 0xFF0000FFL; private val green = 0xFF00C000L
    private val gold = 0xFFFFC000L; private val black = 0xFF000000L; private val white = 0xFFFFFFFFL; private val gray = 0xFF808080L

    @Test fun pureColorsLandInTheirGroups() {
        assertEquals(ColorGroup.RED, ColorMatch.group(ColorAnalyzer.analyze(solid(red))))
        assertEquals(ColorGroup.BLUE, ColorMatch.group(ColorAnalyzer.analyze(solid(blue))))
        assertEquals(ColorGroup.GREEN, ColorMatch.group(ColorAnalyzer.analyze(solid(green))))
        assertEquals(ColorGroup.GOLD, ColorMatch.group(ColorAnalyzer.analyze(solid(gold))))
    }

    @Test fun neutralPhotosAreBlackAndWhite() {
        assertEquals(ColorGroup.BLACK_WHITE, ColorMatch.group(ColorAnalyzer.analyze(mix(solid(black), solid(white)))))
        assertEquals(ColorGroup.BLACK_WHITE, ColorMatch.group(ColorAnalyzer.analyze(solid(gray))))
        // 80% black with a little blue is still mostly neutral
        assertEquals(ColorGroup.BLACK_WHITE, ColorMatch.group(ColorAnalyzer.analyze(mix(solid(black, 80), solid(blue, 20)))))
    }

    @Test fun twoStrongColorsAreMulti_notTheFirstOne() {
        assertEquals(ColorGroup.MULTI, ColorMatch.group(ColorAnalyzer.analyze(mix(solid(red, 50), solid(blue, 50)))))
    }

    @Test fun mostlyOneColorWins() {
        assertEquals(ColorGroup.BLUE, ColorMatch.group(ColorAnalyzer.analyze(mix(solid(blue, 80), solid(red, 20)))))
    }

    @Test fun transparentPixelsAreIgnored_andEmptyIsUnmeasurable() {
        val p = ColorAnalyzer.analyze(solid(0x00FF0000L))
        assertEquals(0, p.satPct)
        assertEquals(ColorGroup.MULTI, ColorMatch.group(p))
    }

    @Test fun hueScore_windowAndWraparound() {
        val r = ColorAnalyzer.analyze(solid(red))
        assertTrue(ColorMatch.hueScore(r, 0f, 20f) > 0.9f)
        assertTrue("wraps around 360", ColorMatch.hueScore(r, 350f, 20f) > 0.9f)
        assertEquals(0f, ColorMatch.hueScore(r, 180f, 20f), 0.0001f)
        // a wider window reaches a neighbouring hue; a narrow one does not
        val orange = ColorAnalyzer.analyze(solid(0xFFFF8000L))   // hue ~30
        assertEquals(0f, ColorMatch.hueScore(orange, 0f, 10f), 0.0001f)
        assertTrue(ColorMatch.hueScore(orange, 0f, 40f) > 0.5f)
    }

    @Test fun neutralPhotosDontMatchAHue() {
        assertEquals(0f, ColorMatch.hueScore(ColorAnalyzer.analyze(solid(gray)), 200f, 60f), 0.0001f)
    }

    @Test fun encodeDecode_roundTrip_andBadInput() {
        val p = ColorAnalyzer.analyze(mix(solid(blue, 60), solid(black, 40)))
        val q = ColorProfile.decode(p.encode())!!
        assertArrayEquals(p.bins, q.bins)
        assertEquals(listOf(p.blackPct, p.whitePct, p.grayPct, p.satPct), listOf(q.blackPct, q.whitePct, q.grayPct, q.satPct))
        assertNull(ColorProfile.decode("1,2,3"))
        assertNull(ColorProfile.decode("a,b,c"))
    }
}
