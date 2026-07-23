package com.woolab.lumella.agents

import com.woolab.lumella.slowpath.SlowPathTask
import com.woolab.lumella.state.CorrectionStatus
import com.woolab.lumella.util.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PedagogyAgentsTest {

    private fun chatResponse(contentJson: String): String {
        // content is itself a JSON string -> escape quotes for embedding.
        val escaped = contentJson.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"choices":[{"message":{"content":"$escaped"}}]}"""
    }

    @Test
    fun miniJsonParsesNestedStructures() {
        val v = MiniJson.asObject(MiniJson.parse("""{"a":[1,2],"b":{"c":"x"},"d":true,"e":null}"""))
        assertEquals(2, MiniJson.asArray(v?.get("a"))?.size)
        assertEquals("x", MiniJson.string(MiniJson.asObject(v?.get("b")), "c"))
        assertEquals(true, v?.get("d"))
        assertTrue(v!!.containsKey("e"))
        assertNull(v["e"])
    }

    @Test
    fun miniJsonReturnsNullOnMalformed() {
        assertNull(MiniJson.parse("{not valid"))
    }

    @Test
    fun grammarAgentProducesErrorRecordsAndCorrectionsWithTurnId() {
        val task = SlowPathTask(turnId = 7, userTranscript = "I goed home")
        val body = chatResponse("""{"errors":[{"span":"I goed","type":"verb tense","recast":"I went"}]}""")
        val delta = GrammarAgent().toStateDelta(body, task)

        assertEquals(7, delta.sourceTurnId)
        assertEquals(1, delta.addGrammarErrors.size)
        val err = delta.addGrammarErrors.first()
        assertEquals("I went", err.recast)
        assertEquals(7, err.turnId)
        assertEquals(CorrectionStatus.NEW, err.status)
        assertEquals(1, delta.addDeferredCorrections.size)
        assertEquals("grammar", delta.addDeferredCorrections.first().sourceAgent)
        assertEquals(7, delta.addDeferredCorrections.first().turnId)
    }

    @Test
    fun grammarAgentEmptyErrorsYieldsNoCorrections() {
        val task = SlowPathTask(turnId = 1, userTranscript = "I went home")
        val delta = GrammarAgent().toStateDelta(chatResponse("""{"errors":[]}"""), task)
        assertTrue(delta.addGrammarErrors.isEmpty())
        assertTrue(delta.addDeferredCorrections.isEmpty())
    }

    @Test
    fun pronunciationAgentMapsProblemPhonemes() {
        val task = SlowPathTask(turnId = 3, userTranscript = "I think")
        val body = chatResponse("""{"problemPhonemes":["th"],"notes":"Mind the th sound"}""")
        val delta = PronunciationFluencyAgent().toStateDelta(body, task)
        assertEquals(listOf("th"), delta.pronFluency?.problemPhonemes)
        assertEquals(1, delta.addDeferredCorrections.size)
        assertEquals("pronunciation", delta.addDeferredCorrections.first().sourceAgent)
    }

    @Test
    fun visualContextAgentMapsCaptionAndObjects() {
        val task = SlowPathTask(turnId = 5, userTranscript = "what is this", imageBase64 = "img")
        val body = chatResponse("""{"caption":"a red apple on a table","groundedObjects":["apple","table"]}""")
        val delta = VisualContextAgent().toStateDelta(body, task)
        assertEquals(1, delta.addVisualContext.size)
        val vc = delta.addVisualContext.first()
        assertEquals(5, vc.turnId)
        assertEquals("a red apple on a table", vc.caption)
        assertEquals(listOf("apple", "table"), vc.groundedObjects)
    }

    @Test
    fun coalescerMergesPerTurnDeltasAndOrdersByPriority() {
        val task = SlowPathTask(turnId = 9, userTranscript = "I goed")
        val grammar = GrammarAgent().toStateDelta(
            chatResponse("""{"errors":[{"span":"I goed","type":"tense","recast":"I went"}]}"""), task,
        )
        val pron = PronunciationFluencyAgent().toStateDelta(
            chatResponse("""{"problemPhonemes":["t"],"notes":"t sound"}"""), task,
        )
        val visual = VisualContextAgent().toStateDelta(
            chatResponse("""{"caption":"a street","groundedObjects":["car"]}"""), task,
        )

        val merged = SlowPathCoalescer.coalesce(listOf(grammar, pron, visual))!!
        assertEquals(9, merged.sourceTurnId)
        assertEquals(1, merged.addGrammarErrors.size)
        assertEquals(1, merged.addVisualContext.size)
        assertEquals(listOf("t"), merged.pronFluency?.problemPhonemes)
        assertEquals(2, merged.addDeferredCorrections.size)
        // pronunciation priority 1 before grammar priority 2
        assertEquals("pronunciation", merged.addDeferredCorrections.first().sourceAgent)
    }

    @Test
    fun coalescerRejectsMismatchedTurns() {
        val a = GrammarAgent().toStateDelta(chatResponse("""{"errors":[]}"""), SlowPathTask(1, "x"))
        val b = GrammarAgent().toStateDelta(chatResponse("""{"errors":[]}"""), SlowPathTask(2, "y"))
        assertThrows(IllegalArgumentException::class.java) {
            SlowPathCoalescer.coalesce(listOf(a, b))
        }
    }

    @Test
    fun coalescerEmptyReturnsNull() {
        assertNull(SlowPathCoalescer.coalesce(emptyList()))
    }
}
