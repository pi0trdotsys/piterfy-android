package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyAutoSearch.Candidate
import com.eddyizm.tempus.util.PiterfyAutoSearch.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyAutoSearchTest {
    private fun parse(text: String) = PiterfyAutoSearch.parse(text)

    @Test fun stripsTheVerbAndKeepsTheName() {
        assertEquals(PiterfyAutoSearch.Parsed(Kind.ANY, "Taco Hemingway", null), parse("zagraj Taco Hemingway"))
        assertEquals("Dark Mix", parse("Zagraj mi Dark Mix").term)
        assertEquals("Taco Hemingway", parse("play Taco Hemingway").term)
        assertEquals("Taco Hemingway", parse("ok zagraj Taco Hemingway").term)
        assertEquals("Taco Hemingway", parse("włącz Taco Hemingway").term)
    }

    @Test fun recognisesTheKindOfWhatIsRequested() {
        assertEquals(Kind.ALBUM, parse("zagraj album Wolność").kind)
        assertEquals("Wolność", parse("zagraj album Wolność").term)
        assertEquals(Kind.ALBUM, parse("zagraj z albumu Wolność").kind)
        assertEquals(Kind.ARTIST, parse("zagraj wykonawcę Taco Hemingway").kind)
        assertEquals(Kind.SONG, parse("zagraj utwór Mgła").kind)
        assertEquals(Kind.PLAYLIST, parse("zagraj playlistę Odkrywaj").kind)
    }

    @Test fun splitsTitleAndArtist() {
        val parsed = parse("zagraj album Wolność od Taco Hemingway")
        assertEquals(Kind.ALBUM, parsed.kind)
        assertEquals("Wolność", parsed.term)
        assertEquals("Taco Hemingway", parsed.artist)
        assertEquals("Wolność Taco Hemingway", parsed.queryText)
        assertEquals("Lose Yourself", parse("play Lose Yourself by Eminem").term)
        assertEquals("Eminem", parse("play Lose Yourself by Eminem").artist)
    }

    @Test fun musicByAnArtist() {
        assertEquals(PiterfyAutoSearch.Parsed(Kind.ARTIST, "Metallica", null), parse("zagraj utwory zespołu Metallica"))
        assertEquals(PiterfyAutoSearch.Parsed(Kind.ARTIST, "Taco Hemingway", null), parse("zagraj coś od Taco Hemingway"))
        assertEquals(PiterfyAutoSearch.Parsed(Kind.ARTIST, "Queen", null), parse("play music by Queen"))
    }

    @Test fun emptyCommandsMeanResume() {
        assertTrue(parse("").isEmpty)
        assertTrue(PiterfyAutoSearch.parse(null).isEmpty)
        assertTrue(parse("zagraj").isEmpty)
        assertTrue(parse("zagraj muzykę").isEmpty)
        assertTrue(parse("play music").isEmpty)
        assertTrue(parse("  ").isEmpty)
    }

    @Test fun aTitleStartingWithAFillerWordSurvivesWhenItIsTheWholeName() {
        assertEquals("Somebody That I Used To Know", parse("zagraj Somebody That I Used To Know").term)
        assertEquals("Music Sounds Better With You", parse("play Music Sounds Better With You").term)
        assertEquals("Coś Do Mnie", parse("zagraj Coś Do Mnie").term)
    }

    private fun song(id: String, title: String, artist: String, starred: Boolean = false, plays: Long = 0) =
        Candidate(id, title, artist, Kind.SONG, starred, plays)

    @Test fun exactMatchBeatsContains() {
        val items = listOf(
            song("1", "Mgła Remix 2019", "DJ X"),
            song("2", "Mgła", "Taco Hemingway"),
            song("3", "W mgle", "Ktoś"),
        )
        val ranked = PiterfyAutoSearch.rank(items, parse("zagraj Mgła"))
        assertEquals("2", ranked.first().id)
    }

    @Test fun diacriticsAndCaseAreIgnored() {
        val items = listOf(song("1", "Wolność", "Taco Hemingway"), song("2", "Inna", "Ktoś"))
        assertEquals("1", PiterfyAutoSearch.best(items, parse("zagraj wolnosc"))?.id)
        assertEquals("1", PiterfyAutoSearch.best(items, parse("zagraj WOLNOŚĆ"))?.id)
    }

    @Test fun anArtistNameBeatsASongWithTheSameName() {
        val items = listOf(
            song("s", "Taco Hemingway", "Ktoś"),
            Candidate("a", "Taco Hemingway", "", Kind.ARTIST),
            Candidate("b", "Taco Hemingway", "Taco Hemingway", Kind.ALBUM),
        )
        assertEquals("a", PiterfyAutoSearch.rank(items, parse("zagraj Taco Hemingway")).first().id)
    }

    @Test fun theRequestedKindWins() {
        val items = listOf(
            Candidate("a", "Wolność", "", Kind.ARTIST),
            Candidate("b", "Wolność", "Taco Hemingway", Kind.ALBUM),
            song("s", "Wolność", "Taco Hemingway"),
        )
        assertEquals("b", PiterfyAutoSearch.rank(items, parse("zagraj album Wolność")).first().id)
        assertEquals("s", PiterfyAutoSearch.rank(items, parse("zagraj utwór Wolność")).first().id)
    }

    @Test fun theArtistHintSeparatesTheSameTitle() {
        val items = listOf(song("1", "Intro", "Ktoś"), song("2", "Intro", "Taco Hemingway"))
        assertEquals("2", PiterfyAutoSearch.rank(items, parse("zagraj Intro od Taco Hemingway")).first().id)
    }

    @Test fun likedAndOftenPlayedBreakTies() {
        val items = listOf(song("1", "Intro", "A"), song("2", "Intro", "B", starred = true), song("3", "Intro", "C", plays = 50))
        val ranked = PiterfyAutoSearch.rank(items, parse("zagraj Intro")).map { it.id }
        assertEquals(listOf("2", "3", "1"), ranked)
    }

    @Test fun tiesKeepTheServerOrder() {
        val items = listOf(song("1", "Intro", "A"), song("2", "Intro", "B"))
        assertEquals(listOf("1", "2"), PiterfyAutoSearch.rank(items, parse("zagraj Intro")).map { it.id })
    }

    @Test fun bestRejectsRandomHits() {
        val items = listOf(song("1", "Zupełnie coś innego", "Ktoś"))
        assertNull(PiterfyAutoSearch.best(items, parse("zagraj Taco Hemingway")))
        assertNull(PiterfyAutoSearch.best(emptyList(), parse("zagraj Taco Hemingway")))
    }

    @Test fun wordsInAnyOrderStillMatch() {
        val items = listOf(song("1", "Dark Mix", ""))
        assertEquals("1", PiterfyAutoSearch.best(items, parse("zagraj Mix Dark"))?.id)
    }

    @Test fun anEmptyQueryScoresNothing() {
        val parsed = parse("zagraj")
        assertEquals(0.0, PiterfyAutoSearch.score(song("1", "x", "y"), parsed), 0.0)
    }
}
