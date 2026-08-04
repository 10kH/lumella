package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFitTest {
    @Test
    fun `short text is shown whole`() {
        val text = "네, 저건 에어컨이에요."
        assertEquals(text, SubtitleFit.tail(text, SubtitleFit.SUBTITLE_MAX_LINES))
    }

    @Test
    fun `hangul costs two units and latin costs one`() {
        assertEquals(2, SubtitleFit.widthOf('네'))
        assertEquals(1, SubtitleFit.widthOf('a'))
        assertEquals(1, SubtitleFit.widthOf(' '))
        // Mixed run: one Hangul at two columns plus three single-column characters.
        assertEquals(5, SubtitleFit.widthOf("네a b"))
    }

    @Test
    fun `korean is budgeted by width so it cannot overflow the visible lines`() {
        // The reported bug: a character budget let Korean wrap to five lines in a
        // four-line view, so the closing words were clipped away unseen.
        val korean = "요즘 날씨가 부쩍 쌀쌀해져서 아침에 나갈 때마다 외투를 챙기게 되는데요, " +
            "어제는 깜빡하고 얇은 셔츠만 입고 나갔다가 하루 종일 덜덜 떨었어요. " +
            "그래서 오늘은 두꺼운 코트를 꺼내 입고 목도리까지 둘렀답니다."
        val shown = SubtitleFit.tail(korean, SubtitleFit.SUBTITLE_MAX_LINES)

        val budget = SubtitleFit.SUBTITLE_MAX_LINES * SubtitleFit.UNITS_PER_LINE
        assertTrue("width ${SubtitleFit.widthOf(shown)} exceeds $budget", SubtitleFit.widthOf(shown) <= budget)
        // A plain character count would have passed the old budget while overflowing.
        assertTrue("expected the character count to be below the old 168 budget", shown.length < 168)
    }

    @Test
    fun `the tail is kept so the newest words survive`() {
        val korean = "가".repeat(200) + " 마지막말"
        val shown = SubtitleFit.tail(korean, SubtitleFit.SUBTITLE_MAX_LINES)
        assertTrue("tail lost: $shown", shown.endsWith("마지막말"))
        assertTrue("head should be marked as cut", shown.startsWith("…"))
    }

    @Test
    fun `english keeps whole words`() {
        val english = "This is a deliberately long English sentence, well past what the glasses " +
            "can show at once, written so that the shaping code has to drop a good deal of the " +
            "opening before it can leave the closing words on screen and still readable."
        val shown = SubtitleFit.tail(english, SubtitleFit.SUBTITLE_MAX_LINES)
        assertTrue(shown.endsWith("readable."))
        // Never resumes mid-word.
        val firstWord = shown.removePrefix("…").substringBefore(' ')
        assertTrue("resumed mid-word: $firstWord", english.contains(" $firstWord"))
    }

    @Test
    fun `a two line echo gets half the budget`() {
        val korean = "가".repeat(200)
        val echo = SubtitleFit.tail(korean, SubtitleFit.USER_ECHO_MAX_LINES)
        assertTrue(
            SubtitleFit.widthOf(echo) <= SubtitleFit.USER_ECHO_MAX_LINES * SubtitleFit.UNITS_PER_LINE,
        )
    }
}
