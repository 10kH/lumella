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
 * The store's whole reason to exist is that the tutor remembers across launches. These prove
 * the codec is lossless, that a fresh store reads what the previous one wrote, and that a
 * corrupt or foreign file is ignored rather than crashing boot.
 */
class LearnerStatePersistenceTest {

    private fun tmpFile(): File = Files.createTempFile("learner-state", ".json").toFile().also { it.delete() }

    private fun fullState() = LearnerState(
        profile = Profile(l1 = "ko", proficiencyEstimate = "B1", goals = listOf("cafe", "travel")),
        grammarErrors = listOf(
            ErrorRecord("goed", "irregular-past", "went", turnId = 3),
            ErrorRecord("have eaten yesterday", "tense-aspect", "ate yesterday", turnId = 5, status = CorrectionStatus.DELIVERED),
        ),
        pronFluency = PronFluency(lastScore = 0.72, problemPhonemes = listOf("θ", "ɹ"), wpm = 110.5, pauseRatio = 0.18),
        vocabTargets = listOf(VocabTarget("bustling", "market", introduced = true)),
        visualContext = listOf(VisualContextItem(turnId = 4, caption = "fruit market", groundedObjects = listOf("apples", "bananas"))),
        deferredCorrections = listOf(Correction("Try: I went to the store.", priority = 2, sourceAgent = "grammar", turnId = 3, age = 1)),
        turnHistory = listOf(TurnRecord(3, "I goed to the store", "Oh, you went shopping!", imageAttached = false)),
        ruleGap = "irregular past tense: adds -ed to strong verbs",
        practiceTargets = listOf("went", "ate", "bought"),
        lastConsolidatedTurnId = 6,
        revision = 9,
    )

