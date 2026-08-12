package com.woolab.lumella

import java.util.concurrent.atomic.AtomicReference

/**
 * What the wearer asked the display to be, by voice (08/05 requirement 2).
 *
 * The four Korean text commands map to one mode argument rather than independent size and
 * visibility flags, because the requirement couples them: "텍스트 작게" both shrinks the tutor
 * text AND hides the learner echo, and "텍스트 켜줘" restores the full default view rather
 * than whatever combination was last active. Modelling the coupling here keeps every caller
 * from re-deriving it.
 *
 * Pure and dependency-free so the transitions are testable on a plain JVM; MainActivity maps
 * [State] onto views. Per-session by design — nothing persists.
 *
 * This file is hand-synchronised between the two glasses apps and pinned by
 * DisplaySettingsDriftTest against a checked-in copy, like SubtitleRetention.
 */
class DisplaySettings {

    /** Tutor-subtitle size when visible. LARGE is the pre-existing default look. */
    enum class SubtitleSize { LARGE, SMALL }

    data class State(
        val subtitleVisible: Boolean = true,
        val subtitleSize: SubtitleSize = SubtitleSize.LARGE,
        /** "텍스트 작게" hides the learner echo too — the wearer asked for less text, not more. */
        val echoVisible: Boolean = true,
        val hintsVisible: Boolean = true,
    )

    private val state = AtomicReference(State())

    fun current(): State = state.get()

    /**
     * Applies a `set_text_display` tool call. Returns the new state, or null when [mode] is
     * not one the tool declares — the caller answers the model with an error rather than
     * guessing, because a guessed display change is invisible to the model and the wearer
     * ends up arguing with a tutor that thinks it already helped.
     */
    fun applyTextMode(mode: String): State? {
        val next = when (mode) {
            "large" -> State(subtitleVisible = true, subtitleSize = SubtitleSize.LARGE, echoVisible = true, hintsVisible = current().hintsVisible)
            "small" -> State(subtitleVisible = true, subtitleSize = SubtitleSize.SMALL, echoVisible = false, hintsVisible = current().hintsVisible)
            "off" -> State(subtitleVisible = false, subtitleSize = current().subtitleSize, echoVisible = false, hintsVisible = current().hintsVisible)
            "on" -> State(subtitleVisible = true, subtitleSize = SubtitleSize.LARGE, echoVisible = true, hintsVisible = current().hintsVisible)
            else -> return null
        }
        state.set(next)
        return next
    }

    /** Applies a `set_hints_visible` tool call. Text settings are untouched. */
    fun applyHintsVisible(visible: Boolean): State {
        val next = current().copy(hintsVisible = visible)
        state.set(next)
        return next
    }

    companion object {
        /** sp when LARGE — matches the layout default. */
        const val SUBTITLE_SP_LARGE = 24f

        /** sp when SMALL — the user-echo size, per the requirement's own reference point. */
        const val SUBTITLE_SP_SMALL = 18f
    }
}
