package com.eddyizm.tempus.util

import org.junit.Assert.*
import org.junit.Test

class PiterfyOfflineRulesTest {
    private val now=200000000L
    private fun eligible(owner:String="a",account:String="a",completed:Boolean=true,updated:Long=now-86400000L,podcast:Boolean=true,pinned:Boolean=false,playing:Boolean=false)=
        PiterfyOfflineRules.canClean(owner,account,completed,updated,now,podcast,pinned,playing)
    @Test fun onlyCompletedOwnedPodcastAfterGracePeriodIsEligible(){assertTrue(eligible());assertFalse(eligible(completed=false));assertFalse(eligible(updated=now-86399999L));assertFalse(eligible(updated=0));assertFalse(eligible(podcast=false))}
    @Test fun playbackPinsAndAccountChangesProtectFiles(){assertFalse(eligible(playing=true));assertFalse(eligible(pinned=true));assertFalse(eligible(owner="b"));assertFalse(eligible(owner=""))}
}
