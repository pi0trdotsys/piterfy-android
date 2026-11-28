package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyMotionPlanTest {
    @Test fun unknownModeIsSystem() {
        assertEquals("system", PiterfyMotionPlan.normalize(null))
        assertEquals("system", PiterfyMotionPlan.normalize(""))
        assertEquals("system", PiterfyMotionPlan.normalize("turbo"))
        assertEquals("full", PiterfyMotionPlan.normalize("full"))
        assertEquals("reduced", PiterfyMotionPlan.normalize("reduced"))
    }

    @Test fun systemModeFollowsTheSystemSwitch() {
        assertTrue(PiterfyMotionPlan.enabled("system", true))
        assertFalse(PiterfyMotionPlan.enabled("system", false))
        assertTrue(PiterfyMotionPlan.enabled(null, true))
        assertFalse(PiterfyMotionPlan.enabled("garbage", false))
    }

    @Test fun fullAlwaysAnimatesAndReducedNever() {
        assertTrue(PiterfyMotionPlan.enabled("full", false))
        assertTrue(PiterfyMotionPlan.enabled("full", true))
        assertFalse(PiterfyMotionPlan.enabled("reduced", true))
        assertFalse(PiterfyMotionPlan.enabled("reduced", false))
    }

    @Test fun durationIsZeroWhenMotionIsOff() {
        assertEquals(PiterfyMotionPlan.NORMAL_MS, PiterfyMotionPlan.duration(PiterfyMotionPlan.NORMAL_MS, "full", false))
        assertEquals(0L, PiterfyMotionPlan.duration(PiterfyMotionPlan.NORMAL_MS, "reduced", true))
        assertEquals(0L, PiterfyMotionPlan.duration(PiterfyMotionPlan.SLOW_MS, "system", false))
    }

    @Test fun tokensAreOrderedAndMatchPreviousValues() {
        assertTrue(PiterfyMotionPlan.FAST_MS < PiterfyMotionPlan.NORMAL_MS)
        assertTrue(PiterfyMotionPlan.NORMAL_MS < PiterfyMotionPlan.SLOW_MS)
        assertEquals(420L, PiterfyMorphPlan.DURATION_MS)   // morph kontenera trwa dokładnie tyle, co „slow”
        assertEquals(4, PiterfyMotionPlan.EMPHASIZED.size)
    }

    @Test fun cascadeDelayIsClamped() {
        assertEquals(0L, PiterfyMotionPlan.cascadeDelay(-5))
        assertEquals(45L, PiterfyMotionPlan.cascadeDelay(1))
        assertEquals(450L, PiterfyMotionPlan.cascadeDelay(999))
    }
}
