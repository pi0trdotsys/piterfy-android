package com.eddyizm.tempus.util

import com.eddyizm.tempus.util.PiterfyArtistTimeline.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyArtistTimelineTest {
    private fun radar(id: String, artist: String, title: String, released: String) =
        PiterfyRadarModel.Album(id, artist, title, "album", released, null, 10, "new", "", 0, 10)

    private val library = listOf(
        Library("a1", "First Light", 2019, "c1"),
        Library("a2", "Second Wind (Deluxe Edition)", 2021, "c2"),
        Library("a3", "Third", 2021, null),
    )

    @Test fun keyIgnoresAccentsCaseAndEditions() {
        assertEquals("zolc", PiterfyArtistTimeline.key("Żółć"))
        assertEquals("second wind", PiterfyArtistTimeline.key("Second Wind (Deluxe Edition)"))
        assertEquals("cafe", PiterfyArtistTimeline.key("Café"))
        assertEquals("", PiterfyArtistTimeline.key(null))
    }

    @Test fun artistsMatchWithoutAccentsOrCase() {
        assertTrue(PiterfyArtistTimeline.sameArtist("Beyoncé", "BEYONCE"))
        assertFalse(PiterfyArtistTimeline.sameArtist("Beyoncé", "Beyonce Knowles"))
        assertFalse(PiterfyArtistTimeline.sameArtist("", ""))
    }

    @Test fun yearsAreNewestFirstAndNewReleasesLeadTheirYear() {
        val timeline = PiterfyArtistTimeline.build("Artist", library, listOf(
            radar("r1", "Artist", "Fresh Drop", "2026-11-20"),
            radar("r2", "Artist", "Fresh EP", "2026-03-01"),
            radar("r3", "Somebody Else", "Not Mine", "2026-11-21"),
        ))
        assertEquals(listOf(2026, 2021, 2019), timeline.map { it.year })
        assertEquals(listOf("Fresh Drop", "Fresh EP"), timeline[0].entries.map { it.title })
        assertTrue(timeline[0].entries.all { it.isNew })
        assertEquals(listOf("Second Wind (Deluxe Edition)", "Third"), timeline[1].entries.map { it.title })
        assertEquals(2, PiterfyArtistTimeline.newCount(timeline))
    }

    @Test fun releaseAlreadyInTheLibraryIsNotDuplicated() {
        val timeline = PiterfyArtistTimeline.build("Artist", library, listOf(radar("r1", "Artist", "Second Wind", "2021-05-05")))
        assertEquals(0, PiterfyArtistTimeline.newCount(timeline))
        assertEquals(2, timeline.first { it.year == 2021 }.entries.size)
    }

    @Test fun undatedEntriesGoLastAndEmptyInputGivesEmptyTimeline() {
        val timeline = PiterfyArtistTimeline.build("Artist", listOf(Library("x", "Old", 0, null), Library("y", "New", 2020, null)), emptyList())
        assertEquals(listOf(2020, 0), timeline.map { it.year })
        assertTrue(PiterfyArtistTimeline.build("Artist", emptyList(), emptyList()).isEmpty())
    }

    @Test fun libraryEntriesKeepTheirIds() {
        val e = PiterfyArtistTimeline.build("Artist", library, emptyList()).first { it.year == 2019 }.entries.single()
        assertEquals("a1", e.albumId)
        assertFalse(e.isNew)
        assertEquals("c1", e.coverId)
    }
}
