package com.eddyizm.tempus.service

import org.junit.Assert.*
import org.junit.Test

class DJAnnouncementGateTest {
    @Test fun bufferingDoesNotConsumeTheIntroduction(){
        val gate=DJAnnouncementGate()
        assertFalse(gate.shouldStart("block","song","song",false,0,true,false))
        assertTrue(gate.shouldStart("block","song","song",true,500,true,false))
        assertFalse(gate.shouldStart("block","song","song",true,1000,true,false))
    }
    @Test fun metadataCanArriveAfterPlaybackHasStarted(){
        val gate=DJAnnouncementGate()
        assertFalse(gate.shouldStart(null,"song",null,true,500,false,false))
        assertTrue(gate.shouldStart("block","song","song",true,8500,true,false))
    }
    @Test fun transferredClaimedBlockAndMidSongSeeksStaySilent(){
        val gate=DJAnnouncementGate()
        assertFalse(gate.shouldStart("block","song","song",true,0,true,true))
        assertFalse(gate.shouldStart("other","song","song",true,25000,true,false))
        assertFalse(gate.shouldStart("next","song2","song1",true,0,true,false))
    }
}
