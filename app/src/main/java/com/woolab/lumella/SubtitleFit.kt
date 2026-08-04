package com.woolab.lumella

/**
 * Decides how much of a transcript can be shown on the glasses.
 *
 * Kept dependency-free so the sizing rules are unit-testable on a plain JVM, away from the
 * Android view code in MainActivity.
 *
 * Two things make this less trivial than it looks:
 *
 * A TextView with `maxLines` renders the FIRST lines, so handing it a growing transcript
 * pins the opening of a sentence on screen and hides whatever the tutor just said. The tail
 * is what matters, so the head is dropped here rather than by the view.
 *
 * And a budget counted in characters is wrong for this app. Hangul occupies about twice the
 * width of a Latin letter at the same size, so 168 characters of English fit in four lines
 * while 168 characters of Korean need seven — the surplus is silently clipped and the reply
 * ends mid-thought. Budget is therefore counted in width units, with wide scripts costing
 * two, which keeps one rule correct for Korean, English, and sentences that mix both.
 *
 * The numbers are measured on the device (2026-08-04), not assumed: framebuffer 1280x480 at
 * density 160 gives 640dp per eye, and after 24dp margins a 24sp line held 51-57 Latin
 * characters. [UNITS_PER_LINE] is set below that measurement, and the residual error is
 * caught at runtime by the clipping check in MainActivity rather than trusted blindly.
 */
object SubtitleFit {
    /** Latin characters that fit on one 592dp line at 24sp, kept under the measured 51. */
    const val UNITS_PER_LINE = 48
    const val SUBTITLE_MAX_LINES = 4
    const val USER_ECHO_MAX_LINES = 2
    private const val ELLIPSIS = "…"

    /** Width of [c] relative to a Latin letter: wide scripts occupy two columns. */
    fun widthOf(c: Char): Int {
        val cp = c.code
        val wide = cp in 0x1100..0x115F || // Hangul Jamo
            cp in 0x2E80..0x303E || // CJK radicals, punctuation
            cp in 0x3041..0x33FF || // kana, compatibility
            cp in 0x3400..0x4DBF || // CJK ext A
            cp in 0x4E00..0x9FFF || // CJK unified
            cp in 0xA000..0xA4CF || // Yi
            cp in 0xAC00..0xD7A3 || // Hangul syllables
            cp in 0xF900..0xFAFF || // CJK compatibility
            cp in 0xFE30..0xFE6F || // CJK compatibility forms
            cp in 0xFF00..0xFF60 || // fullwidth forms
            cp in 0xFFE0..0xFFE6
        return if (wide) 2 else 1
    }

    /** Display width of [text] in the same units as [UNITS_PER_LINE]. */
    fun widthOf(text: String): Int = text.sumOf { widthOf(it) }

    /**
     * Returns the trailing part of [text] that fits in [maxLines], prefixed with an ellipsis
     * when the head was dropped. The cut snaps forward to a word boundary so a word is never
     * split, which matters more in English than Korean but costs nothing either way.
     */
    fun tail(text: String, maxLines: Int, unitsPerLine: Int = UNITS_PER_LINE): String {
        val budget = (maxLines * unitsPerLine).coerceAtLeast(1)
        if (widthOf(text) <= budget) return text

        // The ellipsis is drawn too, so it has to come out of the same budget or the
        // result overflows by exactly one column.
        val room = (budget - widthOf(ELLIPSIS)).coerceAtLeast(1)
        var used = 0
        var start = text.length
        while (start > 0) {
            val next = used + widthOf(text[start - 1])
            if (next > room) break
            used = next
            start--
        }
        if (start <= 0) return text

        // Snap forward to the next word boundary; if the remainder has no space (common in
        // Korean) keep the raw cut rather than dropping the whole tail.
        val space = text.indexOf(' ', start)
        val boundary = if (space in start until text.length) space + 1 else start
        return ELLIPSIS + text.substring(boundary).trimStart()
    }
}
