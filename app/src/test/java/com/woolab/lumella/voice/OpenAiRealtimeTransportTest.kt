package com.woolab.lumella.voice

import com.woolab.lumella.TokenHttpResponse
import com.woolab.lumella.TokenServiceCredentialProvider
import com.woolab.lumella.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRealtimeTransportTest {

    private class FakeWebSocket : RealtimeWebSocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean {
            sent.add(text)
            return true
        }
        override fun close(code: Int, reason: String) {
            closed = true
        }
    }

    private class FakeFactory : RealtimeWebSocketFactory {
        var lastUrl: String? = null
        var lastHeaders: Map<String, String>? = null
        var lastListener: RealtimeWebSocketListener? = null
        val socket = FakeWebSocket()
        override fun connect(url: String, headers: Map<String, String>, listener: RealtimeWebSocketListener): RealtimeWebSocket {
            lastUrl = url
            lastHeaders = headers
            lastListener = listener
            return socket
        }
    }

    private class RecordingListener : OpenAiRealtimeTransport.Listener {
        val statuses = mutableListOf<RealtimeConnectionStatus>()
        val errors = mutableListOf<String>()
        var lastAudioDelta: String? = null
        var lastTranscript: String? = null
        var lastTranscriptDelta: String? = null
        val transcriptDeltas = mutableListOf<String>()
        override fun onStatus(status: RealtimeConnectionStatus) { statuses.add(status) }
        override fun onAudioDelta(base64Pcm16: String) { lastAudioDelta = base64Pcm16 }
        override fun onInputTranscript(text: String) { lastTranscript = text }
        override fun onTranscriptDelta(text: String) {
            lastTranscriptDelta = text
            transcriptDeltas.add(text)
        }
        override fun onError(message: String) { errors.add(message) }
        var speechStarted = 0
        var speechStopped = 0
        override fun onSpeechStarted() { speechStarted++ }
        override fun onSpeechStopped() { speechStopped++ }
        val toolCalls = mutableListOf<Pair<String, String>>()
        override fun onToolCall(name: String, callId: String) { toolCalls.add(name to callId) }
        var responseStarted = 0
        override fun onResponseStarted() { responseStarted++ }
    }

    private fun successProvider(token: String = "ek_test_token"): TokenServiceCredentialProvider =
        TokenServiceCredentialProvider(
            transport = { _, _, _, callback ->
                callback(Result.success(TokenHttpResponse(200, """{"token":"$token","expiresAt":${Long.MAX_VALUE / 2}}""")))
            },
            baseUrl = "http://localhost:8788",
            localToken = "shared-secret",
        )

    private fun failingProvider(): TokenServiceCredentialProvider =
        TokenServiceCredentialProvider(
            transport = { _, _, _, callback -> callback(Result.success(TokenHttpResponse(503, "{}"))) },
            baseUrl = "http://localhost:8788",
            localToken = "shared-secret",
        )

    // --- D-4: sendInstructions is the ONLY channel that reaches response.create.instructions ---

    @Test
    fun sendInstructionsComposesResponseCreateWithInstructions() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        transport.sendInstructions("steer the learner toward past tense")

        val last = factory.socket.sent.last()
        assertEquals(
            """{"type":"response.create","response":{"instructions":"steer the learner toward past tense"}}""",
            last,
        )
    }

    @Test
    fun sendInstructionsEscapesSpecialCharacters() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        transport.sendInstructions("say \"hello\"\nnext line")

        val last = factory.socket.sent.last()
        assertTrue(last.contains("\\\"hello\\\""))
        assertTrue(last.contains("\\n"))
    }

    @Test
    fun blankInstructionsOmitTheResponseField() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        transport.sendInstructions("   ")

        assertEquals("""{"type":"response.create"}""", factory.socket.sent.last())
    }

    // --- session.update composition (persona/audio config, sent once on open) ---

    @Test
    fun connectSendsSessionUpdateOnceOnOpen() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        assertEquals(1, factory.socket.sent.size)
        val sessionUpdate = factory.socket.sent.single()
        assertTrue(sessionUpdate.contains("\"type\":\"session.update\""))
        assertTrue(sessionUpdate.contains("\"rate\":24000"))
        assertTrue(sessionUpdate.contains("\"voice\":\"shimmer\""))
        assertTrue(sessionUpdate.contains("\"model\":\"whisper-1\""))
    }

    @Test
    fun connectUsesModelQueryParamAndBearerHeader() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(token = "ek_abc123"), factory, model = "gpt-realtime")
        transport.connect()

        assertEquals("wss://api.openai.com/v1/realtime?model=gpt-realtime", factory.lastUrl)
        assertEquals("Bearer ek_abc123", factory.lastHeaders?.get("Authorization"))
    }

    // --- audio append/commit framing ---

    @Test
    fun appendAudioFramesBase64Payload() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        transport.appendAudio("QUJD")

        assertEquals(
            """{"type":"input_audio_buffer.append","audio":"QUJD"}""",
            factory.socket.sent.last(),
        )
    }

    @Test
    fun commitAudioSendsCommitEventAndReturnsTrueWhenSent() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        transport.appendAudio("QUJD")
        assertTrue(transport.commitAudio())
        assertEquals("""{"type":"input_audio_buffer.commit"}""", factory.socket.sent.last())
    }

    @Test
    fun commitWithoutAppendedAudioIsSkippedClientSide() {
        // On-device finding 2026-07-23: empty taps produced input_audio_buffer_commit_empty.
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        val sentBefore = factory.socket.sent.size

        assertFalse(transport.commitAudio())                       // nothing appended -> no send
        assertEquals(sentBefore, factory.socket.sent.size)

        transport.appendAudio("QUJD")
        assertTrue(transport.commitAudio())                        // real audio -> commit
        assertFalse(transport.commitAudio())                       // guard resets after commit
    }

    @Test
    fun commitAudioReturnsFalseBeforeConnect() {
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory())

        assertFalse(transport.commitAudio())
    }

    // --- server event parsing ---

    @Test
    fun sessionCreatedEventMarksSessionReadyAndStatusReady() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"session.created"}""")

        assertTrue(transport.sessionReady)
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.READY))
    }

    @Test
    fun audioDeltaEventForwardsBase64ToListener() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.audio.delta","delta":"QUJD"}""")

        assertEquals("QUJD", listener.lastAudioDelta)
    }

    @Test
    fun gaAudioDeltaEventAliasAlsoForwardsToListener() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.output_audio.delta","delta":"WFla"}""")

        assertEquals("WFla", listener.lastAudioDelta)
    }

    @Test
    fun audioTranscriptDeltaEventForwardsTextToListener() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.audio_transcript.delta","delta":"hel"}""")

        assertEquals("hel", listener.lastTranscriptDelta)
    }

    @Test
    fun gaAudioTranscriptDeltaEventAliasAlsoForwardsToListener() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.output_audio_transcript.delta","delta":"lo"}""")

        assertEquals("lo", listener.lastTranscriptDelta)
    }

    @Test
    fun multipleAudioTranscriptDeltasAccumulateInOrder() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.audio_transcript.delta","delta":"안"}""")
        factory.lastListener?.onMessage("""{"type":"response.audio_transcript.delta","delta":"녕"}""")

        assertEquals(listOf("안", "녕"), listener.transcriptDeltas)
    }

    @Test
    fun inputTranscriptCompletedForwardsTranscriptToListener() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello there"}""",
        )

        assertEquals("hello there", listener.lastTranscript)
    }

    @Test
    fun malformedServerEventIsIgnoredWithoutThrowing() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("not json at all")

        assertFalse(transport.sessionReady)
    }

    // --- fail-closed token-service degrade paths ---

    @Test
    fun tokenFetchFailureDegradesToTokenFailWithoutOpeningSocket() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(failingProvider(), factory, listener = listener)

        transport.connect()

        assertNull(factory.lastListener)
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.CONNECTING))
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.TOKEN_FAIL))
        assertFalse(transport.sessionReady)
    }

    @Test
    fun standardOpenAiApiKeyIsRejectedNotOpened() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(token = "sk-should-be-rejected"), factory, listener = listener)

        transport.connect()

        assertNull(factory.lastListener)
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.TOKEN_FAIL))
    }

    @Test
    fun socketFailureReportsDegradedStatus() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()

        factory.lastListener?.onFailure(RuntimeException("connection reset"))

        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
        assertFalse(transport.sessionReady)
    }

    @Test
    fun sendInstructionsBeforeConnectReportsErrorInsteadOfThrowing() {
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory())
        val listener = RecordingListener()
        val transportWithListener = OpenAiRealtimeTransport(successProvider(), FakeFactory(), listener = listener)

        transportWithListener.sendInstructions("hello")

        assertTrue(listener.errors.any { it.contains("sendInstructions failed") })
        // also exercises the no-listener default path without throwing
        transport.sendInstructions("hello")
    }

    @Test
    fun closeClosesSocketAndClearsSessionReady() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"session.created"}""")
        assertTrue(transport.sessionReady)

        transport.close()

        assertTrue(factory.socket.closed)
        assertFalse(transport.sessionReady)
    }
    // --- Auto-reconnect: unexpected close (e.g. 60-min session_expired) must self-heal ---

    private class CountingFactory : RealtimeWebSocketFactory {
        var connectCount = 0
        var lastListener: RealtimeWebSocketListener? = null
        override fun connect(url: String, headers: Map<String, String>, listener: RealtimeWebSocketListener): RealtimeWebSocket {
            connectCount++
            lastListener = listener
            return object : RealtimeWebSocket {
                override fun send(text: String): Boolean = true
                override fun close(code: Int, reason: String) {}
            }
        }
    }

    @Test
    fun unexpectedServerCloseSchedulesReconnectWithFreshConnect() {
        val factory = CountingFactory()
        val scheduledDelays = mutableListOf<Long>()
        val pendingTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { delayMs, task ->
                scheduledDelays.add(delayMs)
                pendingTasks.add(task)
            },
        )
        transport.connect()
        assertEquals(1, factory.connectCount)

        // Server kills the session (session_expired path ends in onClosed).
        factory.lastListener?.onClosed(1000, "session_expired")

        assertEquals(listOf(OpenAiRealtimeTransport.RECONNECT_BASE_DELAY_MS), scheduledDelays)
        pendingTasks.removeAt(0).invoke()
        assertEquals(2, factory.connectCount)
    }

    @Test
    fun reconnectBackoffDoublesAndResetsOnReady() {
        val factory = CountingFactory()
        val scheduledDelays = mutableListOf<Long>()
        val pendingTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { delayMs, task ->
                scheduledDelays.add(delayMs)
                pendingTasks.add(task)
            },
        )
        transport.connect()

        factory.lastListener?.onClosed(1000, "expired")   // schedules @1s
        pendingTasks.removeAt(0).invoke()                  // reconnect #1
        factory.lastListener?.onClosed(1000, "expired")   // schedules @2s
        pendingTasks.removeAt(0).invoke()                  // reconnect #2
        assertEquals(listOf(1_000L, 2_000L), scheduledDelays)

        // READY resets the backoff to base.
        factory.lastListener?.onMessage("""{"type":"session.created"}""")
        factory.lastListener?.onClosed(1000, "expired")
        assertEquals(1_000L, scheduledDelays.last())
    }

    @Test
    fun clientCloseNeverReconnects() {
        val factory = CountingFactory()
        val scheduledDelays = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { delayMs, _ -> scheduledDelays.add(delayMs) },
        )
        transport.connect()
        transport.close()
        // okhttp will still deliver onClosed after a client-initiated close.
        factory.lastListener?.onClosed(1000, "client teardown")

        assertTrue(scheduledDelays.isEmpty())
        assertEquals(1, factory.connectCount)
    }

    @Test
    fun onlyOneReconnectPendingAtATime() {
        val factory = CountingFactory()
        val scheduledDelays = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { delayMs, _ -> scheduledDelays.add(delayMs) },
        )
        transport.connect()

        // onClosing + onClosed both fire for one server close — only one reconnect.
        factory.lastListener?.onClosing(1000, "expired")
        factory.lastListener?.onClosed(1000, "expired")

        assertEquals(1, scheduledDelays.size)
    }
    @Test
    fun supersededZombieSocketCallbacksAreIgnored() {
        // Regression for the on-device 2026-07-22 09:03:39 artifact: the expired
        // session's okhttp pinger timed out ~20s AFTER a successful reconnect and
        // caused a spurious DEGRADED flash + an extra reconnect cycle.
        val listeners = mutableListOf<RealtimeWebSocketListener>()
        val factory = object : RealtimeWebSocketFactory {
            override fun connect(url: String, headers: Map<String, String>, listener: RealtimeWebSocketListener): RealtimeWebSocket {
                listeners.add(listener)
                return object : RealtimeWebSocket {
                    override fun send(text: String): Boolean = true
                    override fun close(code: Int, reason: String) {}
                }
            }
        }
        val scheduledTasks = mutableListOf<() -> Unit>()
        val recording = RecordingListener()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = recording,
            reconnectScheduler = { _, task -> scheduledTasks.add(task) },
        )
        transport.connect()
        val oldListener = listeners[0]

        // Server expires the session -> reconnect fires -> generation 2 socket live.
        oldListener.onClosed(1000, "session_expired")
        scheduledTasks.removeAt(0).invoke()
        assertEquals(2, listeners.size)
        listeners[1].onMessage("""{"type":"session.created"}""")
        assertTrue(transport.sessionReady)
        val statusCountAfterReady = recording.statuses.size

        // Zombie pinger of the OLD socket fails late — must be fully ignored:
        oldListener.onFailure(RuntimeException("sent ping but didn't receive pong"))
        oldListener.onClosed(1006, "zombie close")

        assertTrue(transport.sessionReady)                       // session stays READY
        assertEquals(statusCountAfterReady, recording.statuses.size) // no status flash
        assertTrue(scheduledTasks.isEmpty())                     // no extra reconnect
    }

    // --- Idle timeout: unattended sessions must not be kept alive indefinitely (2026-07-26 cost risk) ---

    @Test
    fun idleTimeoutClosesSessionAndDoesNotScheduleReconnect() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val idleTasks = mutableListOf<() -> Unit>()
        val reconnectDelays = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            reconnectScheduler = { delayMs, _ -> reconnectDelays.add(delayMs) },
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"session.created"}""")
        assertTrue(transport.sessionReady)

        // Simulate the idle threshold elapsing (test invokes the captured task directly
        // instead of waiting on a real clock).
        idleTasks.last().invoke()

        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.IDLE))
        assertFalse(transport.sessionReady)
        assertTrue(factory.socket.closed)
        assertTrue(transport.isClosed)
        assertTrue(reconnectDelays.isEmpty())
    }

    @Test
    fun userActivityResetsIdleTimerInvalidatingStaleTask() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val idleTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect() // idle task #0 armed
        factory.lastListener?.onOpen()

        transport.appendAudio("QUJD") // user activity -> idle task #1 armed, #0 now stale

        assertEquals(2, idleTasks.size)
        idleTasks[0].invoke() // stale task fires late -> must be ignored
        assertFalse(listener.statuses.contains(RealtimeConnectionStatus.IDLE))
        assertFalse(factory.socket.closed)

        idleTasks[1].invoke() // the reset (latest) task fires -> closes for real
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.IDLE))
        assertTrue(factory.socket.closed)
    }

    @Test
    fun serverExpiredSessionStillAutoReconnectsWithIdleTimerArmed() {
        // Regression: the idle-timeout addition must not interfere with the existing
        // session_expired self-heal (D-4/W-1 steady state — see reconnect tests above).
        val factory = CountingFactory()
        val reconnectTasks = mutableListOf<() -> Unit>()
        val idleTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { _, task -> reconnectTasks.add(task) },
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect()
        assertEquals(1, factory.connectCount)

        factory.lastListener?.onClosed(1000, "session_expired")

        assertEquals(1, reconnectTasks.size)
        reconnectTasks.removeAt(0).invoke()
        assertEquals(2, factory.connectCount) // reconnected despite the idle timer being armed
    }

    // --- Fatal account errors must NOT trigger the reconnect loop ---

    @Test
    fun insufficientQuotaStopsReconnectingAndReportsAccountBlocked() {
        // On-device 2026-07-28: an out-of-credit account produced a
        // CONNECT -> error -> CLOSE cycle every ~4s forever.
        val factory = CountingFactory()
        val scheduled = mutableListOf<Long>()
        val recording = RecordingListener()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = recording,
            reconnectScheduler = { delayMs, _ -> scheduled.add(delayMs) },
        )
        transport.connect()

        factory.lastListener?.onMessage(
            """{"type":"error","error":{"type":"invalid_request_error","code":"insufficient_quota","message":"You exceeded your current quota"}}""",
        )
        // The server closes right after the error event.
        factory.lastListener?.onClosed(1000, "insufficient_quota")

        assertTrue(recording.statuses.contains(RealtimeConnectionStatus.ACCOUNT_BLOCKED))
        assertTrue("must not schedule a reconnect", scheduled.isEmpty())
        assertEquals(1, factory.connectCount)
    }

    @Test
    fun unknownServerErrorStillReconnects() {
        val factory = CountingFactory()
        val scheduled = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { delayMs, _ -> scheduled.add(delayMs) },
        )
        transport.connect()

        factory.lastListener?.onMessage("""{"type":"error","error":{"code":"some_transient_thing"}}""")
        factory.lastListener?.onClosed(1000, "transient")

        assertEquals(1, scheduled.size)
    }

    @Test
    fun fatalCodeDetectionCoversKnownAccountErrors() {
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory())
        assertTrue(transport.isFatalAccountError("""{"error":{"code":"insufficient_quota"}}"""))
        assertTrue(transport.isFatalAccountError("""{"error":{"code":"invalid_api_key"}}"""))
        assertFalse(transport.isFatalAccountError("""{"error":{"code":"server_error"}}"""))
    }

    // --- Image input channel (learner input, NOT brain output) ---

    @Test
    fun sendUserImageEmitsConversationItemWithInputImage() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        assertTrue(transport.sendUserImage("QUJDRA=="))
        val sent = factory.socket.sent.last()
        assertTrue(sent.contains("\"type\":\"conversation.item.create\""))
        assertTrue(sent.contains("\"role\":\"user\""))
        assertTrue(sent.contains("\"type\":\"input_image\""))
        assertTrue(sent.contains("data:image/jpeg;base64,QUJDRA=="))
    }

    @Test
    fun imageChannelIsNotReachableThroughTheRealtimeTransportInterface() {
        // D-4: VoiceFastPath sees only RealtimeTransport, which must stay single-method so
        // brain/steering text can never reach speech outside composed instructions. The image
        // channel lives on the concrete class, reachable only by MainActivity.
        val methods = RealtimeTransport::class.java.declaredMethods.filter { !it.isSynthetic }
        assertEquals(1, methods.size)
        assertEquals("sendInstructions", methods.first().name)
    }

    @Test
    fun clientInitiatedCloseEmitsNoMisleadingReconnectingStatus() {
        // Idle timeout / teardown / fatal account error all suppress reconnect, so surfacing
        // CLOSED (rendered as "Reconnecting...") would claim a recovery that never comes and
        // overwrite the accurate idle text.
        val factory = FakeFactory()
        val recording = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = recording)
        transport.connect()
        factory.lastListener?.onOpen()
        transport.close()
        val before = recording.statuses.size

        factory.lastListener?.onClosing(1000, "client teardown")
        factory.lastListener?.onClosed(1000, "client teardown")

        assertEquals(before, recording.statuses.size)
    }

    @Test
    fun serverInitiatedCloseStillReportsClosedAndReconnects() {
        val factory = FakeFactory()
        val recording = RecordingListener()
        val scheduled = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(), factory, listener = recording,
            reconnectScheduler = { d, _ -> scheduled.add(d) },
        )
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onClosed(1000, "session_expired")

        assertTrue(recording.statuses.contains(RealtimeConnectionStatus.CLOSED))
        assertEquals(1, scheduled.size)
    }

    // --- Hands-free: server VAD, not taps, decides where a turn ends ---

    @Test
    fun sessionAsksTheServerToDetectTurnsQuicklyEnoughToFeelHandsFree() {
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory())
        val session = transport.buildSessionUpdateJson()

        assertTrue("server VAD must be on", session.contains(""""type":"server_vad""""))
        assertTrue(
            "silence window is the wearer's wait before the tutor answers",
            session.contains(""""silence_duration_ms":700"""),
        )
        // Letting the server auto-reply would remove the one place per-turn teaching
        // instructions can be carried, so it stays off even hands-free.
        assertTrue("""create_response must stay false""", session.contains(""""create_response":false"""))
    }

    @Test
    fun speechEventsAreDeliveredSoTheAppCanCloseTheTurnItself() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"input_audio_buffer.speech_started"}""")
        factory.lastListener?.onMessage("""{"type":"input_audio_buffer.speech_stopped"}""")

        assertEquals(1, listener.speechStarted)
        assertEquals(1, listener.speechStopped)
    }

    @Test
    fun aDroppedSocketDoesNotLeaveTheNextTurnCancellingAGhostResponse() {
        // A reply was in flight when a ping timeout killed the socket, so response.done
        // never arrived. Without clearing the flag the next turn opened with a cancel and
        // the server answered response_cancel_not_active, which the wearer saw as an error.
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created"}""")

        factory.lastListener?.onFailure(RuntimeException("sent ping but didn't receive pong"))

        factory.socket.sent.clear()
        transport.sendInstructions("keep going")
        assertTrue(
            "must not cancel a response that died with the socket",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    @Test
    fun aClosedSocketClearsTheInFlightResponseToo() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created"}""")

        factory.lastListener?.onClosed(1006, "abnormal")

        factory.socket.sent.clear()
        transport.sendInstructions("keep going")
        assertTrue(factory.socket.sent.none { it.contains("response.cancel") })
    }

    // --- Voice-driven photo capture ---

    @Test
    fun theSessionOffersCapturePhotoSoTheWearerCanAskInsteadOfTapping() {
        val session = OpenAiRealtimeTransport(successProvider(), FakeFactory()).buildSessionUpdateJson()
        assertTrue(session.contains(""""name":"capture_photo""""))
        assertTrue(session.contains(""""tool_choice":"auto""""))
    }

    @Test
    fun aFinishedToolCallReachesTheApp() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"function_call",""" +
                """"name":"capture_photo","call_id":"call_abc"}}""",
        )

        assertEquals(listOf("capture_photo" to "call_abc"), listener.toolCalls)
    }

    @Test
    fun ordinaryOutputItemsAreNotMistakenForToolCalls() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant"}}""",
        )

        assertTrue(listener.toolCalls.isEmpty())
    }

    @Test
    fun answeringAToolCallCarriesTheCallIdAndResumesTheReply() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        transport.sendFunctionCallOutput("call_abc", """{"status":"ok"}""")
        transport.requestResponseContinuation()

        assertTrue(factory.socket.sent[0].contains(""""call_id":"call_abc""""))
        assertTrue(factory.socket.sent[0].contains("function_call_output"))
        // The response that emitted the call is already done; without this nothing resumes.
        assertEquals("""{"type":"response.create"}""", factory.socket.sent[1])
    }

    @Test
    fun theSessionUpdateIsValidJson() {
        // A malformed session.update is rejected wholesale and the socket stays open, so the
        // session silently keeps SERVER DEFAULTS: no persona, no tools, no VAD tuning. It
        // looks like the model ignoring instructions rather than a syntax error. That is
        // exactly what one stray brace did on 2026-08-05, and nothing here noticed.
        val json = OpenAiRealtimeTransport(successProvider(), FakeFactory()).buildSessionUpdateJson()
        val root = com.woolab.lumella.util.MiniJson.asObject(com.woolab.lumella.util.MiniJson.parse(json))
        assertTrue("session.update did not parse: $json", root != null)

        val session = com.woolab.lumella.util.MiniJson.asObject(root!!["session"])
        assertTrue("session object missing", session != null)
        // Everything the app relies on has to survive the round trip, not just parse.
        assertTrue("instructions lost", com.woolab.lumella.util.MiniJson.string(session!!, "instructions") != null)
        assertTrue("tools lost", session["tools"] != null)
        assertEquals("auto", com.woolab.lumella.util.MiniJson.string(session, "tool_choice"))
        assertTrue("audio config lost", com.woolab.lumella.util.MiniJson.asObject(session["audio"]) != null)
    }
    // --- Red-team: hostile tool-call round trip and session payload (ultragoal QA) ---

    @Test
    fun hostileToolCallItemsNeverReachOnToolCallOrCrash() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        // missing call_id
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"function_call","name":"capture_photo"}}""",
        )
        // missing name
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"function_call","call_id":"call_x"}}""",
        )
        // item absent entirely
        factory.lastListener?.onMessage("""{"type":"response.output_item.done"}""")
        // item is a string instead of an object
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":"not-an-object"}""",
        )
        // type is something other than function_call
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"reasoning","name":"capture_photo","call_id":"call_y"}}""",
        )

        assertTrue("none of these may reach onToolCall", listener.toolCalls.isEmpty())
    }

    @Test
    fun functionCallOutputSurvivesHostileCallIdAndOutputThroughMiniJson() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        val hostileCallId = "call_\"'\\\n\t 한글 \uD83D\uDE00"
        val hostileOutput = "{\"nested\":\"json\"}\nwith\\backslash and \"quotes\" 한국어 결과"

        transport.sendFunctionCallOutput(hostileCallId, hostileOutput)

        val sent = factory.socket.sent.single()
        val root = MiniJson.asObject(MiniJson.parse(sent))
        assertTrue("payload must parse as valid JSON: $sent", root != null)
        val item = MiniJson.asObject(root!!["item"])
        assertEquals("function_call_output", MiniJson.string(item, "type"))
        assertEquals(hostileCallId, MiniJson.string(item, "call_id"))
        assertEquals(hostileOutput, MiniJson.string(item, "output"))
    }

    @Test
    fun sessionUpdateSurvivesHostilePersonaInstructionsByteIdentically() {
        // Guard for the stray-brace class of bug: a hostile persona MUST NOT be able to
        // desync the JSON, and every field the app relies on must survive the round trip.
        val hostileInstructions = "Line1\nLine2\ttab \"quoted\" \\backslash\\ 한국어 대화 조사 을/를 \u0001\u001f 끝"
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            FakeFactory(),
            sessionInstructions = hostileInstructions,
        )

        val json = transport.buildSessionUpdateJson()
        val root = MiniJson.asObject(MiniJson.parse(json))
        assertTrue("session.update did not parse: $json", root != null)
        val session = MiniJson.asObject(root!!["session"])
        assertTrue("session object missing", session != null)

        assertEquals(hostileInstructions, MiniJson.string(session, "instructions"))

        val tools = MiniJson.asArray(session!!["tools"])
        assertTrue("tools missing", tools != null && tools.isNotEmpty())
        val tool = MiniJson.asObject(tools!!.first())
        assertEquals("capture_photo", MiniJson.string(tool, "name"))
        assertEquals("auto", MiniJson.string(session, "tool_choice"))

        val audio = MiniJson.asObject(session["audio"])
        val input = MiniJson.asObject(audio?.get("input"))
        val turnDetection = MiniJson.asObject(input?.get("turn_detection"))
        assertEquals(false, turnDetection?.get("create_response"))
        assertEquals(700.0, turnDetection?.get("silence_duration_ms"))
    }

    @Test
    fun toolCallAnswerOrderingAndBareContinuation() {
        // Answering a tool call for the response that JUST emitted it must NOT cancel that
        // response — response.done has not arrived yet, so it is still "active" only because
        // the app answered inline, on the reader thread, before response.done showed up
        // (review finding, HIGH: cancelling here used to cancel the very response being
        // answered, and the model's next response.create was rejected with
        // conversation_already_has_active_response).
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_abc"}}""",
        )

        transport.sendFunctionCallOutput("call_abc", "ok")
        transport.requestResponseContinuation()

        assertEquals(2, factory.socket.sent.size)
        val first = MiniJson.asObject(MiniJson.parse(factory.socket.sent[0]))
        assertEquals("conversation.item.create", MiniJson.string(first, "type"))
        val item = MiniJson.asObject(first?.get("item"))
        assertEquals("function_call_output", MiniJson.string(item, "type"))

        val second = MiniJson.asObject(MiniJson.parse(factory.socket.sent[1]))
        assertEquals("response.create", MiniJson.string(second, "type"))
        assertTrue(
            "continuation must be bare — the response it is continuing has no fresh steering",
            second != null && !second.containsKey("response"),
        )
        assertTrue(
            "answering the response that emitted the call must not cancel it",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    @Test
    fun toolCallContinuationCancelsADifferentResponseThanTheOneThatEmittedTheCall() {
        // Server VAD opened a genuinely NEW response while the tool-call answer was still
        // pending (the 1-5s camera window). That new response IS a different one from the one
        // the continuation is answering, so it must be cancelled first — unlike the "same
        // response" case above.
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_abc"}}""",
        )
        // A second, unrelated response starts before the photo answer is ready.
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_2"}}""")
        factory.socket.sent.clear()

        val sent = transport.requestResponseContinuation()

        assertTrue(sent)
        assertEquals(listOf("""{"type":"response.cancel"}"""), factory.socket.sent.dropLast(1))
        val last = MiniJson.asObject(MiniJson.parse(factory.socket.sent.last()))
        assertEquals("response.create", MiniJson.string(last, "type"))
        assertTrue(
            "continuation must stay a bare response.create even when it cancelled first",
            last != null && !last.containsKey("response"),
        )
    }

    @Test
    fun sendUserImageWithHugeBase64ProducesParseableUserMessage() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        val hugeBase64 = "A".repeat(500_000)
        assertTrue(transport.sendUserImage(hugeBase64))

        val root = MiniJson.asObject(MiniJson.parse(factory.socket.sent.last()))
        assertTrue("huge image payload must still parse", root != null)
        val item = MiniJson.asObject(root!!["item"])
        assertEquals("user", MiniJson.string(item, "role"))
        val content = MiniJson.asArray(item?.get("content"))
        val part = MiniJson.asObject(content?.firstOrNull())
        assertEquals("input_image", MiniJson.string(part, "type"))
        assertTrue(MiniJson.string(part, "image_url")?.endsWith(hugeBase64) == true)
    }

    @Test
    fun sendUserTextWithHostileKoreanProducesParseableUserMessage() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        val hostileText = "\"이거 뭐야\"\n줄바꿈과 \\backslash\\ 포함, 탭\t끝"
        assertTrue(transport.sendUserText(hostileText))

        val root = MiniJson.asObject(MiniJson.parse(factory.socket.sent.last()))
        assertTrue(root != null)
        val item = MiniJson.asObject(root!!["item"])
        assertEquals("user", MiniJson.string(item, "role"))
        val content = MiniJson.asArray(item?.get("content"))
        val part = MiniJson.asObject(content?.firstOrNull())
        assertEquals(hostileText, MiniJson.string(part, "text"))
    }

    @Test
    fun toolCallAfterSocketLossDoesNotResurrectStaleResponseActiveCancel() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created"}""")

        factory.lastListener?.onFailure(RuntimeException("dropped mid-response"))

        // A racing tool-call event still lands after the failure callback.
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","item":{"type":"function_call",""" +
                """"name":"capture_photo","call_id":"call_late"}}""",
        )
        assertEquals(listOf("capture_photo" to "call_late"), listener.toolCalls)

        factory.socket.sent.clear()
        transport.sendInstructions("continue")

        assertTrue(
            "a tool call landing after socket loss must not resurrect a stale response.cancel",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    @Test
    fun aCorruptImagePayloadCannotDesyncTheEventJson() {
        // Not reachable through the camera — base64 has no quotes — but the failure mode is
        // the one that already cost a session: malformed JSON is discarded server-side while
        // the socket stays open, so nothing looks wrong locally.
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory())
        val json = transport.buildImageItemJson("""abc"injected\\and\nnewline""")

        val root = com.woolab.lumella.util.MiniJson.asObject(com.woolab.lumella.util.MiniJson.parse(json))
        assertTrue("image item did not parse: $json", root != null)
    }

    // --- Continuation in-flight guard (review finding 4): server VAD can open a new turn
    // during the camera window before the tool-call continuation fires. ---

    @Test
    fun requestResponseContinuationCancelsAnActiveResponseFirstAndStaysBare() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        // Server VAD opened a new turn while the photo answer was still pending — no tool
        // call has been dispatched on this transport at all, so it differs by identity from
        // whatever (nonexistent) response the continuation would be answering.
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_vad"}}""")
        factory.socket.sent.clear()

        val sent = transport.requestResponseContinuation()

        assertTrue(sent)
        assertEquals(listOf("""{"type":"response.cancel"}"""), factory.socket.sent.dropLast(1))
        val last = MiniJson.asObject(MiniJson.parse(factory.socket.sent.last()))
        assertEquals("response.create", MiniJson.string(last, "type"))
        assertTrue(
            "continuation must stay a bare response.create even when it cancelled first",
            last != null && !last.containsKey("response"),
        )
    }

    @Test
    fun requestResponseContinuationWithNoActiveResponseSkipsCancel() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        assertTrue(transport.requestResponseContinuation())

        assertEquals("""{"type":"response.create"}""", factory.socket.sent.single())
    }

    @Test
    fun requestResponseContinuationFailureReportsErrorInsteadOfDiscardingTheBoolean() {
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory(), listener = listener)

        val sent = transport.requestResponseContinuation()

        assertFalse(sent)
        assertTrue(listener.errors.any { it.contains("requestResponseContinuation failed") })
    }

    @Test
    fun sendUserTextFailureReportsErrorInsteadOfDiscardingTheBoolean() {
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), FakeFactory(), listener = listener)

        // No connect()/onOpen(): the socket is null, so sendRaw fails closed.
        val sent = transport.sendUserText("hello")

        assertFalse(sent)
        assertTrue(listener.errors.any { it.contains("sendUserText failed") })
    }

    @Test
    fun racingThreadsThroughTheEmitterProduceAtMostOneCancelPerActiveResponse() {
        // sendInstructions (e.g. a tap, CameraX-thread-adjacent) and requestResponseContinuation
        // (websocket reader thread answering a tool call) can now both run concurrently. Without
        // routing cancel-then-create through one lock, both threads can observe the same active
        // response, both cancel it, and the server accepts only one of the two response.creates
        // that follow — dropping the other (review finding, HIGH). A CountDownLatch forces both
        // threads to arrive at the emitter at the same instant instead of hoping a race shows up.
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.socket.sent.clear()

        val ready = java.util.concurrent.CountDownLatch(2)
        val go = java.util.concurrent.CountDownLatch(1)
        val threads = listOf(
            Thread {
                ready.countDown()
                go.await()
                transport.sendInstructions("steer")
            },
            Thread {
                ready.countDown()
                go.await()
                transport.requestResponseContinuation()
            },
        )
        threads.forEach { it.isDaemon = true; it.start() }
        ready.await()
        go.countDown()
        threads.forEach { it.join(5_000) }

        val cancels = factory.socket.sent.count { it == """{"type":"response.cancel"}""" }
        val creates = factory.socket.sent.count { MiniJson.string(MiniJson.asObject(MiniJson.parse(it)), "type") == "response.create" }
        // Each create supersedes the one before it, so N callers produce N creates and N-1
        // cancels. That IS the protocol: without the cancel the server rejects the newcomer
        // with conversation_already_has_active_response and drops it silently. What must
        // never happen is a cancel stranded without the create it was clearing the way for.
        val ordered = factory.socket.sent.filter {
            it == """{"type":"response.cancel"}""" ||
                MiniJson.string(MiniJson.asObject(MiniJson.parse(it)), "type") == "response.create"
        }
        ordered.forEachIndexed { i, e ->
            if (e == """{"type":"response.cancel"}""") {
                assertTrue(
                    "a cancel at index $i with no create after it strands the turn",
                    ordered.getOrNull(i + 1)?.contains("response.create") == true,
                )
            }
        }
        assertTrue("every cancel is paired with a create, so it can never outnumber them", cancels <= creates)
        assertEquals("both callers must still each get their response.create", 2, creates)
    }

    @Test
    fun sendFunctionCallOutputCountsAsActivityAndReportsSendFailure() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val idleTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect() // idle task #0 armed by connect()/noteActivity()
        factory.lastListener?.onOpen()

        transport.sendFunctionCallOutput("call_abc", "ok")

        // A tool round trip is activity: a fresh idle task must have been armed on top of
        // connect()'s, invalidating the earlier one.
        assertTrue(idleTasks.size >= 2)

        val failingListener = RecordingListener()
        val disconnected = OpenAiRealtimeTransport(successProvider(), FakeFactory(), listener = failingListener)
        val sent = disconnected.sendFunctionCallOutput("call_abc", "ok")

        assertFalse(sent)
        assertTrue(failingListener.errors.any { it.contains("sendFunctionCallOutput failed") })
    }

    // --- session.updated confirmation (review finding 5): session.created fires BEFORE the
    // server has processed session.update, so a rejected update must not stay invisible. ---

    @Test
    fun sessionUpdatedEchoingInstructionsAndToolsKeepsTheSessionHealthy() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"session.updated","session":{"instructions":"be a tutor",""" +
                """"tools":[{"type":"function","name":"capture_photo"}]}}""",
        )

        assertTrue(transport.sessionReady)
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.READY))
        assertFalse(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun sessionUpdatedMissingInstructionsAndToolsReportsDegradedNamingWhatsMissing() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        // The stray-brace class of bug: the server accepted the socket message but the
        // echoed session carries no persona and no tools.
        factory.lastListener?.onMessage(
            """{"type":"session.updated","session":{"instructions":"","tools":[]}}""",
        )

        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
        assertTrue(
            "error must name what's missing",
            listener.errors.any { it.contains("instructions") && it.contains("tools") },
        )
        // Additive, not a hard gate: the socket/session are not torn down over this.
        assertTrue(transport.sessionReady)
    }

    @Test
    fun sessionUpdatedMissingOnlyToolsNamesOnlyTools() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"session.updated","session":{"instructions":"be a tutor","tools":[]}}""",
        )

        val message = listener.errors.single { it.contains("session.update confirmation incomplete") }
        assertTrue(message.contains("tools"))
        assertFalse(message.contains("instructions"))
    }

    @Test
    fun sessionCreatedOnlyStillReachesReadyWithoutAnySessionUpdatedTests() {
        // Regression: several existing tests drive session.created only, never session.updated
        // — the new confirmation check must not turn those into DEGRADED/stalled sessions.
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"session.created"}""")

        assertTrue(transport.sessionReady)
        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.READY))
        assertFalse(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun errorEventEchoingTheSessionUpdateEventIdReportsDegraded() {
        // The server can also reject the session.update by answering an `error` event whose
        // inner event_id names the client event that failed, rather than by omitting fields
        // from session.updated.
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()
        val sentSessionUpdate = MiniJson.asObject(MiniJson.parse(factory.socket.sent.single()))
        val eventId = MiniJson.string(sentSessionUpdate, "event_id")
        assertTrue(eventId != null)

        factory.lastListener?.onMessage(
            """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_session_config",""" +
                """"message":"bad session.update","event_id":"$eventId"}}""",
        )

        assertTrue(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
    }

    @Test
    fun errorEventForAnUnrelatedEventIdDoesNotReportDegraded() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage(
            """{"type":"error","error":{"code":"some_transient_thing","event_id":"unrelated_evt"}}""",
        )

        assertFalse(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
    }

    // --- Response identity survives hostile and lossy servers ---

    @Test
    fun aMalformedToolCallItemCannotStealTheIdOfOneStillBeingAnswered() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )
        // A second item with no call_id: not dispatched, so it must not touch the id either.
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_9",""" +
                """"item":{"type":"function_call","name":"capture_photo"}}""",
        )

        factory.socket.sent.clear()
        transport.requestResponseContinuation()
        assertTrue(
            "answering resp_1 must not cancel resp_1",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    @Test
    fun anUnknownIdFallsBackToCancellingRatherThanGuessing() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        // A response with no id at all, and no tool call ever dispatched.
        factory.lastListener?.onMessage("""{"type":"response.created","response":{}}""")

        factory.socket.sent.clear()
        transport.requestResponseContinuation()
        assertTrue(
            "an unprovable match must cancel rather than assume it is the same response",
            factory.socket.sent.any { it.contains("response.cancel") },
        )
    }

    @Test
    fun aDoneForAnOlderResponseDoesNotClearTheCurrentOne() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_2"}}""")
        factory.lastListener?.onMessage("""{"type":"response.done","response":{"id":"resp_1"}}""")

        factory.socket.sent.clear()
        transport.sendInstructions("next turn")
        assertTrue(
            "resp_2 is still running and must be cancelled first",
            factory.socket.sent.any { it.contains("response.cancel") },
        )
    }

    @Test
    fun aDroppedSocketForgetsWhichResponseWasRunning() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )

        factory.lastListener?.onClosed(1006, "abnormal")

        factory.socket.sent.clear()
        transport.sendInstructions("after reconnect")
        assertTrue(
            "nothing was in flight; a stale id must not produce a cancel",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    // --- Red-team: reconnect storms ---

    @Test
    fun aToolCallThatDiedWithItsSocketCannotCancelAResponseOnTheNewOne() {
        // The reason identity tracking exists, reached through the machinery meant to prevent
        // it: a ping timeout kills the socket mid-capture, the camera finishes after the
        // reconnect, and the app answers a call from a conversation that no longer exists.
        // Comparing ids cannot help — they are gone — so "unknown, cancel to be safe" killed
        // whatever legitimately started on the new socket.
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            reconnectScheduler = { _, task -> task() },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )

        factory.lastListener?.onFailure(RuntimeException("sent ping but didn't receive pong"))
        factory.lastListener?.onOpen()
        // A genuinely new, unrelated reply is under way on the fresh socket.
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_2"}}""")

        factory.socket.sent.clear()
        val answered = transport.sendFunctionCallOutput("call_1", """{"status":"ok"}""")
        val resumed = transport.requestResponseContinuation()

        assertFalse("the call belongs to a session that is gone", answered)
        assertFalse("there is nothing to resume", resumed)
        assertTrue(
            "the live reply on the new socket must survive",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
        assertTrue(listener.errors.any { it.contains("closed session") })
    }

    @Test
    fun aStaleToolCallIsDroppedEvenWhenTheNewSocketIsIdle() {
        // Nothing is running on the new socket, so a cancel would be harmless — but the
        // function_call_output would still reference a call id this conversation never issued.
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            reconnectScheduler = { _, task -> task() },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )

        factory.lastListener?.onFailure(RuntimeException("socket died"))
        factory.lastListener?.onOpen()

        factory.socket.sent.clear()
        assertFalse(transport.sendFunctionCallOutput("call_1", """{"status":"ok"}"""))
        assertTrue(factory.socket.sent.none { it.contains("function_call_output") })
    }

    @Test
    fun reconnectStormOpenCreatedToolCallFailureReconnectOpenAnswerNeverDuplicatesTheSessionUpdate() {
        val factory = FakeFactory()
        val reconnectTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { _, task -> reconnectTasks.add(task) },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"session.created"}""")
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")

        factory.lastListener?.onFailure(RuntimeException("first drop"))
        reconnectTasks.removeAt(0).invoke()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"session.created"}""")

        // A second storm on the SAME fresh socket, before anything answers the first one.
        factory.lastListener?.onFailure(RuntimeException("second drop"))
        reconnectTasks.removeAt(0).invoke()
        factory.lastListener?.onOpen()

        val sessionUpdates = factory.socket.sent.count { it.contains("\"type\":\"session.update\"") }
        assertEquals("one session.update per socket open, never batched or skipped", 3, sessionUpdates)
        assertTrue(transport.isClosed.not())
    }

    // --- Red-team: out-of-order / duplicate server events ---

    @Test
    fun duplicateResponseCreatedEventsWithTheSameIdDoNotDesyncTheGuard() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")

        factory.socket.sent.clear()
        transport.sendInstructions("next")
        assertTrue(
            "a response is active (even duplicated) and must be cancelled first",
            factory.socket.sent.any { it.contains("response.cancel") },
        )

        // One response.done for that id is enough to clear it, even though created fired twice.
        factory.lastListener?.onMessage("""{"type":"response.done","response":{"id":"resp_1"}}""")
        factory.socket.sent.clear()
        transport.sendInstructions("after done")
        assertTrue(
            "response.done cleared the flag; nothing left to cancel",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
    }

    @Test
    fun responseDoneBeforeResponseCreatedDoesNotWedgeOrThrow() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()

        // A response.done with no prior response.created at all.
        factory.lastListener?.onMessage("""{"type":"response.done","response":{"id":"resp_1"}}""")

        factory.socket.sent.clear()
        transport.sendInstructions("turn")
        assertTrue(
            "no response was ever active; nothing to cancel",
            factory.socket.sent.none { it.contains("response.cancel") },
        )

        // The real response.created for a NEW response arrives; tracking must still work.
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_2"}}""")
        factory.socket.sent.clear()
        transport.sendInstructions("interrupt")
        assertTrue(
            "resp_2 is genuinely active and must be cancelled",
            factory.socket.sent.any { it.contains("response.cancel") },
        )
    }

    @Test
    fun toolCallResponseIdNamingAResponseThatNeverStartedIsRecordedButCannotForceACancel() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        // No response.created at all -- response_id names a response the transport never saw.
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_ghost",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_g"}}""",
        )
        assertEquals(listOf("capture_photo" to "call_g"), listener.toolCalls)

        factory.socket.sent.clear()
        val sent = transport.requestResponseContinuation()
        assertTrue(sent)
        // activeResponseId is null (no response.created ever happened), so responseActive is
        // false and the cancel-guard's getAndSet(false) makes any cancel a no-op regardless
        // of the identity comparison.
        assertTrue(
            "nothing was ever active; a dangling response_id cannot manufacture a cancel",
            factory.socket.sent.none { it.contains("response.cancel") },
        )
        assertEquals("""{"type":"response.create"}""", factory.socket.sent.single())
    }

    @Test
    fun sessionUpdatedArrivingTwiceIsIdempotentAndStaysReady() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        val healthy = """{"type":"session.updated","session":{"instructions":"be a tutor",""" +
            """"tools":[{"type":"function","name":"capture_photo"}]}}"""
        factory.lastListener?.onMessage(healthy)
        factory.lastListener?.onMessage(healthy)

        assertTrue(transport.sessionReady)
        assertFalse(listener.statuses.contains(RealtimeConnectionStatus.DEGRADED))
        assertTrue(listener.errors.isEmpty())
    }

    @Test
    fun aHealthySessionUpdatedAfterAnUnrelatedErrorStillReachesReady() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"error","error":{"code":"some_transient_thing"}}""")
        factory.lastListener?.onMessage(
            """{"type":"session.updated","session":{"instructions":"be a tutor",""" +
                """"tools":[{"type":"function","name":"capture_photo"}]}}""",
        )

        assertEquals(RealtimeConnectionStatus.READY, listener.statuses.last())
        assertTrue(transport.sessionReady)
    }

    // --- Red-team: concurrency through the emitter with server events interleaving ---

    @Test
    fun manyConcurrentSendInstructionsCallsEachProduceExactlyOneCreateAndAtMostOneCancel() {
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.socket.sent.clear()

        val threadCount = 8
        val ready = java.util.concurrent.CountDownLatch(threadCount)
        val go = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until threadCount).map { i ->
            Thread {
                ready.countDown()
                go.await()
                transport.sendInstructions("turn $i")
            }
        }
        threads.forEach { it.isDaemon = true; it.start() }
        ready.await()
        go.countDown()
        threads.forEach { it.join(5_000) }

        val creates = factory.socket.sent.count {
            MiniJson.string(MiniJson.asObject(MiniJson.parse(it)), "type") == "response.create"
        }
        val cancels = factory.socket.sent.count { it == """{"type":"response.cancel"}""" }
        assertEquals("every caller gets its own response.create", threadCount, creates)
        // Each create supersedes the one before it, so N callers produce N creates and N-1
        // cancels. That IS the protocol: without the cancel the server rejects the newcomer
        // with conversation_already_has_active_response and drops it silently. What must
        // never happen is a cancel stranded without the create it was clearing the way for.
        val ordered = factory.socket.sent.filter {
            it == """{"type":"response.cancel"}""" ||
                MiniJson.string(MiniJson.asObject(MiniJson.parse(it)), "type") == "response.create"
        }
        ordered.forEachIndexed { i, e ->
            if (e == """{"type":"response.cancel"}""") {
                assertTrue(
                    "a cancel at index $i with no create after it strands the turn",
                    ordered.getOrNull(i + 1)?.contains("response.create") == true,
                )
            }
        }
        assertTrue("every cancel is paired with a create, so it can never outnumber them", cancels <= creates)
    }

    @Test
    fun sendInstructionsRacingServerLifecycleEventsNeverProducesACancelSeparatedFromItsCreate() {
        // A thread hammering sendInstructions (taps) races the websocket reader thread
        // delivering response.created/response.done for a DIFFERENT, unrelated response --
        // the invariant is that every cancel this test observes is immediately followed by
        // a create in the same emitted pair (they are emitted together, under one lock, so
        // no observer can see one without the other appearing right after it in the log).
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        val iterations = 200
        val emitter = Thread {
            repeat(iterations) { i -> transport.sendInstructions("turn $i") }
        }
        val serverEvents = Thread {
            repeat(iterations) { i ->
                factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_$i"}}""")
                factory.lastListener?.onMessage("""{"type":"response.done","response":{"id":"resp_$i"}}""")
            }
        }
        emitter.isDaemon = true; serverEvents.isDaemon = true
        emitter.start(); serverEvents.start()
        emitter.join(10_000); serverEvents.join(10_000)

        // Walk the sent log: every response.cancel must be immediately followed by a
        // response.create (the two are emitted as one pair under responseEmitLock; nothing
        // else runs sendRaw between them).
        val sent = factory.socket.sent
        for (i in sent.indices) {
            if (sent[i] == """{"type":"response.cancel"}""") {
                assertTrue(
                    "a cancel at index $i must be immediately followed by its paired create",
                    i + 1 < sent.size &&
                        MiniJson.string(MiniJson.asObject(MiniJson.parse(sent[i + 1])), "type") == "response.create",
                )
            }
        }
    }

    // --- Red-team: idle timeout racing an in-flight tool call (the camera window) ---

    @Test
    fun anIdleTimeoutDuringTheCameraWindowClosesTheSocketAndLeavesTheToolCallUnanswerable() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val idleTasks = mutableListOf<() -> Unit>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )
        // Nobody touched anything through the whole camera-bind/photo/upload window -- the
        // idle timer armed on connect() (nothing mid-capture resets it) fires before the
        // photo is even ready, closing the socket exactly like a client teardown.
        idleTasks.last().invoke()
        assertTrue(transport.isClosed)
        listener.errors.clear()

        // The camera finishes AFTER the socket is gone and the app still tries to answer.
        val imageSent = transport.sendUserImage("QUJD")
        val outputSent = transport.sendFunctionCallOutput("call_1", """{"status":"ok"}""")
        val continuationSent = transport.requestResponseContinuation()

        assertFalse("the model is gone; the photo cannot be delivered", imageSent)
        assertFalse("the model is gone; the tool call cannot be answered", outputSent)
        assertFalse("the model is gone; nothing can resume", continuationSent)
        // The app DOES notice for the function-call-output/continuation half of the answer --
        // both report onError, matching sendInstructions/sendUserText's pattern.
        assertTrue(listener.errors.any { it.contains("sendFunctionCallOutput failed") })
        assertTrue(listener.errors.any { it.contains("requestResponseContinuation failed") })
        // The photo half reports too. It used to be the one send* method on this class with
        // no onError branch, which made a listener watching only onError believe the photo
        // had reached the model. The single production call site checked the boolean, so it
        // was never a live break — just a trap laid for the next caller.
        assertTrue(
            "a photo that never left must not look delivered",
            listener.errors.any { it.contains("sendUserImage") },
        )
    }

    @Test
    fun idleTimeoutFiringMidCameraWindowNeverSchedulesAReconnectSoTheAppMustWakeOnATap() {
        // The model is left holding an unanswered tool call and no reconnect is coming --
        // per design (idle timeout suppresses auto-reconnect), the wearer's next tap is the
        // only path back to a working session, and that tap starts a BRAND NEW realtime
        // session with no memory of the abandoned call, which is what actually keeps this
        // safe rather than leaving the model stuck waiting forever.
        val factory = FakeFactory()
        val idleTasks = mutableListOf<() -> Unit>()
        val reconnectDelays = mutableListOf<Long>()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            reconnectScheduler = { d, _ -> reconnectDelays.add(d) },
            idleScheduler = { _, task -> idleTasks.add(task) },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )

        idleTasks.last().invoke()

        assertTrue(reconnectDelays.isEmpty())
        assertTrue(transport.isClosed)
        // A tap-driven wake calls connect() again -- must actually open a fresh socket.
        val socketBefore = factory.socket.sent.size
        transport.connect()
        factory.lastListener?.onOpen()
        assertTrue(factory.socket.sent.size > socketBefore)
    }

    @Test
    fun aSecondTurnRightAfterTheFirstStillCancelsIt() {
        // Deterministic, not a race: two sequential emissions with no server events between
        // them. Before the emitter claimed the slot as it sent, the second call saw no active
        // response, skipped its cancel, and the server dropped its turn with
        // conversation_already_has_active_response. Reverting that one line fails this.
        val factory = FakeFactory()
        val transport = OpenAiRealtimeTransport(successProvider(), factory)
        transport.connect()
        factory.lastListener?.onOpen()
        factory.socket.sent.clear()

        transport.sendInstructions("first turn")
        transport.sendInstructions("second turn")

        val kinds = factory.socket.sent.mapNotNull {
            MiniJson.string(MiniJson.asObject(MiniJson.parse(it)), "type")
        }
        assertEquals(
            "the second turn must clear the first before creating its own",
            listOf("response.create", "response.cancel", "response.create"),
            kinds,
        )
    }

    @Test
    fun aPhotoIsNotDeliveredIntoAConversationThatNeverAskedForOne() {
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(
            successProvider(),
            factory,
            listener = listener,
            reconnectScheduler = { _, task -> task() },
        )
        transport.connect()
        factory.lastListener?.onOpen()
        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")
        factory.lastListener?.onMessage(
            """{"type":"response.output_item.done","response_id":"resp_1",""" +
                """"item":{"type":"function_call","name":"capture_photo","call_id":"call_1"}}""",
        )

        factory.lastListener?.onFailure(RuntimeException("socket died mid-capture"))
        factory.lastListener?.onOpen()

        factory.socket.sent.clear()
        assertFalse("the photo belongs to a session that is gone", transport.sendUserImage("QUJD"))
        assertTrue(factory.socket.sent.none { it.contains("input_image") })
        assertTrue(listener.errors.any { it.contains("closed session") })
    }

    @Test
    fun responseCreatedReachesTheListenerOrTheFirstBargeInSuppressesSubtitlesForever() {
        // The subtitle suppression set at a learner speech start is lifted ONLY by this
        // callback. If response.created stops reaching the listener — a mapping regression,
        // or this dispatch line lost in a refactor — every reply after the first interruption
        // renders no subtitle while the audio plays on, which in the field looks like a
        // display bug and points nowhere near the transport.
        val factory = FakeFactory()
        val listener = RecordingListener()
        val transport = OpenAiRealtimeTransport(successProvider(), factory, listener = listener)
        transport.connect()
        factory.lastListener?.onOpen()

        factory.lastListener?.onMessage("""{"type":"response.created","response":{"id":"resp_1"}}""")

        assertEquals(1, listener.responseStarted)
    }
}
