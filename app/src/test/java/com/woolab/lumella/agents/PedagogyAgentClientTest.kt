package com.woolab.lumella.agents

import com.woolab.lumella.contract.BrainCapabilities
import com.woolab.lumella.contract.BrainConnection
import com.woolab.lumella.contract.BrainConnectionState
import com.woolab.lumella.contract.BrainCredentialsProvider
import com.woolab.lumella.contract.BrainSession
import com.woolab.lumella.contract.ImageContext
import com.woolab.lumella.contract.SessionPolicy
import com.woolab.lumella.contract.SteeringCorrection
import com.woolab.lumella.contract.SteeringEvidence
import com.woolab.lumella.contract.SteeringResult
import com.woolab.lumella.contract.TurnEvidence
import com.woolab.lumella.contract.TutorBrain
import com.woolab.lumella.contract.UnavailableReason
import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PedagogyAgentClientTest (P3 adaptation): the legacy EndpointPedagogyAgentClient
 * (direct HTTPS-only okhttp calls) is FORBIDDEN in :app — slow-path evidence now
 * flows exclusively through [TutorBrain] via [TutorBrainPedagogyClient].
 */
class PedagogyAgentClientTest {

    /** Fake brain: records submitted evidence, returns a canned steering result. */
    private class FakeBrain(private val steering: (String) -> SteeringResult) : TutorBrain {
        val submitted = mutableListOf<TurnEvidence>()
        val fetchSteeringCalls = mutableListOf<String>()
        override fun connect(provider: BrainCredentialsProvider) =
            BrainConnection(BrainConnectionState.READY, BrainCapabilities(coach = true, capabilitiesRoute = true))
        override fun startSession(policy: SessionPolicy) = BrainSession(sessionId = "s1", resumed = false)
        override fun submitTurnEvidence(evidence: TurnEvidence) { submitted.add(evidence) }
        override fun fetchSteering(sessionId: String): SteeringResult {
            fetchSteeringCalls.add(sessionId)
            return steering(sessionId)
        }
        override fun analyzeImage(bytes: ByteArray, mime: String) = ImageContext(imageId = "img")
        override fun endSession(sessionId: String) {}
    }

    @Test
    fun analyzeDoesNotSubmitTurnEvidence() {
        // VoiceFastPath is the single submitter (once per turn, fire-and-forget); a
        // per-role TutorBrainPedagogyClient.analyze() call must never also submit,
        // or the same turn's evidence would be duplicated up to 3x.
        val brain = FakeBrain {
            SteeringResult.Available(SteeringEvidence(corrections = emptyList(), hints = emptyList(), confidence = 1.0, sourceTurnId = 4))
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "session-x" })
        val task = SlowPathTask(turnId = 4, userTranscript = "I goed home", ellaTranscript = "reply", imageBase64 = "imgA")

        client.analyze("grammar", task) {}
        client.analyze("pronunciation", task) {}
        client.analyze("visual", task) {}

