package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyStatsModel.Compare
import com.eddyizm.tempus.util.PiterfyStatsModel.Count
import com.eddyizm.tempus.util.PiterfyStatsModel.Listener
import com.eddyizm.tempus.util.PiterfyStatsModel.Period
import com.eddyizm.tempus.util.PiterfyStatsModel.Track
import com.eddyizm.tempus.util.PiterfyStoryPlan.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyStoryPlanTest {
    private fun period(
        artists: List<Count> = listOf(Count("A", 30, "al-1")),
        tracks: List<Track> = listOf(Track("T", "A", 30, "s1", "al-1", 200)),
        genres: List<Count> = listOf(Count("Rock", 30)),
        listener: Listener? = Listener("loyal", "Wierny fan", "blurb", listOf("why")),
        compare: Compare? = Compare(10, 5, null, 40),
    ) = Period("month", "2026-11", "Listopad 2026", "2026-11-01", "2026-11-16", true, 90, 208, 3, 3, 15, 15, "2026-11-15", 6,
        artists, tracks, 1, null, null, listener, compare, genres)

    private fun kinds(p: Period) = PiterfyStoryPlan.slides(p).map { it.kind }

    // ── wybór slajdów ──
    @Test fun fullPeriodGivesAllEightSlidesInOrder() {
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.ARTIST, Kind.TRACKS, Kind.GENRES, Kind.LISTENER, Kind.COMPARE, Kind.FINALE), kinds(period()))
    }

    @Test fun slidesWithoutDataAreSkipped() {
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.ARTIST, Kind.TRACKS, Kind.GENRES, Kind.FINALE), kinds(period(listener = null, compare = null)))
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.TRACKS, Kind.LISTENER, Kind.COMPARE, Kind.FINALE), kinds(period(artists = emptyList(), genres = emptyList())))
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.FINALE), kinds(period(emptyList(), emptyList(), emptyList(), null, null)))
    }

    @Test fun compareSlideNeedsAtLeastOneDelta() {
        assertFalse(Kind.COMPARE in kinds(period(compare = Compare(null, null, null, 40))))
        assertTrue(Kind.COMPARE in kinds(period(compare = Compare(null, null, -7, 40))))
    }

    @Test fun blankNamesDoNotMakeSlides() {
        val p = period(artists = listOf(Count(" ", 3)), tracks = listOf(Track("", "A", 3)), genres = listOf(Count("Rock", 0)))
        assertEquals(listOf(Kind.INTRO, Kind.TIME, Kind.LISTENER, Kind.COMPARE, Kind.FINALE), kinds(p))
    }

    // ── czas trwania ──
    @Test fun autoAdvanceDurations() {
        val slides = PiterfyStoryPlan.slides(period())
        assertEquals(7000L, slides.first { it.kind == Kind.INTRO }.durationMs)
        assertEquals(7000L, slides.first { it.kind == Kind.GENRES }.durationMs)
        assertEquals(12000L, slides.first { it.kind == Kind.TRACKS }.durationMs)
        assertEquals(0L, slides.last().durationMs)          // finał zostaje
    }

    // ── ścieżka dźwiękowa ──
    @Test fun windowStartsAtThirtyFivePercentForNormalSongs() {
        val p = PiterfyStoryPlan.preview(200)
        assertEquals(70_000L, p.startMs)
        assertEquals(45_000L, p.lengthMs)
    }

    @Test fun shortSongsPlayFromTheStart() {
        val p = PiterfyStoryPlan.preview(45)
        assertEquals(0L, p.startMs)
        assertEquals(45_000L, p.lengthMs)
        assertEquals(0L, PiterfyStoryPlan.preview(59).startMs)
        assertEquals(21_000L, PiterfyStoryPlan.preview(60).startMs)
    }

    @Test fun windowNeverRunsPastTheEndOfTheSong() {
        val p = PiterfyStoryPlan.preview(10)
        assertEquals(10_000L, p.lengthMs)
        val q = PiterfyStoryPlan.preview(70)             // start 24,5 s, do końca 45,5 s: mieści się w 45 s
        assertEquals(24_500L, q.startMs)
        assertEquals(45_000L, q.lengthMs)
        val r = PiterfyStoryPlan.preview(100)            // start 35 s, do końca 65 s: ucięte do 45 s
        assertEquals(45_000L, r.lengthMs)
        val short = PiterfyStoryPlan.preview(62)         // start 21,7 s, do końca 40,3 s
        assertEquals(21_700L, short.startMs)
        assertEquals(40_300L, short.lengthMs)
    }

    @Test fun unknownLengthPlaysFromTheStart() {
        assertEquals(PiterfyStoryPlan.Preview(0, 45_000L), PiterfyStoryPlan.preview(0))
        assertEquals(PiterfyStoryPlan.Preview(0, 45_000L), PiterfyStoryPlan.preview(-5))
    }

    @Test fun volumeFadesInAndOut() {
        val p = PiterfyStoryPlan.preview(200)
        assertEquals(0f, PiterfyStoryPlan.volumeAt(0, p), 1e-6f)
        assertEquals(0.5f, PiterfyStoryPlan.volumeAt(400, p), 1e-6f)
        assertEquals(1f, PiterfyStoryPlan.volumeAt(800, p), 1e-6f)
        assertEquals(1f, PiterfyStoryPlan.volumeAt(20_000, p), 1e-6f)
        assertEquals(0.5f, PiterfyStoryPlan.volumeAt(45_000 - 300, p), 1e-6f)
        assertEquals(0f, PiterfyStoryPlan.volumeAt(45_000, p), 1e-6f)
        assertEquals(0f, PiterfyStoryPlan.volumeAt(-1, p), 1e-6f)
    }

    private fun track(id: String, seconds: Int, title: String = "T$id") = Track(title, "A$id", 10, id, "al-$id", seconds)

    @Test fun soundtrackTakesTheFirstFiveTracksWithAnId() {
        val tracks = listOf(track("1", 200), track("", 200), track("2", 30), track("3", 100), track("4", 100), track("5", 100), track("6", 100))
        val s = PiterfyStoryPlan.soundtrack(tracks)
        assertEquals(listOf("1", "2", "3", "4", "5"), s.map { it.id })
        assertEquals("T1", s[0].title); assertEquals("A1", s[0].artist)
        assertEquals(PiterfyStoryPlan.preview(200), s[0].preview)
        assertEquals(PiterfyStoryPlan.preview(30), s[1].preview)
    }

    @Test fun soundtrackIsEmptyWithoutIds() {
        assertTrue(PiterfyStoryPlan.soundtrack(emptyList()).isEmpty())
        assertTrue(PiterfyStoryPlan.soundtrack(listOf(track("", 100))).isEmpty())
        assertNull(PiterfyStoryPlan.positionAt(emptyList(), 0))
    }

    @Test fun positionWalksThroughSegmentsBackToBack() {
        val s = PiterfyStoryPlan.soundtrack(listOf(track("1", 200), track("2", 30), track("3", 100)))   // 45 s, 30 s, 45 s
        assertEquals(120_000L, PiterfyStoryPlan.soundtrackMs(s))
        assertEquals(PiterfyStoryPlan.Position(0, 0), PiterfyStoryPlan.positionAt(s, 0))
        assertEquals(PiterfyStoryPlan.Position(0, 44_999), PiterfyStoryPlan.positionAt(s, 44_999))
        assertEquals(PiterfyStoryPlan.Position(1, 0), PiterfyStoryPlan.positionAt(s, 45_000))
        assertEquals(PiterfyStoryPlan.Position(1, 29_999), PiterfyStoryPlan.positionAt(s, 74_999))
        assertEquals(PiterfyStoryPlan.Position(2, 0), PiterfyStoryPlan.positionAt(s, 75_000))
        assertEquals(PiterfyStoryPlan.Position(2, 44_999), PiterfyStoryPlan.positionAt(s, 119_999))
        assertNull(PiterfyStoryPlan.positionAt(s, 120_000))        // po ostatnim fragmencie cisza
        assertNull(PiterfyStoryPlan.positionAt(s, -1))
    }

    @Test fun nextSegmentIsPreparedFiveSecondsBeforeTheEnd() {
        val s = PiterfyStoryPlan.soundtrack(listOf(track("1", 200), track("2", 200)))
        assertFalse(PiterfyStoryPlan.prefetchDue(s, PiterfyStoryPlan.Position(0, 39_999)))
        assertTrue(PiterfyStoryPlan.prefetchDue(s, PiterfyStoryPlan.Position(0, 40_000)))
        assertTrue(PiterfyStoryPlan.prefetchDue(s, PiterfyStoryPlan.Position(0, 44_000)))
        assertFalse(PiterfyStoryPlan.prefetchDue(s, PiterfyStoryPlan.Position(1, 44_000)))      // ostatni: nie ma następnego
    }

    @Test fun theWholeShowIsCoveredByMusic() {
        // ok. 60 s pokazu: 6 slajdów × 7 s + utwory 12 s; jeden utwór 45 s plus drugi domykają całość
        val s = PiterfyStoryPlan.soundtrack(listOf(track("1", 200), track("2", 200)))
        val show = 6 * PiterfyStoryPlan.SLIDE_MS + PiterfyStoryPlan.TRACKS_MS
        assertTrue(PiterfyStoryPlan.soundtrackMs(s) >= show)
        assertNotNull(PiterfyStoryPlan.positionAt(s, show - 1))
    }

    @Test fun previewUrlForcesLightMp3AndKeepsTheTimeOffset() {
        val url = "https://s/rest/stream?u=me&t=tok&s=salt&v=1.16.1&c=Tempus&maxBitRate=320&format=flac&timeOffset=70&id=s1"
        val out = PiterfyStoryPlan.previewUrl(url)
        assertEquals("https://s/rest/stream?u=me&t=tok&s=salt&v=1.16.1&c=Tempus&timeOffset=70&id=s1&maxBitRate=128&format=mp3", out)
        assertEquals(1, Regex("maxBitRate=").findAll(out).count())
        assertEquals(1, Regex("format=").findAll(out).count())
    }

    @Test fun previewUrlAddsTheParametersWhenThereAreNone() {
        assertEquals("https://s/rest/stream?id=1&maxBitRate=128&format=mp3", PiterfyStoryPlan.previewUrl("https://s/rest/stream?id=1"))
        assertEquals("https://s/stream?maxBitRate=128&format=mp3", PiterfyStoryPlan.previewUrl("https://s/stream"))
        assertEquals("https://s/stream?u=a&maxBitRate=128&format=mp3", PiterfyStoryPlan.previewUrl("https://s/stream?format=opus&u=a"))
    }

    // ── animacje i napisy ──
    @Test fun easingClampsAndAppearHonoursTheDelay() {
        assertEquals(0f, PiterfyStoryPlan.ease(-1f), 1e-6f)
        assertEquals(1f, PiterfyStoryPlan.ease(2f), 1e-6f)
        assertEquals(0f, PiterfyStoryPlan.appear(100, 300), 1e-6f)
        assertEquals(1f, PiterfyStoryPlan.appear(5_000, 300), 1e-6f)
        assertTrue(PiterfyStoryPlan.appear(500, 300) in 0.01f..0.99f)
        assertTrue(PiterfyStoryPlan.stagger(2) > PiterfyStoryPlan.stagger(1))
    }

    @Test fun listeningCounterKeepsItsUnitAndLandsOnTheFinalText() {
        assertEquals("0\u00A0min", PiterfyStoryPlan.listeningAt(208, 0f, true))
        assertEquals("104\u00A0min", PiterfyStoryPlan.listeningAt(208, 0.5f, true))
        assertEquals("208\u00A0min", PiterfyStoryPlan.listeningAt(208, 1f, true))
        assertEquals("0\u00A0godz.", PiterfyStoryPlan.listeningAt(6022, 0f, true))
        assertEquals("50\u00A0godz.", PiterfyStoryPlan.listeningAt(6022, 0.5f, true))     // 6022 min * 0,5 / 60 = 50,18
        assertEquals(PiterfyStatsFormat.listening(6022, true), PiterfyStoryPlan.listeningAt(6022, 1f, true))
        assertEquals("100\u00A0h", PiterfyStoryPlan.listeningAt(6022, 1f, false))
    }

    @Test fun deltaCounterShowsArrowAndGrowingPercent() {
        assertEquals("▲ 0%", PiterfyStoryPlan.deltaAt(125, 0f))
        assertEquals("▲ 63%", PiterfyStoryPlan.deltaAt(125, 0.5f))
        assertEquals("▲ 125%", PiterfyStoryPlan.deltaAt(125, 1f))
        assertEquals("▼ 7%", PiterfyStoryPlan.deltaAt(-7, 1f))
        assertEquals("=", PiterfyStoryPlan.deltaAt(0, 0.3f))
    }

    @Test fun genreBarFraction() {
        assertEquals(1f, PiterfyStoryPlan.barFraction(50, 50), 1e-6f)
        assertEquals(0.5f, PiterfyStoryPlan.barFraction(25, 50), 1e-6f)
        assertEquals(0.03f, PiterfyStoryPlan.barFraction(1, 1000), 1e-6f)
        assertEquals(0f, PiterfyStoryPlan.barFraction(0, 50), 1e-6f)
    }

    @Test fun periodLengthInDays() {
        assertEquals(0L, PiterfyStoryPlan.epochDay("1970-01-01"))
        assertEquals(20_773L, PiterfyStoryPlan.epochDay("2026-11-16"))
        assertEquals(16, PiterfyStoryPlan.daysIn("2026-11-01", "2026-11-16", 1))
        assertEquals(366, PiterfyStoryPlan.daysIn("2024-01-01", "2024-12-31", 1))
        assertEquals(365, PiterfyStoryPlan.daysIn("2026-01-01", "2026-12-31", 1))
        assertEquals(29, PiterfyStoryPlan.daysIn("2024-02-01", "2024-02-29", 1))
        assertEquals(7, PiterfyStoryPlan.daysIn("", "x", 7))
    }

    @Test fun fileNamePerSlide() {
        assertEquals("piterfy-wrapped-2026-11-tracks.png", PiterfyStoryPlan.fileName(period(), Kind.TRACKS))
    }

    // ── akcent z okładki ──
    private fun argb(r: Int, g: Int, b: Int, a: Int = 255) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun accentAveragesSaturatedBrightPixelsAndIgnoresGreyAndDark() {
        val pixels = IntArray(100) { argb(120, 120, 120) }           // szare tło
        for (i in 0 until 10) pixels[i] = argb(220, 40, 40)           // czerwone
        for (i in 10 until 20) pixels[i] = argb(10, 10, 60)           // zbyt ciemne
        val accent = PiterfyStoryPlan.accentOf(pixels)!!
        assertEquals(220, (accent shr 16) and 0xFF)
        assertEquals(40, (accent shr 8) and 0xFF)
        assertEquals(0xFF, accent ushr 24)
    }

    @Test fun accentIsLightenedForDarkBackgrounds() {
        val accent = PiterfyStoryPlan.accentOf(IntArray(16) { argb(100, 20, 20) })!!
        assertEquals(200, (accent shr 16) and 0xFF)
        assertTrue(((accent shr 8) and 0xFF) in 35..45)
    }

    @Test fun greyOrTransparentCoversHaveNoAccent() {
        assertNull(PiterfyStoryPlan.accentOf(IntArray(64) { argb(128, 128, 128) }))
        assertNull(PiterfyStoryPlan.accentOf(IntArray(64) { argb(255, 0, 0, 40) }))
        assertNull(PiterfyStoryPlan.accentOf(IntArray(0)))
        assertNotNull(PiterfyStoryPlan.accentOf(IntArray(4) { argb(0, 200, 90) }))
    }
}
