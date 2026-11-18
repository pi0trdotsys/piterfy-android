package com.eddyizm.tempus.ui.components

import com.eddyizm.tempus.subsonic.models.Playlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHomePanelChartTest {
    private fun playlist(name: String, comment: String?) = Playlist("id-$name", name).also { it.comment = comment }

    @Test
    fun recognisesChartPlaylistsByTheServersCommentPrefix() {
        assertTrue(PiterfyHomePanel.isChart(playlist("Top 100 Polska", "Piterfy Chart: Deezer Top Poland · w bibliotece 31/100 · aktualizacja 2026-11-18. Odświeżana automatycznie.")))
    }

    @Test
    fun otherPlaylistsAreNotCharts() {
        assertFalse(PiterfyHomePanel.isChart(playlist("Top 100 Polska", "moja własna lista")))
        assertFalse(PiterfyHomePanel.isChart(playlist("Happy Mix", "Piterfy Mix: Radosne. Odświeżany automatycznie (40 utworów).")))
        assertFalse(PiterfyHomePanel.isChart(playlist("Odkrywaj", "Podobne do tego, czego słuchasz.")))
        assertFalse(PiterfyHomePanel.isChart(playlist("Bez komentarza", null)))
        assertFalse(PiterfyHomePanel.isChart(null))
    }

    @Test
    fun prefixMatchIsCaseSensitiveLikeTheServer() {
        assertFalse(PiterfyHomePanel.isChart(playlist("x", "piterfy chart: coś")))
    }

    @Test
    fun chartsAreNeverMixesAndMixesAreNeverCharts() {
        val chart = playlist("Top 100 Polska", "Piterfy Chart: Deezer Top Poland · w bibliotece 3/100")
        val mix = playlist("Happy Mix", "Piterfy Mix: Radosne.")
        assertFalse(PiterfyHomePanel.isMix(chart))
        assertFalse(PiterfyHomePanel.isChart(mix))
    }

    @Test
    fun readsCountsFromTheComment() {
        assertArrayEquals(intArrayOf(31, 100), PiterfyHomePanel.chartCounts("Piterfy Chart: Deezer Top Poland · w bibliotece 31/100 · aktualizacja 2026-11-18."))
        assertArrayEquals(intArrayOf(0, 100), PiterfyHomePanel.chartCounts("Piterfy Chart: X · w bibliotece 0/100"))
    }

    @Test
    fun missingOrBrokenCountsGiveNull() {
        assertNull(PiterfyHomePanel.chartCounts(null))
        assertNull(PiterfyHomePanel.chartCounts("Piterfy Chart: X"))
        assertNull(PiterfyHomePanel.chartCounts("w bibliotece abc/100"))
        assertNull(PiterfyHomePanel.chartCounts("w bibliotece 99999999999/100"))
    }
}
