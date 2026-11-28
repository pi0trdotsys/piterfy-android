package com.eddyizm.tempus.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyQueuePlanTest {
    private val ids = listOf("a", "b", "c", "d", "e")

    @Test fun clearsEverythingAfterTheCurrentTrack() {
        assertArrayEquals(intArrayOf(3, 5), PiterfyQueuePlan.clearRange(2, 5))
        assertArrayEquals(intArrayOf(0, 5), PiterfyQueuePlan.clearRange(-1, 5))   // nic nie gra: cała kolejka
        assertNull(PiterfyQueuePlan.clearRange(4, 5))                              // bieżący jest ostatni
        assertNull(PiterfyQueuePlan.clearRange(0, 0))
    }

    @Test fun idsAfterRemovingCutsTheRange() {
        assertEquals(listOf("a", "b", "c"), PiterfyQueuePlan.idsAfterRemoving(ids, 3, 5))
        assertEquals(listOf("a", "e"), PiterfyQueuePlan.idsAfterRemoving(ids, 1, 4))
        assertEquals(ids, PiterfyQueuePlan.idsAfterRemoving(ids, 3, 9))            // zakres poza kolejką: bez zmian
        assertEquals(ids, PiterfyQueuePlan.idsAfterRemoving(ids, 2, 2))
    }

    @Test fun restoreOnlyWhenTheQueueIsExactlyAsExpected() {
        val after = listOf("a", "b", "c")
        assertTrue(PiterfyQueuePlan.canRestore(after, after, 3))
        assertFalse(PiterfyQueuePlan.canRestore(after + "x", after, 3))            // coś dodano w międzyczasie
        assertFalse(PiterfyQueuePlan.canRestore(after.reversed(), after, 3))       // zmieniono kolejność
        assertFalse(PiterfyQueuePlan.canRestore(after, after, 4))                  // miejsce poza kolejką
        assertFalse(PiterfyQueuePlan.canRestore(after, after, -1))
    }

    @Test fun enqueuedItemLandsNextOrLast() {
        assertEquals(3, PiterfyQueuePlan.enqueuedIndex(true, 2, 9))
        assertEquals(8, PiterfyQueuePlan.enqueuedIndex(false, 2, 9))
    }

    @Test fun undoEnqueueRemovesOnlyTheItemWeAdded() {
        assertEquals(3, PiterfyQueuePlan.undoEnqueueIndex(listOf("a", "b", "c", "x", "d"), "x", true, 2))
        assertEquals(4, PiterfyQueuePlan.undoEnqueueIndex(listOf("a", "b", "c", "d", "x"), "x", false, 0))
        assertEquals(-1, PiterfyQueuePlan.undoEnqueueIndex(listOf("a", "b", "c", "d", "e"), "x", true, 2))   // przesunięte albo już usunięte
        assertEquals(-1, PiterfyQueuePlan.undoEnqueueIndex(listOf("a", "x"), "x", false, 1))                // to teraz gra: nie ruszamy
        assertEquals(-1, PiterfyQueuePlan.undoEnqueueIndex(emptyList(), "x", false, -1))
    }
}
