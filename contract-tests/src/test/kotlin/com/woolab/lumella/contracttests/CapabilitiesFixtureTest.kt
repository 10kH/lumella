package com.woolab.lumella.contracttests

import com.woolab.lumella.adapter.LumaAdapterConfig
import com.woolab.lumella.adapter.LumaHttpResponse
import com.woolab.lumella.adapter.LumaJson
import com.woolab.lumella.adapter.LumaJsonParser
import com.woolab.lumella.adapter.LumaJsonWriter
import com.woolab.lumella.adapter.LumaTutorBrain
import com.woolab.lumella.adapter.obj
import com.woolab.lumella.contract.BrainConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G004 mechanical gate: replays `fixtures/capabilities.json`'s REAL
 * server-emitted `/v1/capabilities` body through
 * [LumaTutorBrain.connect]'s real parse path and asserts the adapter
 * reports `coach = true`. A silent regression (capabilities parse
 * breaking, or the fixture drifting to `coach: false` without anyone
 * noticing) fails this test, not just a manual read of the JSON.
 */
class CapabilitiesFixtureTest {

    @Test
    fun `capabilities fixture reports coach true through the real adapter parse`() {
        val fixtureText = FixtureLoader.readText("capabilities.json")
        val fixture = LumaJsonParser.parse(fixtureText) as LumaJson.Obj
        val responseBody = (fixture.obj("response") ?: error("fixture missing response")).obj("body")
            ?: error("fixture missing response.body")
        val capabilitiesJson = LumaJsonWriter.write(responseBody)

        val transport = FixtureFakeTransport().apply {
            on("POST", "/v1/auth/session", LumaHttpResponse(200, """{"accessToken":"tok-1","refreshToken":"r-1","user":{"id":"u1"}}"""))
            on("GET", "/v1/capabilities", LumaHttpResponse(200, capabilitiesJson))
            on("POST", "/v1/devices/register", LumaHttpResponse(200, """{"deviceId":"dev-1"}"""))
            on("POST", "/v1/devices/dev-1/heartbeat", LumaHttpResponse(200, "{}"))
            on("GET", "/v1/users/me/active-orchestrator-session", LumaHttpResponse(200, "{}"))
        }
        val brain = LumaTutorBrain(transport = transport, config = LumaAdapterConfig(heartbeatIntervalMs = 60_000))

        val connection = brain.connect(FixtureCredentialsProvider())

        assertTrue(connection.capabilities.coach, "adapter must report coach=true from the real capabilities.json fixture")
        assertTrue(connection.capabilities.capabilitiesRoute)
        assertEquals(BrainConnectionState.READY, connection.state)
        brain.stopHeartbeat()
    }
}
