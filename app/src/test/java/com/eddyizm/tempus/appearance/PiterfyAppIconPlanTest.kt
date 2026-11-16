package com.eddyizm.tempus.appearance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyAppIconPlanTest {
    private val all = listOf("classic", "neon", "vinyl", "halloween")
    private fun state(vararg on: String) = all.associateWith { it in on }

    @Test fun newAliasIsEnabledBeforeTheOldOneIsDisabled() {
        val plan = PiterfyAppIcon.plan("neon", state("classic"), all)
        assertEquals(listOf(PiterfyAppIcon.Change("neon", true), PiterfyAppIcon.Change("classic", false)), plan)
    }

    @Test fun nothingToDoWhenAlreadyTheOnlyActiveAlias() {
        assertTrue(PiterfyAppIcon.plan("classic", state("classic"), all).isEmpty())
        assertTrue(PiterfyAppIcon.plan("halloween", state("halloween"), all).isEmpty())
    }

    @Test fun extraEnabledAliasesAreDisabled() {
        val plan = PiterfyAppIcon.plan("vinyl", state("vinyl", "classic", "neon"), all)
        assertEquals(setOf(PiterfyAppIcon.Change("classic", false), PiterfyAppIcon.Change("neon", false)), plan.toSet())
    }

    @Test fun noActiveAliasGetsTheGoalEnabled() {
        assertEquals(listOf(PiterfyAppIcon.Change("neon", true)), PiterfyAppIcon.plan("neon", state(), all))
    }

    @Test fun unknownTargetFallsBackToTheFirstIcon() {
        val plan = PiterfyAppIcon.plan("nonexistent", state("neon"), all)
        assertEquals(PiterfyAppIcon.Change("classic", true), plan.first())
        assertTrue(plan.contains(PiterfyAppIcon.Change("neon", false)))
    }

    @Test fun manifestDefaultOnlyEnablesClassic() {
        assertTrue(PiterfyAppIcon.isEnabled(0, true))      // COMPONENT_ENABLED_STATE_DEFAULT
        assertTrue(!PiterfyAppIcon.isEnabled(0, false))
        assertTrue(PiterfyAppIcon.isEnabled(1, false))     // ENABLED
        assertTrue(!PiterfyAppIcon.isEnabled(2, true))     // DISABLED
    }
}