        assertTrue(brain.submitted.isEmpty())
        assertEquals(listOf("session-x", "session-x", "session-x"), brain.fetchSteeringCalls)
    }

    @Test
    fun grammarRoleMapsAvailableCorrectionsToParseableErrorsJson() {
        val brain = FakeBrain {
            SteeringResult.Available(
                SteeringEvidence(
                    corrections = listOf(SteeringCorrection(original = "I goed", corrected = "I went", errorType = "tense", sourceTurnId = 7)),
                    hints = emptyList(),
                    confidence = 0.9,
                    sourceTurnId = 7,
                ),
            )
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var result: Result<String>? = null
        client.analyze("grammar", SlowPathTask(turnId = 7, userTranscript = "I goed home")) { result = it }

        assertTrue(result!!.isSuccess)
        val delta = GrammarAgent().toStateDelta(result!!.getOrThrow(), SlowPathTask(turnId = 7, userTranscript = "x"))
        assertEquals(1, delta.addGrammarErrors.size)
        assertEquals("I went", delta.addGrammarErrors.first().recast)
    }

    @Test
    fun pronunciationRoleEmitsNoFabricatedPhonemes() {
        // Grammar-role hints/focusHint are generic coaching text, not per-phoneme
        // analysis; relabeling them as "problemPhonemes" would be fabricated
        // evidence. Until the W-1 coach schema defines role-scoped fields, the
        // pronunciation role must emit an empty-but-valid body (no delta).
        val brain = FakeBrain {
            SteeringResult.Available(
                SteeringEvidence(
                    corrections = emptyList(),
                    hints = listOf("watch your consonants"),
                    focusHint = "slow down",
                    confidence = 0.8,
                    sourceTurnId = 3,
                ),
            )
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var result: Result<String>? = null
        client.analyze("pronunciation", SlowPathTask(turnId = 3, userTranscript = "x")) { result = it }

        assertTrue(result!!.isSuccess)
        val body = result!!.getOrThrow()
        assertFalse(body.contains("watch your consonants"))
        assertFalse(body.contains("slow down"))

        val delta = PronunciationFluencyAgent().toStateDelta(body, SlowPathTask(turnId = 3, userTranscript = "x"))
        assertTrue(delta.pronFluency?.problemPhonemes.orEmpty().isEmpty())
        assertTrue(delta.addDeferredCorrections.isEmpty())
    }

    @Test
    fun visualRoleNeverPresentsFocusHintAsASeenSceneCaption() {
        // focusHint is a free-form coaching hint, not an ImageContext-derived
        // caption; presenting it as `caption` would fabricate "learner is looking
        // at X" the brain never actually grounded in an image.
        val brain = FakeBrain {
            SteeringResult.Available(
                SteeringEvidence(
                    corrections = emptyList(),
                    hints = emptyList(),
                    focusHint = "learner is looking at a red car",
                    confidence = 0.8,
                    sourceTurnId = 5,
                ),
            )
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var result: Result<String>? = null
        client.analyze("visual", SlowPathTask(turnId = 5, userTranscript = "x")) { result = it }

        assertTrue(result!!.isSuccess)
        val body = result!!.getOrThrow()
        assertFalse(body.contains("red car"))
        assertFalse(body.contains("looking at"))

        val delta = VisualContextAgent().toStateDelta(body, SlowPathTask(turnId = 5, userTranscript = "x"))
        assertTrue(delta.addVisualContext.isEmpty())
    }

    @Test
    fun unavailableSteeringYieldsFailureCarryingReason() {
        val brain = FakeBrain { SteeringResult.Unavailable(UnavailableReason.SLOW_PATH_UNAVAILABLE) }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var result: Result<String>? = null
        client.analyze("grammar", SlowPathTask(1, "x")) { result = it }

        assertTrue(result!!.isFailure)
        val err = result!!.exceptionOrNull()
        assertTrue(err is SlowPathUnavailableException)
        assertEquals(UnavailableReason.SLOW_PATH_UNAVAILABLE, (err as SlowPathUnavailableException).reason)
    }

    @Test
    fun brainThrowingOnFetchSteeringYieldsFailure() {
        val brain = object : TutorBrain by FakeBrain({ SteeringResult.Unavailable(UnavailableReason.NOT_READY) }) {
            override fun fetchSteering(sessionId: String): SteeringResult { throw IllegalStateException("boom") }
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var result: Result<String>? = null
        client.analyze("grammar", SlowPathTask(1, "x")) { result = it }

        assertTrue(result!!.isFailure)
    }

    @Test
    fun miniJsonRoundTripsGeneratedGrammarContent() {
        val brain = FakeBrain {
            SteeringResult.Available(
                SteeringEvidence(
                    corrections = listOf(SteeringCorrection(original = "he go", corrected = "he goes", errorType = "agreement", sourceTurnId = 2)),
                    hints = emptyList(),
                    confidence = 0.5,
                    sourceTurnId = 2,
                ),
            )
        }
        val client = TutorBrainPedagogyClient(brain, sessionId = { "s" })
        var body: String? = null
        client.analyze("grammar", SlowPathTask(turnId = 2, userTranscript = "he go home")) { body = it.getOrNull() }

        val obj = MiniJson.asObject(MiniJson.parse(body!!))!!
        val choices = MiniJson.asArray(obj["choices"])!!
        val message = MiniJson.asObject(MiniJson.asObject(choices.first())!!["message"])!!
        assertTrue((message["content"] as String).contains("he goes"))
    }
}
