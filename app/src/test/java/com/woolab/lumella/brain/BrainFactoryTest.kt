package com.woolab.lumella.brain

import com.woolab.lumella.contract.BrainCapabilities
import com.woolab.lumella.contract.BrainConnection
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.BrainSession
import com.woolab.lumella.contract.ImageContext
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainFactoryTest {
    /**
     * Test-only fixture (plan G006 P3): a companion-object property initializer that throws
     * forces the JVM to fail this class's <clinit>, which Class.forName/newInstance surfaces
     * as ExceptionInInitializerError (a LinkageError) rather than a plain Exception — exercises
     * BrainFactory's `catch (e: LinkageError)` branch distinctly from the ReflectiveOperationException
     * branch covered elsewhere in this file.
     */
    class ThrowingStaticInitBrain : TutorBrain {
        companion object {
            @Suppress("unused")
            private val boom: Unit = throw IllegalStateException("simulated static-init failure")
        }
        override fun connect(provider: BrainCredentialsProvider) =
            BrainConnection(BrainConnectionState.READY, BrainCapabilities(coach = true, capabilitiesRoute = true))
        override fun startSession(policy: SessionPolicy) = BrainSession(sessionId = "unreachable", resumed = false)
        override fun submitTurnEvidence(evidence: TurnEvidence) = Unit
        override fun fetchSteering(sessionId: String): SteeringResult =
            SteeringResult.Unavailable(UnavailableReason.NOT_READY)
        override fun analyzeImage(bytes: ByteArray, mime: String) = ImageContext(imageId = "unreachable")
        override fun endSession(sessionId: String) = Unit
    }

    /** Stand-in for an engine adapter present on the runtime (but not compile) classpath. */
    class FakeBoundBrain : TutorBrain {
        override fun connect(provider: BrainCredentialsProvider) =
            BrainConnection(BrainConnectionState.READY, BrainCapabilities(coach = true, capabilitiesRoute = true))
        override fun startSession(policy: SessionPolicy) = BrainSession(sessionId = "fake", resumed = false)
        override fun submitTurnEvidence(evidence: TurnEvidence) = Unit
        override fun fetchSteering(sessionId: String): SteeringResult =
            SteeringResult.Unavailable(UnavailableReason.NOT_READY)
        override fun analyzeImage(bytes: ByteArray, mime: String) = ImageContext(imageId = "fake-image")
        override fun endSession(sessionId: String) = Unit
    }

    /** Not a [TutorBrain] — exercises the "wrong type" fallback branch. */
    class NotATutorBrain

    @Test
    fun classNotFoundFallsBackToNoOpBrainAndReportsReason() {
        var reported: String? = null
        val brain = BrainFactory.create("com.woolab.lumella.adapter.DoesNotExistAtAll") { reported = it }

        assertTrue(brain is NoOpBrain)
        assertTrue(reported.orEmpty().contains("not found"))
    }

    @Test
    fun blankClassNameFallsBackToDefaultBrainClassName() {
        // :luma-adapter is bound `runtimeOnly` (see app/build.gradle.kts), which Gradle's test
        // runtime classpath extends by convention — so on THIS classpath the
        // DEFAULT_BRAIN_CLASS_NAME (LumaTutorBrain) genuinely resolves, exercising the real
        // runtime-DI seam end to end. The "class absent" degrade path is covered separately by
        // classNotFoundFallsBackToNoOpBrainAndReportsReason above (an FQCN that never exists).
        var reported: String? = null
        val brain = BrainFactory.create("") { reported = it }

        assertEquals("com.woolab.lumella.adapter.LumaTutorBrain", brain.javaClass.name)
        assertEquals(null, reported)
    }

    @Test
    fun wrongTypeClassFallsBackToNoOpBrain() {
        var reported: String? = null
        val brain = BrainFactory.create(NotATutorBrain::class.java.name) { reported = it }

        assertTrue(brain is NoOpBrain)
        assertTrue(reported.orEmpty().contains("does not implement TutorBrain"))
    }

    @Test
    fun noOpBrainFetchSteeringReportsSlowPathUnavailable() {
        val brain = BrainFactory.create("nonexistent.ClassName")

        val result = brain.fetchSteering("session-1")

        assertEquals(SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE), result)
    }

    @Test
    fun noOpBrainConnectDegradesRatherThanThrowing() {
        val brain = BrainFactory.create("nonexistent.ClassName")

        val connection = brain.connect(object : BrainCredentialsProvider {
            override fun credentials() = com.woolab.lumella.contract.BrainCredentials(baseUrl = "http://x", email = "a", password = "b")
        })

        assertEquals(BrainConnectionState.DEGRADED, connection.state)
        assertEquals(false, connection.capabilities.coach)
    }

    @Test
    fun validClassNamePresentOnClasspathResolvesToTheRealImplementation() {
        var reported: String? = null
        val brain = BrainFactory.create(FakeBoundBrain::class.java.name) { reported = it }

        assertTrue(brain is FakeBoundBrain)
        assertEquals(null, reported)
    }

    @Test
    fun linkageErrorDuringStaticInitFallsBackToNoOpBrainAndReportsReason() {
        var reported: String? = null
        val brain = BrainFactory.create(ThrowingStaticInitBrain::class.java.name) { reported = it }

        assertTrue(brain is NoOpBrain)
        assertTrue(reported.orEmpty().contains("failed to link/initialize"))
    }
}
