package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyMotionTest {
    @Test fun cascadeDelayGrowsThenStops() {
        assertEquals(0L, PiterfyMotion.delayFor(0))
        assertEquals(45L, PiterfyMotion.delayFor(1))
        assertEquals(450L, PiterfyMotion.delayFor(10))
        assertEquals(450L, PiterfyMotion.delayFor(500))  // długa lista nie czeka w nieskończoność
    }

    @Test fun negativeIndexIsTreatedAsFirst() {
        assertEquals(0L, PiterfyMotion.delayFor(-3))
        assertTrue(PiterfyMotion.delayFor(2) > PiterfyMotion.delayFor(1))
    }
}
