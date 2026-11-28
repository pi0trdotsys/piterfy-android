package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyCoverColorsTest {
    private val surface = 0xFF121212.toInt()

    @Test fun tintIsOpaqueAndKeepsWhiteTextReadable() {
        for (avg in listOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00AAFF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0x80336699.toInt())) {
            val t = PiterfyCoverColors.tint(avg, surface)
            assertEquals(0xFF, (t shr 24) and 0xFF)
            assertTrue(PiterfyCoverTheme.contrast(0xFFFFFFFF.toInt(), t) >= PiterfyCoverTheme.MIN_CONTRAST)
        }
    }

    @Test fun tintStaysCloserToTheSurfaceThanToTheFullColour() {
        val red = 0xFFFF0000.toInt()
        val t = PiterfyCoverColors.tint(red, surface)
        assertTrue(PiterfyCoverTheme.luminance(t) < PiterfyCoverTheme.luminance(red))
        assertFalse(t == surface)   // ale odcień okładki nadal widać
    }

    @Test fun lastKnownFileNamesAreSafeAndDistinct() {
        val a = PiterfyLastKnown.fileName("server 1", "folder/2", "recent")
        assertFalse(a.contains("/")); assertFalse(a.contains(" "))
        assertEquals("server_1__folder_2__recent.json", a)
        assertFalse(a == PiterfyLastKnown.fileName("server 1", "folder/3", "recent"))
        assertFalse(a == PiterfyLastKnown.fileName("server 1", "folder/2", "newest"))
        assertTrue(PiterfyLastKnown.fileName(null, null, "x").endsWith("x.json"))
    }
}
