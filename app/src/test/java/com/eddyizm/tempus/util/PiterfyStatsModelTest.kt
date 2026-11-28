package com.eddyizm.tempus.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Czyta prawdziwy wynik serwera (tools/stats_fixture.py), więc łączy stats.py z parserem w aplikacji. */
class PiterfyStatsModelTest {
    private val stats: PiterfyStatsModel.Stats by lazy {
        val json = javaClass.classLoader!!.getResourceAsStream("piterfy_stats_sample.json")!!.bufferedReader().readText()
        PiterfyStatsModel.parse(json)!!
    }

    @Test fun libraryNumbersMatchTheServer() {
        val l = stats.library
        assertEquals(5L, l.tracks)
        assertEquals(3L, l.albums)
        assertEquals(4L, l.artists)
        assertEquals(3L, l.lossless)
        assertEquals(2L, l.lossy)
        assertEquals(1L, l.hires)
        assertEquals(1L, l.hiresUpsampled)
        assertEquals(1L, l.played)
        assertEquals(4L, l.unplayed)
        assertEquals(listOf("flac", "mp3", "m4a"), l.formats.map { it.label })
        assertEquals(60_000_000L, l.formatBytes["flac"])
        assertEquals(listOf("<128", "128–191", "192–255", "256–319", "320+"), l.lossyBitrates.map { it.label })
        assertEquals(1L, l.lossyBitrates.first { it.label == "128–191" }.n)
        assertEquals(listOf("16", "24"), l.bitDepths.map { it.label })
    }

    @Test fun recordsAndYears() {
        val l = stats.library
        assertEquals("Song 1", l.oldest!!.title)
        assertEquals(1991, l.oldest!!.year)
        assertEquals(2021, l.newest!!.year)
        assertEquals(200, l.longest!!.seconds)
        assertEquals(15, l.shortest!!.seconds)
        assertEquals(30_000_000L, l.biggest!!.bytes)
    }

    @Test fun qualityLedgerAndReplacementList() {
        val q = stats.library.quality
        assertEquals(1, q.ok)
        assertEquals(1, q.fake)
        assertEquals(1, q.transcoded)
        assertEquals(1, q.upsampled)
        assertEquals(1, q.pending)
        assertEquals(1, q.genuineLossless)
        assertEquals(2, q.replaceTotal)
        assertEquals(listOf("s4", "s3"), q.replace.map { it.id })     // najczęściej słuchane „udawane” pierwsze
        assertEquals(128, q.replace.first().sourceKbps)
        val segments = PiterfyStatsModel.qualitySegments(q)
        assertEquals(listOf("ok", "suspect", "fake", "pending"), segments.map { it.key })
        assertEquals(listOf(1.0, 1.0, 2.0, 1.0), segments.map { it.n })
        assertEquals(1.0, segments.sumOf { it.fraction.toDouble() }, 1e-5)
    }

    @Test fun serverSection() {
        val s = stats.server
        assertEquals(12, s.weeks.size)
        assertEquals(5, s.weeks.sumOf { it.n }.toInt())
        assertEquals(2, s.acquisition.got)
        assertEquals(1, s.acquisition.missed)
        assertEquals(1, s.acquisition.pending)
        assertEquals(0.667, s.acquisition.soulseekRate!!, 1e-9)
        assertEquals(mapOf("Last.fm" to PiterfyStatsModel.Rate(2, 2), "Deezer" to PiterfyStatsModel.Rate(1, 0)).toSortedMap(),
            s.acquisition.engines.toSortedMap())
        assertEquals(PiterfyStatsModel.Rate(2, 2), s.acquisition.pools["new"])
        assertEquals(1, s.liked)
        assertEquals(1, s.trashed)
        assertEquals("2026-11-15T04:00", s.lastRun)
        assertEquals("2026-11-16T09:12:00Z", s.lastScan)
        assertEquals(100L, s.trashBytes)
        assertEquals(false, s.scanning)
    }

    @Test fun diskSegmentsClampToTheDisk() {
        val (segments, usedPct) = PiterfyStatsModel.diskSegments(stats.server.disk, 500, stats.server.trashBytes)!!
        assertEquals(60, usedPct)
        assertEquals(listOf(500L, 100L, 0L, 400L), segments.map { it.n.toLong() })
        assertEquals(1.0, segments.sumOf { it.fraction.toDouble() }, 1e-5)
        assertNull(PiterfyStatsModel.diskSegments(null, 1, 1))
        assertNull(PiterfyStatsModel.diskSegments(PiterfyStatsModel.Disk(0, 0, 0), 1, 1))
        val tooBig = PiterfyStatsModel.diskSegments(PiterfyStatsModel.Disk(1000, 600, 400), 900, 900)!!.first
        assertEquals(listOf(600L, 0L, 0L, 400L), tooBig.map { it.n.toLong() })
    }

