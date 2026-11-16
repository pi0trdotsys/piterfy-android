package com.eddyizm.tempus.util

import com.eddyizm.tempus.subsonic.models.Child
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlin.random.Random

class PiterfySmartPolicyTest {
    private fun song(id: String, artist: String, genre: String = "Rock", title: String = "t$id", plays: Long = 0): Child =
        Child(id).also { it.artist = artist; it.genre = genre; it.title = title; it.playCount = plays }

    private fun pick(
        lists: List<List<Child>>, history: List<Child> = emptyList(), queue: List<String> = emptyList(),
        recent: Set<String> = emptySet(), count: Int = 5, exploration: Int = 50, perArtist: Int = 1, seed: Int = 1,
    ) = PiterfySmartPolicy.pick(PiterfySmartPolicy.merge(lists), history, queue, recent, count, exploration, perArtist, Random(seed))

    @Test fun seedsAreDistinctArtistsNewestFirst() {
        val history = listOf(song("1", "Daft Punk"), song("2", "Daft Punk feat. Pharrell"), song("3", "Justice"),
            song("4", "Air"), song("5", "Moby"), song("6", "Röyksopp"))
        assertEquals(listOf("1", "3", "4", "5"), PiterfySmartPolicy.seeds(history, 4).map { it.id })
        assertTrue(PiterfySmartPolicy.seeds(emptyList()).isEmpty())
    }

    @Test fun mergeAddsUpHitsAcrossSeeds() {
        val a = song("a", "A"); val b = song("b", "B"); val c = song("c", "C")
        val rows = PiterfySmartPolicy.merge(listOf(listOf(a, b), listOf(b, c), listOf(b))).associateBy { it.song.id }
        assertEquals(3.0, rows.getValue("b").hits, 0.0)
        assertEquals(1.0, rows.getValue("a").hits, 0.0)
        assertEquals(0, rows.getValue("b").best)
    }

    @Test fun neverProposesQueuedPlayedRememberedOrDuplicateSongs() {
        val out = pick(
            listOf(listOf(song("q", "A"), song("p", "B"), song("m", "C"), song("d1", "D", title = "Same Song"),
                song("d2", "D", title = "Same Song (Live)"), song("ok", "E"))),
            history = listOf(song("p", "B")), queue = listOf("q"), recent = setOf("m"), count = 10,
        )
        assertEquals(setOf("d1", "ok"), out.map { it.id }.toSet())
    }

    @Test fun podcastsAreNeverRecommended() {
        val out = pick(listOf(listOf(song("x", "Show", genre = "Podcast"), song("y", "Band"))))
        assertEquals(listOf("y"), out.map { it.id })
    }

    @Test fun oneSongPerArtistInABatch() {
        val many = (0 until 30).flatMap { listOf(song("s$it", "Same"), song("o$it", "Other$it")) }
        val out = pick(listOf(many), count = 6)
        assertEquals(1, out.count { it.artist == "Same" })
    }

    @Test fun recentArtistsAreStronglyAvoided() {
        val history = listOf(song("h1", "Recent"), song("h2", "Other"))
        val pool = (0 until 20).flatMap { listOf(song("r$it", "Recent"), song("n$it", "Fresh$it")) }
        var recent = 0; var total = 0
        for (seed in 1..40) for (s in pick(listOf(pool), history, count = 3, perArtist = 3, seed = seed)) {
            total++; if (s.artist == "Recent") recent++
        }
        assertTrue("recent share ${recent.toDouble() / total}", recent.toDouble() / total < 0.15)
    }

    @Test fun resultsVaryAndFollowTheWeights() {
        val pool = (0 until 60).map { song("s$it", "A$it") }
        val hot = song("hot", "Hot")
        val rows = PiterfySmartPolicy.merge(listOf(pool, listOf(hot), listOf(hot), listOf(hot)))
        val counts = HashMap<String, Int>()
        for (seed in 1..200) PiterfySmartPolicy.pick(rows, count = 5, random = Random(seed)).forEach { counts.merge(it.id, 1, Int::plus) }
        val others = counts.filterKeys { it != "hot" }.values.sum() / 60.0
        assertTrue("variety ${counts.size}", counts.size > 50)
        assertTrue("hot ${counts["hot"]} vs $others", counts.getValue("hot") > others * 1.6)
    }

    @Test fun explorationSliderShiftsBetweenHeardAndUnheard() {
        val heard = (0 until 40).map { song("h$it", "H$it", plays = 20) }
        val fresh = (0 until 40).map { song("f$it", "F$it") }
        fun share(exploration: Int): Double {
            var f = 0; var n = 0
            for (seed in 1..40) for (s in pick(listOf(heard + fresh), exploration = exploration, seed = seed)) { n++; if (s.id.startsWith("f")) f++ }
            return f.toDouble() / n
        }
        assertTrue(share(100) > share(0) + 0.15)
    }

    @Test fun genreDoesNotLockInAfterARun() {
        val history = (1..4).map { song("g$it", "G$it", genre = "Metal") }
        val pool = (0 until 30).flatMap { listOf(song("m$it", "M$it", genre = "Metal"), song("p$it", "P$it", genre = "Pop")) }
        var pop = 0; var n = 0
        for (seed in 1..40) for (s in pick(listOf(pool), history, count = 4, seed = seed)) { n++; if (s.genre == "Pop") pop++ }
        assertTrue("pop share ${pop.toDouble() / n}", pop.toDouble() / n > 0.55)
    }

    @Test fun starredSongsGetABonus() {
        val starred = song("star", "S").also { it.starred = Date() }
        val rows = PiterfySmartPolicy.merge(listOf((0 until 60).map { song("s$it", "A$it") } + starred))
        var hits = 0
        for (seed in 1..200) if (PiterfySmartPolicy.pick(rows, count = 5, random = Random(seed)).any { it.id == "star" }) hits++
        assertTrue("starred picked $hits/200", hits > 200 * 5 / 61.0 * 1.3)
    }

    @Test fun memoryKeepsRecentIdsOnlyAndIsBounded() {
        val now = 20_000L * 86_400_000L
        val memory = PiterfySmartPolicy.remember(mapOf("old" to now - 20 * 86_400_000L, "week" to now - 3 * 86_400_000L), listOf("a", "b"), now)
        assertEquals(setOf("a", "b", "week"), memory.keys)
        assertEquals(setOf("a", "b"), PiterfySmartPolicy.recentIds(memory, now, 2))
        assertEquals(600, PiterfySmartPolicy.remember(emptyMap(), (0 until 700).map { "id$it" }, now).size)
    }
}
