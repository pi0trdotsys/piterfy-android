package com.eddyizm.tempus.util

import androidx.media3.common.PlaybackException
import com.eddyizm.tempus.util.PiterfyAutoErrors.Kind
import com.eddyizm.tempus.util.PiterfyAutoPlaylists.Group
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyAutoLogicTest {
    // ── stronicowanie ──

    private val items = (1..250).toList()

    @Test fun pagesCoverTheListWithoutGapsOrOverlap() {
        val all = (0..2).flatMap { PiterfyAutoPaging.slice(items, it, 100) }
        assertEquals(items, all)
        assertEquals(50, PiterfyAutoPaging.slice(items, 2, 100).size)
        assertTrue(PiterfyAutoPaging.slice(items, 3, 100).isEmpty())
    }

    @Test fun noPagingAsksForEverythingOnPageZero() {
        assertEquals(items, PiterfyAutoPaging.slice(items, 0, Int.MAX_VALUE))
        assertEquals(items, PiterfyAutoPaging.slice(items, 0, 0))
        assertTrue(PiterfyAutoPaging.slice(items, 1, Int.MAX_VALUE).isEmpty())
    }

    @Test fun pagingSurvivesNegativeAndHugeValues() {
        assertEquals(items.take(10), PiterfyAutoPaging.slice(items, -3, 10))
        assertTrue(PiterfyAutoPaging.slice(items, Int.MAX_VALUE, 1000).isEmpty())
        assertTrue(PiterfyAutoPaging.slice(emptyList<Int>(), 0, 10).isEmpty())
    }

    // ── wznawianie ──

    @Test fun resumeStartsWhereTheLastTrackStopped() {
        val ids = listOf("a", "b", "c")
        assertEquals(PiterfyAutoResume.Start(2, 41_000), PiterfyAutoResume.start(ids, "c", 41_000))
        assertEquals(PiterfyAutoResume.Start(0, 0), PiterfyAutoResume.start(ids, "zniknął", 41_000)) // utworu nie ma w kolejce
        assertEquals(PiterfyAutoResume.Start(0, 0), PiterfyAutoResume.start(ids, null, 5))
        assertEquals(PiterfyAutoResume.Start(1, 0), PiterfyAutoResume.start(ids, "b", -5))
        assertNull(PiterfyAutoResume.start(emptyList(), "a", 1000))
    }

    @Test fun continuePositionIgnoresTheVeryStartAndTheVeryEnd() {
        assertEquals(0L, PiterfyAutoResume.continuePosition(3_000, 600_000))
        assertEquals(300_000L, PiterfyAutoResume.continuePosition(300_000, 600_000))
        assertEquals(0L, PiterfyAutoResume.continuePosition(595_000, 600_000))   // prawie koniec = już wysłuchane
        assertEquals(120_000L, PiterfyAutoResume.continuePosition(120_000, 0))    // nieznana długość
    }

    @Test fun completionIsAFractionOrNull() {
        assertEquals(0.5, PiterfyAutoResume.completion(300_000, 600_000)!!, 1e-9)
        assertEquals(1.0, PiterfyAutoResume.completion(900_000, 600_000)!!, 1e-9)
        assertNull(PiterfyAutoResume.completion(1000, 0))
        assertNull(PiterfyAutoResume.completion(0, 1000))
    }

    @Test fun durationComesFromSecondsOnlyWhenKnown() {
        assertEquals(215_000L, PiterfyAutoResume.durationMs(215))
        assertNull(PiterfyAutoResume.durationMs(0))
        assertNull(PiterfyAutoResume.durationMs(-1))
    }

    // ── playlisty Piterfy ──

    @Test fun serverPlaylistsAreRecognisedByName() {
        assertEquals(Group.DISCOVER, PiterfyAutoPlaylists.group("Odkrywaj"))
        assertEquals(Group.DISCOVER, PiterfyAutoPlaylists.group("Odkrywaj 12.10"))
        assertEquals(Group.DISCOVER, PiterfyAutoPlaylists.group("Rok temu o tej porze"))
        assertEquals(Group.MIX, PiterfyAutoPlaylists.group("Dark Mix"))
        assertEquals(Group.MIX, PiterfyAutoPlaylists.group("Polski Rap"))
        assertEquals(Group.MIX, PiterfyAutoPlaylists.group("Lata 90."))
        assertEquals(Group.MIX, PiterfyAutoPlaylists.group("bass boost"))
        assertEquals(Group.CHART, PiterfyAutoPlaylists.group("Top 100 Polska"))
        assertEquals(Group.SOUNDTRACK, PiterfyAutoPlaylists.group("Soundtrack: The Witcher 3"))
    }

    @Test fun userPlaylistsAreLeftAlone() {
        assertEquals(Group.OTHER, PiterfyAutoPlaylists.group("Na siłownię"))
        assertEquals(Group.OTHER, PiterfyAutoPlaylists.group("Mixed feelings"))   // „mix” w środku to nie miks
        assertEquals(Group.OTHER, PiterfyAutoPlaylists.group(""))
        assertEquals(Group.OTHER, PiterfyAutoPlaylists.group(null))
        assertFalse(PiterfyAutoPlaylists.isPiterfy("Topowe z lat 90"))            // „top ” tylko na początku całego słowa
    }

    @Test fun piterfyPlaylistsGoFirstInAStableOrder() {
        val names = listOf("Moja", "Soundtrack: Skyrim", "Dark Mix", "Top 100 Polska", "Inna", "Odkrywaj", "Happy Mix")
        val ordered = PiterfyAutoPlaylists.order(names) { it }
        assertEquals(listOf("Odkrywaj", "Dark Mix", "Happy Mix", "Top 100 Polska", "Soundtrack: Skyrim", "Moja", "Inna"), ordered)
    }

    // ── błędy odtwarzania ──

    @Test fun errorsAreSortedIntoWhatTheDriverCanDoSomethingAbout() {
        assertEquals(Kind.NETWORK, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null))
        assertEquals(Kind.NETWORK, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null))
        assertEquals(Kind.AUTH, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401))
        assertEquals(Kind.AUTH, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(Kind.UNAVAILABLE, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(Kind.NETWORK, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
        assertEquals(Kind.OTHER, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
        assertEquals(Kind.UNAVAILABLE, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null))
        assertEquals(Kind.OTHER, PiterfyAutoErrors.classify(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }

    @Test fun onlyAuthAndNetworkErrorsOfferToOpenTheApp() {
        assertTrue(PiterfyAutoErrors.offersOpenApp(Kind.AUTH))
        assertTrue(PiterfyAutoErrors.offersOpenApp(Kind.NETWORK))
        assertFalse(PiterfyAutoErrors.offersOpenApp(Kind.UNAVAILABLE))
        assertFalse(PiterfyAutoErrors.offersOpenApp(Kind.OTHER))
    }
}
