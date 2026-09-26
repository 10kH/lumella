package com.woolab.tutor.slowpath

/**
 * Everything the slow path needs to know about the language it tutors.
 *
 * The slow layer's design is language-agnostic — a real-time model recasts the slip in the
 * turn, a reasoning model reads the accumulated record and names the habit — and the paper
 * says so. The code was not: ELLA and lumella each carried a copy of the slow path with the
 * language baked into three places. The English gate refused any Hangul; the Korean gate
 * refused any CJK or kana and counted Hangul words. The steering scaffold told an English
 * learner who slipped into Korean to try English, and a Korean learner who slipped into
 * English to try Korean. The endpoint client sent "en" or "ko". Everything else — 4 files
 * byte-identical, 3 more differing only in those lines — was duplicated for no reason, and
 * porting one week of ELLA's changes to lumella took a day.
 *
 * This is the seam. One instance per app; the slow path takes it and asks.
 */
data class TutorLanguage(
    /** The code the pedagogy function selects its prompt table by: "en", "ko". */
    val code: String,
    /**
     * Whether a transcript is plausibly in the tutored language. The second wall after
     * Whisper's language pin: ambient audio in another language must not enter the learner's
     * record as errors. What counts as "another language" is the language's own business.
     */
    val isPlausibly: (String) -> Boolean,
    /**
     * Whether the learner's last utterance was in the tutored language, for the steering
     * scaffold. Null when the language does not scaffold code-switching.
     */
    val learnerUsed: ((String) -> Boolean)?,
    /**
     * What the tutor is told when the learner replied in some other language. Warm, short,
     * offers a scaffold in the tutored language, and tells the tutor not to switch itself.
     */
    val codeSwitchScaffold: String,
) {
    companion object {
        private fun isHangul(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c) ?: return false
            return block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
        }

        private fun isCjkOrKana(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c) ?: return false
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA
        }

        private fun isLatin(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c) ?: return false
            return block == Character.UnicodeBlock.BASIC_LATIN || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        }

        fun containsHangul(text: String): Boolean = text.any(::isHangul)

        /**
         * English tutor for Korean learners. Any Hangul, CJK or kana in the transcript means
         * the mic caught something other than the learner's English; at least half the letters
         * must be Latin. A learner who slips into Korean is scaffolded back toward English.
         */
        val ENGLISH = TutorLanguage(
            code = "en",
            isPlausibly = { text ->
                var latin = 0
                var letters = 0
                var foreign = false
                for (ch in text) {
                    if (isHangul(ch) || isCjkOrKana(ch)) { foreign = true; break }
                    if (Character.isLetter(ch)) {
                        letters++
                        if (isLatin(ch)) latin++
                    }
                }
                !foreign && letters > 0 && latin * 2 >= letters
            },
            learnerUsed = { text -> !containsHangul(text) },
            codeSwitchScaffold =
                "The learner just code-switched into Korean. Warmly encourage them to try in " +
                    "English and offer a short scaffold (\"You can say it like ...\"); do not switch to Korean yourself.",
        )

        /**
         * Korean tutor. Chinese or Japanese anywhere means a screen or a neighbour, not the
         * learner. Otherwise count words, not letters: a Hangul syllable carries what three
         * Latin letters do, and a letter ratio gated "저는 Jennifer예요" — a visitor introducing
         * themselves. A word with any Hangul in it is a Korean word; half the words must be.
         * A learner who replies in English is scaffolded back toward Korean.
         */
        val KOREAN = TutorLanguage(
            code = "ko",
            isPlausibly = { text ->
                if (text.any(::isCjkOrKana)) {
                    false
                } else {
                    var words = 0
                    var korean = 0
                    for (token in text.split(Regex("\\s+"))) {
                        var hasLetter = false
                        var hasHangul = false
                        for (ch in token) {
                            if (!Character.isLetter(ch)) continue
                            hasLetter = true
                            if (isHangul(ch)) hasHangul = true
                        }
                        if (hasLetter) {
                            words++
                            if (hasHangul) korean++
                        }
                    }
                    words > 0 && korean * 2 >= words
                }
            },
            learnerUsed = ::containsHangul,
            codeSwitchScaffold =
                "The learner replied without using Korean. Warmly encourage them to try in " +
                    "Korean and offer a short scaffold (\"이렇게 말해볼 수 있어요: ...\"); keep speaking Korean yourself.",
        )
    }
}
