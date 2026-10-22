package com.filemind.smartmanager.core

/** dHash: compares adjacent pixels of a 9x8 grayscale thumbnail → 64-bit fingerprint. Detects visually SIMILAR images, not identical files. */
object PerceptualHash {
    const val W = 9
    const val H = 8

    fun dHash(gray: IntArray): Long {
        require(gray.size == W * H) { "expected ${W * H} pixels" }
        var h = 0L; var bit = 0
        for (y in 0 until H) for (x in 0 until W - 1) {
            if (gray[y * W + x] > gray[y * W + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Union-find clustering of ids whose hashes are within [maxDist] bits. Returns only groups of 2+. */
    fun cluster(items: List<Pair<String, Long>>, maxDist: Int): List<List<String>> {
        val parent = IntArray(items.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (distance(items[i].second, items[j].second) <= maxDist) parent[find(i)] = find(j)
        }
        return items.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { items[it].first } }
    }
}
