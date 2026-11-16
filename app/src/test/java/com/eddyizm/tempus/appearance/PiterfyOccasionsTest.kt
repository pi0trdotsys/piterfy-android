package com.eddyizm.tempus.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyOccasionsTest {
    private fun on(y: Int, m: Int, d: Int) = PiterfyOccasions.active(y, m, d)
    private fun idOn(y: Int, m: Int, d: Int) = on(y, m, d)?.occasion?.id

    @Test fun epochDayMatchesKnownDates() {
        assertEquals(0L, PiterfyOccasions.epochDay(1970, 1, 1))
        assertEquals(11017L, PiterfyOccasions.epochDay(2000, 3, 1))
        assertEquals(1L, PiterfyOccasions.epochDay(2024, 3, 1) - PiterfyOccasions.epochDay(2024, 2, 29))   // rok przestępny
        assertEquals(365L, PiterfyOccasions.epochDay(2027, 1, 1) - PiterfyOccasions.epochDay(2026, 1, 1))
    }

    @Test fun easterIsComputedForManyYears() {
        val known = mapOf(2024 to (3 to 31), 2025 to (4 to 20), 2026 to (4 to 5), 2027 to (3 to 28), 2028 to (4 to 16),
            2029 to (4 to 1), 2030 to (4 to 21), 2038 to (4 to 25))
        known.forEach { (year, date) -> assertEquals("Wielkanoc $year", date, PiterfyOccasions.easterSunday(year)) }
    }

    @Test fun windowIsTwoDaysBeforeAndAfter() {
        // Boże Narodzenie: 24.12 ± 2
        assertNull(idOn(2026, 12, 20) ?.takeIf { it == "christmas" })
        assertEquals("christmas", idOn(2026, 12, 22))
        assertEquals("christmas", idOn(2026, 12, 24))
        assertEquals("christmas", idOn(2026, 12, 26))
        assertNull(idOn(2026, 12, 27))
        assertNull(idOn(2026, 12, 21))
    }

    @Test fun newYearWindowCrossesTheYearBoundary() {
        assertEquals("newyear", idOn(2026, 12, 29))
        assertEquals("newyear", idOn(2026, 12, 31))
        assertEquals("newyear", idOn(2027, 1, 1))
        assertEquals("newyear", idOn(2027, 1, 2))
        assertNull(idOn(2027, 1, 3))
        assertNull(idOn(2026, 12, 28))
    }

    @Test fun easterWindowFollowsTheMovingHoliday() {
        assertEquals("easter", idOn(2026, 4, 3))
        assertEquals("easter", idOn(2026, 4, 7))
        assertNull(idOn(2026, 4, 2))
        assertNull(idOn(2026, 4, 8))
        assertEquals("easter", idOn(2027, 3, 26))   // Wielkanoc 2027: 28.03
        assertEquals("easter", idOn(2027, 3, 30))
    }

    @Test fun otherFixedHolidays() {
        assertEquals("valentine", idOn(2027, 2, 12)); assertEquals("valentine", idOn(2027, 2, 16)); assertNull(idOn(2027, 2, 17))
        assertEquals("halloween", idOn(2026, 10, 29)); assertEquals("halloween", idOn(2026, 11, 2)); assertNull(idOn(2026, 11, 3))
        assertEquals("independence", idOn(2026, 11, 9)); assertEquals("independence", idOn(2026, 11, 13)); assertNull(idOn(2026, 11, 16))
        assertEquals("music", idOn(2026, 6, 19)); assertEquals("music", idOn(2026, 6, 23))
        assertEquals("mikolajki", idOn(2026, 12, 4)); assertEquals("mikolajki", idOn(2026, 12, 8))
        assertEquals("birthday", idOn(2026, 10, 10)); assertEquals("birthday", idOn(2026, 10, 14))
    }

    @Test fun matchTellsHowFarFromTheEvent() {
        assertEquals(-2, on(2026, 12, 22)!!.daysFromEvent)
        assertEquals(0, on(2026, 12, 24)!!.daysFromEvent)
        assertEquals(2, on(2026, 12, 26)!!.daysFromEvent)
    }

    @Test fun mappingToIconAndTheme() {
        val m = on(2026, 10, 31)!!.occasion
        assertEquals("halloween", m.iconId); assertEquals("halloween", m.themeId)
        assertEquals("neon", on(2026, 6, 21)!!.occasion.iconId); assertEquals("noir_neon", on(2026, 6, 21)!!.occasion.themeId)
    }

    @Test fun overlappingWindowsPickTheNearerHoliday() {
        val first = PiterfyOccasions.Occasion("a", PiterfyOccasions.Rule.Fixed(5, 10), "i", "t")
        val second = PiterfyOccasions.Occasion("b", PiterfyOccasions.Rule.Fixed(5, 14), "i", "t")
        val both = listOf(first, second)
        assertEquals("a", PiterfyOccasions.active(2026, 5, 11, both, 2)!!.occasion.id)
        assertEquals("b", PiterfyOccasions.active(2026, 5, 13, both, 2)!!.occasion.id)
        assertEquals("a", PiterfyOccasions.active(2026, 5, 12, both, 2)!!.occasion.id)   // remis: wcześniejsza na liście
    }

    @Test fun nextOccasionIsTheNearestWindowStart() {
        val next = PiterfyOccasions.next(2026, 11, 16)!!
        assertEquals("mikolajki", next.first.id)
        assertEquals(18, next.second)                         // okno zaczyna się 4.12
        assertNotNull(PiterfyOccasions.next(2026, 12, 30))
        assertTrue(PiterfyOccasions.next(2026, 1, 1)!!.second > 0)
    }

    @Test fun noOccasionOnOrdinaryDays() {
        assertNull(on(2026, 11, 16)); assertNull(on(2026, 8, 20)); assertNull(on(2027, 5, 5))
    }
}
