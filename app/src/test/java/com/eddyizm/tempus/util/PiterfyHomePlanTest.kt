package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyHomePlan.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHomePlanTest {
    private val defaults = PiterfyHomePlan.DEFAULT_SECTORS

    @Test fun partsCoverTheWholeDay() {
        assertEquals(Part.NIGHT, PiterfyHomePlan.part(0))
        assertEquals(Part.NIGHT, PiterfyHomePlan.part(4))
        assertEquals(Part.MORNING, PiterfyHomePlan.part(5))
        assertEquals(Part.MORNING, PiterfyHomePlan.part(10))
        assertEquals(Part.DAY, PiterfyHomePlan.part(11))
        assertEquals(Part.DAY, PiterfyHomePlan.part(17))
        assertEquals(Part.EVENING, PiterfyHomePlan.part(18))
        assertEquals(Part.EVENING, PiterfyHomePlan.part(22))
        assertEquals(Part.NIGHT, PiterfyHomePlan.part(23))
        assertEquals(Part.NIGHT, PiterfyHomePlan.part(24))   // 24 = północ
        assertEquals(Part.NIGHT, PiterfyHomePlan.part(-1))
    }

    @Test fun defaultLayoutIsUntouched() {
        assertTrue(PiterfyHomePlan.isUntouched(defaults))
        assertFalse(PiterfyHomePlan.isUntouched(defaults.reversed()))
        assertFalse(PiterfyHomePlan.isUntouched(defaults.drop(1)))
    }

    @Test fun usersOwnOrderIsNeverChanged() {
        val custom = defaults.reversed()
        for (part in Part.values()) assertEquals(custom, PiterfyHomePlan.orderSectors(custom, part, true, custom))
    }

    @Test fun defaultLayoutGetsTimeOfDayPromotions() {
        val evening = PiterfyHomePlan.orderSectors(defaults, Part.EVENING, true, defaults)
        assertEquals(
            listOf(Constants.HOME_SECTOR_CONTINUE_LISTENING, Constants.HOME_SECTOR_RADIO_STATION, Constants.HOME_SECTOR_MADE_FOR_YOU),
            evening.take(3),
        )
        assertEquals(defaults.size, evening.size)
        assertEquals(defaults.toSet(), evening.toSet())
        val night = PiterfyHomePlan.orderSectors(defaults, Part.NIGHT, true, defaults)
        assertEquals(Constants.HOME_SECTOR_RADIO_STATION, night.first())
    }

    @Test fun hiddenSectorsStayHiddenAndRestKeepsOrder() {
        val visible = defaults.filter { it != Constants.HOME_SECTOR_RADIO_STATION }
        val night = PiterfyHomePlan.orderSectors(visible, Part.NIGHT, true, defaults)
        assertFalse(Constants.HOME_SECTOR_RADIO_STATION in night)
        assertEquals(Constants.HOME_SECTOR_CONTINUE_LISTENING, night.first())
        val rest = night.drop(1)
        assertEquals(visible.filter { it != Constants.HOME_SECTOR_CONTINUE_LISTENING }, rest)
    }

    @Test fun disabledPlanChangesNothing() {
        assertEquals(defaults, PiterfyHomePlan.orderSectors(defaults, Part.NIGHT, false, defaults))
    }

    @Test fun mixesAreOrderedByTimeOfDayStably() {
        val names = listOf("Bass Boost", "Chill Mix", "Rock Mix", "Dark Mix", "Happy Mix", "Phonk Mix", "Lata 90.")
        val morning = PiterfyHomePlan.orderMixes(names, Part.MORNING, true).map { names[it] }
        assertEquals("Chill Mix", morning.first())
        assertEquals("Happy Mix", morning[1])
        assertTrue(morning.indexOf("Dark Mix") > morning.indexOf("Rock Mix"))
        val night = PiterfyHomePlan.orderMixes(names, Part.NIGHT, true).map { names[it] }
        assertEquals(listOf("Dark Mix", "Phonk Mix"), night.take(2))   // remis: kolejność wejściowa
        assertEquals(names.indices.toList(), PiterfyHomePlan.orderMixes(names, Part.NIGHT, false))
        assertEquals(names.size, PiterfyHomePlan.orderMixes(names, Part.EVENING, true).toSet().size)
    }

    @Test fun progressIsClampedAndSafe() {
        assertEquals(0f, PiterfyHomePlan.progress(0, 1000), 0.0001f)
        assertEquals(0.25f, PiterfyHomePlan.progress(250, 1000), 0.0001f)
        assertEquals(1f, PiterfyHomePlan.progress(5000, 1000), 0.0001f)
        assertEquals(0f, PiterfyHomePlan.progress(500, 0), 0.0001f)
        assertEquals(0f, PiterfyHomePlan.progress(-5, 1000), 0.0001f)
    }
}
