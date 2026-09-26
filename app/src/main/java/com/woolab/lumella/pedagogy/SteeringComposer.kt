package com.woolab.lumella.pedagogy

import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.LearnerState

/**
 * Composes the per-response steering text (plan P5) injected via
 * response.create.instructions. Pure + JVM-testable.
 *
 * Combines: clean persona summary, target vocabulary, the most recent egocentric
 * visual context (AC6 — grounding what the learner is looking at), target-language
 * code-switching scaffolding (v1: KOREAN tutoring — inverted from ELLA's EFL AC11),
 * and non-stale deferred corrections.
 */
object SteeringComposer {

    /** True if the text contains any Hangul syllable/jamo. */
    fun containsKorean(text: String): Boolean = text.any { c ->
        c in '\uAC00'..'\uD7A3' || // Hangul syllables
            c in '\u1100'..'\u11FF' || // Jamo
            c in '\u3130'..'\u318F' // compatibility jamo
    }

    fun compose(
        personaSummary: String,
        state: LearnerState,
        corrections: List<Correction>,
        lastUserUtterance: String?,
        /**
         * B0 (pre-registered buffer-vs-structural definition): when false, the steering
         * draws ONLY on the ephemeral per-turn correction buffer passed in [corrections];
         * structured cross-turn learner-state (accumulated vocab targets, visual-context
         * continuity) is suppressed. NO_LEARNER_STATE sets this false; FULL/IMMEDIATE_ONLY/
         * DEFERRED_ONLY set it true. This is what makes FULL vs NO_LEARNER_STATE a real,
         * judge-distinguishable contrast rather than a degenerate (identical) pair.
         */
        useLearnerState: Boolean = true,
    ): String {
        val sb = StringBuilder()
        if (personaSummary.isNotBlank()) sb.append(personaSummary.trim()).append('\n')

        // v1 Korean tutoring (Intent Reconciliation 2026-07-21) — inverted from ELLA's
        // English-EFL AC11: the target language is KOREAN, so the scaffold fires when the
        // learner avoided Korean, encouraging them to try in Korean.
        if (!lastUserUtterance.isNullOrBlank() && !containsKorean(lastUserUtterance)) {
            sb.append(
                "The learner replied without using Korean. Warmly encourage them to try in " +
                    "Korean and offer a short scaffold (\"이렇게 말해볼 수 있어요: ...\"); keep speaking Korean yourself.\n",
            )
        }

        if (useLearnerState) {
            // AC6: ground in what the learner is currently looking at (most recent visual context).
            state.visualContext.lastOrNull()?.let { vc ->
                sb.append("The learner is looking at: ").append(vc.caption)
                if (vc.groundedObjects.isNotEmpty()) {
                    sb.append(" (objects: ").append(vc.groundedObjects.joinToString(", ")).append(")")
                }
                sb.append(". Ground vocabulary and questions in what they can see.\n")
            }

            val targets = state.vocabTargets.filter { !it.introduced }.take(3)
            if (targets.isNotEmpty()) {
                sb.append("Gently work in these target words if natural: ")
                    .append(targets.joinToString(", ") { it.word }).append('\n')
            }

            // The slow layer's longitudinal diagnosis. This is the recast that crosses turns:
            // not "say X instead of Y" for one slip (the fast layer does that on the same
            // turn), but "this learner keeps getting this pattern wrong — expose the correct
            // form repeatedly and naturally, without pointing at it". Practice targets give
            // the tutor concrete forms so the exposure is not left to chance.
            // Measured 2026-09-18: asked "what happened on your trip?" the tutor replied "I drove
            // up, hiked, and caught the sunset" — three irregular pasts, none of the three listed
            // examples. That is the pattern followed correctly; the examples are there so the
            // model is never short of a form, not as a checklist. The wording makes that explicit.
            state.ruleGap?.let { gap ->
                sb.append("This learner keeps making the same mistake: ").append(gap).append(". ")
                sb.append("In your reply, naturally model the correct pattern two or three times")
                if (state.practiceTargets.isNotEmpty()) {
                    sb.append(" — any correct forms will do; if you need some, ")
                        .append(state.practiceTargets.joinToString(", ")).append(" fit this learner")
                }
                sb.append(". Do not point at the mistake or explain the rule.\n")
            }
        }

        if (corrections.isNotEmpty()) {
            sb.append("Weave in this brief correction supportively, then continue the conversation: ")
                .append(corrections.sortedBy { it.priority }.joinToString(" ") { it.text })
        }

        return sb.toString().trim()
    }
}
