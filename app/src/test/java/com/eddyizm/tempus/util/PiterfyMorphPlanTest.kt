package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyMorphPlan.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyMorphPlanTest {
    private val mini = Rect(60f, 40f, 180f, 160f)          // 120 × 120
    private val big = Rect(72f, 220f, 1008f, 1156f)        // 936 × 936 w panelu 1080 × 2200

    @Test fun `ghost is visible only while the sheet is between the two states`() {
        assertFalse(PiterfyMorphPlan.ghostVisible(0f))
        assertFalse(PiterfyMorphPlan.ghostVisible(0.003f))
        assertTrue(PiterfyMorphPlan.ghostVisible(0.01f))
        assertTrue(PiterfyMorphPlan.ghostVisible(0.5f))
        assertTrue(PiterfyMorphPlan.ghostVisible(0.99f))
        assertFalse(PiterfyMorphPlan.ghostVisible(0.997f))
        assertFalse(PiterfyMorphPlan.ghostVisible(1f))
    }

    @Test fun `rectangle interpolation starts on the mini cover and ends on the big one`() {
        assertEquals(mini, PiterfyMorphPlan.lerp(mini, big, 0f))
        assertEquals(big, PiterfyMorphPlan.lerp(mini, big, 1f))
        val half = PiterfyMorphPlan.lerp(mini, big, 0.5f)
        assertEquals((60f + 72f) / 2, half.left, 0.001f)
        assertEquals((40f + 220f) / 2, half.top, 0.001f)
        assertEquals((120f + 936f) / 2, half.width, 0.001f)
        assertEquals(half.width, half.height, 0.001f)                    // kwadrat zostaje kwadratem
    }

    @Test fun `interpolation is clamped outside zero to one`() {
        assertEquals(mini, PiterfyMorphPlan.lerp(mini, big, -3f))
        assertEquals(big, PiterfyMorphPlan.lerp(mini, big, 7f))
        assertEquals(0f, PiterfyMorphPlan.lerp(0f, 10f, -1f), 0f)
        assertEquals(10f, PiterfyMorphPlan.lerp(0f, 10f, 2f), 0f)
    }

    @Test fun `big cover and ghost cross-fade only at the end and are complementary`() {
        assertEquals(0f, PiterfyMorphPlan.bigCoverAlpha(0f), 0f)
        assertEquals(0f, PiterfyMorphPlan.bigCoverAlpha(0.5f), 0f)
        assertEquals(0f, PiterfyMorphPlan.bigCoverAlpha(PiterfyMorphPlan.BLEND_FROM), 0f)
        assertEquals(1f, PiterfyMorphPlan.bigCoverAlpha(1f), 0f)
        var last = -1f
        for (i in 0..100) {
            val o = i / 100f
            val a = PiterfyMorphPlan.bigCoverAlpha(o)
            assertTrue("monotonicznie", a >= last)
            last = a
            assertEquals(1f, a + PiterfyMorphPlan.ghostAlpha(o), 0.0001f)
        }
        val mid = PiterfyMorphPlan.bigCoverAlpha((PiterfyMorphPlan.BLEND_FROM + 1f) / 2)
        assertTrue(mid > 0.4f && mid < 0.6f)
    }

    @Test fun `corner radius moves from the mini radius to the big radius`() {
        assertEquals(8f, PiterfyMorphPlan.radiusAt(0f, 8f, 48f), 0f)
        assertEquals(48f, PiterfyMorphPlan.radiusAt(1f, 8f, 48f), 0f)
        assertEquals(28f, PiterfyMorphPlan.radiusAt(0.5f, 8f, 48f), 0.001f)
        assertEquals(0f, PiterfyMorphPlan.radiusAt(0.5f, 0f, 0f), 0f)
    }

    @Test fun `cover morph is allowed for the normal portrait layout`() {
        assertTrue(PiterfyMorphPlan.shouldMorphCover(true, false, mini, big, 1080, 2200))
    }

    @Test fun `cover morph is refused for anything unusual`() {
        assertFalse("animacje wyłączone", PiterfyMorphPlan.shouldMorphCover(false, false, mini, big, 1080, 2200))
        assertFalse("telewizor", PiterfyMorphPlan.shouldMorphCover(true, true, mini, big, 1080, 2200))
        assertFalse("brak prostokąta", PiterfyMorphPlan.shouldMorphCover(true, false, null, big, 1080, 2200))
        assertFalse("brak prostokąta", PiterfyMorphPlan.shouldMorphCover(true, false, mini, null, 1080, 2200))
        assertFalse("pusty", PiterfyMorphPlan.shouldMorphCover(true, false, Rect(0f, 0f, 0f, 0f), big, 1080, 2200))
        assertFalse("panel bez rozmiaru", PiterfyMorphPlan.shouldMorphCover(true, false, mini, big, 0, 2200))
        assertFalse("okładka poza panelem (inna strona pagera)", PiterfyMorphPlan.shouldMorphCover(true, false, mini, Rect(1100f, 220f, 2036f, 1156f), 1080, 2200))
        assertFalse("okładka poniżej panelu", PiterfyMorphPlan.shouldMorphCover(true, false, mini, Rect(72f, 2000f, 1008f, 2936f), 1080, 2200))
        assertFalse("nie kwadrat", PiterfyMorphPlan.shouldMorphCover(true, false, mini, Rect(72f, 220f, 1008f, 700f), 1080, 2200))
        assertFalse("duża okładka nie jest wyraźnie większa", PiterfyMorphPlan.shouldMorphCover(true, false, mini, Rect(72f, 220f, 232f, 380f), 1080, 2200))
    }

    @Test fun `touch is fresh for a few seconds only`() {
        assertTrue(PiterfyMorphPlan.touchFresh(10_000, 9_500))
        assertTrue(PiterfyMorphPlan.touchFresh(10_000, 7_000))
        assertFalse(PiterfyMorphPlan.touchFresh(10_000, 6_999))
        assertFalse(PiterfyMorphPlan.touchFresh(10_000, 0))        // nie było dotyku (klawiatura, pilot)
        assertFalse(PiterfyMorphPlan.touchFresh(10_000, 10_500))   // zegar poszedł wstecz
    }

    @Test fun `transition names are unique per destination, list and position`() {
        val a = PiterfyMorphPlan.transitionName(12, 345, 0)
        assertEquals(a, PiterfyMorphPlan.transitionName(12, 345, 0))
        assertNotEquals(a, PiterfyMorphPlan.transitionName(12, 345, 1))
        assertNotEquals(a, PiterfyMorphPlan.transitionName(12, 346, 0))
        assertNotEquals(a, PiterfyMorphPlan.transitionName(13, 345, 0))
        assertTrue(a.startsWith("pf_morph_"))
    }

    @Test fun `list morph needs motion, no television, a fresh touch and a target`() {
        assertTrue(PiterfyMorphPlan.shouldMorphList(true, false, true, true))
        assertFalse(PiterfyMorphPlan.shouldMorphList(false, false, true, true))
        assertFalse(PiterfyMorphPlan.shouldMorphList(true, true, true, true))
        assertFalse(PiterfyMorphPlan.shouldMorphList(true, false, false, true))
        assertFalse(PiterfyMorphPlan.shouldMorphList(true, false, true, false))
    }

    @Test fun `return is recognised only on the screen the user left`() {
        assertTrue(PiterfyMorphPlan.isReturnTo(7, 7))
        assertFalse(PiterfyMorphPlan.isReturnTo(7, 8))
        assertFalse(PiterfyMorphPlan.isReturnTo(0, 0))
    }

    @Test fun `duration and timeout stay within the spec`() {
        assertTrue(PiterfyMorphPlan.DURATION_MS in 380..450)
        assertTrue(PiterfyMorphPlan.POSTPONE_TIMEOUT_MS in 200..500)
    }
}
