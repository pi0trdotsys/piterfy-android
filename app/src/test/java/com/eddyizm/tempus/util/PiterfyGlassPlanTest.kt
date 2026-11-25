package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyGlassPlan.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyGlassPlanTest {
    private fun plan(sdk: Int = 34, on: Boolean = true, intensity: Int = 60, powerSave: Boolean = false, animators: Boolean = true) =
        PiterfyGlassPlan.plan(sdk, on, intensity, powerSave, animators)

    @Test fun offWhenDisabled() {
        val p = plan(on = false, intensity = 100)
        assertEquals(Mode.OFF, p.mode)
        assertFalse(p.enabled)
        assertEquals(1f, p.fillAlpha, 0f)
        assertFalse(p.animate)
    }

    @Test fun blurNeedsAndroid12AndNoPowerSave() {
        assertEquals(Mode.TINT, plan(sdk = 30).mode)
        assertEquals(Mode.TINT, plan(sdk = 24).mode)
        assertEquals(Mode.BLUR, plan(sdk = 31).mode)
        assertEquals(Mode.BLUR, plan(sdk = 36).mode)
        assertEquals(Mode.TINT, plan(sdk = 34, powerSave = true).mode)
        assertTrue(plan(sdk = 34).blur)
        assertFalse(plan(sdk = 30).blur)
    }

    @Test fun tintHasNoBlurRadius() {
        assertEquals(0f, plan(sdk = 28).blurDp, 0f)
        assertTrue(plan(sdk = 34).blurDp > 0f)
    }

    @Test fun intensityScalesMonotonically() {
        for (sdk in listOf(28, 34)) {
            var last = plan(sdk = sdk, intensity = 0)
            for (i in 5..100 step 5) {
                val p = plan(sdk = sdk, intensity = i)
                assertTrue("fill gets more transparent", p.fillAlpha <= last.fillAlpha)
                assertTrue("blur grows", p.blurDp >= last.blurDp)
                assertTrue("edge grows", p.edgeAlpha >= last.edgeAlpha)
                assertTrue("sheen grows", p.sheenAlpha >= last.sheenAlpha)
                last = p
            }
        }
    }

    @Test fun intensityIsClampedTo0To100() {
        assertEquals(plan(intensity = 0), plan(intensity = -40))
        assertEquals(plan(intensity = 100), plan(intensity = 250))
        assertEquals(0, PiterfyGlassPlan.clampIntensity(-1))
        assertEquals(100, PiterfyGlassPlan.clampIntensity(101))
        assertEquals(60, PiterfyGlassPlan.clampIntensity(60))
    }

    @Test fun animationNeedsAnimatorsAndNoPowerSave() {
        assertTrue(plan().animate)
        assertFalse(plan(animators = false).animate)
        assertFalse(plan(powerSave = true).animate)
    }

    @Test fun valuesStayInReadableRanges() {
        for (sdk in listOf(24, 34)) for (i in 0..100 step 10) {
            val p = plan(sdk = sdk, intensity = i)
            assertTrue(p.fillAlpha in 0.35f..0.95f)
            assertTrue(p.edgeAlpha in 0f..1f)
            assertTrue(p.sheenAlpha in 0f..0.5f)
        }
    }
}
