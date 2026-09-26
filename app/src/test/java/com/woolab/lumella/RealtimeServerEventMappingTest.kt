package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeServerEventMappingTest {
    @Test
    fun mapsInputTranscriptionEvents() {
        assertEquals(
            RealtimeServerEventKind.INPUT_TRANSCRIPT_COMPLETED,
            RealtimeServerEventTypes.kindOf("conversation.item.input_audio_transcription.completed"),
        )
        assertEquals(
            RealtimeServerEventKind.INPUT_TRANSCRIPT_DELTA,
            RealtimeServerEventTypes.kindOf("conversation.item.input_audio_transcription.delta"),
        )
    }
}
