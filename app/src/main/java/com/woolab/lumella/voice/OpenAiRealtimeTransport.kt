package com.woolab.lumella.voice

import com.woolab.lumella.RealtimeCredentialGuard
import com.woolab.lumella.RealtimeServerEventKind
import com.woolab.lumella.RealtimeServerEventTypes
import com.woolab.lumella.StandardOpenAiApiKeyRejectedException
import com.woolab.lumella.TokenServiceCredentialProvider
import com.woolab.lumella.util.MiniJson

/** Coarse connection/session status surfaced to the UI (mirrors LEGACY's status-text states, plan G006). */
enum class RealtimeConnectionStatus { CONNECTING, READY, DEGRADED, TOKEN_FAIL, CLOSED, IDLE, ACCOUNT_BLOCKED }

/**
 * [RealtimeTransport] implementation wired to OpenAI's Realtime API over WebSocket
 * (`wss://api.openai.com/v1/realtime?model=<model>`), ported from ELLA's LEGACY
 * MainActivity WS/event recipe: one `session.update` on open (persona/audio config),
 * `input_audio_buffer.append`/`.commit` audio framing, and `response.create` with
 * `response.instructions` per turn.
 *
 * D-4: as a [RealtimeTransport] this class exposes ONLY [sendInstructions] to
 * [VoiceFastPath]; the wider surface below (connect/audio/close) is for
 * [com.woolab.lumella.MainActivity]'s device wiring, not the fast-path loop — brain/steering
 * text still has no channel to spoken output other than composed instructions.
 *
 * The WebSocket itself is injected via [RealtimeWebSocketFactory] so unit tests exercise
 * event composition (session.update / response.create / audio append JSON shape) against a
 * fake socket — no real network in tests.
 */
