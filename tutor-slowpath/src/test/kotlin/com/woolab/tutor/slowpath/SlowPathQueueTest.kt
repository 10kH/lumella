package com.woolab.tutor.slowpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowPathQueueTest {

    @Test
    fun turnTrackerIsMonotonic() {
        val t = TurnTracker()
        assertEquals(0, t.current())
        assertEquals(1, t.next())
        assertEquals(2, t.next())
        assertEquals(2, t.current())
    }

    @Test
    fun queueIsFifoAndNonBlockingPoll() {
        val q = SlowPathQueue()
        assertTrue(q.isEmpty())
        assertNull(q.poll())

        q.enqueue(SlowPathTask(turnId = 1, userTranscript = "hello", imageBase64 = "img1"))
        q.enqueue(SlowPathTask(turnId = 2, userTranscript = "how are you"))
        assertEquals(2, q.size())

        val first = q.poll()
        assertEquals(1, first?.turnId)
        assertEquals("img1", first?.imageBase64)
        assertEquals(2, q.poll()?.turnId)
        assertTrue(q.isEmpty())
    }
}
