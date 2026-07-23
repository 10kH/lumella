package com.woolab.lumella.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC8 foundation: the ablation truth table must be internally consistent so the
 * P6 evaluation harness drives each condition correctly.
 */
class EllaMaConfigTest {

    private data class Row(
        val mode: AblationMode,
        val learnerState: Boolean,
        val slowAgents: Boolean,
        val immediateRecast: Boolean,
        val deferred: Boolean,
    )

    private val table = listOf(
        Row(AblationMode.SINGLE_AGENT, learnerState = false, slowAgents = false, immediateRecast = true, deferred = false),
        Row(AblationMode.NO_LEARNER_STATE, learnerState = false, slowAgents = true, immediateRecast = true, deferred = true),
        Row(AblationMode.IMMEDIATE_ONLY, learnerState = true, slowAgents = true, immediateRecast = true, deferred = false),
        Row(AblationMode.DEFERRED_ONLY, learnerState = true, slowAgents = true, immediateRecast = false, deferred = true),
        Row(AblationMode.FULL, learnerState = true, slowAgents = true, immediateRecast = true, deferred = true),
    )

    @Test
    fun ablationTruthTableIsConsistent() {
        for (r in table) {
            assertEquals("usesLearnerState for ${r.mode}", r.learnerState, r.mode.usesLearnerState)
            assertEquals("usesSlowAgents for ${r.mode}", r.slowAgents, r.mode.usesSlowAgents)
            assertEquals("usesImmediateRecast for ${r.mode}", r.immediateRecast, r.mode.usesImmediateRecast)
            assertEquals("usesDeferredCorrections for ${r.mode}", r.deferred, r.mode.usesDeferredCorrections)
        }
    }

    @Test
    fun everyModeSurfacesAtLeastOneCorrectionLane() {
        // No mode should fire slow agents while surfacing nothing (the bug fixed in review).
        for (mode in AblationMode.values()) {
            if (mode.usesSlowAgents) {
                assertTrue(
                    "$mode runs slow agents but surfaces neither immediate nor deferred corrections",
                    mode.usesImmediateRecast || mode.usesDeferredCorrections,
                )
            }
        }
    }

    @Test
    fun noLearnerStateStillDeliversCorrections() {
        // Steelman rebuttal: corrections delivered from an ephemeral buffer, no shared state.
        assertTrue(AblationMode.NO_LEARNER_STATE.usesDeferredCorrections)
        assertFalse(AblationMode.NO_LEARNER_STATE.usesLearnerState)
    }

    @Test
    fun kValidatorRejectsNonPositive() {
        assertThrows(IllegalArgumentException::class.java) {
            EllaMaConfig(stalenessGuardMaxAgeTurns = 0)
        }
    }

    @Test
    fun defaultsAndBaseline() {
        val def = EllaMaConfig()
        assertTrue(def.ellaMaEnabled)
        assertEquals(AblationMode.FULL, def.ablationMode)
        assertEquals(3, def.stalenessGuardMaxAgeTurns)

        val baseline = EllaMaConfig.SINGLE_AGENT_BASELINE
        assertFalse(baseline.ellaMaEnabled)
        assertEquals(AblationMode.SINGLE_AGENT, baseline.ablationMode)
    }
}
