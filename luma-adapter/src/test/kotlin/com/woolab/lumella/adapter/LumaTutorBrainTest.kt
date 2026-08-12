package com.woolab.lumella.adapter

import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentials
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.CoachIndicator
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.UnavailableReason
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FIXED_NOW: Instant = Instant.parse("2026-07-21T12:00:00Z")

private val TEST_CREDENTIALS = BrainCredentials(
    baseUrl = "http://luma.test",
    email = "learner@example.com",
    password = "secret",
    deviceName = "glasses-1",
)

private class FakeCredentialsProvider(private val creds: BrainCredentials = TEST_CREDENTIALS) : BrainCredentialsProvider {
    override fun credentials(): BrainCredentials = creds
}

private fun json(body: String, code: Int = 200) = LumaHttpResponse(code, body)

/** Wires the standard happy-path routes (login, capabilities, device register/heartbeat, active session). */
private fun FakeLumaHttpTransport.wireHappyPath(
    coach: Boolean = true,
    capabilitiesCode: Int = 200,
    activeSessionBody: String? = """{"session":{"id":"sess-1","lastMessageAt":"2026-07-21T11:55:00Z"}}""",
) {
    on("POST", "/v1/auth/session", json("""{"accessToken":"tok-1","refreshToken":"r","user":{"id":"u1"}}"""))
    if (capabilitiesCode == 200) {
        on("GET", "/v1/capabilities", json("""{"schemaRev":1,"coach":$coach,"routes":["coach"]}"""))
    } else {
        on("GET", "/v1/capabilities", json("not found", code = capabilitiesCode))
    }
    on("POST", "/v1/devices/register", json("""{"deviceId":"dev-1"}"""))
    on("POST", "/v1/devices/dev-1/heartbeat", json("{}"))
    if (activeSessionBody != null) {
        on("GET", "/v1/users/me/active-orchestrator-session", json(activeSessionBody))
    } else {
        on("GET", "/v1/users/me/active-orchestrator-session", json("""{}"""))
    }
}

private fun newBrain(
    transport: FakeLumaHttpTransport,
    heartbeatIntervalMs: Long = 20,
    resumeWindowMinutes: Long = 30,
    clock: () -> Instant = { FIXED_NOW },
): LumaTutorBrain = LumaTutorBrain(
    transport = transport,
    config = LumaAdapterConfig(heartbeatIntervalMs = heartbeatIntervalMs, resumeWindowMinutes = resumeWindowMinutes),
    clock = clock,
)

class LumaTutorBrainTest {

    @Test
    fun `connect happy path is READY with coach capability`() {
        val transport = FakeLumaHttpTransport().apply { wireHappyPath(coach = true) }
        val brain = newBrain(transport)

        val connection = brain.connect(FakeCredentialsProvider())

        assertEquals(BrainConnectionState.READY, connection.state)
        assertTrue(connection.capabilities.coach)
        assertTrue(connection.capabilities.capabilitiesRoute)
        assertNotNull(connection.resumableSession)
        assertEquals("sess-1", connection.resumableSession?.sessionId)
        brain.stopHeartbeat()
    }

    @Test
    fun `404 capabilities degrades connection and blocks steering`() {
        val transport = FakeLumaHttpTransport().apply { wireHappyPath(capabilitiesCode = 404) }
        val brain = newBrain(transport)

        val connection = brain.connect(FakeCredentialsProvider())

        assertEquals(BrainConnectionState.DEGRADED, connection.state)
        assertFalse(connection.capabilities.coach)
        assertFalse(connection.capabilities.capabilitiesRoute)

        val steering = brain.fetchSteering("sess-1")
        assertEquals(SteeringResult.Unavailable(UnavailableReason.COACH_UNSUPPORTED), steering)
        brain.stopHeartbeat()
    }

