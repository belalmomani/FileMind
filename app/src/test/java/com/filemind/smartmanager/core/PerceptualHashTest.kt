package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class PerceptualHashTest {
    private val base = IntArray(72) { (it * 37 + (it / 9) * 11) % 256 }

    @Test fun brightnessShiftKeepsHash() {
        val brighter = IntArray(72) { base[it] + 10 }
        assertEquals(0, PerceptualHash.distance(PerceptualHash.dHash(base), PerceptualHash.dHash(brighter)))
    }
    @Test fun invertedImageIsFar() {
        val inv = IntArray(72) { 255 - base[it] }
        assertTrue(PerceptualHash.distance(PerceptualHash.dHash(base), PerceptualHash.dHash(inv)) > 20)
    }
    @Test fun clusterGroupsOnlySimilarAndDropsSingles() {
        val a = PerceptualHash.dHash(base); val far = PerceptualHash.dHash(IntArray(72) { 255 - base[it] })
        val g = PerceptualHash.cluster(listOf("a" to a, "b" to (a xor 1L), "c" to far), maxDist = 6)
        assertEquals(1, g.size); assertEquals(setOf("a", "b"), g[0].toSet())
    }
}
