package com.woolab.lumella

import com.woolab.lumella.voice.OpenAiRealtimeTransport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseTranscriptTest {
    @Test
    fun promptEchoedBackOnBabbleIsNoise() {
        // Verbatim from the cafe-babble probe (results-vad-noise.json, app config, both runs).
        val echoed = "들리는 대로 정확하게 받아쓰세요. 문법 실수도 그대로 적으세요. 조사, 어미, 시제를 고치지 마세요. " +
            "예: \"어제 친구를 만나요\"는 \"어제 친구를 만나요\" 그대로."
        assertTrue(NoiseTranscript.isNoise(echoed))
    }

    @Test
    fun aFragmentOfThePromptIsNoise() {
        assertTrue(NoiseTranscript.isNoise("문법 실수도 그대로 적으세요."))
        assertTrue(OpenAiRealtimeTransport.TRANSCRIPTION_PROMPT.contains("문법 실수도 그대로 적으세요."))
    }

    @Test
    fun blankIsNoise() {
        assertTrue(NoiseTranscript.isNoise("   "))
    }

    @Test
    fun learnerLinesAreNotNoise() {
        for (line in listOf("친구가 커피가 샀어요", "이거 뭐예요?", "네", "음", "어제 친구를 만나요",
                "근데 BTS 콘서트 티켓은 어떻게 사요?")) {
            assertFalse(line, NoiseTranscript.isNoise(line))
        }
    }
}
