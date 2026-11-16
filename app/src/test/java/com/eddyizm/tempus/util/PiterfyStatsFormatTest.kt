package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyStatsFormatTest {
    private fun nb(s: String) = s.replace(' ', ' ')

    @Test fun numbersAreGroupedPerLanguage() {
        assertEquals("1 234 567", nb(PiterfyStatsFormat.num(1234567, true)))
        assertEquals("1,234,567", PiterfyStatsFormat.num(1234567, false))
        assertEquals("999", PiterfyStatsFormat.num(999, true))
        assertEquals("0", PiterfyStatsFormat.num(null, true))
        assertEquals("-1 200", nb(PiterfyStatsFormat.num(-1200, true)))
        assertEquals("0", PiterfyStatsFormat.num(Double.NaN, true))
    }

    @Test fun decimalsDropTrailingZeros() {
        assertEquals("1,5", PiterfyStatsFormat.dec(1.5, 1, true))
        assertEquals("1.5", PiterfyStatsFormat.dec(1.5, 1, false))
        assertEquals("2", PiterfyStatsFormat.dec(2.0, 2, true))
        assertEquals("10", PiterfyStatsFormat.dec(10.0, 0, true))      // zera całkowite zostają
        assertEquals("100", PiterfyStatsFormat.dec(100.0, 1, true))
        assertEquals("0", PiterfyStatsFormat.dec(0.0, 1, true))
    }

    @Test fun bytesPickTheRightUnit() {
        assertEquals("0 B", nb(PiterfyStatsFormat.bytes(0, true)))
        assertEquals("1,5 KB", nb(PiterfyStatsFormat.bytes(1536, true)))
        assertEquals("5 GB", nb(PiterfyStatsFormat.bytes(5L * 1024 * 1024 * 1024, true)))
        assertEquals("12,3 GB", nb(PiterfyStatsFormat.bytes((12.34 * 1024 * 1024 * 1024).toLong(), true)))
        assertEquals("123 GB", nb(PiterfyStatsFormat.bytes((123.4 * 1024 * 1024 * 1024).toLong(), true)))
        assertEquals("1.5 KB", nb(PiterfyStatsFormat.bytes(1536, false)))
    }

    @Test fun durationsAndListening() {
        assertEquals("1 d 1 h", nb(PiterfyStatsFormat.duration(90061)))
        assertEquals("1 h 2 min", nb(PiterfyStatsFormat.duration(3725)))
        assertEquals("0 min", nb(PiterfyStatsFormat.duration(59)))
        assertEquals("95 min", nb(PiterfyStatsFormat.listening(95, true)))
        assertEquals("10 godz.", nb(PiterfyStatsFormat.listening(600, true)))
        assertEquals("412 h", nb(PiterfyStatsFormat.listening(24700, false)))
    }

    @Test fun percentDeltaAndDates() {
        assertEquals("25%", PiterfyStatsFormat.percent(1.0, 4.0, 0, true))
        assertEquals("12,5%", PiterfyStatsFormat.percent(1.0, 8.0, 1, true))
        assertEquals("—", PiterfyStatsFormat.percent(1.0, 0.0, 0, true))
        assertEquals("▲ 18%", PiterfyStatsFormat.delta(18))
        assertEquals("▼ 7%", PiterfyStatsFormat.delta(-7))
        assertEquals("=", PiterfyStatsFormat.delta(0))
        assertEquals("", PiterfyStatsFormat.delta(null))
        assertEquals("5 lis 2026", nb(PiterfyStatsFormat.day("2026-11-05", true)))
        assertEquals("5 Nov 2026", nb(PiterfyStatsFormat.day("2026-11-05", false)))
        assertEquals("—", PiterfyStatsFormat.day("zepsute", true))
        assertEquals("—", PiterfyStatsFormat.day("2026-13-05", true))
        assertEquals("16.11", PiterfyStatsFormat.shortDay("2026-11-16"))
        assertEquals("", PiterfyStatsFormat.shortDay(null))
    }

    @Test fun barFractionKeepsATraceAndZero() {
        assertEquals(0f, PiterfyStatsFormat.fraction(0.0, 10.0), 0f)
        assertEquals(0.02f, PiterfyStatsFormat.fraction(1.0, 1000.0), 1e-6f)
        assertEquals(1f, PiterfyStatsFormat.fraction(10.0, 10.0), 0f)
        assertEquals(0f, PiterfyStatsFormat.fraction(5.0, 0.0), 0f)
    }

    @Test fun wrapBreaksByMeasuredWidthAndEllipsizes() {
        val measure = { s: String -> s.length * 10f }
        assertEquals(listOf("ala ma kota"), PiterfyStatsFormat.wrap("ala ma kota", 1000f, 3, measure))
        assertEquals(listOf("ala ma", "kota i", "psa"), PiterfyStatsFormat.wrap("ala ma kota i psa", 80f, 5, measure))
        val cut = PiterfyStatsFormat.wrap("bardzo długi tytuł który się nie mieści w dwóch liniach", 100f, 2, measure)
        assertEquals(2, cut.size)
        assertTrue(cut[1].endsWith("…"))
        assertTrue(measure(cut[1]) <= 100f)
        assertEquals(emptyList<String>(), PiterfyStatsFormat.wrap("", 100f, 2, measure))
        assertEquals(listOf("superdługiejednosłowo"), PiterfyStatsFormat.wrap("superdługiejednosłowo", 50f, 2, measure))
    }
}
