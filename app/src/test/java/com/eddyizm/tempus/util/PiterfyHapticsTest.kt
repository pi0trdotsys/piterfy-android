package com.eddyizm.tempus.util

import android.view.HapticFeedbackConstants
import com.eddyizm.tempus.util.PiterfyHaptics.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHapticsTest {
    @Test fun modernDevicesGetTheModernConstants() {
        assertEquals(HapticFeedbackConstants.CONFIRM, PiterfyHaptics.constantFor(Kind.CONFIRM, 34))
        assertEquals(HapticFeedbackConstants.REJECT, PiterfyHaptics.constantFor(Kind.REJECT, 30))
        assertEquals(HapticFeedbackConstants.GESTURE_START, PiterfyHaptics.constantFor(Kind.START, 31))
        assertEquals(HapticFeedbackConstants.GESTURE_END, PiterfyHaptics.constantFor(Kind.END, 31))
        assertEquals(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK, PiterfyHaptics.constantFor(Kind.TICK, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, PiterfyHaptics.constantFor(Kind.CLICK, 23))
    }

    @Test fun olderDevicesFallBackToWhatTheyHave() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, PiterfyHaptics.constantFor(Kind.CONFIRM, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, PiterfyHaptics.constantFor(Kind.REJECT, 29))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, PiterfyHaptics.constantFor(Kind.TICK, 33))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, PiterfyHaptics.constantFor(Kind.CLICK, 22))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, PiterfyHaptics.constantFor(Kind.HEAVY, 24))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, PiterfyHaptics.constantFor(Kind.START, 24))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, PiterfyHaptics.constantFor(Kind.END, 24))
    }

    @Test fun everyKindMapsOnEverySupportedApi() {
        for (sdk in 24..37) Kind.values().forEach { PiterfyHaptics.constantFor(it, sdk) }
    }

    @Test fun throttleSuppressesBursts() {
        assertTrue(PiterfyHaptics.gapElapsed(-1, 0))             // pierwszy impuls zawsze
        assertFalse(PiterfyHaptics.gapElapsed(100, 110))
        assertTrue(PiterfyHaptics.gapElapsed(100, 128))
    }

    @Test fun detentsStepWithTheFraction() {
        assertEquals(0, PiterfyHaptics.detent(0f, 20))
        assertEquals(10, PiterfyHaptics.detent(0.5f, 20))
        assertEquals(20, PiterfyHaptics.detent(1.7f, 20))      // przycięte
        assertEquals(0, PiterfyHaptics.detent(-1f, 20))
        assertEquals(1, PiterfyHaptics.detent(0.9f, 0))        // steps ≥ 1
    }

    @Test fun enabledDefaultsToOnWithoutAnApp() {
        assertTrue(PiterfyHaptics.enabled())
    }
}
