package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyCoverFadeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val surface = 0xFF121212.toInt()

    @Test fun easeIsMonotoneAndClamped() {
        assertEquals(0f, PiterfyCoverTheme.ease(-1f), 0f)
        assertEquals(1f, PiterfyCoverTheme.ease(2f), 0f)
        assertEquals(0.5f, PiterfyCoverTheme.ease(0.5f), 1e-6f)
        var last = -1f
        for (i in 0..20) {
            val e = PiterfyCoverTheme.ease(i / 20f)
            assertTrue(e >= last)
            last = e
        }
    }

    @Test fun fadeHitsBothEndsExactly() {
        val a = 0xFF2D5A27.toInt()
        val b = 0xFF5A2D82.toInt()
        assertEquals(a, PiterfyCoverTheme.fade(a, b, 0f))
        assertEquals(b, PiterfyCoverTheme.fade(a, b, 1f))
        assertEquals(b, PiterfyCoverTheme.fade(a, b, 3f))
    }

    @Test fun fadeBetweenReadableColoursNeverLosesWhiteTextContrast() {
        val pairs = listOf(
            PiterfyAmbientSamples.green to PiterfyAmbientSamples.purple,
            PiterfyAmbientSamples.dark to PiterfyAmbientSamples.red,
            PiterfyAmbientSamples.purple to PiterfyAmbientSamples.dark,
        )
        for ((from, to) in pairs) {
            val floor = minOf(PiterfyCoverTheme.contrast(white, from), PiterfyCoverTheme.contrast(white, to))
            assertTrue(floor >= PiterfyCoverTheme.MIN_CONTRAST)
            for (i in 0..30) {
                val c = PiterfyCoverTheme.fade(from, to, i / 30f)
                assertTrue("krok $i: ${PiterfyCoverTheme.contrast(white, c)}", PiterfyCoverTheme.contrast(white, c) >= floor - 0.25)   // zaokrąglenie kanałów do 8 bitów daje drobny rozrzut
            }
        }
    }

    @Test fun readableAccentKeepsWhiteIconsReadableForAnyCover() {
        val covers = listOf(PiterfyAmbientSamples.green, PiterfyAmbientSamples.purple, PiterfyAmbientSamples.red, PiterfyAmbientSamples.dark,
            0xFFFFFFFF.toInt(), 0xFFFAFAF5.toInt(), 0xFFFFD400.toInt(), 0xFF000000.toInt(), 0x00FF8800)
        for (cover in covers) {
            val accent = PiterfyCoverTheme.readableAccent(cover)
            assertEquals(0xFF, accent ushr 24)
            assertTrue("kontrast ${PiterfyCoverTheme.contrast(white, accent)} dla $cover",
                PiterfyCoverTheme.contrast(white, accent) >= PiterfyCoverTheme.MIN_CONTRAST)
        }
    }

    @Test fun readableAccentIsLighterThanTheCoverWhenThereIsRoom() {
        val ambient = PiterfyAmbientSamples.dark   // bardzo ciemny: rozjaśnienie jest możliwe
        val accent = PiterfyCoverTheme.readableAccent(ambient)
        assertTrue(PiterfyCoverTheme.luminance(accent) > PiterfyCoverTheme.luminance(ambient))
    }

    @Test fun visibleOnDarkLiftsDarkColoursAndLeavesBrightOnes() {
        val dark = 0xFF1B2A1B.toInt()
        val lifted = PiterfyCoverTheme.visibleOnDark(dark, surface)
        assertTrue(PiterfyCoverTheme.contrast(lifted, surface) >= 3.0)
        val bright = 0xFF7CFFB2.toInt()
        assertEquals(bright, PiterfyCoverTheme.visibleOnDark(bright, surface))
        // czerń i biel: wynik zawsze czytelny na ciemnym tle
        assertTrue(PiterfyCoverTheme.contrast(PiterfyCoverTheme.visibleOnDark(0xFF000000.toInt(), surface), surface) >= 3.0)
        assertTrue(PiterfyCoverTheme.contrast(PiterfyCoverTheme.visibleOnDark(white, surface), surface) >= 3.0)
    }

    private object PiterfyAmbientSamples {
        val green = 0xFF1D521D.toInt()
        val purple = 0xFF5A2D82.toInt()
        val red = 0xFF7A2230.toInt()
        val dark = 0xFF2A2A2A.toInt()
    }
}
