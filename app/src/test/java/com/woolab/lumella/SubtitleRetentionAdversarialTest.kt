package com.woolab.lumella

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Ultragoal red-team pass on [SubtitleRetention]'s contract: no stale timer may ever blank
 * CURRENT text, and the current timer must always be able to clear ITS text.
 *
 * This file is deliberately duplicated verbatim (module-relative package aside) between the
 * two glasses apps, same as [SubtitleRetentionDriftTest] pins the class itself — an attack
 * that only exists in one sibling is worth nothing once the other drifts.
 */
class SubtitleRetentionAdversarialTest {

    // ---- 1. Interleavings ----------------------------------------------------------------

    @Test
    fun `arm, text, arm - the second timer is current and must clear`() {
        val retention = SubtitleRetention()
        retention.onSpeechStarted()
        retention.onNewTutorText()
        val second = retention.onSpeechStarted()
        assertTrue("the newest arm is always current", retention.mayClear(second))
    }

    @Test
    fun `arm, arm, text - neither timer survives the text that followed both`() {
        val retention = SubtitleRetention()
        val first = retention.onSpeechStarted()
        val second = retention.onSpeechStarted()
        retention.onNewTutorText()
        assertFalse("first timer predates the text on screen", retention.mayClear(first))
        assertFalse("second timer also predates the text on screen", retention.mayClear(second))
    }

    @Test
    fun `arm, reset, arm - only the post-reset timer clears`() {
        val retention = SubtitleRetention()
        val first = retention.onSpeechStarted()
        retention.onSessionReset()
        val second = retention.onSpeechStarted()
        assertFalse("pre-reset timer belongs to a dead session", retention.mayClear(first))
        assertTrue("post-reset timer belongs to the live session", retention.mayClear(second))
    }

    @Test
    fun `text, arm - a timer armed after the text it is arming for is still current`() {
        // The learner starts answering (arm) AFTER the tutor's text lands (text) is the
        // ordinary path (the requirement text speaks to). Here the ARM happens after the
        // TEXT, i.e. the timer was posted for the text currently on screen — this is the
        // normal "reply lands, wearer starts talking about it, clear it after the window"
        // path, not a stale-timer case at all. The token must be current and able to clear.
        val retention = SubtitleRetention()
        retention.onNewTutorText()
        val token = retention.onSpeechStarted()
        assertTrue(
            "a timer armed after the text it retains is the current owner of the clear",
            retention.mayClear(token),
        )
    }

    // ---- 2. Concurrency --------------------------------------------------------------------

