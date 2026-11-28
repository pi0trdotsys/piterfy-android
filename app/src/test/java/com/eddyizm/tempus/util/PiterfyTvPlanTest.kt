package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyTvPlanTest {
    private val fourMinutes = 240_000L

    // ── przewijanie ──

    @Test fun firstPressSeeksFiveSecondsNotTwelve() {
        assertEquals(5_000L, PiterfyTvPlan.scrubStepMs(0, 0, 0, fourMinutes))
    }

    @Test fun shortTrackNeverSeeksMoreThanItsCap() {
        // 20-sekundowy sygnał: 1/20 = 1 s, ale nie mniej niż jedno naciśnięcie (5 s)
        assertEquals(5_000L, PiterfyTvPlan.scrubStepMs(0, 0, 0, 20_000))
        assertTrue(PiterfyTvPlan.scrubStepMs(40, 4_000, 50, 20_000) <= 5_000L)
    }

    @Test fun holdingAcceleratesAndNeverShrinks() {
        var last = 0L
        for (heldMs in 100L..6_000L step 100L) {
            val step = PiterfyTvPlan.scrubStepMs(3, heldMs, 50, 3_600_000)
            assertTrue("held=$heldMs step=$step last=$last", step >= last)
            last = step
        }
        assertTrue(last > PiterfyTvPlan.scrubStepMs(1, 100, 50, 3_600_000))
    }

    @Test fun holdSpeedStages() {
        assertEquals(8, PiterfyTvPlan.holdSpeed(0))
        assertEquals(16, PiterfyTvPlan.holdSpeed(600))
        assertEquals(32, PiterfyTvPlan.holdSpeed(1_500))
        assertEquals(64, PiterfyTvPlan.holdSpeed(10_000))
    }

    @Test fun stepIsBoundedByOneTwentiethOfTheTrack() {
        // przy 64x i 50 ms krok to 3,2 s, ale w krótkim utworze ogranicza go 1/20 (min 5 s)
        assertTrue(PiterfyTvPlan.scrubStepMs(100, 5_000, 50, 100_000) <= 5_000)
        assertTrue(PiterfyTvPlan.scrubStepMs(100, 5_000, 250, 1_000_000) <= 50_000)
    }

    @Test fun stepIsAlwaysPositiveAndSurvivesBadClocks() {
        for (since in listOf(-5L, 0L, 1L, 50L, 250L, 10_000L)) {
            for (duration in listOf(-1L, 0L, 1L, 60_000L)) {
                val step = PiterfyTvPlan.scrubStepMs(5, 700, since, duration)
                assertTrue("since=$since duration=$duration step=$step", step >= 1_000)
            }
        }
    }

    @Test fun unknownDurationUsesOnlyTheSpeed() {
        assertEquals(5_000L, PiterfyTvPlan.scrubStepMs(0, 0, 0, -1))
        assertEquals(400L.coerceAtLeast(1_000L), PiterfyTvPlan.scrubStepMs(1, 0, 50, -1))
    }

    // ── marginesy ──

    @Test fun safeMarginsAreFivePercentOfTheScreen() {
        assertEquals(48, PiterfyTvPlan.safeHorizontalDp(960f))
        assertEquals(27, PiterfyTvPlan.safeVerticalDp(540f))
        assertEquals(64, PiterfyTvPlan.safeHorizontalDp(1920f))   // 4K bez gigantycznych marginesów
        assertEquals(24, PiterfyTvPlan.safeHorizontalDp(300f))
        assertEquals(12, PiterfyTvPlan.safeVerticalDp(100f))
        assertEquals(40, PiterfyTvPlan.safeVerticalDp(2160f))
    }

    // ── fokus ──

    @Test fun defaultFocusIsTheTopLeftUsableItem() {
        val items = listOf(
            PiterfyTvPlan.FocusCandidate(top = 0, left = 0, usable = false),    // np. pasek narzędzi, nieużyteczny
            PiterfyTvPlan.FocusCandidate(top = 120, left = 300, usable = true),
            PiterfyTvPlan.FocusCandidate(top = 120, left = 40, usable = true),
            PiterfyTvPlan.FocusCandidate(top = 400, left = 0, usable = true),
        )
        assertEquals(2, PiterfyTvPlan.pickDefaultFocus(items))
    }

    @Test fun defaultFocusWithNothingUsableIsNull() {
        assertNull(PiterfyTvPlan.pickDefaultFocus(emptyList()))
        assertNull(PiterfyTvPlan.pickDefaultFocus(listOf(PiterfyTvPlan.FocusCandidate(0, 0, false))))
    }

    @Test fun defaultFocusTieKeepsTheViewTreeOrder() {
        val items = listOf(PiterfyTvPlan.FocusCandidate(10, 10, true), PiterfyTvPlan.FocusCandidate(10, 10, true))
        assertEquals(0, PiterfyTvPlan.pickDefaultFocus(items))
    }

    @Test fun focusRetriesGrowThenStop() {
        assertEquals(120L, PiterfyTvPlan.focusRetryDelayMs(0))
        assertEquals(200L, PiterfyTvPlan.focusRetryDelayMs(1))
        var total = 0L
        var attempt = 0
        while (true) total += PiterfyTvPlan.focusRetryDelayMs(attempt++) ?: break
        assertEquals(8, attempt - 1)
        assertTrue("lista ma kilka sekund na załadowanie, nie ułamek", total in 2_000..4_000)
        assertNull(PiterfyTvPlan.focusRetryDelayMs(-1))
    }

    // ── lżejszy interfejs ──

    @Test fun glassIsCappedOnlyOnTv() {
        assertEquals(40, PiterfyTvPlan.glassIntensity(90, true))
        assertEquals(25, PiterfyTvPlan.glassIntensity(25, true))
        assertEquals(90, PiterfyTvPlan.glassIntensity(90, false))
    }

    @Test fun motionIsShorterOnTv() {
        assertEquals(294L, PiterfyTvPlan.motionDuration(420, true))
        assertEquals(420L, PiterfyTvPlan.motionDuration(420, false))
        assertEquals(0L, PiterfyTvPlan.motionDuration(0, true))
        assertEquals(1L, PiterfyTvPlan.motionDuration(1, true))
    }

    @Test fun hapticsNeverOnTv() {
        assertTrue(PiterfyTvPlan.hapticsAllowed(true, false))
        assertFalse(PiterfyTvPlan.hapticsAllowed(false, false))
        assertFalse(PiterfyTvPlan.hapticsAllowed(true, true))
    }

    @Test fun undoWaitsLongerForTheRemote() {
        assertEquals(9_000, PiterfyTvPlan.undoDurationMs(2_750, true))
        assertEquals(2_750, PiterfyTvPlan.undoDurationMs(2_750, false))
        assertEquals(12_000, PiterfyTvPlan.undoDurationMs(12_000, true))
    }
}
