package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePolicyTest {

    @Test
    fun `a photo request is allowed`() {
        assertEquals(CapturePolicy.Decision.Capture, CapturePolicy().decide("capture_photo"))
    }

    @Test
    fun `an unimplemented tool is refused rather than ignored`() {
        // Ignoring it is the tempting option and the wrong one: a tool call ends the response
        // that emitted it, so a silent return leaves the model waiting forever.
        val decision = CapturePolicy().decide("send_email")
        assertTrue(decision is CapturePolicy.Decision.Refuse)
        assertEquals("unknown_tool", (decision as CapturePolicy.Decision.Refuse).reason)
        assertTrue(decision.payload.contains(""""status":"error""""))
    }

    @Test
    fun `an unimplemented tool does not consume the retry budget`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 2)
        repeat(5) { policy.decide("send_email") }
        assertEquals("refusals must not burn the budget", 0, policy.attemptsSoFar())
        assertEquals(CapturePolicy.Decision.Capture, policy.decide("capture_photo"))
    }

    @Test
    fun `repeated looking stops at the ceiling`() {
        // In a dark room every photo is unusable and the persona asks to try again, so the
        // loop has no floor of its own. Each round costs a realtime response and a camera bind.
        val policy = CapturePolicy(maxCapturesPerUtterance = 2)

        assertEquals(CapturePolicy.Decision.Capture, policy.decide("capture_photo"))
        assertEquals(CapturePolicy.Decision.Capture, policy.decide("capture_photo"))

        val third = policy.decide("capture_photo")
        assertTrue(third is CapturePolicy.Decision.Refuse)
        assertEquals("too_many_attempts", (third as CapturePolicy.Decision.Refuse).reason)
    }

    @Test
    fun `the ceiling stays shut until the learner speaks again`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 1)
        policy.decide("capture_photo")
        assertTrue(policy.decide("capture_photo") is CapturePolicy.Decision.Refuse)
        assertTrue(policy.decide("capture_photo") is CapturePolicy.Decision.Refuse)

        policy.onLearnerSpoke()
        assertEquals(CapturePolicy.Decision.Capture, policy.decide("capture_photo"))
    }

    @Test
    fun `every refusal carries a payload the model can act on`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 0)
        for (name in listOf("capture_photo", "nonsense")) {
            val decision = policy.decide(name)
            assertTrue("$name should be refused", decision is CapturePolicy.Decision.Refuse)
            val payload = (decision as CapturePolicy.Decision.Refuse).payload
            assertTrue("payload not JSON-ish: $payload", payload.startsWith("{") && payload.endsWith("}"))
            assertTrue(payload.contains(decision.reason))
        }
    }

    @Test
    fun `the attempt count reports attempts, loops included`() {
        // The number is here to answer "is the model looping", so refused attempts count.
        val policy = CapturePolicy(maxCapturesPerUtterance = 2)
        repeat(5) { policy.decide("capture_photo") }
        assertEquals(5, policy.attemptsSoFar())
    }

    @Test
    fun `a refusal payload stays valid JSON`() {
        val payload = (CapturePolicy().decide("nope") as CapturePolicy.Decision.Refuse).payload
        assertEquals("""{"status":"error","reason":"unknown_tool"}""", payload)
    }

    // --- Concurrency: decide() and onLearnerSpoke() are reachable from different threads
    // (server-event thread vs. wherever a turn boundary is detected) at once. ---

    @Test
    fun `concurrent decide calls never exceed the ceiling and every caller is answered`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 3)
        val threadCount = 50
        val ready = java.util.concurrent.CountDownLatch(threadCount)
        val go = java.util.concurrent.CountDownLatch(1)
        val decisions = java.util.concurrent.ConcurrentLinkedQueue<CapturePolicy.Decision>()
        val threads = (0 until threadCount).map {
            Thread {
                ready.countDown()
                go.await()
                decisions.add(policy.decide("capture_photo"))
            }
        }
        threads.forEach { it.isDaemon = true; it.start() }
        ready.await()
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals("every tool call must be answered", threadCount, decisions.size)
        val captures = decisions.count { it == CapturePolicy.Decision.Capture }
        assertEquals("the ceiling must be exact under contention, not merely 'not exceeded'", 3, captures)
        val refusals = decisions.count { it is CapturePolicy.Decision.Refuse }
        assertEquals(threadCount - 3, refusals)
        assertTrue(
            "every refusal past the ceiling must still carry the too-many-attempts reason",
            decisions.filterIsInstance<CapturePolicy.Decision.Refuse>()
                .all { it.reason == CapturePolicy.REASON_TOO_MANY },
        )
    }

    @Test
    fun `onLearnerSpoke racing decide never lets the counter go negative or throws`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 2)
        val iterations = 500
        val decisions = java.util.concurrent.ConcurrentLinkedQueue<CapturePolicy.Decision>()
        val threads = listOf(
            Thread { repeat(iterations) { decisions.add(policy.decide("capture_photo")) } },
            Thread { repeat(iterations) { policy.onLearnerSpoke() } },
            Thread { repeat(iterations) { decisions.add(policy.decide("capture_photo")) } },
        )
        threads.forEach { it.isDaemon = true; it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals("every call across the race must still be answered", iterations * 2, decisions.size)
        assertTrue("the attempt counter must never go negative", policy.attemptsSoFar() >= 0)
        assertTrue(
            "every decision produced under the race is still one of the two known shapes",
            decisions.all { it == CapturePolicy.Decision.Capture || it is CapturePolicy.Decision.Refuse },
        )
    }

    @Test
    fun `concurrent decide calls for an unknown tool never consume the budget even under contention`() {
        val policy = CapturePolicy(maxCapturesPerUtterance = 2)
        val threadCount = 30
        val ready = java.util.concurrent.CountDownLatch(threadCount)
        val go = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until threadCount).map {
            Thread {
                ready.countDown()
                go.await()
                policy.decide("send_email")
            }
        }
        threads.forEach { it.isDaemon = true; it.start() }
        ready.await()
        go.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals("unknown-tool refusals must never burn the budget, even racing", 0, policy.attemptsSoFar())
        assertEquals(CapturePolicy.Decision.Capture, policy.decide("capture_photo"))
    }
}
