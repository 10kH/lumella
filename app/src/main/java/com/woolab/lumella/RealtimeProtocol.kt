package com.woolab.lumella

object RealtimeCredentialGuard {
    private val bearerPrefix = Regex("^Bearer\\s+", RegexOption.IGNORE_CASE)

    fun isStandardOpenAiApiKey(token: String): Boolean {
        val credential = bearerPrefix.replace(token.trim(), "").trim()
        return credential.startsWith("sk-") || credential.startsWith("sk_")
    }
}

class StandardOpenAiApiKeyRejectedException : SecurityException(
    "Token endpoint returned a standard OpenAI API key; Android requires a short-lived Realtime client secret or relay credential."
)

enum class RealtimeServerEventKind {
    SESSION_CREATED,
    SESSION_UPDATED,
    AUDIO_DELTA,
    AUDIO_DONE,
    RESPONSE_CREATED,
    RESPONSE_DONE,
    ERROR,
    SPEECH_STARTED,
    SPEECH_STOPPED,
    INPUT_AUDIO_COMMITTED,
    AUDIO_TRANSCRIPT_DELTA,
    INPUT_TRANSCRIPT_COMPLETED,
    INPUT_TRANSCRIPT_DELTA,
    INPUT_TRANSCRIPT_FAILED,
    RESPONSE_OUTPUT_ITEM_DONE,
    OTHER
}

object RealtimeServerEventTypes {
    fun kindOf(type: String): RealtimeServerEventKind = when (type) {
        "session.created" -> RealtimeServerEventKind.SESSION_CREATED
        "session.updated" -> RealtimeServerEventKind.SESSION_UPDATED
        "response.audio.delta",
        "response.output_audio.delta" -> RealtimeServerEventKind.AUDIO_DELTA
        "response.audio.done",
        "response.output_audio.done" -> RealtimeServerEventKind.AUDIO_DONE
        "response.created" -> RealtimeServerEventKind.RESPONSE_CREATED
        "response.done" -> RealtimeServerEventKind.RESPONSE_DONE
        "error" -> RealtimeServerEventKind.ERROR
        "input_audio_buffer.speech_started" -> RealtimeServerEventKind.SPEECH_STARTED
        "input_audio_buffer.speech_stopped" -> RealtimeServerEventKind.SPEECH_STOPPED
        "input_audio_buffer.committed" -> RealtimeServerEventKind.INPUT_AUDIO_COMMITTED
        "response.output_item.done" -> RealtimeServerEventKind.RESPONSE_OUTPUT_ITEM_DONE
        "response.audio_transcript.delta",
        "response.output_audio_transcript.delta" -> RealtimeServerEventKind.AUDIO_TRANSCRIPT_DELTA
        "conversation.item.input_audio_transcription.completed" ->
            RealtimeServerEventKind.INPUT_TRANSCRIPT_COMPLETED
        "conversation.item.input_audio_transcription.delta" ->
            RealtimeServerEventKind.INPUT_TRANSCRIPT_DELTA
        "conversation.item.input_audio_transcription.failed" ->
            RealtimeServerEventKind.INPUT_TRANSCRIPT_FAILED
        else -> RealtimeServerEventKind.OTHER
    }
}
