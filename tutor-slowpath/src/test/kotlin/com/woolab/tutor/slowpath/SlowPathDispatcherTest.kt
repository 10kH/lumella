package com.woolab.tutor.slowpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPathDispatcherTest {

    /** Fake client: returns a canned chat-completions body per role, synchronously. */
    private class FakeClient(private val byRole: Map<String, String>) : PedagogyAgentClient {
        val calledRoles = mutableListOf<String>()
        override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
            calledRoles.add(role)
            val content = byRole[role] ?: "{}"
            val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
            callback(Result.success("""{"choices":[{"message":{"content":"$escaped"}}]}"""))
        }
    }

    private fun orchestrator(store: LearnerStateStore) =
        StateGraphOrchestrator(TutorLanguage.KOREAN, store, StalenessGuard(2, 4), AblationMode.FULL)

    @Test
    fun consolidateCadenceFiresOnTurnsOrOnErrorCount() {
        val should = SlowPathDispatcher::shouldConsolidate
        val err = { t: Int -> ErrorRecord("x", "t", "y", turnId = t) }
        // Nothing recorded and nothing diagnosed: never.
        assertFalse(should(LearnerState(), 10, 3, 4))
        // A standing diagnosis with NO new errors still re-examines on cadence, so it can clear.
        val diagnosed = LearnerState(ruleGap = "x", lastConsolidatedTurnId = 3)
        assertFalse(should(diagnosed, 5, 3, 4))
        assertTrue(should(diagnosed, 6, 3, 4))
        // 1 error, 2 turns since last consolidation: not yet (K=3).
        val s2 = LearnerState(grammarErrors = listOf(err(2)), lastConsolidatedTurnId = 0)
        assertFalse(should(s2, 2, 3, 4))
        // 1 error, 3 turns since: yes.
        assertTrue(should(s2, 3, 3, 4))
        // 4 new errors in 2 turns: yes, even though K not reached (N=4).
        val s4 = LearnerState(grammarErrors = listOf(err(1), err(1), err(2), err(2)), lastConsolidatedTurnId = 0)
        assertTrue(should(s4, 2, 3, 4))
        // Errors before the last consolidation do not count toward N.
        val sOld = LearnerState(grammarErrors = listOf(err(1), err(1), err(1), err(1), err(6)), lastConsolidatedTurnId = 5)
        assertFalse(should(sOld, 6, 3, 4))
    }

    @Test
    fun consolidateFiresAfterKTurnsWithTheAccumulatedStateAndReplacesTheDiagnosis() {
        val store = LearnerStateStore()
        val seen = mutableListOf<String>()
        val client = object : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
                seen.add(role)
                val content = when (role) {
                    "grammar" -> """{"errors":[{"span":"goed","type":"irregular past","recast":"went"}]}"""
                    "pronunciation" -> """{"problemPhonemes":[],"notes":""}"""
                    "consolidate" -> {
                        // The agent must have received the accumulated record, not one turn.
                        assertTrue("consolidate gets the serialised state", task.userTranscript.contains("errorsSinceLastDiagnosis"))
                        assertTrue(task.userTranscript.contains("goed"))
                        """{"ruleGap":"irregular past tense: adds -ed to strong verbs","evidence":["goed"],"practiceTargets":["went","ate","bought"],"proficiencyEstimate":"A2","vocabTargets":[]}"""
                    }
                    else -> "{}"
                }
                val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                callback(Result.success("""{"choices":[{"message":{"content":"$escaped"}}]}"""))
            }
        }
        val dispatcher = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store), consolidateAgent = ConsolidateAgent(), consolidateEveryTurns = 3, consolidateOnErrorCount = 4)

        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "어제 학교에 가요"))
        dispatcher.dispatch(SlowPathTask(turnId = 2, userTranscript = "어제 학교에 가요"))
        assertFalse("not before K turns", seen.contains("consolidate"))
        assertEquals(null, store.snapshot().ruleGap)

        dispatcher.dispatch(SlowPathTask(turnId = 3, userTranscript = "어제 학교에 가요"))
        assertEquals(1, seen.count { it == "consolidate" })
        val s = store.snapshot()
        assertEquals("irregular past tense: adds -ed to strong verbs", s.ruleGap)
        assertEquals(listOf("went", "ate", "bought"), s.practiceTargets)
        assertEquals("A2", s.profile.proficiencyEstimate)
        assertEquals(3, s.lastConsolidatedTurnId)

        // Cadence restarts from the consolidation turn.
        dispatcher.dispatch(SlowPathTask(turnId = 4, userTranscript = "어제 학교에 가요"))
        dispatcher.dispatch(SlowPathTask(turnId = 5, userTranscript = "어제 학교에 가요"))
        assertEquals(1, seen.count { it == "consolidate" })
        dispatcher.dispatch(SlowPathTask(turnId = 6, userTranscript = "어제 학교에 가요"))
        assertEquals(2, seen.count { it == "consolidate" })
    }

    @Test
    fun grammarAgentRecordsErrorsWithoutPerTurnCorrectionsWhenConsolidating() {
        val body = """{"choices":[{"message":{"content":"{\"errors\":[{\"span\":\"goed\",\"type\":\"tense\",\"recast\":\"went\"}]}"}}]}"""
        val task = SlowPathTask(turnId = 1, userTranscript = "어제 학교에 가요")
        // FULL with consolidation: record only.
        val recorded = GrammarAgent(emitDeferredCorrections = false).toStateDelta(body, task)
        assertEquals(1, recorded.addGrammarErrors.size)
        assertEquals(0, recorded.addDeferredCorrections.size)
        // Ablations without consolidation: the old per-turn "Try:" path is intact.
        val legacy = GrammarAgent(emitDeferredCorrections = true).toStateDelta(body, task)
        assertEquals(1, legacy.addGrammarErrors.size)
        assertEquals(1, legacy.addDeferredCorrections.size)
        assertTrue(legacy.addDeferredCorrections[0].text.startsWith("Try:"))
    }

    @Test
    fun dispatcherDefaultKeepsPerTurnCorrectionsAndOptInConsolidationDropsThem() {
        val client = FakeClient(mapOf("grammar" to """{"errors":[{"span":"goed","type":"t","recast":"went"}]}"""))
        // Default (what the pre-registered eval harness constructs): per-turn corrections.
        val legacy = LearnerStateStore()
        SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(legacy)).dispatch(SlowPathTask(turnId = 1, userTranscript = "어제 학교에 가요"))
        assertEquals(1, legacy.snapshot().grammarErrors.size)
        assertEquals(1, legacy.snapshot().deferredCorrections.size)
        // Product opt-in (MainActivity): errors recorded, no per-turn corrections.
        val product = LearnerStateStore()
        SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(product), consolidateAgent = ConsolidateAgent())
            .dispatch(SlowPathTask(turnId = 1, userTranscript = "어제 학교에 가요"))
        assertEquals(1, product.snapshot().grammarErrors.size)
        assertEquals(0, product.snapshot().deferredCorrections.size)
    }

    @Test
    fun failedConsolidateCallIsReportedAndLeavesStateAlone() {
        val store = LearnerStateStore(LearnerState(
            grammarErrors = listOf(ErrorRecord("goed", "t", "went", 1)),
            ruleGap = "live", practiceTargets = listOf("went"), lastConsolidatedTurnId = 0,
        ))
        val warnings = mutableListOf<String>()
        val client = object : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
                when (role) {
                    "consolidate" -> callback(Result.failure(java.io.IOException("502 from vercel")))
                    else -> callback(Result.success("""{"choices":[{"message":{"content":"{}"}}]}"""))
                }
            }
        }
        val d = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store), consolidateAgent = ConsolidateAgent(), consolidateEveryTurns = 3, consolidateOnErrorCount = 4, warn = { warnings.add(it) })
        d.dispatch(SlowPathTask(turnId = 3, userTranscript = "네 괜찮아요"))
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("consolidate call failed at turn 3"))
        assertTrue(warnings[0].contains("502"))
        val s = store.snapshot()
        assertEquals("live", s.ruleGap)
        assertEquals(0, s.lastConsolidatedTurnId) // cadence anchor untouched, so it retries next turn
    }

    @Test
    fun quietTurnStillRunsTheCadenceSoADiagnosisCanClear() {
        // Agents return nothing (no delta at all); the cadence must still fire.
        val store = LearnerStateStore(LearnerState(ruleGap = "stale", practiceTargets = listOf("x"), lastConsolidatedTurnId = 3))
        val seen = mutableListOf<String>()
        val client = object : PedagogyAgentClient {
            override fun analyze(role: String, task: SlowPathTask, callback: (Result<String>) -> Unit) {
                seen.add(role)
                val content = if (role == "consolidate") """{"ruleGap":null,"evidence":[],"practiceTargets":[],"proficiencyEstimate":null,"vocabTargets":[]}""" else "{}"
                val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"")
                callback(Result.success("""{"choices":[{"message":{"content":"$escaped"}}]}"""))
            }
        }
        val d = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store), consolidateAgent = ConsolidateAgent(), consolidateEveryTurns = 3, consolidateOnErrorCount = 4, warn = {})
        d.dispatch(SlowPathTask(turnId = 6, userTranscript = "오늘 정말 좋은 하루였어요"))
        assertTrue("consolidate fired on a quiet turn", seen.contains("consolidate"))
        assertEquals(null, store.snapshot().ruleGap)
        assertEquals(6, store.snapshot().lastConsolidatedTurnId)
    }

    @Test
    fun garbageOrShapelessConsolidateResponseLeavesTheDiagnosisAlone() {
        val agent = ConsolidateAgent()
        val before = LearnerState(ruleGap = "live gap", practiceTargets = listOf("went"), lastConsolidatedTurnId = 3)
        val task = SlowPathTask(turnId = 6, userTranscript = "")
        val cases = listOf(
            "not json at all",
            """{"choices":[{"message":{"content":"{{{ truncated"}}]}""",
            """{"choices":[{"message":{"content":"{}"}}]}""",                       // parsed, no ruleGap key
            """{"choices":[{"message":{"content":"{\"evidence\":[]}"}}]}""",   // parsed, other keys only
            """{"error":{"message":"rate limited"}}""",
        )
        for (body in cases) {
            val delta = agent.toStateDelta(body, task)
            assertFalse("must not be a consolidation: $body", delta.consolidated)
            val after = delta.applyTo(before)
            assertEquals("ruleGap survives: $body", "live gap", after.ruleGap)
            assertEquals(listOf("went"), after.practiceTargets)
            assertEquals("cadence anchor untouched: $body", 3, after.lastConsolidatedTurnId)
        }
    }

    @Test
    fun serialiseSendsOnlyErrorsSinceTheLastDiagnosis() {
        val err = { t: Int, s: String -> ErrorRecord(s, "t", "r", turnId = t) }
        val state = LearnerState(
            grammarErrors = listOf(err(1, "goed"), err(2, "eated"), err(5, "a apple")),
            ruleGap = "irregular past",
            lastConsolidatedTurnId = 3,
        )
        val text = ConsolidateAgent.serialise(state, currentTurnId = 6)
        assertTrue(text.contains("currentTurn: 6"))
        assertTrue(text.contains("previousRuleGap: irregular past (diagnosed at turn 3)"))
        assertTrue("error after the diagnosis is sent", text.contains("a apple"))
        assertFalse("errors before the diagnosis are NOT re-sent", text.contains("goed"))
        assertFalse(text.contains("eated"))
        // No new errors at all: the model is told so explicitly.
        val quiet = state.copy(grammarErrors = state.grammarErrors.take(2))
        assertTrue(ConsolidateAgent.serialise(quiet, 6).contains("(none)"))
    }

    @Test
    fun consolidateWithNullRuleGapClearsAStaleDiagnosis() {
        val agent = ConsolidateAgent()
        val body = """{"choices":[{"message":{"content":"{\"ruleGap\":null,\"evidence\":[],\"practiceTargets\":[\"went\"],\"proficiencyEstimate\":\"B1\",\"vocabTargets\":[]}"}}]}"""
        val delta = agent.toStateDelta(body, SlowPathTask(turnId = 9, userTranscript = ""))
        assertTrue(delta.consolidated)
        assertEquals(null, delta.ruleGap)
        assertEquals(emptyList<String>(), delta.practiceTargets) // no gap, no targets
        val before = LearnerState(ruleGap = "old gap", practiceTargets = listOf("x"), lastConsolidatedTurnId = 3)
        val after = delta.applyTo(before)
        assertEquals(null, after.ruleGap)
        assertEquals(emptyList<String>(), after.practiceTargets)
        assertEquals(9, after.lastConsolidatedTurnId)
        assertEquals("B1", after.profile.proficiencyEstimate)
    }

    @Test
    fun theEvalHarnessCanOptOutOfTheLanguageGate() {
        // The pre-registered corpus contains a deliberate English code-switch turn; gating it
        // silently moved the ablation numbers. The product keeps the gate, the study does not.
        val client = FakeClient(mapOf("grammar" to """{"errors":[{"span":"x","type":"t","recast":"y"}]}"""))
        val gated = LearnerStateStore()
        SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(gated))
            .dispatch(SlowPathTask(turnId = 1, userTranscript = "How do you say that in Korean?"))
        assertEquals(0, gated.snapshot().grammarErrors.size)

        val study = LearnerStateStore()
        SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(study), gateOtherLanguages = false)
            .dispatch(SlowPathTask(turnId = 1, userTranscript = "How do you say that in Korean?"))
        assertEquals(1, study.snapshot().grammarErrors.size)
    }

    @Test
    fun theTurnItselfIsRecordedSoTheDiagnosisSeesTheSentences() {
        val store = LearnerStateStore()
        val client = FakeClient(mapOf("grammar" to """{"errors":[{"span":"a apple","type":"article","recast":"an apple"}]}"""))
        SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store))
            .dispatch(SlowPathTask(turnId = 4, userTranscript = "사과가 먹었어요", ellaTranscript = "사과를 드셨군요!"))
        val h = store.snapshot().turnHistory
        assertEquals(1, h.size)
        assertEquals(4, h[0].turnId)
        assertEquals("사과가 먹었어요", h[0].userTranscript)
        // and serialise carries it to the model, windowed like the errors
        val text = ConsolidateAgent.serialise(store.snapshot(), currentTurnId = 5)
        assertTrue(text.contains("learnerTurnsSinceLastDiagnosis"))
        assertTrue(text.contains("사과가 먹었어요"))
    }

    @Test
    fun languageGateIsLooseOnEnglishAndStrictOnOtherScripts() {
        val ok = TutorLanguage.KOREAN.isPlausibly
        assertTrue(ok("어제 친구를 만나요"))                              // plain Korean
        assertTrue(ok("친구랑 pizza 먹었어요"))                          // one borrowed noun: 2 of 3 words Korean
        assertTrue(ok("저는 Jennifer예요"))                             // a name with a Korean ending is a Korean word
        assertTrue(ok("이거 iPhone이에요"))                              // same
        assertTrue(ok("오늘은 정말 busy한 하루였어요"))                   // realistic learner code-switch
        assertFalse(ok("I said 안녕하세요 to her"))                      // 1 of 5 words Korean
        assertFalse(ok("My friend and I ate tteokbokki"))              // English sentence
        assertFalse(ok("被性命困佔的"))                                 // Chinese
        assertFalse(ok("怒りを感じる"))                                  // Japanese
        assertFalse(ok(""))
        assertFalse(ok("... !!! 123"))                                   // no letters at all
    }

    @Test
    fun nonEnglishTurnSkipsLanguageAgentsButStillRunsVisualOnAPhoto() {
        val store = LearnerStateStore()
        val client = FakeClient(
            mapOf(
                "grammar" to """{"errors":[{"span":"부산 갈 수 있게","type":"spacing","recast":"부산에 갈 수 있도록"}]}""",
                "visual" to """{"caption":"a desk","groundedObjects":["laptop"]}""",
            ),
        )
        val dispatcher = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store))

        // English transcript, no photo: nothing fires, nothing is recorded.
        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "so people can get to Busan comfortably"))
        assertEquals(emptyList<String>(), client.calledRoles)
        assertEquals(0, store.snapshot().revision)
        assertEquals(0, store.snapshot().grammarErrors.size)

        // English transcript WITH a photo: only the visual agent fires.
        dispatcher.dispatch(SlowPathTask(turnId = 2, userTranscript = "what is this", imageBase64 = "img"))
        assertEquals(listOf("visual"), client.calledRoles)
        assertEquals(1, store.snapshot().visualContext.size)
        assertEquals(0, store.snapshot().grammarErrors.size)
    }

    @Test
    fun dispatchFiresAgentsCoalescesAndAppliesOneRevision() {
        val store = LearnerStateStore()
        val client = FakeClient(
            mapOf(
                "grammar" to """{"errors":[{"span":"I goed","type":"tense","recast":"I went"}]}""",
                "pronunciation" to """{"problemPhonemes":["t"],"notes":"t sound"}""",
                "visual" to """{"caption":"a park","groundedObjects":["tree"]}""",
            ),
        )
        val dispatcher = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store))

        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "어제 학교에 가요", imageBase64 = "img"))

        val snap = store.snapshot()
        // 3 agents fired; coalesced into exactly one apply (single revision bump).
        assertEquals(listOf("grammar", "pronunciation", "visual"), client.calledRoles)
        assertEquals(1, snap.revision)
        assertEquals(1, snap.grammarErrors.size)
        assertEquals(1, snap.visualContext.size)
        assertEquals(listOf("t"), snap.pronFluency.problemPhonemes)
        // grammar + pronunciation each produced a deferred correction.
        assertEquals(2, snap.deferredCorrections.size)
    }

    @Test
    fun visualAgentSkippedWhenNoImage() {
        val store = LearnerStateStore()
        val client = FakeClient(mapOf("grammar" to """{"errors":[]}""", "pronunciation" to """{"problemPhonemes":[]}"""))
        val dispatcher = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store))

        dispatcher.dispatch(SlowPathTask(turnId = 1, userTranscript = "저는 괜찮아요"))

        assertTrue("visual agent must not fire without an image", "visual" !in client.calledRoles)
        assertEquals(setOf("grammar", "pronunciation"), client.calledRoles.toSet())
    }

    @Test
    fun drainProcessesAllQueuedTurns() {
        val store = LearnerStateStore()
        val client = FakeClient(mapOf("grammar" to """{"errors":[{"span":"a","type":"t","recast":"b"}]}""", "pronunciation" to """{"problemPhonemes":[]}"""))
        val dispatcher = SlowPathDispatcher(TutorLanguage.KOREAN, client, orchestrator(store))
        val queue = SlowPathQueue()
        queue.enqueue(SlowPathTask(1, "첫 번째"))
        queue.enqueue(SlowPathTask(2, "두 번째"))

        dispatcher.drain(queue)

        assertTrue(queue.isEmpty())
        assertEquals(2, store.snapshot().revision) // one apply per turn
    }
    @Test
    fun theEnglishGateIsTheMirrorImage() {
        // Same seam, other direction: any Hangul, CJK or kana means the mic caught something
        // other than the learner's English; half the letters must be Latin.
        val ok = TutorLanguage.ENGLISH.isPlausibly
        assertTrue(ok("Yesterday I go to the park"))
        assertTrue(ok("My friend Minjun and I ate tteokbokki"))   // romanised Korean is fine
        assertFalse(ok("I said 안녕하세요 to her"))                // any Hangul disqualifies
        assertFalse(ok("어제 친구를 만나요"))
        assertFalse(ok("被性命困佔的"))
        assertFalse(ok("怒りを感じる"))
        assertFalse(ok(""))
        assertFalse(ok("... !!! 123"))
    }
}
