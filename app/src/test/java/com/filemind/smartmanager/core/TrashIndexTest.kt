package com.filemind.smartmanager.core

import org.junit.Assert.*
import org.junit.Test

class TrashIndexTest {
    private val day = 86_400_000L

    @Test fun roundTrip_keepsEverythingIncludingSpecialCharacters() {
        val list = listOf(
            TrashRecord("1-1", 1_700_000_000_000L, "primary:Download/Film", "حلقة 12 - a\tb\nc\\d.ts", 829_300_000L, false),
            TrashRecord("2-1", 5L, "primary:", "مجلد", 0L, true),
        )
        assertEquals(list, TrashIndex.decode(TrashIndex.encode(list)))
    }

    @Test fun decode_ignoresBadHeaderAndBadLines() {
        assertTrue(TrashIndex.decode("garbage\n1\t2\t3\t4\t5\t0").isEmpty())
        assertTrue(TrashIndex.decode(null).isEmpty())
        val ok = TrashRecord("1-1", 1L, "p", "n", 2L, false)
        val text = TrashIndex.encode(listOf(ok)) + "broken line\nx\tnotanumber\tp\tn\t1\t0\n"
        assertEquals(listOf(ok), TrashIndex.decode(text))
    }

    @Test fun expiry_onlyForKnownOldDeletions() {
        val now = 100 * day
        assertTrue(TrashIndex.isExpired(TrashRecord("a", now - 31 * day, "", "n", 1, false), now, 30))
        assertFalse(TrashIndex.isExpired(TrashRecord("a", now - 29 * day, "", "n", 1, false), now, 30))
        assertFalse("unknown time must never be purged", TrashIndex.isExpired(TrashRecord("a", 0L, "", "n", 1, false), now, 30))
        assertFalse("days = 0 means trash off", TrashIndex.isExpired(TrashRecord("a", 1L, "", "n", 1, false), now, 0))
    }

    @Test fun newId_isUnique() {
        assertEquals("10-1", TrashIndex.newId(10, emptySet()))
        assertEquals("10-3", TrashIndex.newId(10, setOf("10-1", "10-2")))
    }
}
