package com.filemind.smartmanager.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MergePlanTest {
    private fun f(p: String, uri: String? = "content://x/$p") = RelFile(p, 1, null, uri)
    private val rows = listOf(
        CompareRow("a/1.txt", CompareStatus.ONLY_A, f("a/1.txt"), null),
        CompareRow("2.txt", CompareStatus.ONLY_A, f("2.txt", null), null),   // no readable URI -> excluded
        CompareRow("3.txt", CompareStatus.ONLY_B, null, f("3.txt")),
        CompareRow("4.txt", CompareStatus.DIFFERENT, f("4.txt"), f("4.txt")), // never touched
        CompareRow("5.txt", CompareStatus.UNREADABLE, f("5.txt"), null),      // never touched
        CompareRow("6.txt", CompareStatus.IDENTICAL, f("6.txt"), f("6.txt")),
    )

    @Test fun fromA_onlyOnlyAWithUri() = assertEquals(listOf("a/1.txt"), MergePlan.missing(rows, true).map { it.path })
    @Test fun fromB_onlyOnlyB() = assertEquals(listOf("3.txt"), MergePlan.missing(rows, false).map { it.path })
    @Test fun dirOf_works() { assertEquals("a/b", MergePlan.dirOf("a/b/c.txt")); assertEquals("", MergePlan.dirOf("c.txt")) }
}
