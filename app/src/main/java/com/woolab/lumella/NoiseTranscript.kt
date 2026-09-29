package com.woolab.lumella

import com.woolab.lumella.voice.OpenAiRealtimeTransport

/**
 * Recognises a "turn" that was noise, from its finished transcript.
 *
 * Server VAD opens turns on background chatter — 2-3 a minute on synthetic cafe babble at every
 * threshold tried, 0.5 to 0.8, and semantic VAD was no better (aaai27 artifacts/shoot-risk). The
 * transcriber, given nothing to hear, returns its own prompt back ("들리는 대로 정확하게
 * 받아쓰세요…"), in both runs. The tutor's reply to such a turn is already on its way by then; what
 * this keeps out is everything downstream of the transcript: the learner record (a fake turn is a
 * fake data point for the habit diagnosis), the coach, and the on-screen echo.
 *
 * Conservative on purpose: only a blank transcript or the prompt echoed back. A short real
 * utterance ("네", "음") is a turn.
 */
object NoiseTranscript {
    private val promptMarkers: List<String> = listOf("들리는 대로", "받아쓰")

    fun isNoise(transcript: String): Boolean {
        val t = transcript.trim()
        if (t.isEmpty()) return true
        return promptMarkers.all { t.contains(it) } ||
            (t.length >= 12 && OpenAiRealtimeTransport.TRANSCRIPTION_PROMPT.contains(t))
    }
}
