package com.woolab.lumella.slowpath

import com.woolab.lumella.contract.TurnEvidence

/**
 * Assembles [TurnEvidence] for a completed turn, extracted out of
 * `MainActivity.submitCurrentTurnEvidence()` so the imageId wiring (the last
 * cell of the fast-path evidence contract that can otherwise only be checked
 * by wearer speech) is covered by a pure, unit-testable class.
 *
 * [pendingImageId] mirrors a photo captured mid-session ([GlassesCamera] ->
 * `analyzeImage`): it is attached to at most ONE turn. [assemble] consumes it
 * one-shot so a photo is never silently reused on a later, unrelated turn.
 */
class TurnEvidenceAssembler {
    @Volatile private var pendingImageId: String? = null

    /** Stages an image id to be attached to the next assembled turn. Blank ids are treated as absent. */
    fun setPendingImageId(imageId: String?) {
        pendingImageId = imageId?.takeIf { it.isNotBlank() }
    }

    /** Current pending image id without consuming it (null if none is staged). */
    fun peekPendingImageId(): String? = pendingImageId

    /**
     * Builds [TurnEvidence] for [turnId]/[transcript], attaching and consuming
     * the currently staged image id (if any). After this call the staged
     * image id is cleared, so it will not be attached to a subsequent turn.
     */
    fun assemble(turnId: Int, transcript: String): TurnEvidence {
        val imageId = pendingImageId
        pendingImageId = null
        return TurnEvidence(turnId = turnId, learnerTranscript = transcript, imageId = imageId)
    }
}
