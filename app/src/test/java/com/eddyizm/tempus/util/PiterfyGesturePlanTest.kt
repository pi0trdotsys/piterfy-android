package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyGesturePlan.Axis
import com.eddyizm.tempus.util.PiterfyGesturePlan.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyGesturePlanTest {
    @Test fun doubleTapZonesAreLeftRightAndNarrowCentre() {
        assertEquals(Zone.LEFT, PiterfyGesturePlan.zoneOf(10f, 1000f))
        assertEquals(Zone.LEFT, PiterfyGesturePlan.zoneOf(399f, 1000f))
        assertEquals(Zone.CENTER, PiterfyGesturePlan.zoneOf(500f, 1000f))
        assertEquals(Zone.RIGHT, PiterfyGesturePlan.zoneOf(601f, 1000f))
        assertEquals(Zone.CENTER, PiterfyGesturePlan.zoneOf(5f, 0f))   // nieznana szerokość
    }

    @Test fun seekMovesTenSecondsAndNeverLeavesTheTrack() {
        assertEquals(50_000L, PiterfyGesturePlan.seekTarget(60_000, 200_000, Zone.LEFT))
        assertEquals(70_000L, PiterfyGesturePlan.seekTarget(60_000, 200_000, Zone.RIGHT))
        assertEquals(0L, PiterfyGesturePlan.seekTarget(4_000, 200_000, Zone.LEFT))
        assertEquals(199_500L, PiterfyGesturePlan.seekTarget(195_000, 200_000, Zone.RIGHT))   // nie przeskakuje na następny utwór
        assertEquals(75_000L, PiterfyGesturePlan.seekTarget(65_000, -1, Zone.RIGHT))          // nieznana długość (radio, strumień)
        assertEquals(65_000L, PiterfyGesturePlan.seekTarget(65_000, 200_000, Zone.CENTER))
    }

    @Test fun labelsOnlyForSides() {
        assertEquals("−10 s", PiterfyGesturePlan.seekLabel(Zone.LEFT))
        assertEquals("+10 s", PiterfyGesturePlan.seekLabel(Zone.RIGHT))
        assertNull(PiterfyGesturePlan.seekLabel(Zone.CENTER))
    }

    @Test fun axisLocksOnlyAfterTheSlop() {
        assertNull(PiterfyGesturePlan.axisLock(3f, -4f, 8f))
        assertEquals(Axis.HORIZONTAL, PiterfyGesturePlan.axisLock(20f, 5f, 8f))
        assertEquals(Axis.VERTICAL, PiterfyGesturePlan.axisLock(-4f, 30f, 8f))
        assertEquals(Axis.HORIZONTAL, PiterfyGesturePlan.axisLock(10f, -10f, 8f))   // remis idzie do poziomu
    }

    @Test fun rubberBandResistsAndIsBoundedAndSymmetric() {
        assertEquals(0f, PiterfyGesturePlan.rubberBand(0f, 100f), 0f)
        val small = PiterfyGesturePlan.rubberBand(20f, 100f)
        val big = PiterfyGesturePlan.rubberBand(200f, 100f)
        val huge = PiterfyGesturePlan.rubberBand(5000f, 100f)
        assertTrue(small in 0f..20f)           // na początku prawie 1:1, nigdy więcej niż palec
        assertTrue(small > 15f)
        assertTrue(big > small && big <= 100f)
        assertTrue(huge <= 100f && huge >= big)
        assertEquals(-small, PiterfyGesturePlan.rubberBand(-20f, 100f), 0.0001f)
        assertEquals(0f, PiterfyGesturePlan.rubberBand(50f, 0f), 0f)
    }

    @Test fun edgeBumpOnlyBeyondTheEndsOfTheQueue() {
        assertTrue(PiterfyGesturePlan.pastEdge(0, 5, 40f, 36f))       // pierwszy utwór, palec w prawo
        assertFalse(PiterfyGesturePlan.pastEdge(0, 5, -40f, 36f))     // w lewo jest następny
        assertTrue(PiterfyGesturePlan.pastEdge(4, 5, -40f, 36f))      // ostatni, palec w lewo
        assertFalse(PiterfyGesturePlan.pastEdge(4, 5, 40f, 36f))
        assertFalse(PiterfyGesturePlan.pastEdge(2, 5, 90f, 36f))      // środek kolejki
        assertFalse(PiterfyGesturePlan.pastEdge(0, 5, 10f, 36f))      // za krótko
        assertTrue(PiterfyGesturePlan.pastEdge(0, 1, -40f, 36f))      // jedyny utwór: oba końce
        assertFalse(PiterfyGesturePlan.pastEdge(0, 0, 90f, 36f))
    }

    @Test fun backGestureShrinksAndMovesCoverMonotonically() {
        assertEquals(1f, PiterfyGesturePlan.backScale(0f), 0f)
        assertEquals(0.86f, PiterfyGesturePlan.backScale(1f), 0.0001f)
        var last = 1f
        for (i in 1..10) {
            val s = PiterfyGesturePlan.backScale(i / 10f)
            assertTrue(s <= last)
            last = s
        }
        assertEquals(0f, PiterfyGesturePlan.backShift(0f, 300f), 0f)
        assertEquals(300f, PiterfyGesturePlan.backShift(1f, 300f), 0.001f)
        assertEquals(PiterfyGesturePlan.backShift(1f, 300f), PiterfyGesturePlan.backShift(7f, 300f), 0f)   // postęp jest przycinany
        assertEquals(8f, PiterfyGesturePlan.backCorner(0f, 8f), 0f)
        assertEquals(28f, PiterfyGesturePlan.backCorner(1f, 8f), 0.001f)
        assertEquals(40f, PiterfyGesturePlan.backCorner(1f, 40f), 0.001f)   // większe zaokrąglenie niż docelowe zostaje
    }
}
