package com.woolab.lumella.contract

/**
 * Result of [TutorBrain.analyzeImage]: descriptive context extracted from an
 * image, used as grounding for the fast path's own response — not spoken
 * directly to the learner (see the D-4 rule on [SteeringEvidence]).
 *
 * @param imageId identifier the brain assigned to the analyzed image.
 * @param caption optional short description of the image.
 * @param imageKind optional coarse classification (e.g. "text", "scene").
 * @param visibleText OCR/visible text extracted from the image, if any.
 */
data class ImageContext(
    val imageId: String,
    val caption: String? = null,
    val imageKind: String? = null,
    val visibleText: List<String> = emptyList()
)
