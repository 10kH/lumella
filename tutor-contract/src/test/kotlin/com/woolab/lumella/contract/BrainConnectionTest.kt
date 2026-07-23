package com.woolab.lumella.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Sanity checks that [BrainConnectionState] values compose correctly with
 * [BrainCapabilities] and [ResumableSession] for the W-1 degrade path
 * (coach capability ABSENT -> DEGRADED, no resumable session assumed).
 */
class BrainConnectionTest {

    @Test
    fun `ready state with coach capability carries no forced resumable session`() {
        val connection = BrainConnection(
            state = BrainConnectionState.READY,
            capabilities = BrainCapabilities(coach = true, capabilitiesRoute = true)
        )

        assertEquals(BrainConnectionState.READY, connection.state)
        assertNull(connection.resumableSession)
    }

    @Test
    fun `degraded state reflects absent coach capability per W-1`() {
        val connection = BrainConnection(
            state = BrainConnectionState.DEGRADED,
            capabilities = BrainCapabilities(coach = false, capabilitiesRoute = false)
        )

        assertEquals(BrainConnectionState.DEGRADED, connection.state)
        assertFalse(connection.capabilities.coach)
    }

    @Test
    fun `connection states are distinct and auth_required is not ready or degraded`() {
        assertNotEquals(BrainConnectionState.READY, BrainConnectionState.AUTH_REQUIRED)
        assertNotEquals(BrainConnectionState.DEGRADED, BrainConnectionState.AUTH_REQUIRED)
        assertNotEquals(BrainConnectionState.READY, BrainConnectionState.DEGRADED)
    }

    @Test
    fun `resumable session carries id and age distinct from a fresh connection`() {
        val resumable = ResumableSession(sessionId = "sess-1", ageMinutes = 5)
        val connection = BrainConnection(
            state = BrainConnectionState.READY,
            capabilities = BrainCapabilities(coach = true, capabilitiesRoute = true),
            resumableSession = resumable
        )

        assertEquals("sess-1", connection.resumableSession?.sessionId)
        assertEquals(5L, connection.resumableSession?.ageMinutes)
    }
}
