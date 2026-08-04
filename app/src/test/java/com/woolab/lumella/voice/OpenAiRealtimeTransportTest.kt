package com.woolab.lumella.voice

import com.woolab.lumella.TokenHttpResponse
import com.woolab.lumella.TokenServiceCredentialProvider
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
}
