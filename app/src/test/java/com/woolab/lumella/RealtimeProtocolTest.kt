package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeProtocolTest {
    @Test
    fun rejectsStandardOpenAiApiKeysAsRealtimeClientCredentials() {
        assertTrue(RealtimeCredentialGuard.isStandardOpenAiApiKey("s" + "k-proj-secret"))
        assertTrue(RealtimeCredentialGuard.isStandardOpenAiApiKey("s" + "k-secret"))
        assertTrue(RealtimeCredentialGuard.isStandardOpenAiApiKey("Bearer " + "s" + "k-proj-secret"))
        assertTrue(RealtimeCredentialGuard.isStandardOpenAiApiKey("bearer    " + "s" + "k-secret"))
        assertFalse(RealtimeCredentialGuard.isStandardOpenAiApiKey("ek_short_lived_client_secret"))
    }

    @Test
    fun mapsLegacyAndGaAudioDeltaEvents() {
        assertEquals(
            RealtimeServerEventKind.AUDIO_DELTA,
            RealtimeServerEventTypes.kindOf("response.audio.delta")
        )
        assertEquals(
            RealtimeServerEventKind.AUDIO_DELTA,
            RealtimeServerEventTypes.kindOf("response.output_audio.delta")
        )
    }

    @Test
    fun mapsLegacyAndGaAudioTranscriptEvents() {
        assertEquals(
            RealtimeServerEventKind.AUDIO_TRANSCRIPT_DELTA,
            RealtimeServerEventTypes.kindOf("response.audio_transcript.delta")
        )
        assertEquals(
            RealtimeServerEventKind.AUDIO_TRANSCRIPT_DELTA,
            RealtimeServerEventTypes.kindOf("response.output_audio_transcript.delta")
        )
    }
}
