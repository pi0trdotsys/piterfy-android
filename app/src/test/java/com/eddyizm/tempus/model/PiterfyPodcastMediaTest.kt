package com.eddyizm.tempus.model

import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class PiterfyPodcastMediaTest {
    @Test fun libraryPodcastRetainsStreamIdAndPodcastType() {
        val child = Child("episode")
        child.genre = "Podcast"
        val item = SessionMediaItem(child)
        assertEquals(Constants.MEDIA_TYPE_PODCAST, item.type)
        assertEquals("episode", item.streamId)
    }
    @Test fun resumeItemWithoutGenreRetainsExplicitPodcastType() {
        val child = Child("resume")
        child.type = Constants.MEDIA_TYPE_PODCAST
        val item = SessionMediaItem(child)
        assertEquals(Constants.MEDIA_TYPE_PODCAST, item.type)
        assertEquals("resume", item.streamId)
    }
}
