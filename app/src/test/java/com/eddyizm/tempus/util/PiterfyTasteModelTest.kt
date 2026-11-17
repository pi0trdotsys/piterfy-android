package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyTasteModel.Quadrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyTasteModelTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()
    private val taste get() = PiterfyTasteModel.parse(resource("piterfy_taste_sample.json"))!!

    // ── parsowanie ──
    @Test fun parsesTheWholePanel() {
        val t = taste
        assertEquals(30, t.periodDays); assertFalse(t.estimated); assertEquals("2026-10-20", t.historySince)
        assertEquals(412L, t.totals.plays); assertEquals(1260L, t.totals.minutes); assertEquals(37, t.totals.artists); assertEquals(118, t.totals.tracks)
        assertEquals(4, t.daily.size); assertEquals(PiterfyTasteModel.Day("2026-11-15", 25), t.daily[2])
        assertEquals(7, t.artists.size); assertEquals("Artysta Jeden", t.artists[0].name); assertEquals(88L, t.artists[0].plays)
        assertEquals(2, t.tracks.size); assertEquals("Piosenka A", t.tracks[0].title); assertEquals("Płyta", t.tracks[0].album)
        assertEquals(listOf("Rock" to 120L, "Jazz" to 60L, "Ambient" to 12L), t.genres.map { it.label to it.n })
        assertEquals(1980L, t.decades[0].label.toLong()); assertEquals(150L, t.decades.last().n)
    }

    @Test fun blankImagesAndAlbumsBecomeNull() {
        val t = taste
        assertEquals("https://img.example/one.jpg", t.artists[0].img)
        assertNull(t.artists[1].img)            // pusty napis
        assertNull(t.artists[2].img)            // null w JSON
        assertNull(t.artists[3].img)            // brak pola
        assertNull(t.tracks[1].album)
    }

    @Test fun parsesMoodClockAndDiscover() {
        val t = taste
        assertEquals(0.82, t.mood.coverage, 1e-9)
        assertEquals(0.64, t.mood.averages!!.energy, 1e-9); assertEquals(121.4, t.mood.averages!!.tempo, 1e-9)
        assertEquals(4, t.mood.dots.size); assertEquals(10L, t.mood.dots[0].plays); assertEquals(0.9, t.mood.dots[0].energy, 1e-9)
        val clock = t.clock!!
        assertEquals(7, clock.grid.size); assertEquals(24, clock.grid[0].size); assertEquals(9, clock.grid[4][23])
        assertEquals("Pn", clock.days[0]); assertEquals(9, PiterfyTasteModel.clockMax(clock))
        val d = t.discover!!
        assertEquals(20, d.downloaded); assertEquals(8, d.liked); assertEquals(5, d.trashed); assertEquals(0.615, d.hitRate!!, 1e-9)
        assertEquals("Artysta Jeden", d.recentLiked[0].via); assertEquals("", d.recentLiked[1].via)
    }

    @Test fun missingOptionalPartsStayEmpty() {
        val t = PiterfyTasteModel.parse("""{"version":2,"totals":{},"mood":{"coverage":0,"averages":null,"tracks":[]},"clock":null}""")!!
        assertNull(t.clock); assertNull(t.discover); assertNull(t.mood.averages)
        assertTrue(t.daily.isEmpty() && t.artists.isEmpty() && t.tracks.isEmpty() && t.genres.isEmpty() && t.decades.isEmpty())
        assertEquals(30, t.periodDays)
        assertEquals(0L, t.totals.plays)
    }

    @Test fun rejectsOtherVersionsAndGarbage() {
        assertNull(PiterfyTasteModel.parse(null)); assertNull(PiterfyTasteModel.parse("")); assertNull(PiterfyTasteModel.parse("<html>"))
        assertNull(PiterfyTasteModel.parse("""{"version":1,"totals":{}}"""))
        assertNull(PiterfyTasteModel.parse("""{"totals":{}}"""))
        assertNull(PiterfyTasteModel.parse("[]"))
    }

    @Test fun parsesBaseline() {
        val b = PiterfyTasteModel.parseBaseline(resource("piterfy_taste_baseline_sample.json"))!!
        assertEquals(1500, b.totalTracks); assertEquals(listOf("artysta jeden", "Dawny Ulubieniec"), b.artists); assertEquals(listOf("Rock", "Pop"), b.genres)
        assertEquals(0.6, b.averages!!.energy, 1e-9); assertEquals(0.5, b.averages!!.dance, 1e-9); assertEquals(118.0, b.averages!!.tempo, 1e-9)
        assertNull(PiterfyTasteModel.parseBaseline(null)); assertNull(PiterfyTasteModel.parseBaseline("nope"))
        assertNull(PiterfyTasteModel.parseBaseline("""{"total_tracks":3}""")!!.averages)
    }

    // ── mapa nastroju ──
    @Test fun quadrantsFollowEnergyAndValence() {
        assertEquals(Quadrant.EUFORIA, PiterfyTasteModel.quadrant(0.5, 0.5))
        assertEquals(Quadrant.NAPIECIE, PiterfyTasteModel.quadrant(0.9, 0.1))
        assertEquals(Quadrant.SPOKOJ, PiterfyTasteModel.quadrant(0.4, 0.9))
        assertEquals(Quadrant.MELANCHOLIA, PiterfyTasteModel.quadrant(0.49, 0.49))
    }

    @Test fun moodSharesAreWeightedByPlaysAndSumToOne() {
        val shares = PiterfyTasteModel.moodShares(taste.mood.dots)     // 10 euforia, 5 napięcie, 3 spokój, 2 melancholia
        assertEquals(0.5, shares.getValue(Quadrant.EUFORIA), 1e-9)
        assertEquals(0.25, shares.getValue(Quadrant.NAPIECIE), 1e-9)
        assertEquals(0.15, shares.getValue(Quadrant.SPOKOJ), 1e-9)
        assertEquals(0.1, shares.getValue(Quadrant.MELANCHOLIA), 1e-9)
        assertEquals(1.0, shares.values.sum(), 1e-9)
        assertEquals(0.0, PiterfyTasteModel.moodShares(emptyList()).values.sum(), 1e-9)
    }

    @Test fun dotSizeGrowsWithTheSquareRootOfPlays() {
        assertEquals(1f, PiterfyTasteModel.dotSize(10, 10), 1e-6f)
        assertEquals(0.5f, PiterfyTasteModel.dotSize(5, 20), 1e-6f)
        assertEquals(0f, PiterfyTasteModel.dotSize(5, 0), 1e-6f)
    }

    // ── pozostałe wykresy ──
    @Test fun barAndClockLevels() {
        assertEquals(1f, PiterfyTasteModel.barFraction(30, 30), 1e-6f)
        assertEquals(0.03f, PiterfyTasteModel.barFraction(1, 1000), 1e-6f)    // najmniejszy widoczny
        assertEquals(0f, PiterfyTasteModel.barFraction(0, 1000), 1e-6f)
        assertEquals(0f, PiterfyTasteModel.clockLevel(0, 9), 1e-6f)
        assertEquals(0.5f, PiterfyTasteModel.clockLevel(3, 6), 1e-6f)
        assertEquals(1f, PiterfyTasteModel.clockLevel(20, 9), 1e-6f)
        assertEquals(1, PiterfyTasteModel.clockMax(PiterfyTasteModel.Clock(emptyList(), listOf(listOf(0, 0)))))   // nigdy 0: dzielenie bezpieczne
    }

    @Test fun weekendsAreSaturdayAndSunday() {
        assertTrue(PiterfyTasteModel.isWeekend("2026-11-14"))    // sobota
        assertTrue(PiterfyTasteModel.isWeekend("2026-11-15"))    // niedziela
        assertFalse(PiterfyTasteModel.isWeekend("2026-11-16"))   // poniedziałek
        assertFalse(PiterfyTasteModel.isWeekend("2026-11-13"))   // piątek
        assertTrue(PiterfyTasteModel.isWeekend("1970-01-03"))    // sobota
        assertFalse(PiterfyTasteModel.isWeekend("kiedyś"))
    }

    @Test fun discoverPartsAddUpToDownloaded() {
        val d = taste.discover!!
        assertEquals(Triple(8, 5, 7), PiterfyTasteModel.discoverParts(d))
        assertEquals(Triple(8, 5, 0), PiterfyTasteModel.discoverParts(d.copy(downloaded = 10)))     // nigdy ujemne
    }

    // ── porównanie ze Spotify ──
    @Test fun newOnesIgnoreCase() {
        assertEquals(setOf("Artysta Dwa"), PiterfyTasteModel.newOnes(listOf("Artysta Jeden", "Artysta Dwa"), listOf("artysta jeden", "Dawny Ulubieniec")))
        assertTrue(PiterfyTasteModel.newOnes(emptyList(), listOf("x")).isEmpty())
    }

    @Test fun deltasUsePercentagePointsAndBpm() {
        val t = taste
        val b = PiterfyTasteModel.parseBaseline(resource("piterfy_taste_baseline_sample.json"))!!
        val d = PiterfyTasteModel.deltas(t.mood.averages, b.averages).associateBy { it.key }
        assertEquals(4.0, d.getValue("energy").diff, 1e-9); assertEquals(1, d.getValue("energy").direction); assertEquals("pp", d.getValue("energy").unit)
        assertEquals(5.0, d.getValue("dance").diff, 1e-9)
        assertEquals(-7.0, d.getValue("valence").diff, 1e-9); assertEquals(-1, d.getValue("valence").direction)
        assertEquals(3.4, d.getValue("tempo").diff, 1e-9); assertEquals("BPM", d.getValue("tempo").unit)
        assertEquals(0, PiterfyTasteModel.Delta("x", 1.9, "pp").direction)
        assertTrue(PiterfyTasteModel.deltas(null, b.averages).isEmpty())
        assertTrue(PiterfyTasteModel.deltas(t.mood.averages, null).isEmpty())
    }

    @Test fun tempoGaugeSpansSixtyToOneEightyBpm() {
        assertEquals(0f, PiterfyTasteModel.tempoFraction(60.0), 1e-6f)
        assertEquals(0.5f, PiterfyTasteModel.tempoFraction(120.0), 1e-6f)
        assertEquals(1f, PiterfyTasteModel.tempoFraction(200.0), 1e-6f)
        assertEquals(0f, PiterfyTasteModel.tempoFraction(40.0), 1e-6f)
        assertNotNull(taste)
    }
}
