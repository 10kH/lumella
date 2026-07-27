package com.woolab.lumella.slowpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnEvidenceAssemblerTest {

    @Test
    fun assembleAttachesPendingImageId() {
        val assembler = TurnEvidenceAssembler()
        assembler.setPendingImageId("img-1")

        val evidence = assembler.assemble(turnId = 1, transcript = "hello")

        assertEquals(1, evidence.turnId)
        assertEquals("hello", evidence.learnerTranscript)
        assertEquals("img-1", evidence.imageId)
    }

    @Test
    fun imageIdIsConsumedOnceAndNotReusedOnNextTurn() {
        val assembler = TurnEvidenceAssembler()
        assembler.setPendingImageId("img-1")

        val first = assembler.assemble(turnId = 1, transcript = "first turn")
        val second = assembler.assemble(turnId = 2, transcript = "second turn")

        assertEquals("img-1", first.imageId)
        assertNull(second.imageId)
    }

    @Test
    fun assembleWithoutPendingImageIdYieldsNullImageId() {
        val assembler = TurnEvidenceAssembler()

        val evidence = assembler.assemble(turnId = 1, transcript = "no photo")

        assertNull(evidence.imageId)
    }

    @Test
    fun blankImageIdIsTreatedAsAbsent() {
        val assembler = TurnEvidenceAssembler()
        assembler.setPendingImageId("")

        assertNull(assembler.peekPendingImageId())
        val evidence = assembler.assemble(turnId = 1, transcript = "blank photo id")

        assertNull(evidence.imageId)
    }
}
