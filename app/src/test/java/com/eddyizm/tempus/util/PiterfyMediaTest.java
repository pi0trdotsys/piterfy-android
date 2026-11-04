package com.eddyizm.tempus.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class PiterfyMediaTest {
 @Test public void recognizesPodcastWithCaseWhitespaceAndMultipleGenres(){assertTrue(PiterfyMedia.isPodcast(" podcast "));assertTrue(PiterfyMedia.isPodcast("Spoken Word;Podcast"));assertTrue(PiterfyMedia.isPodcast("Podcasts"));}
 @Test public void keepsOrdinaryMusic(){assertFalse(PiterfyMedia.isPodcast((String)null));assertFalse(PiterfyMedia.isPodcast("Hip-hop"));assertFalse(PiterfyMedia.isPodcast("Podcast Rock"));}
 @Test public void recognizesUntaggedEpisodesByDirectoryNotTitle(){assertTrue(PiterfyMedia.isPodcast("","Podcasty/Raport/episode.mp3","music"));assertTrue(PiterfyMedia.isPodcast(null,"C:\\Library\\Podcasts\\Show\\episode.m4a",null));assertFalse(PiterfyMedia.isPodcast("Rock","Artist/Podcast Rock.mp3","music"));}
}
