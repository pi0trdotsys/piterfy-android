package com.eddyizm.tempus.model;

import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.PiterfyQueues;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public class PiterfyQueueSeparationTest {
    private Child song(String id,String genre){Child child=new Child(id);child.setGenre(genre);return child;}
    @Test public void selectedCategoryKeepsOrderAndDoesNotMutateSource(){
        Child first=song("a","Rock"),episode=song("b","Podcast"),second=song("c","Pop");
        var source=Arrays.asList(first,episode,second);
        assertEquals(Arrays.asList(first,second),PiterfyQueues.partition(source,0));
        assertEquals(Arrays.asList(episode),PiterfyQueues.partition(source,1));
        assertEquals(3,source.size());
    }
    @Test public void invalidSelectionCannotStartUnrelatedCategory(){
        assertTrue(PiterfyQueues.partition(Arrays.asList(song("a","Rock")),-1).isEmpty());
    }
    @Test public void untaggedPodcastCanNeverEnterMusicQueue(){
        Child music=song("music","Rock"),episode=song("episode",null);episode.setPath("Podcasty/Raport/episode.mp3");
        assertEquals(Arrays.asList(music),PiterfyQueues.partition(Arrays.asList(music,episode),0));
        assertEquals(Arrays.asList(episode),PiterfyQueues.partition(Arrays.asList(music,episode),1));
    }
}
