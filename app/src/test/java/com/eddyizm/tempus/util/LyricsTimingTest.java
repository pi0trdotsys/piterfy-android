package com.eddyizm.tempus.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class LyricsTimingTest {
    @Test public void skipsMalformedSyncedEntryAndKeepsPlainFallback() {
        var list = LyricsTiming.parse("[00:02]First");
        var broken = list.getStructuredLyrics().get(0);
        broken.getLine().get(0).setStart(null);
        var plain = new com.eddyizm.tempus.subsonic.models.StructuredLyrics();
        plain.setSynced(false);
        var line = new com.eddyizm.tempus.subsonic.models.Line();
        line.setValue("Plain fallback");
        plain.setLine(java.util.Collections.singletonList(line));
        list.setStructuredLyrics(java.util.Arrays.asList(null, broken, plain));
        assertSame(plain, LyricsTiming.preferred(list));
    }
    @Test public void parsesRepeatedTimestampsFractionsAndOffset() {
        var lyrics = LyricsTiming.preferred(LyricsTiming.parse("[offset:+500]\n[00:12.5][00:42.050]  Sample line  \n[00:05]Earlier\n[ar:Test]"));
        assertEquals(3, lyrics.getLine().size());
        assertEquals(Integer.valueOf(12500), lyrics.getLine().get(1).getStart());
        assertEquals(Integer.valueOf(42050), lyrics.getLine().get(2).getStart());
        assertEquals("Sample line", lyrics.getLine().get(1).getValue());
        assertEquals(-1, LyricsTiming.active(lyrics.getLine(), 0, lyrics.getOffset()));
        assertEquals(0, LyricsTiming.active(lyrics.getLine(), 4500, lyrics.getOffset()));
        assertEquals(1, LyricsTiming.active(lyrics.getLine(), 12000, lyrics.getOffset()));
    }
    @Test public void untimedTextDoesNotInventSynchronization() {
        assertFalse(LyricsTiming.hasLines(LyricsTiming.parse("Plain text without timestamps")));
    }
    @Test public void preservesInstrumentalGapsAndBackwardSeeking() {
        var lyrics = LyricsTiming.preferred(LyricsTiming.parse("[00:02]First\n[00:04]\n[00:08]Last"));
        assertEquals(1, LyricsTiming.active(lyrics.getLine(), 6000, 0));
        assertEquals("", lyrics.getLine().get(1).getValue());
        assertEquals(0, LyricsTiming.active(lyrics.getLine(), 3000, 0));
        assertEquals(-1, LyricsTiming.active(lyrics.getLine(), 1000, 0));
    }
}