    @Test
    fun codec_roundtrip_is_lossless() {
        val original = fullState()
        val decoded = LearnerStateCodec.decode(LearnerStateCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun codec_roundtrip_preserves_nulls_and_empties() {
        val original = LearnerState()  // all defaults, all nullable fields null
        val decoded = LearnerStateCodec.decode(LearnerStateCodec.encode(original))
        assertEquals(original, decoded)
        assertNull(decoded!!.pronFluency.lastScore)
    }

    @Test
    fun codec_reads_a_file_written_before_the_diagnosis_fields_existed() {
        // Devices already carry schema-1 files without ruleGap/practiceTargets; they must load.
        val old = """{"schema":1,"revision":4,"profile":{"l1":"ko","proficiencyEstimate":"unknown","goals":[]},"grammarErrors":[{"span":"goed","type":"t","recast":"went","turnId":1,"status":"NEW"}],"pronFluency":{"problemPhonemes":[]},"vocabTargets":[],"visualContext":[],"deferredCorrections":[],"turnHistory":[]}"""
        val s = LearnerStateCodec.decode(old)
        assertNotNull(s)
        assertNull(s!!.ruleGap)
        assertEquals(emptyList<String>(), s.practiceTargets)
        assertEquals(0, s.lastConsolidatedTurnId)
        assertEquals(1, s.grammarErrors.size)
    }

    @Test
    fun codec_rejects_wrong_schema_and_garbage() {
        assertNull(LearnerStateCodec.decode("""{"schema":999,"revision":1}"""))
        assertNull(LearnerStateCodec.decode("not json at all"))
        assertNull(LearnerStateCodec.decode(""))
    }

    @Test
    fun a_fresh_store_reads_what_the_previous_one_wrote() {
        val file = tmpFile()
        try {
            val first = LearnerStateStore(backing = file)
            first.apply(StateDelta(sourceTurnId = 1, addGrammarErrors = listOf(ErrorRecord("goed", "past", "went", 1))))
            first.apply(StateDelta(sourceTurnId = 2, addVocabTargets = listOf(VocabTarget("bustling", "market"))))
            assertTrue("file should exist after publish", file.isFile)

            val second = LearnerStateStore(backing = file)
            val restored = second.snapshot()
            assertEquals(2, restored.revision)
            assertEquals(1, restored.grammarErrors.size)
            assertEquals("went", restored.grammarErrors[0].recast)
            assertEquals("bustling", restored.vocabTargets[0].word)
        } finally {
            file.delete(); File(file.parentFile, file.name + ".tmp").delete()
        }
    }

    @Test
    fun update_path_also_persists() {
        val file = tmpFile()
        try {
            val first = LearnerStateStore(backing = file)
            first.update { it.copy(profile = it.profile.copy(proficiencyEstimate = "B2")) }
            val second = LearnerStateStore(backing = file)
            assertEquals("B2", second.snapshot().profile.proficiencyEstimate)
        } finally {
            file.delete()
        }
    }

    @Test
    fun corrupt_file_is_ignored_not_fatal() {
        val file = tmpFile()
        try {
            file.writeText("{{{ definitely not our schema")
            val store = LearnerStateStore(backing = file)
            assertEquals("falls back to the initial state", LearnerState(), store.snapshot())
            // And the next publish overwrites the garbage with something readable.
            store.apply(StateDelta(sourceTurnId = 1))
            assertNotNull(LearnerStateCodec.decode(file.readText()))
        } finally {
            file.delete()
        }
    }

    @Test
    fun no_backing_file_means_memory_only_as_before() {
        val store = LearnerStateStore()
        store.apply(StateDelta(sourceTurnId = 1))
        // Nothing to assert on disk; the point is that this path still constructs and works.
        assertEquals(1, store.revision())
        assertFalse(File("learner-state.json").exists())
    }
    @Test
    fun a_relaunch_continues_turn_numbering_so_the_cadence_stays_alive() {
        // The bug this guards: every persisted record carries a turn id, and the consolidate
        // cadence measures turnId - lastConsolidatedTurnId. A tracker restarting at 1 after a
        // relaunch against a persisted lastConsolidatedTurnId of 6 makes turnsSince negative
        // for six turns — the slow layer is silent and a standing diagnosis cannot clear.
        val f = tmpFile()
        // fullState() already carries lastConsolidatedTurnId = 6 and history up to turn 3
        val first = LearnerStateStore(fullState(), backing = f)
        first.apply(StateDelta(sourceTurnId = 6))   // publish to disk

        val relaunched = LearnerStateStore(backing = f)
        val tracker = TurnTracker(seed = relaunched.snapshot().highestTurnId())

        assertEquals(6, tracker.current())
        assertEquals(7, tracker.next())
        // and the dispatcher's cadence sees a fresh window, not a negative one
        val turnsSince = tracker.current() - relaunched.snapshot().lastConsolidatedTurnId
        assertEquals(1, turnsSince)
    }
    @Test
    fun a_corrupt_file_is_reported_not_just_ignored() {
        val f = tmpFile(); f.writeText("{ this is not json")
        val warnings = mutableListOf<String>()
        val store = LearnerStateStore(backing = f, warn = { warnings += it })

        assertEquals(0, store.snapshot().revision)                          // started empty, as before
        assertTrue(warnings.any { it.contains("unreadable") || it.contains("rejected") })  // but said so
    }

    @Test
    fun a_failed_persist_is_reported_not_swallowed() {
        // A directory where the file should be: every write fails.
        val dir = Files.createTempDirectory("learner-state-dir").toFile()
        val warnings = mutableListOf<String>()
        val store = LearnerStateStore(backing = dir, warn = { warnings += it })

        store.apply(StateDelta(sourceTurnId = 1))

        assertEquals(1, store.snapshot().revision)                          // in-memory state advanced
        assertTrue(warnings.any { it.startsWith("learner-state persist failed at revision 1") })
    }
    @Test
    fun a_lost_session_does_not_hand_out_a_turn_id_the_record_already_holds() {
        // The Wi-Fi outage run wrote two transcripts under turn 1: the gate refused the
        // turn that arrived during the outage, the tracker did not move, and the next
        // published turn reused the number. Ids are persisted; they must stay unique.
        val tracker = TurnTracker(seed = 0)
        val first = tracker.next()           // 1, published
        tracker.markSessionLost()            // a turn arrived and was refused
        val next = tracker.next()

        assertEquals(1, first)
        assertTrue("next id must be past the refused one", next > first + 1)
    }
}
