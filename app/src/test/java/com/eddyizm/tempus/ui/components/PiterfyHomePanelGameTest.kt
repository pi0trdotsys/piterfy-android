package com.eddyizm.tempus.ui.components

import com.eddyizm.tempus.subsonic.models.Playlist
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHomePanelGameTest {
    private fun playlist(name: String, comment: String?) = Playlist("id-$name", name).also { it.comment = comment }

    private val game = playlist("Soundtrack: Minecraft", "Piterfy Game: Minecraft · w bibliotece 12/24 · aktualizacja 2026-11-18. Odświeżana automatycznie.")

    @Test
    fun recognisesGamePlaylistsByTheServersCommentPrefix() {
        assertTrue(PiterfyHomePanel.isGame(game))
    }

    @Test
    fun otherPlaylistsAreNotGames() {
        assertFalse(PiterfyHomePanel.isGame(playlist("Soundtrack: Minecraft", "moja własna lista")))
        assertFalse(PiterfyHomePanel.isGame(playlist("Bez komentarza", null)))
        assertFalse(PiterfyHomePanel.isGame(null))
        assertFalse(PiterfyHomePanel.isGame(playlist("x", "piterfy game: coś"))) // wielkość liter jak na serwerze
    }

    @Test
    fun gamesMixesAndChartsAreDisjoint() {
        val mix = playlist("French Music", "Piterfy Mix: Francuska muzyka.")
        val chart = playlist("Top 100 Polska", "Piterfy Chart: Deezer Top Poland · w bibliotece 3/100")
        assertFalse(PiterfyHomePanel.isMix(game))
        assertFalse(PiterfyHomePanel.isChart(game))
        assertFalse(PiterfyHomePanel.isGame(mix))
        assertFalse(PiterfyHomePanel.isGame(chart))
        assertTrue(PiterfyHomePanel.isMix(mix))
    }

    @Test
    fun cardTitleDropsTheSoundtrackPrefix() {
        assertEquals("Minecraft", PiterfyHomePanel.gameTitle("Soundtrack: Minecraft"))
        assertEquals("The Legend of Zelda: Breath of the Wild", PiterfyHomePanel.gameTitle("Soundtrack: The Legend of Zelda: Breath of the Wild"))
        assertEquals("Inna", PiterfyHomePanel.gameTitle("Inna"))
        assertEquals("", PiterfyHomePanel.gameTitle(null))
    }

    @Test
    fun libraryCountsComeFromTheSameCommentFormat() {
        assertArrayEquals(intArrayOf(12, 24), PiterfyHomePanel.chartCounts(game.comment))
    }
}
