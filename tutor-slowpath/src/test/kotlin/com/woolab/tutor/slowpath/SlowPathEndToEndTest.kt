package com.woolab.tutor.slowpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The whole slow path, wired the way MainActivity wires it, driven the way the booth will
 * drive it: six learner turns, three with the same slip and three clean.
 *
 * Why this test exists. On 2026-09-26 the port to lumella compiled, passed 635 unit tests, and
 * formed no diagnosis on the glasses. Four separate faults, three of them wiring: no
 * ConsolidateAgent injected, no backing file on the store, per-turn failures dropped without a
 * log. Every part had a test; nothing tested that the parts were connected. This does.
 *
 * The fake server answers the way the real one is prompted to: grammar flags a subject particle
 * on an object, consolidate names the pattern only when two or more errors since the last
 * diagnosis share it, and returns an explicit null when they do not. That is the contract in
 * api/pedagogy-agent.js, not a convenience.
 */
class SlowPathEndToEndTest {

    /** Plays the pedagogy endpoint. Reads the record the client sends; does not cheat with a script. */
    private class FakeEndpoint : PedagogyAgentClient {
        val calls = mutableListOf<Pair<String, Int>>()

        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            calls += role to task.turnId
            val content = when (role) {
                "grammar" -> grammar(task.userTranscript)
                "pronunciation" -> """{"problemPhonemes":[],"notes":""}"""
                "consolidate" -> consolidate(task.userTranscript)
                else -> "{}"
            }
            callback(Result.success(envelope(content)))
        }

        // A subject particle where the object particle belongs: 친구가 만났 / 빵이 먹었 / 영화가 볼
        private fun grammar(text: String): String {
            val m = Regex("([가-힣]+)(가|이) (만났|먹었|볼)").find(text) ?: return """{"errors":[]}"""
            val noun = m.groupValues[1]
            val fixed = noun + (if (hasFinalConsonant(noun)) "을" else "를")
            return """{"errors":[{"span":"${m.groupValues[1]}${m.groupValues[2]}","type":"object particle","recast":"$fixed"}]}"""
        }

        // ConsolidateAgent.serialise lists errors since the last diagnosis under this header
        private fun consolidate(record: String): String {
            val since = record.substringAfter("errorsSinceLastDiagnosis:", "")
            val objectParticle = Regex("object particle").findAll(since).count()
            return if (objectParticle >= 2) {
                """{"ruleGap":"목적어에 주격 조사를 씀: 을/를 대신 이/가","evidence":["친구가","빵이"],"practiceTargets":["친구를 만났어요","빵을 먹었어요"],"proficiencyEstimate":"A2","vocabTargets":[]}"""
            } else {
                """{"ruleGap":null,"evidence":[],"practiceTargets":[],"proficiencyEstimate":null,"vocabTargets":[]}"""
            }
        }

        private fun hasFinalConsonant(s: String): Boolean {
            val c = s.last().code
            return c in 0xAC00..0xD7A3 && (c - 0xAC00) % 28 != 0
        }

