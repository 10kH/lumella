package com.woolab.lumella.config

import com.woolab.tutor.slowpath.AblationMode


/**
 * Static ELLA-MA runtime configuration (plan P0).
 *
 * `ellaMaEnabled` gates the entire multi-agent layer; when false the app behaves
 * as the original single-agent ELLA. `ablationMode` selects the evaluation
 * condition. `stalenessGuardMaxAgeTurns` (K) bounds how old a deferred
 * correction may be before the staleness guard drops or re-anchors it
 * (plan AC4 / FIX A); default chosen conservatively and overridable per run.
 */
data class EllaMaConfig(
    val ellaMaEnabled: Boolean = true,
    val ablationMode: AblationMode = AblationMode.FULL,
    val stalenessGuardMaxAgeTurns: Int = 3,
) {
    init {
        require(stalenessGuardMaxAgeTurns >= 1) {
            "stalenessGuardMaxAgeTurns (K) must be >= 1"
        }
    }

    companion object {
        /** Baseline single-agent ELLA: multi-agent layer effectively off. */
        val SINGLE_AGENT_BASELINE = EllaMaConfig(
            ellaMaEnabled = false,
            ablationMode = AblationMode.SINGLE_AGENT,
        )
    }
}
