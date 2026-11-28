package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyPeaksModel.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyPeaksModelTest {
    private val sample = javaClass.classLoader!!.getResourceAsStream("piterfy_peaks_sample.json")!!.bufferedReader().readText()

    @Test fun parsesTheServerSample() {
        val ready = PiterfyPeaksModel.parse(200, sample) as Outcome.Ready
        assertEquals(24, ready.peaks.values.size)
        assertEquals(255, ready.peaks.values[6])
        assertEquals(213.4, ready.peaks.durationSec, 1e-9)
    }

    @Test fun pendingMeansComeBackLater() {
        assertTrue(PiterfyPeaksModel.parse(202, """{"pending":true}""") === Outcome.Pending)
        assertTrue(PiterfyPeaksModel.parse(200, """{"pending":true}""") === Outcome.Pending)
    }

    @Test fun everythingElseIsMissingAndNeverThrows() {
        assertTrue(PiterfyPeaksModel.parse(404, "nie ma") === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(500, null) === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(200, "") === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(200, "{nie json") === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(200, "[1,2,3]") === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(200, """{"v":2,"peaks":[1,2,3]}""") === Outcome.Missing)       // nieznana wersja
        assertTrue(PiterfyPeaksModel.parse(200, """{"v":1,"peaks":"x"}""") === Outcome.Missing)
        assertTrue(PiterfyPeaksModel.parse(200, """{"v":1,"peaks":[7]}""") === Outcome.Missing)           // za mało punktów
        val huge = (0..PiterfyPeaksModel.MAX_PEAKS).joinToString(",", "[", "]")
        assertTrue(PiterfyPeaksModel.parse(200, """{"v":1,"peaks":$huge}""") === Outcome.Missing)
    }

    @Test fun valuesAreClampedAndGarbageBecomesZero() {
        val ready = PiterfyPeaksModel.parse(200, """{"v":1,"peaks":[-5,300,"x",null,128],"duration":-4}""") as Outcome.Ready
        assertEquals(listOf(0, 255, 0, 0, 128), ready.peaks.values.toList())
        assertEquals(0.0, ready.peaks.durationSec, 0.0)
    }

    @Test fun downsampleKeepsPeaksWhenShrinking() {
        val peaks = IntArray(100) { if (it == 40) 255 else 10 }
        val bars = PiterfyPeaksModel.downsample(peaks, 10)
        assertEquals(10, bars.size)
        val loud = bars[4]                                            // przedział 40–49
        assertTrue(loud > 0.7f)
        assertTrue(bars.indices.filter { it != 4 }.all { bars[it] < 0.1f })
    }

    @Test fun downsampleInterpolatesWhenGrowingAndHandlesEdges() {
        val grown = PiterfyPeaksModel.downsample(intArrayOf(0, 255), 5)
        assertEquals(listOf(0f, 0.25f, 0.5f, 0.75f, 1f), grown.toList())
        assertEquals(0, PiterfyPeaksModel.downsample(intArrayOf(1, 2), 0).size)
        assertEquals(3, PiterfyPeaksModel.downsample(IntArray(0), 3).size)
        assertEquals(1, PiterfyPeaksModel.downsample(intArrayOf(10, 20, 30), 1).size)
        val same = PiterfyPeaksModel.downsample(intArrayOf(0, 51, 255), 3)
        assertEquals(listOf(0f, 0.2f, 1f), same.toList())
    }

    @Test fun barHeightsNeverVanishAndStayInRange() {
        assertEquals(0.08f, PiterfyPeaksModel.barHeight(0f), 1e-6f)
        assertEquals(1f, PiterfyPeaksModel.barHeight(1f), 1e-6f)
        assertEquals(1f, PiterfyPeaksModel.barHeight(5f), 1e-6f)
        assertTrue(PiterfyPeaksModel.barHeight(0.25f) > 0.25f)   // ciche fragmenty są podniesione
    }

    @Test fun touchMapsToBarAndFraction() {
        assertEquals(0, PiterfyPeaksModel.barAt(-10f, 200f, 50))
        assertEquals(25, PiterfyPeaksModel.barAt(100f, 200f, 50))
        assertEquals(49, PiterfyPeaksModel.barAt(900f, 200f, 50))
        assertEquals(0, PiterfyPeaksModel.barAt(10f, 0f, 50))
        assertEquals(0.5f, PiterfyPeaksModel.fractionAt(100f, 200f), 0f)
        assertEquals(1f, PiterfyPeaksModel.fractionAt(500f, 200f), 0f)
        assertEquals(0f, PiterfyPeaksModel.fractionAt(5f, 0f), 0f)
    }

    @Test fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", PiterfyPeaksModel.clock(0))
        assertEquals("0:00", PiterfyPeaksModel.clock(-5))
        assertEquals("3:07", PiterfyPeaksModel.clock(187_900))
        assertEquals("1:02:03", PiterfyPeaksModel.clock(3_723_000))
    }

    @Test fun retryBackoffGrowsAndGivesUp() {
        assertEquals(2_000L, PiterfyPeaksModel.retryDelayMs(0))
        assertEquals(4_000L, PiterfyPeaksModel.retryDelayMs(1))
        assertEquals(30_000L, PiterfyPeaksModel.retryDelayMs(4))
        assertEquals(30_000L, PiterfyPeaksModel.retryDelayMs(5))
        assertNull(PiterfyPeaksModel.retryDelayMs(PiterfyPeaksModel.MAX_ATTEMPTS))
        assertNull(PiterfyPeaksModel.retryDelayMs(-1))
    }
}
