package com.eddyizm.tempus.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class PiterfyMediaTest {
 @Test public void recognizesPodcastWithCaseWhitespaceAndMultipleGenres(){assertTrue(PiterfyMedia.isPodcast(" podcast "));assertTrue(PiterfyMedia.isPodcast("Spoken Word;Podcast"));assertTrue(PiterfyMedia.isPodcast("Podcasts"));}
 @Test public void keepsOrdinaryMusic(){assertFalse(PiterfyMedia.isPodcast(null));assertFalse(PiterfyMedia.isPodcast("Hip-hop"));assertFalse(PiterfyMedia.isPodcast("Podcast Rock"));}
}
