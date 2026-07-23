package com.woolab.lumella.orchestration

import com.woolab.lumella.state.Correction

/**
 * Staleness guard (plan FIX A / AC4). At delivery time, decides per deferred
 * correction whether to DELIVER (fresh, age <= K), RE_ANCHOR (stale but still within
 * a re-anchor window: re-attach to the current turn with age reset so it is NOT
 * delivered verbatim at a stale age), or DROP (too old to be useful).
 *
 * Invariant (AC4): a correction whose age exceeds K is NEVER delivered verbatim —
 * it is either re-anchored (age reset, turnId moved to current) or dropped.
 */
enum class StalenessOutcome { DELIVER, RE_ANCHOR, DROP }

data class StalenessDecision(
    val original: Correction,
    val outcome: StalenessOutcome,
    val ageAtDecision: Int,
    /** The correction to actually surface (age-reset + re-anchored for RE_ANCHOR), or null for DROP. */
    val delivered: Correction?,
)

class StalenessGuard(
    private val maxAgeTurns: Int,
    /** Corrections older than maxAgeTurns but within reAnchorWindow are re-anchored, not dropped. */
    private val reAnchorWindowTurns: Int = maxAgeTurns * 2,
) {
    init {
        require(maxAgeTurns >= 1) { "maxAgeTurns (K) must be >= 1" }
        require(reAnchorWindowTurns >= maxAgeTurns) { "reAnchorWindow must be >= K" }
    }

    fun evaluate(corrections: List<Correction>, currentTurnId: Int): List<StalenessDecision> =
        corrections.map { c ->
            val age = (currentTurnId - c.turnId).coerceAtLeast(0)
            when {
                age <= maxAgeTurns -> StalenessDecision(
                    original = c,
                    outcome = StalenessOutcome.DELIVER,
                    ageAtDecision = age,
                    delivered = c.copy(age = age),
                )
                age <= reAnchorWindowTurns -> StalenessDecision(
                    original = c,
                    outcome = StalenessOutcome.RE_ANCHOR,
                    ageAtDecision = age,
                    // Re-anchor: surface now, reset age to 0 and move to current turn.
                    delivered = c.copy(turnId = currentTurnId, age = 0),
                )
                else -> StalenessDecision(
                    original = c,
                    outcome = StalenessOutcome.DROP,
                    ageAtDecision = age,
                    delivered = null,
                )
            }
        }
}

/**
 * Staleness-distribution metric (plan AC4 / evaluation). Accumulates age-at-delivery
 * samples and per-outcome counts across the session for the eval report (P6).
 */
class StalenessMetric {
    private val ages = ArrayList<Int>()
    private val counts = linkedMapOf(
        StalenessOutcome.DELIVER to 0,
        StalenessOutcome.RE_ANCHOR to 0,
        StalenessOutcome.DROP to 0,
    )

    fun record(decisions: List<StalenessDecision>) {
        for (d in decisions) {
            ages.add(d.ageAtDecision)
            counts[d.outcome] = (counts[d.outcome] ?: 0) + 1
        }
    }

    fun count(outcome: StalenessOutcome): Int = counts[outcome] ?: 0
    fun totalEvaluated(): Int = ages.size
    fun ageSamples(): List<Int> = ages.toList()
    /** Max age across ALL evaluated corrections (incl. dropped) — distribution stat, not a delivered-age claim. */
    fun maxEvaluatedAge(): Int = ages.maxOrNull() ?: 0

    /** Histogram of age -> frequency for the staleness-distribution figure. */
    fun ageHistogram(): Map<Int, Int> = ages.groupingBy { it }.eachCount().toSortedMap()
}
