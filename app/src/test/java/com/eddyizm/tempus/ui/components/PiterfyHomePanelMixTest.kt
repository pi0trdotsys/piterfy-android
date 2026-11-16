package com.eddyizm.tempus.ui.components

import com.eddyizm.tempus.subsonic.models.Playlist
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyHomePanelMixTest {
    private fun playlist(name: String, comment: String?) = Playlist("id-$name", name).also { it.comment = comment }

    @Test
    fun serverMarkedPlaylistsAreMixes() {
        assertTrue(PiterfyHomePanel.isMix(playlist("Happy Mix", "Piterfy Mix: Radosne. Odświeżany automatycznie (40 utworów).")))
    }

    @Test
    fun otherPlaylistsAreNotMixes() {
        assertFalse(PiterfyHomePanel.isMix(playlist("Happy Mix", "moja własna lista")))
        assertFalse(PiterfyHomePanel.isMix(playlist("Odkrywaj", "Podobne do tego, czego słuchasz.")))
        assertFalse(PiterfyHomePanel.isMix(playlist("Bez komentarza", null)))
        assertFalse(PiterfyHomePanel.isMix(null))
    }
}
