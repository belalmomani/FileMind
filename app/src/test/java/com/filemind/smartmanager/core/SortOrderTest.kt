package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class SortOrderTest {
    private fun e(name: String, size: Long = 1, mod: Long? = 1) = FileEntry("/x/$name", name, size, mod)
    private fun order(list: List<FileEntry>, o: SortOrder) = list.sortedByOrder(o).map { it.name }

    private val files = listOf(e("b.txt", 5, 200), e("A.zip", 9, 100), e("c.jpg", 1, 300), e("d.txt", 7, null))

    @Test fun name_isCaseInsensitive_bothWays() {
        assertEquals(listOf("A.zip", "b.txt", "c.jpg", "d.txt"), order(files, SortOrder.NAME_ASC))
        assertEquals(listOf("d.txt", "c.jpg", "b.txt", "A.zip"), order(files, SortOrder.NAME_DESC))
    }

    @Test fun date_unknownAlwaysLast_inBothDirections() {
        assertEquals(listOf("c.jpg", "b.txt", "A.zip", "d.txt"), order(files, SortOrder.DATE_DESC))
        assertEquals(listOf("A.zip", "b.txt", "c.jpg", "d.txt"), order(files, SortOrder.DATE_ASC))
    }

    @Test fun size_bothWays() {
        assertEquals(listOf("A.zip", "d.txt", "b.txt", "c.jpg"), order(files, SortOrder.SIZE_DESC))
        assertEquals(listOf("c.jpg", "b.txt", "d.txt", "A.zip"), order(files, SortOrder.SIZE_ASC))
    }

    @Test fun type_groupsByExtension_thenName() {
        assertEquals(listOf("c.jpg", "b.txt", "d.txt", "A.zip"), order(files, SortOrder.TYPE_ASC))
        assertEquals(listOf("A.zip", "d.txt", "b.txt", "c.jpg"), order(files, SortOrder.TYPE_DESC))
    }

    @Test fun everyOrderHasItsOwnPair_andOfIsTheInverse() {
        assertEquals(8, SortOrder.entries.size)
        assertEquals(8, SortOrder.entries.map { it.by to it.desc }.toSet().size)
        SortOrder.entries.forEach { assertEquals(it, SortOrder.of(it.by, it.desc)) }
        assertEquals(SortOrder.DATE_DESC, SortOrder.fromName("nope", SortOrder.DATE_DESC))
        assertEquals(SortOrder.SIZE_ASC, SortOrder.fromName("SIZE_ASC", SortOrder.DATE_DESC))
    }

    @Test fun filterApplyUsesTheSameOrders() {
        assertEquals(listOf("c.jpg", "b.txt", "d.txt", "A.zip"), FileFilter(sortBy = SortBy.TYPE, descending = false).apply(files).map { it.name })
        assertEquals(listOf("A.zip", "d.txt", "b.txt", "c.jpg"), FileFilter(sortBy = SortBy.SIZE, descending = true).apply(files).map { it.name })
    }
}