    @Test
    fun `concurrent arms strictly before a text bump never clear after it`() {
        // Deterministic variant: N threads race (released together via a latch, not sleeps)
        // to arm, all joined before the single bump fires, so every collected token is
        // provably ordered before the bump in the AtomicLong's total order. None may clear
        // afterward.
        val retention = SubtitleRetention()
        val threads = 16
        val rounds = 50
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(rounds) {
                val startLatch = CountDownLatch(1)
                val preBumpTokens = ConcurrentLinkedQueue<Long>()
                val futures = (1..threads).map {
                    pool.submit {
                        startLatch.await()
                        preBumpTokens.add(retention.onSpeechStarted())
                    }
                }
                startLatch.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }

                retention.onNewTutorText()

                preBumpTokens.forEach { token ->
                    assertFalse(
                        "token $token was armed before the text bump and must not survive it",
                        retention.mayClear(token),
                    )
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `concurrent arms racing a concurrent text pump - at most the newest token clears`() {
        // True race: arming threads and the text-pumping thread run at the same time,
        // released together off one latch. Whatever the interleaving, AtomicLong gives the
        // operations a total order, so the invariant must hold globally: never more than one
        // collected token may pass mayClear, and if one does it is the numerically newest
        // (generation only ever increases, so an older token passing would mean the counter
        // went backward).
        val retention = SubtitleRetention()
        val armThreads = 12
        val armsPerThread = 400
        val bumpCount = 400
        val startLatch = CountDownLatch(1)
        val tokens = ConcurrentLinkedQueue<Long>()
        val pool = Executors.newFixedThreadPool(armThreads + 1)
        try {
            val armFutures = (1..armThreads).map {
                pool.submit {
                    startLatch.await()
                    repeat(armsPerThread) {
                        tokens.add(retention.onSpeechStarted())
                    }
                }
            }
            val bumpFuture = pool.submit {
                startLatch.await()
                repeat(bumpCount) {
                    retention.onNewTutorText()
                }
            }
            startLatch.countDown()
            armFutures.forEach { it.get(30, TimeUnit.SECONDS) }
            bumpFuture.get(30, TimeUnit.SECONDS)

            val passing = tokens.filter { retention.mayClear(it) }
            assertTrue(
                "at most one collected token may still be current, saw ${passing.size}",
                passing.size <= 1,
            )
            if (passing.isNotEmpty()) {
                assertEquals(
                    "the only token allowed to clear is the numerically newest one collected",
                    tokens.max(),
                    passing.single(),
                )
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- 3. Token forgery / reuse ------------------------------------------------------------

    @Test
    fun `a never-armed session refuses every forgeable token`() {
        // This test used to DOCUMENT a defect: generation was seeded at 0, so a fresh,
        // never-armed instance answered TRUE to mayClear(0) — and 0 is exactly what a Kotlin
        // default (`var token: Long = 0` read by a runnable before the real assignment) would
        // hand it. The seed moved to Long.MIN_VALUE, which no incrementAndGet() result can
        // collide with, so an unarmed instance is unclearable by construction rather than by
        // caller discipline across two hand-synced files.
        val retention = SubtitleRetention()
        assertFalse("a fresh instance must refuse token 0", retention.mayClear(0L))
        assertFalse("and every other easily-forged value", retention.mayClear(1L))
        assertFalse(retention.mayClear(-1L))
        // And the seed itself — the one value a zero-seed fix would have left clearable.
        assertFalse(retention.mayClear(Long.MIN_VALUE))
    }

    @Test
    fun `token 0 stops working the instant anything arms or bumps the session`() {
        val retention = SubtitleRetention()
        retention.onSpeechStarted()
        assertFalse(retention.mayClear(0L))
    }

    @Test
    fun `a token can never be reused - each arm mints a value no earlier arm ever held`() {
        val retention = SubtitleRetention()
        val seen = HashSet<Long>()
        repeat(1_000) {
            val token = retention.onSpeechStarted()
            assertTrue("token $token was already minted once", seen.add(token))
        }
    }

    // ---- 4. Long-session survival ------------------------------------------------------------

    @Test
    fun `10k arms - strictly monotonic, no wraparound, only the last is current`() {
        val retention = SubtitleRetention()
        // Seed-agnostic on purpose: the seed moved once already (0 -> MIN_VALUE, closing the
        // forged-zero-token hole) and this test's literals broke with it. The contract is
        // monotonicity and only-the-last-current, not any particular starting number.
        val tokens = ArrayList<Long>(10_000)
        repeat(10_000) {
            val token = retention.onSpeechStarted()
            if (tokens.isNotEmpty()) {
                assertTrue("generation must be strictly increasing", token > tokens.last())
            }
            tokens.add(token)
        }
        val last = tokens.last()
        assertTrue(retention.mayClear(last))

        // Spot-check earlier tokens from the actual run: none may clear.
        val sample = listOf(tokens[0], tokens[1], tokens[99], tokens[4_999], tokens[9_997], tokens[9_998])
        sample.forEach { token ->
            assertFalse("token $token is stale after 10k arms", retention.mayClear(token))
        }
    }

    @Test
    fun `10k arms interleaved with text bumps - only the most recent event's token is current`() {
        val retention = SubtitleRetention()
        var lastArmToken = -1L
        var lastIterationBumped = false
        repeat(10_000) { i ->
            val token = retention.onSpeechStarted()
            lastArmToken = token
            lastIterationBumped = i % 7 == 0
            if (lastIterationBumped) {
                retention.onNewTutorText()
                assertFalse(
                    "the arm just before this bump must not survive it",
                    retention.mayClear(token),
                )
            }
        }
        // Branching on what the loop actually did: the old unconditional assertion silently
        // depended on the repeat count modulo 7, and would fail for the wrong reason if
        // someone changed 10_000 to a count whose last iteration bumps.
        if (lastIterationBumped) {
            assertFalse(retention.mayClear(lastArmToken))
        } else {
            assertTrue(
                "the final arm, with no bump after it, must still be current",
                retention.mayClear(lastArmToken),
            )
        }
    }
}
