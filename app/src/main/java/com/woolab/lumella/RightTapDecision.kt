package com.woolab.lumella

/**
 * What a tap on the right touchpad means.
 *
 * Pure so the rules are testable on a plain JVM: the real gesture arrives as a MotionEvent on
 * a device with no way to inject one, and this is the path a wearer reported the app closing
 * on them from. It had no coverage at all.
 */
sealed interface RightTap {
    /**
     * Not a tap: the pad bouncing, or a press/swipe held too long. Ignored entirely — the
     * time is not even recorded, because doing so would make a bounce the first half of a
     * phantom double tap.
     */
    data object Ignore : RightTap

    /** Publish the current turn: "I have finished speaking." */
    data object EndTurn : RightTap

    /** A double tap, but exit was not armed. Ask before doing something irreversible. */
    data object ArmExit : RightTap

    /** A tap inside the confirmation window. Quit. */
    data object ConfirmExit : RightTap
}

object RightTapRules {
    /**
     * Contact shorter than this is the pad bouncing, not a person.
     *
     * The discriminator is CONTACT DURATION, not the gap between taps. The bounce that used to
     * kill the app mid-turn measured 6ms of contact while every deliberate tap in the same
     * session measured 87-222ms. Gating on the gap instead made the deliberate double tap
     * unreachable, because the first tap always started a turn.
     */
    const val MIN_CONTACT_MS = 40L

    /** Longer contact is a press or a swipe, not a tap. */
    const val MAX_CONTACT_MS = 500L

    /** Two taps closer together than this are one gesture. */
    const val DOUBLE_TAP_INTERVAL_MS = 400L

    /** How long an armed exit waits for its confirming tap. */
    const val EXIT_CONFIRM_WINDOW_MS = 2_500L

    /**
     * @param contactMs how long the finger was down
     * @param sinceLastTapMs gap since the previous accepted right tap
     * @param nowMs current time
     * @param exitArmedUntilMs deadline set by a previous [RightTap.ArmExit], 0 when unarmed
     */
    fun decide(
        contactMs: Long,
        sinceLastTapMs: Long,
        nowMs: Long,
        exitArmedUntilMs: Long,
    ): RightTap = when {
        contactMs < MIN_CONTACT_MS || contactMs >= MAX_CONTACT_MS -> RightTap.Ignore
        sinceLastTapMs >= DOUBLE_TAP_INTERVAL_MS -> RightTap.EndTurn
        nowMs < exitArmedUntilMs -> RightTap.ConfirmExit
        else -> RightTap.ArmExit
    }
}