class OpenAiRealtimeTransport(
    private val credentialProvider: TokenServiceCredentialProvider,
    private val socketFactory: RealtimeWebSocketFactory,
    private val model: String = "gpt-realtime",
    private val voice: String = "shimmer",
    private val sampleRateHz: Int = 24_000,
    private val sessionInstructions: String = DEFAULT_SESSION_INSTRUCTIONS,
    private val listener: Listener = Listener.NONE,
    /**
     * Schedules a reconnect attempt after `delayMs`. Injectable so unit tests run
     * reconnection synchronously; production default uses a shared daemon scheduler.
     * OpenAI realtime sessions have a hard 60-minute maximum duration
     * (`session_expired`, observed on-device 2026-07-21), so unexpected closes are
     * NORMAL steady-state and MUST self-heal without an app restart.
     */
    private val reconnectScheduler: (delayMs: Long, task: () -> Unit) -> Unit = DEFAULT_SCHEDULER,
    /**
     * Idle-timeout safety valve (cost risk observed 2026-07-26: an unattended session held a
     * realtime WS open ~24h, reconnecting every 60min on `session_expired` — 26 reconnects with
     * no user activity). After [idleTimeoutMs] with no [noteActivity] call (tap or outbound audio
     * chunk), the transport closes itself exactly like a client-initiated [close] (no
     * auto-reconnect); the UI is expected to re-[connect] on the next tap.
     */
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /**
     * Schedules the idle-timeout check after `delayMs`. Injectable so unit tests fire it
     * synchronously with a captured task instead of waiting on a real clock — mirrors
     * [reconnectScheduler]'s pattern above.
     */
    private val idleScheduler: (delayMs: Long, task: () -> Unit) -> Unit = DEFAULT_IDLE_SCHEDULER,
) : RealtimeTransport {

    interface Listener {
        fun onStatus(status: RealtimeConnectionStatus) {}
        fun onAudioDelta(base64Pcm16: String) {}
        fun onAudioDone() {}
        fun onInputTranscript(text: String) {}
        fun onError(message: String) {}

        companion object {
            val NONE: Listener = object : Listener {}
        }
    }

    companion object {
        /**
         * v1 product decision (Intent Reconciliation 2026-07-21): KOREAN tutoring.
         * English meta-instructions, Korean speech — realtime models follow this reliably.
         */
        const val DEFAULT_SESSION_INSTRUCTIONS =
            "You are Lumella, a warm, encouraging Korean-language conversation tutor. " +
                "Speak in natural, clear Korean matched to the learner's level; keep replies " +
                "short and conversational. Weave corrections in gently as part of the dialogue. " +
                "Do not switch to English unless the learner is completely stuck — then give a " +
                "brief Korean scaffold instead."

        /** Reconnect backoff: 1s, 2s, 4s, … capped at 30s; reset on READY. */
        internal const val RECONNECT_BASE_DELAY_MS = 1_000L
        internal const val RECONNECT_MAX_DELAY_MS = 30_000L

        private val defaultReconnectExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                java.util.concurrent.ThreadFactory { r ->
                    Thread(r, "lumella-realtime-reconnect").apply { isDaemon = true }
                },
            )
        }

        private val DEFAULT_SCHEDULER: (Long, () -> Unit) -> Unit = { delayMs, task ->
            defaultReconnectExecutor.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        /** Idle-timeout default: 10 minutes with no tap/audio activity (plan G006 cost-safety follow-up). */
        internal const val DEFAULT_IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        /** Server error codes that reconnecting cannot fix (account/billing/key level). */
        internal val FATAL_ACCOUNT_ERROR_CODES = listOf(
            "insufficient_quota",
            "invalid_api_key",
            "account_deactivated",
        )

        private val defaultIdleExecutor: java.util.concurrent.ScheduledExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                java.util.concurrent.ThreadFactory { r ->
                    Thread(r, "lumella-realtime-idle").apply { isDaemon = true }
                },
            )
        }

        private val DEFAULT_IDLE_SCHEDULER: (Long, () -> Unit) -> Unit = { delayMs, task ->
            defaultIdleExecutor.schedule(task, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    @Volatile
    private var socket: RealtimeWebSocket? = null

    @Volatile
    var sessionReady: Boolean = false
        private set

    @Volatile
    private var closedByClient: Boolean = false

    /** True once [close] (client teardown or idle-timeout) has run and no [connect] has followed. Lets
     *  [com.woolab.lumella.MainActivity] tell "not ready because reconnecting" from "not ready because
     *  the idle-timeout (or user) closed it" on tap, so a tap can wake a closed session. */
    val isClosed: Boolean
        get() = closedByClient

    private val reconnectPending = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var reconnectDelayMs: Long = RECONNECT_BASE_DELAY_MS
    /**
     * Idle-timeout generation guard, same shape as [socketGeneration]: each [noteActivity] call
     * bumps this, invalidating any already-scheduled (stale) idle-timeout task so only the most
     * recent activity's timer can actually fire [onIdleTimeout].
     */
    private val idleGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Socket generation guard: each [openSocket] supersedes the previous socket.
     * Callbacks from a superseded (zombie) socket — e.g. the okhttp pinger of an
     * expired session timing out ~20s after we already reconnected (observed
     * on-device 2026-07-22 09:03:39, spurious DEGRADED flash) — are ignored.
     */
    private val socketGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Fetches a token-service credential and opens the realtime WebSocket. Never throws — fail-closed.
     *
     * THREADING: the credential fetch is a **blocking** HTTP call on the calling thread
     * (see HttpUrlConnectionTokenHttpTransport). Callers MUST invoke this off the Android
     * main thread or it raises NetworkOnMainThreadException.
     */
    fun connect() {
        closedByClient = false
        noteActivity()
        listener.onStatus(RealtimeConnectionStatus.CONNECTING)
        credentialProvider.fetchToken { result ->
            result.fold(
                onSuccess = { token -> openSocket(token.value) },
                onFailure = { error ->
                    listener.onStatus(RealtimeConnectionStatus.TOKEN_FAIL)
                    listener.onError(error.message ?: error.javaClass.simpleName)
                },
            )
        }
    }

    private fun openSocket(bearerToken: String) {
        if (RealtimeCredentialGuard.isStandardOpenAiApiKey(bearerToken)) {
            listener.onStatus(RealtimeConnectionStatus.TOKEN_FAIL)
            listener.onError(StandardOpenAiApiKeyRejectedException().message.orEmpty())
            return
        }

        val url = "wss://api.openai.com/v1/realtime?model=$model"
        // GA endpoint + GA session shape. Do NOT send "OpenAI-Beta: realtime=v1":
        // the live API rejects it with beta_api_shape_disabled (observed on-device
        // 2026-07-21; the beta protocol was the LEGACY ELLA path and is retired).
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
        )
        // Supersede any previous socket: bump the generation FIRST so the old
        // socket's close/failure callbacks are ignored as stale, then close it.
        val generation = socketGeneration.incrementAndGet()
        socket?.close(1000, "superseded by reconnect")
        socket = socketFactory.connect(
            url,
            headers,
            object : RealtimeWebSocketListener {
                private fun stale(): Boolean = generation != socketGeneration.get()

                override fun onOpen() {
                    if (stale()) return
                    sendRaw(buildSessionUpdateJson())
                }

                override fun onMessage(text: String) {
                    if (stale()) return
                    handleServerEvent(text)
                }

                override fun onClosing(code: Int, reason: String) {
                    if (stale()) return
                    sessionReady = false
                    listener.onStatus(RealtimeConnectionStatus.CLOSED)
                    scheduleReconnect()
                }

                override fun onClosed(code: Int, reason: String) {
                    if (stale()) return
                    sessionReady = false
                    listener.onStatus(RealtimeConnectionStatus.CLOSED)
                    scheduleReconnect()
                }

                override fun onFailure(t: Throwable) {
                    if (stale()) return
                    sessionReady = false
                    listener.onStatus(RealtimeConnectionStatus.DEGRADED)
                    listener.onError(t.message ?: t.javaClass.simpleName)
                    scheduleReconnect()
                }
            },
        )
    }

    /** D-4 ONLY channel: composes `response.create` with [instructions] and sends it. */
    override fun sendInstructions(instructions: String) {
        if (!sendRaw(buildResponseCreateJson(instructions))) {
            listener.onError("sendInstructions failed: socket unavailable")
        }
    }

    /** Appends a base64-encoded PCM16 audio chunk (see [com.woolab.lumella.audio.AudioCapture]) to the input buffer. */
    fun appendAudio(base64Pcm16: String) {
        if (sendRaw(buildAudioAppendJson(base64Pcm16))) {
            audioAppendedSinceCommit = true
            noteActivity()
        }
    }

    /**
     * Commits the input audio buffer, ending the learner's turn. Returns false if unsent.
     * Empty-commit guard (on-device finding 2026-07-23: taps without speech produced
     * `input_audio_buffer_commit_empty` server errors): a commit with no appended audio
     * since the last commit is skipped client-side.
     */
    fun commitAudio(): Boolean {
        if (!audioAppendedSinceCommit) return false
        val sent = sendRaw("""{"type":"input_audio_buffer.commit"}""")
        if (sent) audioAppendedSinceCommit = false
        return sent
    }

    @Volatile
    private var audioAppendedSinceCommit: Boolean = false

    /** Closes the WebSocket. Safe to call repeatedly / before connect. Suppresses auto-reconnect. */
    fun close() {
        closedByClient = true
        idleGeneration.incrementAndGet() // invalidate any pending idle-timeout task
        socket?.close(1000, "client teardown")
        socket = null
        sessionReady = false
    }

    /**
     * Marks "now" as the last user-activity instant (tap or outbound audio chunk) and
     * (re)arms the idle-timeout task, superseding any previously scheduled one via
     * [idleGeneration] — the same stale-guard shape [openSocket] uses for sockets. Public so
     * [com.woolab.lumella.MainActivity] can call it on every touchpad tap (not just audio),
     * per the "any user activity resets the window" contract.
     */
    fun noteActivity() {
        if (closedByClient) return
        val generation = idleGeneration.incrementAndGet()
        idleScheduler(idleTimeoutMs) {
            if (generation == idleGeneration.get()) onIdleTimeout()
        }
    }

    /**
     * Fires after [idleTimeoutMs] with no [noteActivity] call: reports [RealtimeConnectionStatus.IDLE]
     * then [close]s exactly like a client-initiated teardown (no auto-reconnect). A subsequent tap
     * is expected to call [connect] again (see MainActivity's toggleSpeechTurn wake-on-tap path).
     */
    private fun onIdleTimeout() {
        if (closedByClient) return
        listener.onStatus(RealtimeConnectionStatus.IDLE)
        close()
    }

    /**
     * Self-heal for unexpected closes/failures (e.g. OpenAI's hard 60-minute
     * `session_expired`, network blips): re-runs [connect] — which mints a fresh
     * ephemeral token via the TTL-cached provider — after an exponential backoff
     * (1s → 2s → 4s … capped 30s, reset on READY). Client-initiated [close]
     * never reconnects. At most one reconnect is pending at a time.
     */
    private fun scheduleReconnect() {
        if (closedByClient) return
        if (!reconnectPending.compareAndSet(false, true)) return
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectScheduler(delay) {
            reconnectPending.set(false)
            if (!closedByClient) connect()
        }
    }

    private fun sendRaw(json: String): Boolean = socket?.send(json) ?: false

    private fun handleServerEvent(text: String) {
        val obj = MiniJson.asObject(MiniJson.parse(text)) ?: return
        val type = MiniJson.string(obj, "type") ?: return
        when (RealtimeServerEventTypes.kindOf(type)) {
            RealtimeServerEventKind.SESSION_CREATED,
            RealtimeServerEventKind.SESSION_UPDATED,
            -> {
                sessionReady = true
                reconnectDelayMs = RECONNECT_BASE_DELAY_MS
                listener.onStatus(RealtimeConnectionStatus.READY)
            }
            RealtimeServerEventKind.AUDIO_DELTA -> {
                MiniJson.string(obj, "delta")?.let(listener::onAudioDelta)
            }
            RealtimeServerEventKind.AUDIO_DONE -> listener.onAudioDone()
            RealtimeServerEventKind.INPUT_TRANSCRIPT_COMPLETED -> {
                MiniJson.string(obj, "transcript")?.let(listener::onInputTranscript)
            }
            RealtimeServerEventKind.ERROR -> {
                if (isFatalAccountError(text)) {
                    // Permanent, account-level failure (no credit / bad key / disabled
                    // account). Retrying cannot fix it and the server closes the socket
                    // right after, so the reconnect loop would hammer the API forever
                    // (observed on-device 2026-07-28: CONNECT -> error -> CLOSE every ~4s).
                    // Stop reconnecting and surface it as its own status.
                    closedByClient = true
                    listener.onStatus(RealtimeConnectionStatus.ACCOUNT_BLOCKED)
                }
                listener.onError(text)
            }
            else -> Unit
        }
    }


    /**
     * True for permanent account-level errors that reconnecting cannot resolve.
     * Kept as substring matching on the server's `code` field so a new sibling code
     * degrades to the old (retrying) behavior rather than being silently swallowed.
     */
    internal fun isFatalAccountError(eventText: String): Boolean =
        FATAL_ACCOUNT_ERROR_CODES.any { eventText.contains("\"code\":\"$it\"") }

    // --- Event JSON composition (internal for unit-test visibility) ---

    internal fun buildSessionUpdateJson(): String {
        val format = """{"type":"audio/pcm","rate":$sampleRateHz}"""
        val session = """{"type":"realtime","output_modalities":["audio"],""" +
            """"instructions":${jsonString(sessionInstructions)},""" +
            """"audio":{"input":{"format":$format,"transcription":{"model":"whisper-1"},""" +
            """"turn_detection":{"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,""" +
            """"silence_duration_ms":10000,"create_response":false}},""" +
            """"output":{"format":$format,"voice":${jsonString(voice)}}}}"""
        return """{"type":"session.update","session":$session}"""
    }

    internal fun buildResponseCreateJson(instructions: String): String =
        if (instructions.isBlank()) {
            """{"type":"response.create"}"""
        } else {
            """{"type":"response.create","response":{"instructions":${jsonString(instructions)}}}"""
        }

    internal fun buildAudioAppendJson(base64Pcm16: String): String =
        """{"type":"input_audio_buffer.append","audio":${jsonString(base64Pcm16)}}"""
}

/** Minimal JSON string-literal escaper — mirrors `LumaJsonWriter`'s escaping (dependency-free). */
internal fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}
