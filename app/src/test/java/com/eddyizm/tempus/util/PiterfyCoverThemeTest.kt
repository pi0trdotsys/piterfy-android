package com.eddyizm.tempus.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyCoverThemeTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val surface = 0xFF121212.toInt()

    @Test
    fun luminanceAndContrastMatchWcagReferenceValues() {
        assertEquals(0.0, PiterfyCoverTheme.luminance(black), 1e-9)
        assertEquals(1.0, PiterfyCoverTheme.luminance(white), 1e-9)
        assertEquals(21.0, PiterfyCoverTheme.contrast(white, black), 1e-6)
        assertEquals(1.0, PiterfyCoverTheme.contrast(white, white), 1e-9)
        // szary #767676 to klasyczna granica kontrastu 4,5 : 1 z bielą
        assertTrue(PiterfyCoverTheme.contrast(white, 0xFF767676.toInt()) in 4.4..4.7)
    }

    @Test
    fun brightCoverColoursAreDarkenedUntilWhiteTextIsReadable() {
        for (bright in intArrayOf(0xFFFFD400.toInt(), 0xFF7CFFB2.toInt(), 0xFFFF8FA3.toInt(), 0xFFFFFFFF.toInt(), 0xFFE0E0E0.toInt())) {
            val fixed = PiterfyCoverTheme.readableHeader(bright)
            assertTrue("kontrast $fixed", PiterfyCoverTheme.contrast(white, fixed) >= PiterfyCoverTheme.MIN_CONTRAST)
        }
    }

    @Test
    fun readableColoursStayUntouchedAndHueIsKept() {
        val already = 0xFF5A2D82.toInt()
        assertEquals(already, PiterfyCoverTheme.readableHeader(already))
        val yellow = PiterfyCoverTheme.readableHeader(0xFFFFD400.toInt())
        val before = PiterfyCoverTheme.toHsv(0xFFFFD400.toInt())[0]
        val after = PiterfyCoverTheme.toHsv(yellow)[0]
        assertEquals(before, after, 3.0)  // odcień ten sam, tylko ciemniejszy
    }

    @Test
    fun nearBlackColouredCoversAreLiftedGreysAndBlackAreNot() {
        val dark = 0xFF05001A.toInt()
        val lifted = PiterfyCoverTheme.readableHeader(dark)
        assertTrue(PiterfyCoverTheme.luminance(lifted) > PiterfyCoverTheme.luminance(dark))
        assertTrue(PiterfyCoverTheme.contrast(white, lifted) >= PiterfyCoverTheme.MIN_CONTRAST)
        assertEquals(black, PiterfyCoverTheme.readableHeader(black))
        assertEquals(0xFF2A2A2A.toInt(), PiterfyCoverTheme.readableHeader(0xFF2A2A2A.toInt()))
    }

    @Test
    fun resultIsAlwaysOpaque() {
        assertEquals(0xFF, PiterfyCoverTheme.readableHeader(0x40FFD400).ushr(24))
    }

    @Test
    fun mixInterpolatesChannelsAndClampsT() {
        assertEquals(0xFF808080.toInt(), PiterfyCoverTheme.mix(black, white, 0.5f))
        assertEquals(black, PiterfyCoverTheme.mix(black, white, -3f))
        assertEquals(white, PiterfyCoverTheme.mix(black, white, 9f))
        assertEquals(0x80, PiterfyCoverTheme.withAlpha(white, 0.5f).ushr(24))
        assertEquals(0x00FFFFFF, PiterfyCoverTheme.withAlpha(white, 0f))
    }

    @Test
    fun headerGradientRunsFromReadableCoverColourToTheSurface() {
        val g = PiterfyCoverTheme.headerGradient(0xFFFFD400.toInt(), surface, translucent = false)
        assertEquals(3, g.size)
        assertEquals(PiterfyCoverTheme.readableHeader(0xFFFFD400.toInt()), g[0])
        assertEquals(surface, g[2])
        // środek leży między końcami (po kanale czerwonym: cover jest jaśniejszy niż powierzchnia)
        val r = { c: Int -> (c shr 16) and 0xFF }
        assertTrue(r(g[1]) in r(g[2])..r(g[0]))
    }

    @Test
    fun translucentGradientForGlassEndsFullyTransparent() {
        val g = PiterfyCoverTheme.headerGradient(0xFF2D7DD2.toInt(), surface, translucent = true)
        assertEquals(3, g.size)
        assertTrue((g[0] ushr 24) in 100..200)
        assertTrue((g[0] ushr 24) > (g[1] ushr 24))
        assertEquals(0, g[2] ushr 24)
    }

    @Test
    fun hsvRoundTripKeepsColours() {
        for (color in intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF336699.toInt(), 0xFFAA5500.toInt(), black, white)) {
            val hsv = PiterfyCoverTheme.toHsv(color)
            assertEquals(color, PiterfyCoverTheme.fromHsv(hsv[0], hsv[1], hsv[2]))
        }
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0), PiterfyCoverTheme.toHsv(black), 1e-9)
    }
}
