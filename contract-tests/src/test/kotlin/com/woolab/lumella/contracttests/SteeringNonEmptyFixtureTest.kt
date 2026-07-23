package com.woolab.lumella.contracttests

import com.woolab.lumella.adapter.LumaAdapterConfig
import com.woolab.lumella.adapter.LumaHttpResponse
import com.woolab.lumella.adapter.LumaJson
import com.woolab.lumella.adapter.LumaJsonParser
import com.woolab.lumella.adapter.LumaJsonWriter
import com.woolab.lumella.adapter.LumaTutorBrain
import com.woolab.lumella.adapter.obj
import com.woolab.lumella.adapter.str
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * G004's mechanical no-silent-empty-steering gate. Replays
 * `fixtures/steering-nonempty-coach.json`'s REAL server-emitted
 * `/v1/orchestrator/turn` response body through
 * [LumaTutorBrain.submitTurnEvidence] / [LumaTutorBrain.fetchSteering] —
 * the adapter's real distillation path, not a reimplementation of its JSON
 * parsing — and asserts [SteeringResult.Available] with a non-empty,
 * exactly-field-mapped correction. If a future adapter change silently
 * drops or empties corrections for this fixture, this test fails instead
 * of the regression going unnoticed.
 */
class SteeringNonEmptyFixtureTest {

    @Test
    fun `steering-nonempty-coach fixture yields Available with non-empty exactly-mapped corrections`() {
        val fixtureText = FixtureLoader.readText("steering-nonempty-coach.json")
        val fixture = LumaJsonParser.parse(fixtureText) as LumaJson.Obj
        val response = fixture.obj("response") ?: error("fixture missing response")
        val turnResponseBody = response.obj("body") ?: error("fixture missing response.body")
        val turnResponseJson = LumaJsonWriter.write(turnResponseBody)

        val transport = FixtureFakeTransport().apply {
            on("POST", "/v1/auth/session", LumaHttpResponse(200, """{"accessToken":"tok-1","refreshToken":"r-1","user":{"id":"u1"}}"""))
            on("GET", "/v1/capabilities", LumaHttpResponse(200, """{"schemaRev":1,"coach":true,"routes":["/v1/orchestrator/turn"]}"""))
            on("POST", "/v1/devices/register", LumaHttpResponse(200, """{"deviceId":"dev-1"}"""))
            on("POST", "/v1/devices/dev-1/heartbeat", LumaHttpResponse(200, "{}"))
            on("GET", "/v1/users/me/active-orchestrator-session", LumaHttpResponse(200, "{}"))
            on("POST", "/v1/orchestrator/turn", LumaHttpResponse(200, turnResponseJson))
        }
        val brain = LumaTutorBrain(transport = transport, config = LumaAdapterConfig(heartbeatIntervalMs = 60_000))

        val connection = brain.connect(FixtureCredentialsProvider())
        assertTrue(connection.capabilities.coach, "fixture wiring must present coach=true so fetchSteering isn't short-circuited")

        val session = brain.startSession(SessionPolicy.FRESH)
        brain.submitTurnEvidence(TurnEvidence(turnId = 1, learnerTranscript = "coach-scenario"))
        val result = brain.fetchSteering(session.sessionId)

        assertIs<SteeringResult.Available>(result)
        val evidence = result.evidence
        assertTrue(evidence.corrections.isNotEmpty(), "steering-nonempty-coach.json must yield >=1 correction through the real adapter parse")

        // Exact field mapping, read straight back out of the fixture body (not a
        // hardcoded duplicate) so this stays true to whatever the fixture records.
        val expectedCorrections = (turnResponseBody.obj("coachEvidence")?.fields?.get("corrections") as? LumaJson.Arr)?.items
            ?: error("fixture missing coachEvidence.corrections")
        assertEquals(expectedCorrections.size, evidence.corrections.size)

        val expectedFirst = expectedCorrections[0] as LumaJson.Obj
        val actualFirst = evidence.corrections[0]
        assertEquals(expectedFirst.str("original"), actualFirst.original)
        assertEquals(expectedFirst.str("corrected"), actualFirst.corrected)
        assertEquals(expectedFirst.str("errorType"), actualFirst.errorType)
        val expectedSourceTurnRef = (expectedFirst.fields["sourceTurnRef"] as? LumaJson.Num)?.value?.toInt()
        assertEquals(expectedSourceTurnRef, actualFirst.sourceTurnId)

        brain.stopHeartbeat()
    }
}
