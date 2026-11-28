package com.eddyizm.tempus.util

import com.eddyizm.tempus.subsonic.models.Child
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PiterfyRadioPlanTest {
    private fun song(id: String, artist: String, rating: Int? = null, genre: String? = null, path: String? = null): Child =
        Child(id).also { it.artist = artist; it.title = "t$id"; it.userRating = rating; it.genre = genre; it.path = path }

    @Test fun cleanDropsPodcastsOneStarExcludedMemoryAndDuplicates() {
        val songs = listOf(
            song("1", "A"), song("2", "B", rating = 1), song("3", "C", genre = "Podcast"), song("4", "D", path = "Podcasty/x/1.mp3"),
            song("5", "E"), song("5", "E"), song("6", "F"), song("7", "G", rating = 5), song("8", "H"),
        )
        val out = PiterfyRadioPlan.clean(songs + null, exclude = setOf("6"), memory = listOf("8"))
        assertEquals(listOf("1", "5", "7"), out.map { it.id })
    }

    @Test fun artistKeyIgnoresCaseAccentsAndFeaturing() {
        assertEquals(PiterfyRadioPlan.artistKey(song("1", "Röyksopp")), PiterfyRadioPlan.artistKey(song("2", "ROYKSOPP")))
        assertEquals(PiterfyRadioPlan.artistKey(song("1", "Daft Punk")), PiterfyRadioPlan.artistKey(song("2", "Daft Punk feat. Pharrell")))
        assertFalse(PiterfyRadioPlan.artistKey(song("1", "Air")) == PiterfyRadioPlan.artistKey(song("2", "Moby")))
    }

    @Test fun orderNeverRepeatsAnArtistBackToBackWhenAnotherIsClose() {
        val artists = listOf("A", "B", "C", "D")
        for (seed in 1..25) {
            val songs = (1..24).map { song("s$it", artists[it % artists.size]) }
            val out = PiterfyRadioPlan.order(songs, Random(seed), previousArtist = "a")
            assertEquals(songs.map { it.id }.toSet(), out.map { it.id }.toSet())
            out.zipWithNext().forEach { (x, y) -> assertFalse("seed $seed", PiterfyRadioPlan.artistKey(x) == PiterfyRadioPlan.artistKey(y)) }
            assertFalse("seed $seed starts with the previous artist", PiterfyRadioPlan.artistKey(out.first()) == "a")
        }
    }

    @Test fun orderOnlyRepeatsWhenNothingElseIsLeft() {
        val out = PiterfyRadioPlan.order(listOf(song("1", "A"), song("2", "A"), song("3", "A")), Random(1))
        assertEquals(3, out.size)
    }

    @Test fun orderVariesBetweenRuns() {
        val songs = (1..30).map { song("s$it", "artist $it") }
        val orders = (1..8).map { PiterfyRadioPlan.order(songs, Random(it)).map { s -> s.id } }.toSet()
        assertTrue(orders.size > 1)
    }

    @Test fun buildPutsSeedFirstAndNeverTwice() {
        val seed = song("seed", "Air")
        val similar = listOf(song("seed", "Air"), song("a", "Air"), song("b", "Moby"), song("c", "Moby", rating = 1), song("d", "Royksopp"))
        val out = PiterfyRadioPlan.build(seed, similar, rng = Random(3))
        assertEquals("seed", out.first().id)
        assertEquals(1, out.count { it.id == "seed" })
        assertFalse(out.any { it.id == "c" })
        assertEquals(setOf("seed", "a", "b", "d"), out.map { it.id }.toSet())
        assertFalse(PiterfyRadioPlan.artistKey(out[0]) == PiterfyRadioPlan.artistKey(out[1]))
    }

    @Test fun buildSkipsAPodcastSeedButStillBuildsFromSimilar() {
        val out = PiterfyRadioPlan.build(song("p", "Host", genre = "Podcast"), listOf(song("a", "A")), rng = Random(1))
        assertEquals(listOf("a"), out.map { it.id })
        assertTrue(PiterfyRadioPlan.build(null, emptyList()).isEmpty())
    }

    @Test fun needMoreWhenThreeOrFewerSongsRemainAfterCurrent() {
        assertTrue(PiterfyRadioPlan.needMore(10, 6))     // 3 left
        assertFalse(PiterfyRadioPlan.needMore(10, 5))    // 4 left
        assertTrue(PiterfyRadioPlan.needMore(10, 9))     // last one
        assertFalse(PiterfyRadioPlan.needMore(0, 0))
        assertFalse(PiterfyRadioPlan.needMore(5, -1))
        assertTrue(PiterfyRadioPlan.needMore(3, 0))
    }

    @Test fun rememberKeepsNewestFirstWithoutDuplicatesAndCaps() {
        assertEquals(listOf("c", "a", "b"), PiterfyRadioPlan.remember(listOf("a", "b"), listOf("c", "a")))
        val big = PiterfyRadioPlan.remember((1..150).map { "o$it" }, listOf("n1", "n2"))
        assertEquals(PiterfyRadioPlan.MEMORY_SIZE, big.size)
        assertEquals(listOf("n1", "n2", "o1"), big.take(3))
    }

    @Test fun shouldStopOnlyForForeignTrackAfterGrace() {
        val known = setOf("a", "b")
        assertFalse(PiterfyRadioPlan.shouldStop("a", known, 60_000))
        assertFalse(PiterfyRadioPlan.shouldStop("x", known, 1_000))     // within grace
        assertTrue(PiterfyRadioPlan.shouldStop("x", known, 6_000))
        assertFalse(PiterfyRadioPlan.shouldStop(null, known, 60_000))
        assertFalse(PiterfyRadioPlan.shouldStop("", known, 60_000))
    }

    @Test fun seedsForTopUpAreCurrentAndTwoPrevious() {
        assertEquals(listOf("d", "c", "b"), PiterfyRadioPlan.seedsForTopUp(listOf("a", "b", "c", "d", "e"), 3))
        assertEquals(listOf("a"), PiterfyRadioPlan.seedsForTopUp(listOf("a", "b"), 0))
        assertEquals(listOf("b", "a"), PiterfyRadioPlan.seedsForTopUp(listOf("a", "b", "b"), 2))
        assertTrue(PiterfyRadioPlan.seedsForTopUp(emptyList(), 0).isEmpty())
    }

    @Test fun interleaveAlternatesBetweenLists() {
        assertEquals(listOf(1, 10, 2, 20, 3), PiterfyRadioPlan.interleave(listOf(listOf(1, 2, 3), listOf(10, 20))))
        assertTrue(PiterfyRadioPlan.interleave<Int>(emptyList()).isEmpty())
    }
}