    @Test
    fun `401 on login is AUTH_REQUIRED`() {
        val transport = FakeLumaHttpTransport().apply {
            on("POST", "/v1/auth/session", json("""{"error":"bad credentials"}""", code = 401))
        }
        val brain = newBrain(transport)

        val connection = brain.connect(FakeCredentialsProvider())

        assertEquals(BrainConnectionState.AUTH_REQUIRED, connection.state)
        assertFalse(connection.capabilities.coach)
        assertNull(connection.resumableSession)
        // No further calls should have been attempted past login.
        assertEquals(0, transport.countOf("GET", "/v1/capabilities"))
    }

    @Test
    fun `D-7 resumes an active session younger than the resume window`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(activeSessionBody = """{"session":{"id":"sess-young","lastMessageAt":"2026-07-21T11:45:00Z"}}""")
        }
        val brain = newBrain(transport, resumeWindowMinutes = 30)
        brain.connect(FakeCredentialsProvider())

        val session = brain.startSession(SessionPolicy.RESUME_ACTIVE)

        assertTrue(session.resumed)
        assertEquals("sess-young", session.sessionId)
        brain.stopHeartbeat()
    }

    @Test
    fun `D-7 does not resume a session at or beyond the resume window`() {
        val transport = FakeLumaHttpTransport().apply {
            // 30 minutes old exactly at FIXED_NOW - resume window is [0,30) minutes.
            wireHappyPath(activeSessionBody = """{"session":{"id":"sess-old","lastMessageAt":"2026-07-21T11:30:00Z"}}""")
        }
        val brain = newBrain(transport, resumeWindowMinutes = 30)
        brain.connect(FakeCredentialsProvider())

        val session = brain.startSession(SessionPolicy.RESUME_ACTIVE)

        assertFalse(session.resumed)
        assertTrue(session.sessionId.startsWith("pending-"))
        brain.stopHeartbeat()
    }

    @Test
    fun `FRESH policy always starts a new session`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(activeSessionBody = """{"session":{"id":"sess-young","lastMessageAt":"2026-07-21T11:59:00Z"}}""")
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        val session = brain.startSession(SessionPolicy.FRESH)

        assertFalse(session.resumed)
        assertTrue(session.sessionId.startsWith("pending-"))
        brain.stopHeartbeat()
    }

    @Test
    fun `coach evidence distillation maps fields exactly including sourceTurnRef to sourceTurnId`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1"},
                  "coachEvidence": {
                    "corrections": [
                      {"original": "I go store", "corrected": "I went to the store", "errorType": "tense", "sourceTurnRef": 7},
                      {"original": "he no like it", "corrected": "he doesn't like it", "sourceTurnRef": 7}
                    ],
                    "hints": ["watch your verb tense", "use articles"],
                    "focusHint": "past tense",
                    "confidence": 0.82
                  }
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 7, learnerTranscript = "I go store"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        val evidence = (result as SteeringResult.Available).evidence
        assertEquals(7, evidence.sourceTurnId)
        assertEquals(0.82, evidence.confidence)
        assertEquals("past tense", evidence.focusHint)
        assertEquals(listOf("watch your verb tense", "use articles"), evidence.hints)
        assertEquals(2, evidence.corrections.size)
        val first = evidence.corrections[0]
        assertEquals("I go store", first.original)
        assertEquals("I went to the store", first.corrected)
        assertEquals("tense", first.errorType)
        assertEquals(7, first.sourceTurnId)
        val second = evidence.corrections[1]
        assertEquals("he no like it", second.original)
        assertEquals("he doesn't like it", second.corrected)
        assertNull(second.errorType)
        assertEquals(7, second.sourceTurnId)
        brain.stopHeartbeat()
    }

    @Test
    fun `tolerant reader ignores unknown fields`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1", "unknownField": 123},
                  "somethingElse": {"nested": true},
                  "coachEvidence": {
                    "corrections": [{"original": "a", "corrected": "b", "sourceTurnRef": 1, "extra": "ignored"}],
                    "hints": [],
                    "confidence": 0.5,
                    "futureField": ["x", "y"]
                  }
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "a"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        assertEquals(1, (result as SteeringResult.Available).evidence.corrections.size)
        brain.stopHeartbeat()
    }

    @Test
    fun `coach evidence distillation maps a real visual object to SteeringVisual`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1"},
                  "coachEvidence": {
                    "corrections": [],
                    "hints": [],
                    "confidence": 0.6,
                    "visual": {
                      "imageId": "img_abc",
                      "caption": "a red mug on a desk",
                      "salientElements": ["mug", "desk"],
                      "visibleTextBlocks": ["CAUTION HOT"]
                    }
                  }
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        val visual = (result as SteeringResult.Available).evidence.visual
        assertNotNull(visual)
        assertEquals("img_abc", visual.imageId)
        assertEquals("a red mug on a desk", visual.caption)
        assertEquals(listOf("mug", "desk"), visual.salientElements)
        assertEquals(listOf("CAUTION HOT"), visual.visibleTextBlocks)
        brain.stopHeartbeat()
    }

    @Test
    fun `coach evidence distillation maps missing visual to null`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "coachEvidence": {"corrections": [], "hints": [], "confidence": 0.4}}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        assertNull((result as SteeringResult.Available).evidence.visual)
        brain.stopHeartbeat()
    }

    @Test
    fun `coach evidence distillation maps empty caption visual to null`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1"},
                  "coachEvidence": {
                    "corrections": [],
                    "hints": [],
                    "confidence": 0.4,
                    "visual": {"imageId": "img_abc", "caption": "", "salientElements": [], "visibleTextBlocks": []}
                  }
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        assertNull((result as SteeringResult.Available).evidence.visual)
        brain.stopHeartbeat()
    }

    @Test
    fun `coach evidence distillation ignores unknown fields inside visual`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1"},
                  "coachEvidence": {
                    "corrections": [],
                    "hints": [],
                    "confidence": 0.4,
                    "visual": {
                      "imageId": "img_abc",
                      "caption": "a scene",
                      "salientElements": ["a"],
                      "visibleTextBlocks": [],
                      "unknownField": "ignored",
                      "boundingBoxes": [{"x": 1, "y": 2}]
                    }
                  }
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "hi"))
        val result = brain.fetchSteering("sess-1")

        assertTrue(result is SteeringResult.Available)
        val visual = (result as SteeringResult.Available).evidence.visual
        assertNotNull(visual)
        assertEquals("a scene", visual.caption)
        assertEquals(listOf("a"), visual.salientElements)
        brain.stopHeartbeat()
    }

    @Test
    fun `malformed turn response fails closed to SLOW_PATH_UNAVAILABLE`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json("""{"session": {"id": "sess-1", """)) // truncated/invalid JSON
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "a"))
        val result = brain.fetchSteering("sess-1")

        assertEquals(SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE), result)
        brain.stopHeartbeat()
    }

    @Test
    fun `no evidence yet reports NOT_READY`() {
        val transport = FakeLumaHttpTransport().apply { wireHappyPath(coach = true) }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        val result = brain.fetchSteering("sess-1")

        assertEquals(SteeringResult.Unavailable(UnavailableReason.NOT_READY), result)
        brain.stopHeartbeat()
    }

    @Test
    fun `submitTurnEvidence is idempotent per turnId - repeat submit does not double-post`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "coachEvidence": {"corrections": [], "hints": [], "confidence": 0.4}}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        val evidence = TurnEvidence(turnId = 5, learnerTranscript = "same turn")
        brain.submitTurnEvidence(evidence)
        brain.submitTurnEvidence(evidence)
        brain.submitTurnEvidence(evidence)

        assertEquals(1, transport.countOf("POST", "/v1/orchestrator/turn"))
        brain.stopHeartbeat()
    }

    @Test
    fun `submitTurnEvidence posts again for a new turnId`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "coachEvidence": {"corrections": [], "hints": [], "confidence": 0.4}}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.startSession(SessionPolicy.FRESH)

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        brain.submitTurnEvidence(TurnEvidence(turnId = 2, learnerTranscript = "two"))

        assertEquals(2, transport.countOf("POST", "/v1/orchestrator/turn"))
        brain.stopHeartbeat()
    }

    @Test
    fun `heartbeat thread starts as a daemon and stop joins cleanly with no leak`() {
        val transport = FakeLumaHttpTransport().apply { wireHappyPath(coach = true) }
        val brain = newBrain(transport, heartbeatIntervalMs = 10)

        brain.connect(FakeCredentialsProvider())
        assertTrue(brain.isHeartbeatRunning())

        // Let a couple of heartbeat cycles fire.
        Thread.sleep(60)
        assertTrue(transport.countOf("POST", "/v1/devices/dev-1/heartbeat") >= 1)

        brain.stopHeartbeat()
        assertFalse(brain.isHeartbeatRunning())

        val countAfterStop = transport.countOf("POST", "/v1/devices/dev-1/heartbeat")
        Thread.sleep(50)
        assertEquals(countAfterStop, transport.countOf("POST", "/v1/devices/dev-1/heartbeat"))
    }

    @Test
    fun `endSession stops heartbeat`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/sessions/sess-1/end", json("{}"))
        }
        val brain = newBrain(transport, heartbeatIntervalMs = 10)
        brain.connect(FakeCredentialsProvider())
        assertTrue(brain.isHeartbeatRunning())

        brain.endSession("sess-1")

        assertFalse(brain.isHeartbeatRunning())
        assertEquals(1, transport.countOf("POST", "/v1/orchestrator/sessions/sess-1/end"))
    }

    @Test
    fun `analyzeImage maps response fields`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/images/analyze", json(
                """{"imageId": "img-1", "caption": "a red door", "imageKind": "scene", "visibleTextBlocks": ["OPEN", "9am-5pm"]}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        val context = brain.analyzeImage(byteArrayOf(1, 2, 3), "image/jpeg")

        assertEquals("img-1", context.imageId)
        assertEquals("a red door", context.caption)
        assertEquals("scene", context.imageKind)
        assertEquals(listOf("OPEN", "9am-5pm"), context.visibleText)
        brain.stopHeartbeat()
    }

    @Test
    fun `08-05 requirement 3 - turn response with selectedRoute and selectedProvider populates coachIndicator`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """
                {
                  "session": {"id": "sess-1"},
                  "selectedRoute": "scenario",
                  "selectedProvider": "etri",
                  "coachEvidence": {"corrections": [], "hints": [], "confidence": 0.4}
                }
                """.trimIndent(),
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        assertNull(brain.coachIndicator())
        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "체크인하고 싶어요"))

        val indicator = brain.coachIndicator()
        assertNotNull(indicator)
        assertEquals("scenario", indicator?.route)
        assertEquals("etri", indicator?.provider)
        brain.stopHeartbeat()
    }

    @Test
    fun `08-05 requirement 3 - a 5xx turn response clears a previously stored coachIndicator on the same brain`() {
        var turnCalls = 0
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { _ ->
                turnCalls++
                if (turnCalls == 1) {
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""")
                } else {
                    json("""{"error":"boom"}""", code = 500)
                }
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        assertNotNull(brain.coachIndicator())

        brain.submitTurnEvidence(TurnEvidence(turnId = 2, learnerTranscript = "two"))

        assertNull(brain.coachIndicator())
        assertEquals(SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE), brain.fetchSteering("sess-1"))
        brain.stopHeartbeat()
    }

    @Test
    fun `08-05 requirement 3 - startSession clears a stale coachIndicator from a prior session`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())
        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        assertNotNull(brain.coachIndicator())

        brain.startSession(SessionPolicy.FRESH)

        assertNull(brain.coachIndicator())
        brain.stopHeartbeat()
    }
    // --- Adversarial: brain lifecycle + dedupe interaction (Ultragoal red-team, 08/12) ---

    @Test
    fun `lifecycle - ok then 5xx then ok again cycles the indicator through set, cleared, set`() {
        var turnCalls = 0
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { _ ->
                turnCalls++
                when (turnCalls) {
                    1 -> json("""{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""")
                    2 -> json("""{"error":"boom"}""", code = 500)
                    else -> json("""{"session": {"id": "sess-1"}, "selectedRoute": "gpt_qa", "selectedProvider": "openai"}""")
                }
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        assertEquals(CoachIndicator(route = "free_chat", provider = "etri"), brain.coachIndicator())

        brain.submitTurnEvidence(TurnEvidence(turnId = 2, learnerTranscript = "two"))
        assertNull(brain.coachIndicator())

        brain.submitTurnEvidence(TurnEvidence(turnId = 3, learnerTranscript = "three"))
        assertEquals(CoachIndicator(route = "gpt_qa", provider = "openai"), brain.coachIndicator())
        brain.stopHeartbeat()
    }

    @Test
    fun `lifecycle - a transport exception on a later turn clears a previously stored coachIndicator`() {
        var turnCalls = 0
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { _ ->
                turnCalls++
                if (turnCalls == 1) {
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""")
                } else {
                    throw LumaTransportException("connection reset")
                }
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        assertNotNull(brain.coachIndicator())

        brain.submitTurnEvidence(TurnEvidence(turnId = 2, learnerTranscript = "two"))
        assertNull(brain.coachIndicator())
        assertEquals(SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE), brain.fetchSteering("sess-1"))
        brain.stopHeartbeat()
    }

    /**
     * This used to PIN a HIGH defect: a response carrying selectedRoute without
     * selectedProvider matched neither the set nor the clear branch, so the PRIOR turn's
     * indicator silently survived, mislabeling a turn it did not describe. Both review
     * lanes found it independently. Fixed: anything short of a complete, non-blank pair
     * clears — nothing honest to show means show nothing.
     */
    @Test
    fun `a partial route-provider pair clears the indicator instead of leaving the prior turn's`() {
        var turnCalls = 0
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { _ ->
                turnCalls++
                if (turnCalls == 1) {
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""")
                } else {
                    // No selectedProvider, no coachEvidence: neither branch at
                    // LumaTutorBrain.kt:183/185 fires.
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "gpt_qa"}""")
                }
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))
        val firstIndicator = brain.coachIndicator()
        assertEquals(CoachIndicator(route = "free_chat", provider = "etri"), firstIndicator)

        brain.submitTurnEvidence(TurnEvidence(turnId = 2, learnerTranscript = "two"))

        org.junit.jupiter.api.Assertions.assertNull(brain.coachIndicator(), "partial pair must clear, not retain the prior turn's label")
        brain.stopHeartbeat()
    }

    /**
     * This used to PIN a MEDIUM defect: `json.str(key)` returns "" — not null — for a
     * present-but-empty JSON string, and the null-only guard let an empty pair through,
     * rendering "코치 ()" on the glasses. Fixed with isNotBlank guards in the adapter.
     */
    @Test
    fun `empty-string route and provider clear the indicator rather than rendering a blank label`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "selectedRoute": "", "selectedProvider": ""}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))

        org.junit.jupiter.api.Assertions.assertNull(brain.coachIndicator())
        brain.stopHeartbeat()
    }

    @Test
    fun `a coach-incapable server never shows a coach indicator, even when its turn response carries route fields`() {
        // The gate this pins: capabilities.coach=false means fetchSteering permanently refuses
        // (COACH_UNSUPPORTED) — displaying "코치 …" from that server would label a coach that
        // never coaches. The hide direction had no test when the gate landed.
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = false)
            on("POST", "/v1/orchestrator/turn", json(
                """{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""",
            ))
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "one"))

        org.junit.jupiter.api.Assertions.assertNull(
            brain.coachIndicator(),
            "coach=false server must never surface a coach label",
        )
        brain.stopHeartbeat()
    }

    @Test
    fun `dedupe - resubmitting the same turnId keeps the FIRST submission's indicator, never doubled or cleared`() {
        var turnCalls = 0
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { _ ->
                turnCalls++
                json("""{"session": {"id": "sess-1"}, "selectedRoute": "gpt_qa", "selectedProvider": "openai"}""")
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        val evidence = TurnEvidence(turnId = 9, learnerTranscript = "same turn")
        brain.submitTurnEvidence(evidence)
        val first = brain.coachIndicator()
        assertEquals(CoachIndicator(route = "gpt_qa", provider = "openai"), first)

        brain.submitTurnEvidence(evidence)
        brain.submitTurnEvidence(evidence)

        // The second/third submits never reach the transport (lastSubmittedTurnId dedupe at
        // LumaTutorBrain.kt:133), so the indicator is neither doubled nor cleared.
        assertEquals(1, turnCalls)
        assertEquals(1, transport.countOf("POST", "/v1/orchestrator/turn"))
        assertEquals(first, brain.coachIndicator())
        brain.stopHeartbeat()
    }

    /**
     * `coachIndicatorField` is `@Volatile` and always assigned as a whole `CoachIndicator`
     * reference (LumaTutorBrain.kt:184), so the JMM guarantees no torn read of the
     * (route, provider) pair even under concurrent submission — this test confirms that
     * holds. Note (LOW, informational): `lastSubmittedTurnId` (LumaTutorBrain.kt:65) is
     * NOT `@Volatile` and its check-then-set dedupe (line 133-134) is not synchronized,
     * unlike the three sibling fields declared `@Volatile` right below it. That is a
     * latent thread-safety gap in the dedupe mechanism itself (possible visibility lag /
     * racy double-post under concurrent callers), separate from the pair-integrity property
     * this test targets.
     */
    @Test
    fun `concurrency - rapid alternating submits from two threads never produce a torn route-provider pair`() {
        val transport = FakeLumaHttpTransport().apply {
            wireHappyPath(coach = true)
            on("POST", "/v1/orchestrator/turn") { req ->
                val body = req.body.orEmpty()
                if (body.contains("\"content\":\"A\"")) {
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "free_chat", "selectedProvider": "etri"}""")
                } else {
                    json("""{"session": {"id": "sess-1"}, "selectedRoute": "gpt_qa", "selectedProvider": "openai"}""")
                }
            }
        }
        val brain = newBrain(transport)
        brain.connect(FakeCredentialsProvider())

        val validPairs = setOf(
            CoachIndicator(route = "free_chat", provider = "etri"),
            CoachIndicator(route = "gpt_qa", provider = "openai"),
        )
        val observedInvalid = CopyOnWriteArrayList<CoachIndicator>()
        val stop = AtomicBoolean(false)

        val threadA = Thread {
            var i = 0
            while (!stop.get()) {
                brain.submitTurnEvidence(TurnEvidence(turnId = 100_000 + i, learnerTranscript = "A"))
                i++
            }
        }
        val threadB = Thread {
            var i = 0
            while (!stop.get()) {
                brain.submitTurnEvidence(TurnEvidence(turnId = 200_000 + i, learnerTranscript = "B"))
                i++
            }
        }
        val reader = Thread {
            repeat(20_000) {
                val snapshot = brain.coachIndicator()
                if (snapshot != null && snapshot !in validPairs) {
                    observedInvalid.add(snapshot)
                }
            }
        }

        threadA.start()
        threadB.start()
        reader.start()
        Thread.sleep(200)
        stop.set(true)
        threadA.join(5_000)
        threadB.join(5_000)
        reader.join(5_000)

        assertTrue(observedInvalid.isEmpty(), "torn/invalid indicator pairs observed: $observedInvalid")
        brain.stopHeartbeat()
    }
}
