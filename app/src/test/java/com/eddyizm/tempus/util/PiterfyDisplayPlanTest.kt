package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyDisplayPlanTest {
    @Test fun textScaleIsClampedAndSnapped() {
        assertEquals(85, PiterfyDisplayPlan.clampTextScale(10))
        assertEquals(130, PiterfyDisplayPlan.clampTextScale(500))
        assertEquals(100, PiterfyDisplayPlan.clampTextScale(100))
        assertEquals(105, PiterfyDisplayPlan.clampTextScale(104))
        assertEquals(100, PiterfyDisplayPlan.clampTextScale(102))
    }

    @Test fun hundredPercentLeavesTheSystemScaleAlone() {
        assertEquals(1.15f, PiterfyDisplayPlan.effectiveFontScale(1.15f, 100), 0.0001f)
    }

    @Test fun scaleMultipliesTheSystemScaleWithinSafeBounds() {
        assertEquals(1.3f, PiterfyDisplayPlan.effectiveFontScale(1f, 130), 0.0001f)
        assertEquals(0.85f, PiterfyDisplayPlan.effectiveFontScale(1f, 85), 0.0001f)
        assertEquals(1.495f, PiterfyDisplayPlan.effectiveFontScale(1.15f, 130), 0.0001f)
        assertEquals(2.0f, PiterfyDisplayPlan.effectiveFontScale(1.8f, 130), 0.0001f)   // góra bezpieczeństwa
        assertEquals(0.7f, PiterfyDisplayPlan.effectiveFontScale(0.7f, 85), 0.0001f)    // dół bezpieczeństwa
    }

    @Test fun densityFallsBackToComfortable() {
        assertEquals("comfortable", PiterfyDisplayPlan.normalizeDensity(null))
        assertEquals("comfortable", PiterfyDisplayPlan.normalizeDensity("tight"))
        assertEquals("compact", PiterfyDisplayPlan.normalizeDensity("compact"))
        assertEquals(1f, PiterfyDisplayPlan.densityFactor("whatever"), 0.0001f)
        assertEquals(0.75f, PiterfyDisplayPlan.densityFactor("compact"), 0.0001f)
        assertEquals(6, PiterfyDisplayPlan.scaledSpace(8, "compact"))
        assertEquals(0, PiterfyDisplayPlan.scaledSpace(-4, "compact"))
    }

    @Test fun previewReflectsEverySetting() {
        val p = PiterfyDisplayPlan.preview(true, 100, true, true, 120, "compact", "reduced", true)
        assertTrue(p.glass); assertTrue(p.coverTint); assertTrue(p.trueBlack)
        assertEquals(1.2f, p.textScale, 0.0001f)
        assertEquals(0.75f, p.density, 0.0001f)
        assertFalse(p.animated)
        assertEquals(0.38f, p.glassAlpha, 0.0001f)
        val off = PiterfyDisplayPlan.preview(false, 80, false, false, 100, "comfortable", "full", false)
        assertEquals(1f, off.glassAlpha, 0.0001f)
        assertTrue(off.animated)
        assertTrue(PiterfyDisplayPlan.preview(true, 0, true, false, 100, null, null, true).glassAlpha > p.glassAlpha)
    }
}
