package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyLyricCardLayoutTest {
    // każdy znak ma szerokość 0,5 × rozmiar (jak w teście lyrics-core.test.cjs)
    private val at = { size: Float, text: String -> text.length * size * 0.5f }

    @Test fun wrapBreaksOnWordsAndKeepsLinesWithinWidth() {
        val lines = PiterfyLyricCardLayout.wrap("aa bb cc dd", 5f) { it.length.toFloat() }
        assertEquals(listOf("aa bb", "cc dd"), lines)
        assertEquals(emptyList<String>(), PiterfyLyricCardLayout.wrap("   ", 10f) { it.length.toFloat() })
        assertEquals(emptyList<String>(), PiterfyLyricCardLayout.wrap(null, 10f) { it.length.toFloat() })
    }

    @Test fun wrapBreaksOverLongWordsByCharacters() {
        val lines = PiterfyLyricCardLayout.wrap("abcdefghij", 4f) { it.length.toFloat() }
        assertEquals(listOf("abcd", "efgh", "ij"), lines)
        // para zastępcza (emoji) nie jest rozrywana
        val emoji = PiterfyLyricCardLayout.wrap("😀😀😀", 2f) { it.codePointCount(0, it.length).toFloat() }
        assertEquals(listOf("😀😀", "😀"), emoji)
    }

    @Test fun fitUsesTheBiggestSizeThatFitsTheFrame() {
        val short = PiterfyLyricCardLayout.fit("Hello world", 888f, 700f, at)
        assertEquals(104f, short.size, 0f)
        assertFalse(short.truncated)
        val long = "x ".repeat(60).trim()
        val fit = PiterfyLyricCardLayout.fit(long, 888f, 700f, at)
        assertTrue(fit.size < 104f && fit.size >= 44f)
        assertTrue(fit.height <= 700f)
        assertEquals(long, fit.lines.joinToString(" "))
    }

    @Test fun fitTruncatesWithEllipsisWhenNothingFitsAtMinimumSize() {
        val huge = "word ".repeat(400).trim()
        val fit = PiterfyLyricCardLayout.fit(huge, 888f, 300f, at)
        assertEquals(44f, fit.size, 0f)
        assertTrue(fit.truncated)
        assertTrue(fit.lines.last().endsWith("…"))
        assertTrue(fit.height <= 300f + 44f * 1.22f)
    }
}
