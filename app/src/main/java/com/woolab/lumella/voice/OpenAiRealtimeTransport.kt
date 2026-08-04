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
        fun onTranscriptDelta(text: String) {}
        fun onError(message: String) {}

        /** The model asked the app to run a tool, e.g. capture_photo. */
        fun onToolCall(name: String, callId: String) {}

        /** Server VAD heard the learner start talking. */
        fun onSpeechStarted() {}

        /**
         * Server VAD heard the learner stop, which ends the turn. The server has already
         * committed the audio buffer by this point, so a listener must ask for a response
         * without committing again — committing twice leaves the server rejecting an empty
         * buffer, and the wearer sees an error for a turn that was actually fine.
         */
        fun onSpeechStopped() {}

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
                "brief Korean scaffold instead. " +
                // Without this the model does not know a camera exists. Asked what was in
                // front of the learner it invented a desk scene — a laptop, a coffee cup, a
                // notebook — and described it with complete confidence, having photographed
                // nothing (2026-08-05). Confident invention is worse than saying nothing.
                "The learner is wearing camera glasses and you can look through them. When " +
                "they ask you to look at something, ask what something is, or say anything " +
                "like \"이거 뭐야\", \"이거 봐봐\", \"사진 찍어서 알려줘\" — call the " +
                "capture_photo tool and talk about what you actually see. NEVER describe " +
                "their surroundings from imagination: if you have not captured a photo this " +
                "turn, say you will take a look and call the tool."

        /**
         * How long the server waits for silence before deciding the learner has finished.
         *
         * This used to sit at 10 seconds, which was not a tuning choice: turns were taken by
         * tapping, and the only job of that number was to keep VAD from cutting a tap-driven
         * turn in half. Hands-free makes VAD the thing that ends a turn, so it becomes a real
         * latency budget — the wearer waits this long after their last word before the tutor
         * starts thinking.
         *
         * 700ms is what the English app settled on in live use: short enough that the pause
         * does not read as being ignored, long enough to survive the gap between clauses.
         */
        internal const val VAD_SILENCE_DURATION_MS = 700

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
        /**
         * `event_id` stamped on the one `session.update` sent per socket open, so a server
         * `error` event that echoes it back can be recognized as "the persona/tools update
         * itself was rejected" rather than an unrelated turn-level error (review finding 5).
         */
        internal const val SESSION_UPDATE_EVENT_ID = "lumella_session_update"

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

    /**
     * True between `response.created` and `response.done`; guards against overlapping
     * responses. An `AtomicBoolean`, not `@Volatile`: the cancel-then-create pair this guards
     * ([sendInstructions], [requestResponseContinuation]) is reachable from two threads at
     * once — the websocket reader thread and the CameraX callback thread — and `@Volatile`
     * only gives visibility, not the check-then-act atomicity a "cancel exactly once" guard
     * needs. Two threads could both observe it true, both cancel, and both create; the server
     * accepts only one of the two response.creates and drops the other (review finding, HIGH —
     * this is the "tutor cuts itself off and restarts" symptom).
     */
    private val responseActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** `response.id` of the currently active response (set on `response.created`, stale once
     *  `response.done` clears [responseActive]). Used to tell a tool-call continuation racing an
     *  intervening VAD turn apart from a continuation answering the response that emitted the
     *  call — see [requestResponseContinuation]. */
    @Volatile
    private var activeResponseId: String? = null

    /** `response_id` captured from the `response.output_item.done` event that carried the most
     *  recently dispatched tool call. Compared against [activeResponseId] by
     *  [requestResponseContinuation] to decide whether the currently active response is the one
     *  that emitted the call (no cancel needed) or a different one (VAD race, cancel it). */
    @Volatile
    private var toolCallResponseId: String? = null

    /**
     * Which socket the outstanding tool call arrived on.
     *
     * Identity alone cannot answer "is this call still real". After a reconnect the ids are
     * gone, and treating unknown as "cancel to be safe" kills the live response that started
     * on the new socket — the very symptom identity tracking exists to prevent, reached by the
     * machinery meant to prevent it. A call from a dead socket has nothing left to answer, so
     * the answer is dropped rather than aimed at whatever is running now.
     */
    @Volatile
    private var toolCallGeneration = -1

    /** Serializes the cancel-then-create pair emitted by [sendInstructions] and
     *  [requestResponseContinuation] so the two calls can never interleave — see [responseActive]. */
    private val responseEmitLock = Any()

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
                    reportClose()
                }

                override fun onClosed(code: Int, reason: String) {
                    if (stale()) return
                    reportClose()
                }

                override fun onFailure(t: Throwable) {
                    if (stale()) return
                    sessionReady = false
                    // Same reason as reportClose(): whatever was mid-flight went down with
                    // the socket, and stale state makes the next turn cancel a ghost. The ids
                    // have to go too — a tool call that died with the old socket, answered
                    // after the reconnect, would otherwise compare against a genuinely new
                    // response, mismatch, and cancel it. That is the very symptom the
                    // identity tracking exists to prevent.
                    responseActive.set(false)
                    activeResponseId = null
                    toolCallResponseId = null
                    listener.onStatus(RealtimeConnectionStatus.DEGRADED)
                    listener.onError(t.message ?: t.javaClass.simpleName)
                    scheduleReconnect()
                }
            },
        )
    }

    /** D-4 ONLY channel: composes `response.create` with [instructions] and sends it. */
    override fun sendInstructions(instructions: String) {
        if (!emitCancelThenCreate(instructions) { true }) {
            listener.onError("sendInstructions failed: socket unavailable")
        }
    }

    /**
     * Cancels the currently active response (if [shouldCancel] says the one active right now is
     * not the one this call is continuing) and sends a `response.create` for [instructions], as
     * one indivisible pair under [responseEmitLock]. [sendInstructions] (tap, CameraX/UI thread)
     * and [requestResponseContinuation] (tool-call answer, websocket reader thread) both funnel
     * through here so the two can never interleave — without the lock, two threads can each
     * observe an active response, each cancel, and each create; the server accepts only the
     * second create and silently drops the first, which reaches the wearer as the tutor cutting
     * itself off mid-sentence and restarting (review finding, HIGH).
     */
    private fun emitCancelThenCreate(instructions: String, shouldCancel: (activeId: String?) -> Boolean): Boolean =
        synchronized(responseEmitLock) {
            if (shouldCancel(activeResponseId) && responseActive.getAndSet(false)) {
                sendRaw("""{"type":"response.cancel"}""")
            }
            // Claim the slot before sending. The lock alone keeps a cancel with its create,
            // but it does not stop two callers each emitting one — and the server accepts
            // exactly one, dropping the other with conversation_already_has_active_response.
            // Marking the response active here means the second caller sees it and cancels
            // first, which is the ordering the server actually requires. Now that the voice
            // and coach paths run on separate queues this is genuinely reachable: a tool-call
            // refusal answers on the websocket reader thread while a turn publishes on the
            // voice queue.
            val sent = sendRaw(buildResponseCreateJson(instructions))
            if (sent) {
                responseActive.set(true)
                // The id must go with the claim. Left in place it still names the response
                // just cancelled, so that response's own response.done matches it and clears
                // the flag this line just set — before the new response has even started. A
                // caller arriving in that window skips its cancel and loses its turn, which
                // is the failure claiming the slot exists to prevent.
                activeResponseId = null
            }
            sent
        }

    /**
     * Puts a captured photo into the conversation as USER input, so the realtime model can
     * actually see it (LEGACY ELLA's recipe: `conversation.item.create` + `input_image`).
     *
     * This is deliberately NOT on [com.woolab.lumella.voice.RealtimeTransport]: that interface
     * stays single-method so [com.woolab.lumella.voice.VoiceFastPath] structurally cannot push
     * brain/steering text to speech (D-4). An image is learner input, not brain output, so it
     * travels on its own channel and only MainActivity — which owns the camera — can send it.
     *
     * Without this the model never sees the photo and answers about something else entirely
     * (reported on-device 2026-07-28); the luma vision caption only reaches it as steering
     * text on a LATER turn.
     */
    fun sendUserImage(base64Jpeg: String): Boolean {
        if (toolCallIsFromADeadSocket()) {
            // Answering the call was already dropped; letting the photo through anyway drops
            // it into a conversation that never asked for one.
            listener.onError("sendUserImage dropped: tool call belongs to a closed session")
            return false
        }
        val sent = sendRaw(buildImageItemJson(base64Jpeg))
        if (sent) noteActivity() else listener.onError("sendUserImage failed: socket unavailable")
        return sent
    }

    /**
     * Puts typed text into the conversation as learner input. Used by the debug broadcast
     * that drives a turn with no wearer and no microphone, which is the only way to exercise
     * the live path from a laptop. Like [sendUserImage] this is learner input rather than
     * brain output, so it stays off the single-method transport interface that keeps steering
     * text on one channel.
     */
    fun sendUserText(text: String): Boolean {
        val sent = sendRaw(buildTextItemJson(text))
        if (sent) {
            noteActivity()
        } else {
            listener.onError("sendUserText failed: socket unavailable")
        }
        return sent
    }

    internal fun buildTextItemJson(text: String): String =
        """{"type":"conversation.item.create","item":{"type":"message","role":"user",""" +
            """"content":[{"type":"input_text","text":${jsonString(text)}}]}}"""

    /**
     * Answers a tool call. Like [sendUserImage] this is not brain output, so it stays off the
     * single-method transport interface that keeps steering text on one channel.
     */
    /** True when the outstanding tool call belongs to a socket that is already gone. */
    private fun toolCallIsFromADeadSocket(): Boolean =
        toolCallGeneration >= 0 && toolCallGeneration != socketGeneration.get()

    fun sendFunctionCallOutput(callId: String, output: String): Boolean {
        if (toolCallIsFromADeadSocket()) {
            // The conversation that asked no longer exists. Answering into the new one would
            // reference a call id it never issued.
            listener.onError("sendFunctionCallOutput dropped: tool call belongs to a closed session")
            return false
        }
        val sent = sendRaw(
            """{"type":"conversation.item.create","item":{"type":"function_call_output",""" +
                """"call_id":${jsonString(callId)},"output":${jsonString(output)}}}""",
        )
        if (sent) {
            noteActivity()
        } else {
            listener.onError("sendFunctionCallOutput failed: socket unavailable")
        }
        return sent
    }

    /**
     * Resumes a response after a tool call. The response that emitted the `function_call` item
     * is NOT necessarily done — the protocol order is `response.created` →
     * `response.output_item.done` (the tool call) → `response.done`, and the app now answers the
     * tool call inline, on the websocket reader thread, still inside that `output_item.done`
     * dispatch. `response.done` has not happened yet, so [responseActive] is still true and
     * [activeResponseId] is still THIS response's id.
     *
     * Cancelling by "is a response active" alone would therefore cancel the very response that
     * just emitted the call the app is answering — the following `response.create` then collides
     * with the cancel's aftermath and the server answers `conversation_already_has_active_response`,
     * exactly the failure this refusal path exists to prevent (review finding, HIGH). Cancel by
     * IDENTITY instead: only cancel when the response active right now differs from
     * [toolCallResponseId], the id captured off the `response.output_item.done` event that
     * carried this tool call — that is the case where server VAD opened a genuinely NEW turn
     * (`response.created`) during the 1-5s camera window before this fires (review finding 4).
     * Routed through [buildResponseCreateJson] with blank instructions so the emitted event
     * stays a BARE `response.create` regardless of which branch ran.
     */
    fun requestResponseContinuation(): Boolean {
        // Both ids must be known to claim "same response". Unknown means unknown: fall back
        // to cancelling, which is the behaviour before identity tracking and merely wasteful,
        // whereas guessing "same" would skip a cancel that was genuinely needed.
        if (toolCallIsFromADeadSocket()) {
            // Nothing to resume: the response that emitted the call died with its socket.
            // Cancelling here would kill whatever legitimately started on the new one.
            listener.onError("requestResponseContinuation dropped: tool call belongs to a closed session")
            return false
        }
        val sent = emitCancelThenCreate("") { activeId ->
            // Skip the cancel only when it can be PROVEN that the active response is the one
            // that emitted the call being answered — it is "active" merely because
            // response.done has not landed yet. Anything else, including an id the server did
            // not give us, gets cancelled: a wasted cancel is cheap, a skipped one drops the
            // reply. The dead-socket case that used to poison this is handled above, so the
            // conservative branch can no longer fire at a response from a later session.
            val provablySameResponse =
                activeId != null && toolCallResponseId != null && activeId == toolCallResponseId
            !provablySameResponse
        }
        if (!sent) {
            listener.onError("requestResponseContinuation failed: socket unavailable")
        }
        return sent
    }

    /** Resets the per-turn audio counter; call after reading it at commit time. */
    fun resetAppendedChunkCounter() {
        appendedChunksSinceCommit = 0
    }

    internal fun buildImageItemJson(base64Jpeg: String): String =
        """{"type":"conversation.item.create","item":{"type":"message","role":"user",""" +
            // Escaped rather than interpolated. Real base64 cannot contain a quote or a
            // backslash, so this is unreachable through the camera today — but an encoder
            // change or an upstream bug leaking raw bytes would desync the JSON exactly the
            // way a stray brace already did once, and the server answers that by discarding
            // the whole event while the socket stays open.
            """"content":[{"type":"input_image","image_url":${jsonString("data:image/jpeg;base64,$base64Jpeg")}}]}}"""

    /**
     * Appends a base64-encoded PCM16 audio chunk (see [com.woolab.lumella.audio.AudioCapture])
     * to the input buffer. Deliberately silent on a failed send unlike [sendUserText] /
     * [sendFunctionCallOutput] / [sendUserImage]: this fires ~20 times/second while the mic is
     * open, so routing every dropped chunk through `listener.onError` would flood the UI/logs
     * with one error per chunk for the whole outage instead of the ONE error the socket-level
     * `onFailure`/`onClosed` callback already reports for the same underlying disconnect.
     */
    fun appendAudio(base64Pcm16: String) {
        if (sendRaw(buildAudioAppendJson(base64Pcm16))) {
            appendedChunksSinceCommit++
            noteActivity()
        }
    }

    /**
     * Commits the input audio buffer, ending the learner's turn. Returns false if unsent.
     * Empty-commit guard (on-device finding 2026-07-23: taps without speech produced
     * `input_audio_buffer_commit_empty` server errors): a commit with no appended audio
     * since the last commit is skipped client-side.
     */
    /**
     * Commits the input buffer, but only when this turn actually captured audio — an empty
     * commit makes the server raise `input_audio_buffer_commit_empty`.
     *
     * [appendedChunksSinceCommit] is the single source of truth. A separate boolean used to
     * track "has audio" alongside the counter, and resetting only the counter left the two
     * disagreeing: turns logged `audioChunks=0 committed=true`, which is impossible and made
     * the log useless for telling a silent microphone from a bookkeeping bug.
     */
    fun commitAudio(): Boolean {
        if (appendedChunksSinceCommit <= 0) return false
        val sent = sendRaw("""{"type":"input_audio_buffer.commit"}""")
        if (sent) appendedChunksSinceCommit = 0
        return sent
    }

    /** Audio chunks appended since the last commit — lets callers spot a silent microphone. */
    @Volatile
    var appendedChunksSinceCommit: Int = 0
        private set

    /**
     * Reports a socket close. A client-initiated close (idle timeout, teardown, a fatal
     * account error) emits NO status: reconnect is suppressed for those, and CLOSED is
     * rendered as "Reconnecting..." — which would sit on screen claiming a recovery that is
     * never coming, overwriting the accurate "Idle - tap to wake" the idle path just showed.
     */
    private fun reportClose() {
        sessionReady = false
        // A response in flight when the socket dies never gets its response.done, so this
        // flag would stay set across the reconnect and every later turn would open by
        // cancelling a response that no longer exists. The server answers that with
        // response_cancel_not_active, which reaches the wearer as an error on a turn that
        // was perfectly fine — seen on-device 2026-08-05 after a ping timeout dropped the
        // socket mid-reply. The new connection carries nothing in flight.
        responseActive.set(false)
        // The ids belonged to a conversation that no longer exists; a new socket starts a new
        // one. Left behind, they would let a stale match skip a cancel that is needed.
        activeResponseId = null
        toolCallResponseId = null
        if (closedByClient) return
        listener.onStatus(RealtimeConnectionStatus.CLOSED)
        scheduleReconnect()
    }

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
            RealtimeServerEventKind.SESSION_CREATED -> {
                sessionReady = true
                reconnectDelayMs = RECONNECT_BASE_DELAY_MS
                listener.onStatus(RealtimeConnectionStatus.READY)
            }
            RealtimeServerEventKind.SESSION_UPDATED -> {
                // session.created (above) fires BEFORE the server has even looked at the
                // session.update we sent on open, so READY there means only "socket is up",
                // not "persona/tools/VAD actually landed". A rejected update (one stray brace
                // shipped this exact bug on 2026-08-05) leaves the session running on server
                // defaults while the UI still says "Ready" — additive, not a hard gate: this
                // can only ever demote an already-READY session, never block it.
                sessionReady = true
                reconnectDelayMs = RECONNECT_BASE_DELAY_MS
                listener.onStatus(RealtimeConnectionStatus.READY)
                val missing = missingConfirmedSessionFields(MiniJson.asObject(obj["session"]))
                if (missing.isNotEmpty()) {
                    listener.onStatus(RealtimeConnectionStatus.DEGRADED)
                    listener.onError(
                        "session.update confirmation incomplete: missing " +
                            "${missing.joinToString(", ")} — session may be running on server defaults",
                    )
                }
            }
            RealtimeServerEventKind.AUDIO_DELTA -> {
                MiniJson.string(obj, "delta")?.let(listener::onAudioDelta)
            }
            RealtimeServerEventKind.AUDIO_TRANSCRIPT_DELTA -> {
                MiniJson.string(obj, "delta")?.let(listener::onTranscriptDelta)
            }
            RealtimeServerEventKind.AUDIO_DONE -> listener.onAudioDone()
            RealtimeServerEventKind.INPUT_TRANSCRIPT_COMPLETED -> {
                MiniJson.string(obj, "transcript")?.let(listener::onInputTranscript)
            }
            RealtimeServerEventKind.RESPONSE_OUTPUT_ITEM_DONE -> {
                val item = MiniJson.asObject(obj["item"])
                if (item != null && MiniJson.string(item, "type") == "function_call") {
                    val name = MiniJson.string(item, "name")
                    val callId = MiniJson.string(item, "call_id")
                    // Which response this tool call belongs to, so a later
                    // requestResponseContinuation() can tell "I'm answering the response that
                    // just emitted this call" (no cancel needed — it's still in-flight only
                    // because response.done has not arrived yet) apart from "a NEW response
                    // started while I was thinking" (review finding, HIGH).
                    if (name != null && callId != null) {
                        // Recorded only when the call is actually dispatched. Set before the
                        // guard, a malformed item would overwrite the id of a call still
                        // being answered and turn a needed cancel into a skipped one.
                        toolCallResponseId = MiniJson.string(obj, "response_id")
                        toolCallGeneration = socketGeneration.get()
                        listener.onToolCall(name, callId)
                    }
                }
            }
            RealtimeServerEventKind.SPEECH_STARTED -> listener.onSpeechStarted()
            RealtimeServerEventKind.SPEECH_STOPPED -> listener.onSpeechStopped()
            RealtimeServerEventKind.RESPONSE_CREATED -> {
                responseActive.set(true)
                activeResponseId = MiniJson.string(MiniJson.asObject(obj["response"]), "id")
            }
            RealtimeServerEventKind.RESPONSE_DONE -> {
                // A response.done for an older response must not clear the flag for the one
                // currently running. Falls back to clearing when either id is unknown, so a
                // server that stops sending ids cannot wedge the transport permanently.
                val doneId = MiniJson.string(MiniJson.asObject(obj["response"]), "id")
                if (doneId == null || activeResponseId == null || doneId == activeResponseId) {
                    responseActive.set(false)
                    activeResponseId = null
                }
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
                if (MiniJson.string(MiniJson.asObject(obj["error"]), "event_id") == SESSION_UPDATE_EVENT_ID) {
                    // The server rejected the one session.update we sent on open — same
                    // "Ready but on server defaults" failure as an incomplete session.updated,
                    // just surfaced from the error side instead of the confirmation side.
                    listener.onStatus(RealtimeConnectionStatus.DEGRADED)
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

    /**
     * Checks a `session.updated` event's echoed `session` object for the fields the app
     * actually relies on. Missing/blank `instructions` or an empty/absent `tools` array means
     * the update the app sent was rejected or silently trimmed — the persona and the
     * capture_photo tool are both load-bearing, so either gap leaves the session on server
     * defaults with no visible symptom (review finding 5).
     *
     * The echo shape is the risky assumption here: a false positive is not benign, because
     * DEGRADED is emitted after READY and so wins on screen, nothing un-degrades it until the
     * next reconnect up to an hour later, and "Voice-only" is also what a dead brain looks
     * like. So it was checked rather than assumed — three fresh sessions against the live
     * server on 2026-08-05, zero demotions, i.e. session.updated really does echo both
     * instructions and tools. Re-check this after any Realtime API version bump; the GA
     * rename absorbed in RealtimeProtocol is precedent for the shape moving.
     */
    internal fun missingConfirmedSessionFields(session: Map<String, Any?>?): List<String> {
        if (session == null) return listOf("session")
        val missing = mutableListOf<String>()
        if (MiniJson.string(session, "instructions").isNullOrBlank()) missing.add("instructions")
        if (MiniJson.asArray(session["tools"]).isNullOrEmpty()) missing.add("tools")
        return missing
    }

    // --- Event JSON composition (internal for unit-test visibility) ---

    internal fun buildSessionUpdateJson(): String {
        val format = """{"type":"audio/pcm","rate":$sampleRateHz}"""
        val session = """{"type":"realtime","output_modalities":["audio"],""" +
            """"instructions":${jsonString(sessionInstructions)},""" +
            """"audio":{"input":{"format":$format,"transcription":{"model":"whisper-1"},""" +
            """"turn_detection":{"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,""" +
            """"silence_duration_ms":$VAD_SILENCE_DURATION_MS,"create_response":false}},""" +
            """"output":{"format":$format,"voice":${jsonString(voice)}}},""" +
            // Hands-free means the wearer's hands are busy, so "look at this" has to work
            // by voice too. The model asks for a photo; MainActivity owns the camera and
            // answers with function_call_output.
            """"tools":[{"type":"function","name":"capture_photo",""" +
            """"description":"Capture a photo through the glasses camera when the learner """ +
            """asks you to look at something in front of them.",""" +
            """"parameters":{"type":"object","properties":{},"required":[]}}],""" +
            """"tool_choice":"auto"}"""
        return """{"type":"session.update","event_id":${jsonString(SESSION_UPDATE_EVENT_ID)},"session":$session}"""
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