        private fun envelope(content: String): String {
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
            return """{"choices":[{"message":{"content":"$escaped"}}]}"""
        }
    }

    /** The activity's wiring, by calling the activity's factory — not by re-typing it. */
    private class Rig(backing: File) {
        val endpoint = FakeEndpoint()
        val warnings = mutableListOf<String>()
        val indicatorRenders = mutableListOf<String>()
        lateinit var assembly: SlowPathAssembly
        init {
            assembly = SlowPathAssembly.build(
            language = TutorLanguage.KOREAN,                backing = backing,
                endpoint = endpoint,
                onStateChanged = { indicatorRenders += LayerIndicator.render(assembly.store.snapshot()) },
                warn = { warnings += it },
            )
        }
        val store get() = assembly.store
        val tracker get() = assembly.tracker
        val orchestrator get() = assembly.orchestrator

        /** One learner turn, the way submitCurrentTurnEvidence does it. */
        fun turn(transcript: String): Int {
            val id = tracker.next()
            assembly.dispatch(SlowPathTask(turnId = id, userTranscript = transcript))
            return id
        }

        fun indicator(): String = LayerIndicator.render(store.snapshot())
        fun steering(): String = SteeringComposer.compose(TutorLanguage.KOREAN, "", store.snapshot(), emptyList(), null)
    }

    private fun tmp(): File = Files.createTempFile("e2e-learner-state", ".json").toFile().also { it.delete() }

    @Test
    fun threeSlipsFormADiagnosisAndThreeCleanTurnsClearIt() {
        val rig = Rig(tmp())

        // Before anything: idle corner, no steering about a habit.
        assertTrue(rig.indicator().contains("coach  ·"))
        assertFalse(rig.steering().contains("목적어"))

        rig.turn("어제 친구가 만났어요")
        rig.turn("오늘 아침에 빵이 먹었어요")
        assertNull("two turns in, no cadence yet", rig.store.snapshot().ruleGap)
        assertEquals(2, rig.store.snapshot().grammarErrors.size)

        rig.turn("내일 영화가 볼 거예요")
        val lit = rig.store.snapshot()
        assertEquals("consolidate runs at the third turn", listOf("consolidate" to 3), rig.endpoint.calls.filter { it.first == "consolidate" })
        assertNotNull("the habit is named", lit.ruleGap)
        assertEquals(listOf("친구를 만났어요", "빵을 먹었어요"), lit.practiceTargets)
        assertEquals(3, lit.lastConsolidatedTurnId)
        assertTrue("corner lights", rig.indicator().contains("coach  luna"))
        assertTrue("the diagnosis reaches the tutor's steering", rig.steering().contains("목적어에 주격 조사를 씀"))

        rig.turn("어제 친구를 만났어요")
        rig.turn("오늘 아침에 빵을 먹었어요")
        assertNotNull("one or two clean turns do not clear it", rig.store.snapshot().ruleGap)
        assertTrue(rig.indicator().contains("coach  luna"))

        rig.turn("내일 영화를 볼 거예요")
        val cleared = rig.store.snapshot()
        assertEquals(listOf(3, 6), rig.endpoint.calls.filter { it.first == "consolidate" }.map { it.second })
        assertNull("the record no longer supports it", cleared.ruleGap)
        assertEquals(6, cleared.lastConsolidatedTurnId)
        assertTrue("corner goes dark", rig.indicator().contains("coach  ·"))
        assertFalse(rig.steering().contains("목적어"))

        // The indicator was redrawn from the store on every state change, not from a cache.
        assertTrue(rig.indicatorRenders.any { it.contains("luna") })
        assertTrue(rig.indicatorRenders.last().contains("·"))
        assertEquals("nothing failed silently", emptyList<String>(), rig.warnings)
    }

    @Test
    fun aRelaunchMidDiagnosisKeepsItAndStillClearsOnSchedule() {
        // The second fault the device found: TurnTracker restarted at 1 per launch while the
        // cadence measures turnId - lastConsolidatedTurnId. Now seeded from the record.
        val file = tmp()
        val first = Rig(file)
        first.turn("어제 친구가 만났어요"); first.turn("오늘 아침에 빵이 먹었어요"); first.turn("내일 영화가 볼 거예요")
        assertNotNull(first.store.snapshot().ruleGap)

        val relaunched = Rig(file)                      // new store, new tracker, same file
        assertNotNull("diagnosis survived the file round-trip", relaunched.store.snapshot().ruleGap)
        assertTrue("corner is lit on boot", relaunched.indicator().contains("coach  luna"))
        assertEquals("numbering continues", 3, relaunched.tracker.current())

        val ids = listOf("어제 친구를 만났어요", "오늘 아침에 빵을 먹었어요", "내일 영화를 볼 거예요").map { relaunched.turn(it) }
        assertEquals(listOf(4, 5, 6), ids)
        assertEquals(listOf(6), relaunched.endpoint.calls.filter { it.first == "consolidate" }.map { it.second })
        assertNull(relaunched.store.snapshot().ruleGap)
    }

    @Test
    fun mixedSlipsDoNotFormADiagnosis() {
        // Three errors of three kinds: the server is told to return null unless a pattern repeats.
        val rig = Rig(tmp())
        rig.turn("어제 친구가 만났어요")   // object particle
        rig.turn("내일 학교에 갔어요")     // tense (the fake grammar does not flag it — one particle error on record)
        rig.turn("선생님이 저한테 말했다") // honorific (likewise)
        assertEquals(1, rig.store.snapshot().grammarErrors.size)
        assertNull(rig.store.snapshot().ruleGap)
        assertTrue(rig.indicator().contains("coach  ·"))
    }

    @Test
    fun aFailedCallIsLoggedAndTheTurnIsStillRecorded() {
        // Third fault: failures used to vanish. Now the dispatcher warns by role.
        val flaky = object : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) =
                callback(Result.failure(java.io.IOException("Pedagogy endpoint request failed")))
        }
        val warnings = mutableListOf<String>()
        val assembly = SlowPathAssembly.build(
            language = TutorLanguage.KOREAN,            backing = tmp(), endpoint = flaky,
            onStateChanged = {}, warn = { warnings += it },
        )
        assembly.dispatch(SlowPathTask(turnId = assembly.tracker.next(), userTranscript = "어제 친구가 만났어요"))

        assertEquals(1, assembly.store.snapshot().turnHistory.size)
        assertEquals(0, assembly.store.snapshot().grammarErrors.size)
        assertTrue(warnings.any { it.startsWith("grammar call failed at turn 1") })
        assertTrue(warnings.any { it.startsWith("pronunciation call failed at turn 1") })
    }

    @Test
    fun nothingConfiguredFailsLoudlyNotSilently() {
        // The fourth way to ship a slow path that does nothing: no endpoint. It must not look
        // like a quiet turn.
        val warnings = mutableListOf<String>()
        val assembly = SlowPathAssembly.build(
            language = TutorLanguage.KOREAN,            backing = null, endpoint = null,
            onStateChanged = {}, warn = { warnings += it },
        )
        assembly.dispatch(SlowPathTask(turnId = 1, userTranscript = "어제 친구가 만났어요"))
        assertTrue(warnings.any { it.contains("no client configured") })
    }
}
