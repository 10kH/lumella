package com.woolab.lumella.pedagogy

import com.woolab.lumella.state.Correction
import com.woolab.lumella.state.LearnerState

/**
 * Composes the per-response steering text (plan P5) injected via
 * response.create.instructions. Pure + JVM-testable.
 *
 * Combines: clean persona summary, target vocabulary, the most recent egocentric
 * visual context (AC6 — grounding what the learner is looking at), Korean-EFL
 * code-switching scaffolding (AC11), and non-stale deferred corrections.
 */
object SteeringComposer {

    /** True if the text contains any Hangul syllable/jamo (learner code-switched to Korean). */
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

        // AC11: Korean-EFL code-switching — encourage English, offer a scaffold, do not switch to Korean.
        if (lastUserUtterance != null && containsKorean(lastUserUtterance)) {
            sb.append(
                "The learner just code-switched into Korean. Warmly encourage them to try in " +
                    "English and offer a short scaffold (\"You can say it like ...\"); do not switch to Korean yourself.\n",
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
        }

        if (corrections.isNotEmpty()) {
            sb.append("Weave in this brief correction supportively, then continue the conversation: ")
                .append(corrections.sortedBy { it.priority }.joinToString(" ") { it.text })
        }

        return sb.toString().trim()
    }
}
