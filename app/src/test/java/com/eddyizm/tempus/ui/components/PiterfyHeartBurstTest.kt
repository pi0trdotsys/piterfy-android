package com.eddyizm.tempus.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHeartBurstTest {
    @Test fun particlesStartAtTheCentreAndSpreadEvenly() {
        val start = PiterfyHeartBurst.particle(0, 8, 0f, 100f)
        assertEquals(0f, start.first, 1e-4f); assertEquals(0f, start.second, 1e-4f)
        val top = PiterfyHeartBurst.particle(0, 8, 1f, 100f)      // pierwsza cząstka leci w górę
        assertEquals(0f, top.first, 1e-3f); assertEquals(-100f, top.second, 1e-3f)
        val right = PiterfyHeartBurst.particle(2, 8, 1f, 100f)
        assertEquals(100f, right.first, 1e-3f); assertEquals(0f, right.second, 1e-3f)
    }

    @Test fun radiusDecelerates() {
        val half = PiterfyHeartBurst.particle(2, 8, 0.5f, 100f).first
        assertTrue(half > 50f)                                     // szybciej na początku niż liniowo
        assertEquals(100f, PiterfyHeartBurst.particle(2, 8, 3f, 100f).first, 1e-3f)
    }

    @Test fun particlesFadeInTheSecondHalf() {
        assertEquals(1f, PiterfyHeartBurst.particleAlpha(0f), 0f)
        assertEquals(1f, PiterfyHeartBurst.particleAlpha(0.45f), 0f)
        assertEquals(0f, PiterfyHeartBurst.particleAlpha(1f), 1e-6f)
        assertTrue(PiterfyHeartBurst.particleAlpha(0.7f) in 0.1f..0.9f)
    }
}