    @Test fun wrappedPeriods() {
        assertEquals(listOf("2026-11", "2026-10", "2026"), stats.periods.map { it.key })
        val nov = stats.periods.first()
        assertEquals("month", nov.kind)
        assertEquals(true, nov.partial)
        assertEquals(90L, nov.plays)
        assertEquals(15, nov.activeDays)
        assertEquals(15, nov.longestStreak)
        assertEquals("Listopad 2026", nov.label)
        assertEquals(125, nov.compare!!.plays)
        assertEquals(40, nov.compare!!.prevPlays)
        assertEquals("repeater", nov.listener!!.id)
        assertTrue(nov.listener!!.why.isNotEmpty())
        assertNull(nov.nightShare)
        assertTrue(nov.topTracks.isNotEmpty())
        assertEquals("2026-01-01", stats.historySince)
    }

    @Test fun storyFieldsCoversIdsAndGenresComeFromTheServer() {
        val nov = stats.periods.first()
        val track = nov.topTracks.first()
        assertEquals("s1", track.id)
        assertEquals("Song 1", track.title)
        assertEquals("al-al1", track.cover)
        assertEquals(200, track.seconds)
        assertEquals(listOf("al-al1", "al-al0", "al-al1"), nov.topArtists.map { it.cover })
        assertEquals(listOf("Rock"), nov.topGenres.map { it.label })
        assertEquals(90L, nov.topGenres.first().n)
    }

    @Test fun storyFieldsAreOptionalForOlderServers() {
        val json = """{"library":{},"wrapped":{"periods":[{"key":"2026-11","label":"Listopad 2026","plays":5,
            "top_artists":[{"name":"A","plays":5}],"top_tracks":[{"title":"T","artist":"A","plays":5}]}]}}"""
        val p = PiterfyStatsModel.parse(json)!!.periods.single()
        assertNull(p.topArtists.single().cover)
        assertEquals("", p.topTracks.single().id)
        assertNull(p.topTracks.single().cover)
        assertEquals(0, p.topTracks.single().seconds)
        assertTrue(p.topGenres.isEmpty())
    }

    @Test fun garbageIsRejectedNotCrashed() {
        assertNull(PiterfyStatsModel.parse(null))
        assertNull(PiterfyStatsModel.parse(""))
        assertNull(PiterfyStatsModel.parse("[]"))
        assertNull(PiterfyStatsModel.parse("{\"error\":\"x\"}"))
        val minimal = PiterfyStatsModel.parse("{\"library\":{}}")!!
        assertEquals(0L, minimal.library.tracks)
        assertTrue(minimal.periods.isEmpty())
        assertNull(minimal.server.disk)
    }

    @Test fun serverHealthComesFromTheServerWithAlertsSortedWorstFirst() {
        val h = stats.server.health!!
        assertEquals(PiterfyStatsModel.Level.CRIT, h.status)
        assertEquals(listOf("library_space", "backup_missing"), h.alerts.map { it.code })
        assertEquals(1, h.crit)
        assertEquals(1, h.warn)
        assertTrue(h.alerts.first().detail.contains("12,0 GB"))
        val space = h.items.first { it.label == "Wolne miejsce" }
        assertEquals(PiterfyStatsModel.Level.CRIT, space.level)
        assertEquals(PiterfyStatsModel.Level.OK, h.items.first { it.label == "Dysk muzyki" }.level)
        assertEquals(PiterfyStatsModel.Level.WARN, h.items.last().level)
    }

    @Test fun healthIsAbsentOnAnOlderStackAndForgivesBadEntries() {
        assertNull(PiterfyStatsModel.parse("{\"library\":{},\"server\":{}}")!!.server.health)
        val h = PiterfyStatsModel.parse(
            "{\"library\":{},\"server\":{\"health\":{\"alerts\":[{\"level\":\"warn\",\"title\":\"A\"},{\"level\":\"crit\",\"title\":\"B\"}," +
                "{\"level\":\"warn\"},{\"level\":\"nonsense\",\"title\":\"C\"}],\"items\":[{\"label\":\"X\"},{\"value\":\"no label\"}]}}}"
        )!!.server.health!!
        assertEquals(listOf("B", "A", "C"), h.alerts.map { it.title })
        assertEquals(PiterfyStatsModel.Level.CRIT, h.status) // bez pola „status”: poziom najgorszego alarmu
        assertEquals(listOf("X"), h.items.map { it.label })
        assertEquals("—", h.items.single().value)
        assertEquals(PiterfyStatsModel.Level.OK, h.alerts.last().level)
    }
}
