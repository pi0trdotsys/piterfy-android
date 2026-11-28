package com.eddyizm.tempus.util

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyRepeatPlanTest {
    private val one = Player.REPEAT_MODE_ONE
    private val seek = Player.MEDIA_ITEM_TRANSITION_REASON_SEEK

    @Test fun manualChangeOfTrackTurnsRepeatOneOff() {
        assertTrue(PiterfyRepeatPlan.shouldTurnOff(one, seek, "a", "b"))
        assertTrue(PiterfyRepeatPlan.shouldTurnOff(one, seek, null, "b"))   // pierwszy utwór po starcie z Android Auto
    }

    @Test fun repeatingTheSameTrackKeepsIt() {
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, "a", "a"))
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, seek, "a", "a"))   // przewinięcie w obrębie tego samego utworu
    }

    @Test fun otherReasonsKeepIt() {
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, "a", "b"))
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, "a", "b"))
    }

    @Test fun otherRepeatModesAreLeftAlone() {
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(Player.REPEAT_MODE_OFF, seek, "a", "b"))
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(Player.REPEAT_MODE_ALL, seek, "a", "b"))
    }

    @Test fun missingNewIdChangesNothing() {
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, seek, "a", null))
        assertFalse(PiterfyRepeatPlan.shouldTurnOff(one, seek, "a", ""))
    }
}
