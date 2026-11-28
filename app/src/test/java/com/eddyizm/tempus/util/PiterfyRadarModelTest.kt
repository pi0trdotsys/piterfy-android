package com.eddyizm.tempus.util

import com.eddyizm.tempus.ui.components.PiterfyGeneratedArt
import com.eddyizm.tempus.ui.components.PiterfyGeneratedArt.Kind
import com.eddyizm.tempus.util.PiterfyRadarModel.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyRadarModelTest {
    private val body = """
        {"albums":[
          {"id":"1","artist":"Taco Hemingway","title":"Starszy","type":"album","released":"2026-11-01","cover":"https://cdn.example/1.jpg","tracks":12,"status":"new"},
          {"id":"2","artist":"Mata","title":"Nowszy","type":"single","released":"2026-11-20","cover":"http://insecure.example/2.jpg","tracks":1,"status":"queued","have":3,"total":10,"note":"Szukam"},
          {"id":"3","artist":"Abba","title":"Ten sam dzień","type":"ep","released":"2026-11-20","status":"done"},
          {"artist":"Bez id","title":"x"}, {"id":"9","artist":"Bez tytułu"}, 7, null
        ],"updated":"2026-11-28T10:00+00:00","fill":true}
    """.trimIndent()

    @Test
    fun parsesSortsAndDropsBrokenRows() {
        val listing = PiterfyRadarModel.parse(body)!!
        assertEquals(listOf("3", "2", "1"), listing.albums.map { it.id })   // najnowsze pierwsze, przy remisie po wykonawcy
        assertTrue(listing.fill)
        val queued = listing.albums[1]
        assertEquals(3, queued.have); assertEquals(10, queued.total); assertEquals("Szukam", queued.note)
        assertEquals("new", listing.albums[2].status)
        assertEquals("", listing.albums[2].note)
    }

    @Test
    fun onlyHttpsCoversAreKept() {
        val albums = PiterfyRadarModel.parse(body)!!.albums.associateBy { it.id }
        assertEquals("https://cdn.example/1.jpg", albums["1"]!!.cover)
        assertNull(albums["2"]!!.cover)
        assertNull(albums["3"]!!.cover)
    }

    @Test
    fun garbageIsNotAListing() {
        assertNull(PiterfyRadarModel.parse(null))
        assertNull(PiterfyRadarModel.parse(""))
        assertNull(PiterfyRadarModel.parse("nie json"))
        assertNull(PiterfyRadarModel.parse("[]"))
        assertNull(PiterfyRadarModel.parse("""{"albums":"nie lista"}"""))
        assertTrue(PiterfyRadarModel.parse("""{"albums":[]}""")!!.albums.isEmpty())
    }

    @Test
    fun fillSwitchDefaultsToOnAndFollowsTheServer() {
        assertTrue(PiterfyRadarModel.parse("""{"albums":[]}""")!!.fill)
        assertFalse(PiterfyRadarModel.parse("""{"albums":[],"fill":false}""")!!.fill)
    }

    private fun album(status: String) = PiterfyRadarModel.Album("1", "A", "T", "album", "2026-11-20", null, 10, status, "", 0, 0)

    @Test
    fun actionFollowsTheRequestLifecycle() {
        assertEquals(Action.NEW, PiterfyRadarModel.actionOf(album("new"), true))
        assertEquals(Action.BUSY, PiterfyRadarModel.actionOf(album("queued"), true))
        assertEquals(Action.DONE, PiterfyRadarModel.actionOf(album("done"), true))
        assertEquals(Action.FAILED, PiterfyRadarModel.actionOf(album("failed"), true))
        assertEquals(Action.OFF, PiterfyRadarModel.actionOf(album("new"), false))
        assertEquals(Action.OFF, PiterfyRadarModel.actionOf(album("disabled"), false))
        assertEquals(Action.NEW, PiterfyRadarModel.actionOf(album("disabled"), true))   // serwer włączył dociąganie po tym zapisie
        assertEquals(Action.DONE, PiterfyRadarModel.actionOf(album("done"), false))     // to, co już dociągnięte, zostaje „Gotowe”
    }

    @Test
    fun pollsOnlyWhileSomethingIsQueued() {
        assertTrue(PiterfyRadarModel.shouldPoll(listOf(album("new"), album("queued"))))
        assertFalse(PiterfyRadarModel.shouldPoll(listOf(album("done"), album("failed"))))
        assertFalse(PiterfyRadarModel.shouldPoll(emptyList()))
    }

    @Test
    fun kindsAreRecognisedIgnoringCase() {
        assertEquals(PiterfyRadarModel.Kind.ALBUM, PiterfyRadarModel.kindOf("Album"))
        assertEquals(PiterfyRadarModel.Kind.EP, PiterfyRadarModel.kindOf("EP"))
        assertEquals(PiterfyRadarModel.Kind.SINGLE, PiterfyRadarModel.kindOf("single"))
        assertEquals(PiterfyRadarModel.Kind.OTHER, PiterfyRadarModel.kindOf("compile"))
        assertEquals(PiterfyRadarModel.Kind.OTHER, PiterfyRadarModel.kindOf(null))
    }

    @Test
    fun dayNumberMatchesTheEpochAndLeapYears() {
        assertEquals(0L, PiterfyRadarModel.dayNumber(1970, 1, 1))
        assertEquals(1L, PiterfyRadarModel.dayNumber(1970, 1, 2))
        assertEquals(19_723L, PiterfyRadarModel.dayNumber(2024, 1, 1))
        assertEquals(1L, PiterfyRadarModel.dayNumber(2024, 3, 1) - PiterfyRadarModel.dayNumber(2024, 2, 29))
        assertEquals(2L, PiterfyRadarModel.dayNumber(2025, 3, 1) - PiterfyRadarModel.dayNumber(2025, 2, 27))
    }

    @Test
    fun ageCountsCalendarDaysInTheUsersTimezone() {
        val noon = PiterfyRadarModel.dayNumber(2026, 11, 28) * 86_400_000L + 12 * 3_600_000L
        assertEquals(0, PiterfyRadarModel.age("2026-11-28", noon, 0).days)
        assertEquals(1, PiterfyRadarModel.age("2026-11-27", noon, 0).days)
        assertEquals(8, PiterfyRadarModel.age("2026-11-20", noon, 0).days)
        assertEquals("20.11", PiterfyRadarModel.age("2026-11-20", noon, 0).shortDate)
        assertEquals(-1, PiterfyRadarModel.age("2026-11-29", noon, 0).days.coerceAtMost(-1))   // zapowiedź: dni ujemne
        // 23:30 UTC to już następny dzień w Warszawie (UTC+1)
        val lateUtc = PiterfyRadarModel.dayNumber(2026, 11, 28) * 86_400_000L + 23 * 3_600_000L + 30 * 60_000L
        assertEquals(1, PiterfyRadarModel.age("2026-11-28", lateUtc, 3_600_000).days)
        assertEquals(0, PiterfyRadarModel.age("2026-11-28", lateUtc, 0).days)
    }

    @Test
    fun unreadableDatesGiveNoAge() {
        assertEquals(-1, PiterfyRadarModel.age("", 0L, 0).days)
        assertEquals(-1, PiterfyRadarModel.age(null, 0L, 0).days)
        assertEquals(-1, PiterfyRadarModel.age("jutro", 0L, 0).days)
        assertEquals(-1, PiterfyRadarModel.age("2026-13-40", 0L, 0).days)
    }

    @Test
    fun fetchAnswersReplaceTheItemInPlace() {
        val listing = PiterfyRadarModel.parse(body)!!
        val answer = """{"album":{"id":"1","artist":"Taco Hemingway","title":"Starszy","type":"album","released":"2026-11-01","tracks":12,"status":"queued"},"result":"queued"}"""
        val requested = PiterfyRadarModel.parseFetch(answer)!!
        assertEquals("queued", requested.code)
        val after = PiterfyRadarModel.replace(listing.albums, requested.album)
        assertEquals(listOf("3", "2", "1"), after.map { it.id })
        assertEquals("queued", after.first { it.id == "1" }.status)
        assertEquals("done", after.first { it.id == "3" }.status)
        assertEquals(listing.albums, PiterfyRadarModel.replace(listing.albums, null))
        assertNull(PiterfyRadarModel.parseFetch("""{"error":"Nie znam tego wydania"}"""))
        assertNull(PiterfyRadarModel.parseFetch("nie json"))
    }

    @Test
    fun withStatusKeepsTheRest() {
        val a = album("new")
        val failed = PiterfyRadarModel.withStatus(a, "failed", "Błąd")
        assertEquals("failed", failed.status); assertEquals("Błąd", failed.note)
        assertEquals(a.title, failed.title); assertEquals(a.id, failed.id)
    }

    @Test
    fun yearAgoMixHasItsOwnGeneratedCover() {
        val spec = PiterfyGeneratedArt.spec("Rok temu o tej porze", Kind.MIX)
        assertEquals(0x3A1C71, spec.from and 0xFFFFFF)
        assertEquals(0xFFAF7B, spec.to and 0xFFFFFF)
        assertEquals("⏳", spec.emoji)
        assertEquals("Rok temu o tej porze", spec.label)
        assertNotNull(spec)
        assertNotEquals("🎵", spec.emoji)
    }
}
