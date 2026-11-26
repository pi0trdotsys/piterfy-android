package com.eddyizm.tempus.ui.components

import com.eddyizm.tempus.ui.components.PiterfyGeneratedArt.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyGeneratedArtTest {
    private fun rgb(color: Int) = color and 0xFFFFFF

    @Test
    fun everyKnownMixHasItsWebColoursAndEmoji() {
        val expected = mapOf(
            "Happy Mix" to Triple(0xFFB800, 0xFF5E62, "😊"), "Dark Mix" to Triple(0x0F0C29, 0x7B2FF7, "🌑"),
            "Electronic Mix" to Triple(0x00D4FF, 0x7209B7, "⚡"), "Bass Boost" to Triple(0xFF0844, 0x5B21B6, "🔊"),
            "Chill Mix" to Triple(0x11998E, 0x38EF7D, "🌊"), "Workout Mix" to Triple(0xF12711, 0xF5AF19, "🔥"),
            "Rock Mix" to Triple(0x232526, 0xC31432, "🎸"), "Hip-Hop Mix" to Triple(0xF7971E, 0x8E2DE2, "🎤"),
            "French Music" to Triple(0x0055A4, 0xEF4135, "🥖"), "Latin Music" to Triple(0xFF512F, 0xF09819, "💃"),
            "Polska Muzyka" to Triple(0xDC143C, 0xFFFFFF, "🦅"), "Polski Rap" to Triple(0x8E0E00, 0x1F1C18, "🎙️"),
            "German Music" to Triple(0x232526, 0xFFCE00, "🍺"), "Asia Pop" to Triple(0xFF9A9E, 0xA18CD1, "🌸"),
            "Brazil Music" to Triple(0x00B09B, 0xF9D423, "🥁"), "Lata 80." to Triple(0xFC00FF, 0x00DBDE, "📼"),
            "Lata 90." to Triple(0x4776E6, 0x8E54E9, "💿"), "Lata 2000." to Triple(0x00C6FF, 0x0072FF, "📀"),
            "Phonk Mix" to Triple(0x0F0C29, 0xFF0844, "👻"),
        )
        assertEquals(19, expected.size)
        for ((name, want) in expected) {
            val spec = PiterfyGeneratedArt.spec(name, Kind.MIX)
            assertEquals(name, want.first, rgb(spec.from))
            assertEquals(name, want.second, rgb(spec.to))
            assertEquals(name, want.third, spec.emoji)
            assertEquals(name, 0xFF, spec.from ushr 24)
            assertEquals(name, 0xFF, spec.to ushr 24)
        }
    }

    @Test
    fun chartAndGameUseTheirOwnPalette() {
        val chart = PiterfyGeneratedArt.spec("Top 300 Polska", Kind.CHART)
        assertEquals(0xB30024, rgb(chart.from)); assertEquals(0xFFD1D9, rgb(chart.to)); assertEquals("🏆", chart.emoji)
        val game = PiterfyGeneratedArt.spec("Soundtrack: Minecraft", Kind.GAME)
        assertEquals(0x1D976C, rgb(game.from)); assertEquals(0x93F9B9, rgb(game.to)); assertEquals("🎮", game.emoji)
        // lista przebojów i gra nie zależą od nazwy
        assertEquals(chart.from, PiterfyGeneratedArt.spec("Top 100 Polska", Kind.CHART).from)
    }

    @Test
    fun labelsDropTheMixWordAndTheSoundtrackPrefix() {
        assertEquals("Happy", PiterfyGeneratedArt.label("Happy Mix", Kind.MIX))
        assertEquals("Hip-Hop", PiterfyGeneratedArt.label("Hip-Hop Mix", Kind.MIX))
        assertEquals("Bass Boost", PiterfyGeneratedArt.label("Bass Boost", Kind.MIX))
        assertEquals("French Music", PiterfyGeneratedArt.label("French Music", Kind.MIX))
        assertEquals("Mix", PiterfyGeneratedArt.label("Mix", Kind.MIX))   // nie zostawiamy pustego podpisu
        assertEquals("The Witcher 3", PiterfyGeneratedArt.label("Soundtrack: The Witcher 3", Kind.GAME))
        assertEquals("Minecraft", PiterfyGeneratedArt.label("Minecraft", Kind.GAME))
        assertEquals("Top 200 Polska", PiterfyGeneratedArt.label("Top 200 Polska", Kind.CHART))
        assertEquals("", PiterfyGeneratedArt.label(null, Kind.MIX))
        assertEquals("Happy", PiterfyGeneratedArt.spec("Happy Mix", Kind.MIX).label)
    }

    @Test
    fun lookupIgnoresCaseAndSpaces() {
        assertEquals(PiterfyGeneratedArt.spec("Happy Mix", Kind.MIX).from, PiterfyGeneratedArt.spec("  HAPPY mix ", Kind.MIX).from)
        assertEquals(PiterfyGeneratedArt.spec("Lata 80.", Kind.MIX).emoji, PiterfyGeneratedArt.spec("lata 80.", Kind.MIX).emoji)
    }

    @Test
    fun unknownMixGetsAStableHashedGradientAndTheFallbackEmoji() {
        val a = PiterfyGeneratedArt.spec("Jazz Mix", Kind.MIX)
        val again = PiterfyGeneratedArt.spec("Jazz Mix", Kind.MIX)
        val other = PiterfyGeneratedArt.spec("Country Mix", Kind.MIX)
        assertEquals(a, again)
        assertEquals("🎵", a.emoji)
        assertNotEquals(a.from, other.from)
        assertEquals(0xFF, a.from ushr 24)
        assertEquals(0xFF, a.to ushr 24)
        assertNotEquals(a.from, a.to)
        assertEquals("Jazz", a.label)
        // także dla pustej i dziwnej nazwy: bez wyjątku
        assertNotNull(PiterfyGeneratedArt.spec("", Kind.MIX))
        assertNotNull(PiterfyGeneratedArt.spec(null, Kind.MIX))
        assertTrue(PiterfyGeneratedArt.spec("🔥🔥 ???", Kind.MIX).from != 0)
    }

    @Test
    fun kindIsRecognisedFromTheServerCommentPrefix() {
        assertEquals(Kind.MIX, PiterfyGeneratedArt.kindOf("Piterfy Mix: Radosne, energiczne"))
        assertEquals(Kind.CHART, PiterfyGeneratedArt.kindOf("Piterfy Chart: Deezer Top Poland · w bibliotece 52/300"))
        assertEquals(Kind.GAME, PiterfyGeneratedArt.kindOf("Piterfy Game: Minecraft · w bibliotece 10/24"))
        assertNull(PiterfyGeneratedArt.kindOf("Moja playlista"))
        assertNull(PiterfyGeneratedArt.kindOf(null))
        assertNull(PiterfyGeneratedArt.kindOf("piterfy mix"))  // wielkość liter ma znaczenie, jak na serwerze
    }
}
