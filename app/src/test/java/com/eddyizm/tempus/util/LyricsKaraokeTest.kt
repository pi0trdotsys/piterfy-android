package com.eddyizm.tempus.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsKaraokeTest {
    @Test fun progressRunsFromLineStartToNextLineStart() {
        assertEquals(0f, PiterfyLyricsKaraoke.progress(2000, 6000, 1000, 0), 0f)
        assertEquals(0f, PiterfyLyricsKaraoke.progress(2000, 6000, 2000, 0), 0f)
        assertEquals(0.5f, PiterfyLyricsKaraoke.progress(2000, 6000, 4000, 0), 0f)
        assertEquals(1f, PiterfyLyricsKaraoke.progress(2000, 6000, 6000, 0), 0f)
        assertEquals(1f, PiterfyLyricsKaraoke.progress(2000, 6000, 9000, 0), 0f)
    }

    @Test fun positiveOffsetShowsTheLineSooner() {
        assertEquals(0.5f, PiterfyLyricsKaraoke.progress(2000, 6000, 3000, 1000), 0f)
    }

    @Test fun longGapsAreCappedAndTheLastLineHasAFixedLength() {
        assertEquals(0.5f, PiterfyLyricsKaraoke.progress(6000, 40000, 10500, 0), 0f)   // 6000→40000, ale 9 s
        assertEquals(1f, PiterfyLyricsKaraoke.progress(6000, 40000, 16000, 0), 0f)
        assertEquals(0.5f, PiterfyLyricsKaraoke.progress(40000, null, 42000, 0), 0f)    // ostatnia: 4 s
        assertEquals(1f, PiterfyLyricsKaraoke.progress(40000, null, 60000, 0), 0f)
    }

    @Test fun zeroLengthLineIsImmediatelyFull() {
        assertEquals(1f, PiterfyLyricsKaraoke.progress(1000, 1000, 1000, 0), 0f)
        assertEquals(1f, PiterfyLyricsKaraoke.progress(1000, 900, 1000, 0), 0f)
    }

    @Test fun styleDimsAndShrinksWithDistanceAndIsSymmetric() {
        assertEquals(PiterfyLyricsKaraoke.LineStyle(1f, 1f, 0f), PiterfyLyricsKaraoke.style(0))
        assertEquals(PiterfyLyricsKaraoke.style(1), PiterfyLyricsKaraoke.style(-1))
        assertEquals(0.86f, PiterfyLyricsKaraoke.style(1).scale, 0f)
        assertEquals(PiterfyLyricsKaraoke.style(5), PiterfyLyricsKaraoke.style(99))
        for (d in 1..5) {
            assertTrue(PiterfyLyricsKaraoke.style(d).alpha < PiterfyLyricsKaraoke.style(d - 1).alpha)
            assertTrue(PiterfyLyricsKaraoke.style(d).blurDp > PiterfyLyricsKaraoke.style(d - 1).blurDp)
            assertTrue(PiterfyLyricsKaraoke.style(d).blurDp <= 4f && PiterfyLyricsKaraoke.style(d).alpha > 0.2f)
        }
    }

    @Test fun blurOnlyAppliesFromTwoLinesAway() {
        assertEquals(0f, PiterfyLyricsKaraoke.blurDp(0), 0f)
        assertEquals(0f, PiterfyLyricsKaraoke.blurDp(1), 0f)
        assertEquals(0f, PiterfyLyricsKaraoke.blurDp(-1), 0f)
        assertEquals(1.4f, PiterfyLyricsKaraoke.blurDp(2), 0f)
        assertEquals(1.4f, PiterfyLyricsKaraoke.blurDp(-2), 0f)
        assertEquals(4f, PiterfyLyricsKaraoke.blurDp(9), 0f)
    }

    @Test fun wrappedLinesFillOneAfterAnotherProportionallyToTheirWidth() {
        val widths = floatArrayOf(300f, 100f)
        assertArrayEquals(floatArrayOf(0f, 0f), PiterfyLyricsKaraoke.fillPerLine(widths, 0f), 0.0001f)
        assertArrayEquals(floatArrayOf(0.5f, 0f), PiterfyLyricsKaraoke.fillPerLine(widths, 0.375f), 0.0001f)
        assertArrayEquals(floatArrayOf(1f, 0f), PiterfyLyricsKaraoke.fillPerLine(widths, 0.75f), 0.0001f)
        assertArrayEquals(floatArrayOf(1f, 0.5f), PiterfyLyricsKaraoke.fillPerLine(widths, 0.875f), 0.0001f)
        assertArrayEquals(floatArrayOf(1f, 1f), PiterfyLyricsKaraoke.fillPerLine(widths, 1f), 0.0001f)
        assertArrayEquals(floatArrayOf(1f), PiterfyLyricsKaraoke.fillPerLine(floatArrayOf(200f), 2f), 0.0001f)   // postęp > 1 przycięty
        assertArrayEquals(floatArrayOf(0f, 0f), PiterfyLyricsKaraoke.fillPerLine(floatArrayOf(0f, 0f), 0.5f), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f), PiterfyLyricsKaraoke.fillPerLine(floatArrayOf(0f, 0f), 1f), 0f)
    }
}
