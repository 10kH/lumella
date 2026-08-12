package com.woolab.lumella

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ultragoal red-team: races voice-tool calls (`set_text_display` / `set_hints_visible`)
 * against each other from multiple threads, per the "contract under attack" —
 * DisplaySettings.State must always be one of the four legal text modes crossed with a
 * boolean, never a torn mix (e.g. SMALL size with echo visible).
 *
 * [DisplaySettings] is pinned byte-identical to ELLA's copy (DisplaySettingsDriftTest) — this
 * file is intentionally NOT pinned, so the two apps can carry different/duplicated
 * concurrency probes without tripping the drift guard.
 */
class DisplaySettingsConcurrencyTest {

    /** Every legal [DisplaySettings.State] pairs size/echo/visible consistently; this must
     * never observe a mix like SMALL+echoVisible=true, which no single `applyTextMode` branch
     * ever produces. */
    private fun assertLegalCombination(s: DisplaySettings.State) {
        when {
            !s.subtitleVisible -> { /* "off": echoVisible is always false too, but subtitleSize is carried over and legal either way */
                assertTrue("off must hide the echo too", !s.echoVisible)
            }
            s.subtitleSize == DisplaySettings.SubtitleSize.SMALL -> {
                assertTrue("SMALL must hide the echo (small always pairs size=SMALL with echo=false)", !s.echoVisible)
            }
            s.subtitleSize == DisplaySettings.SubtitleSize.LARGE -> {
                assertTrue("visible LARGE must show the echo (large/on always pair size=LARGE with echo=true)", s.echoVisible)
            }
        }
    }

    @Test
    fun concurrentTextModeAndHintsVisibleCallsNeverProduceATornState() {
        val settings = DisplaySettings()
        val threads = 8
        val perThreadIterations = 2_000
        val modes = listOf("large", "small", "off", "on")
        val start = CyclicBarrier(threads)
        val crashed = AtomicInteger(0)

        val workers = (0 until threads).map { t ->
            Thread {
                start.await()
                repeat(perThreadIterations) { i ->
                    try {
                        if (t % 2 == 0) {
                            settings.applyTextMode(modes[(t + i) % modes.size])
                        } else {
                            settings.applyHintsVisible((i % 2) == 0)
                        }
                    } catch (e: Throwable) {
                        crashed.incrementAndGet()
                    }
                    // Every reader, mid-race, must still see a legal combination -- never a
                    // half-applied write, because AtomicReference.set() publishes a whole State.
                    assertLegalCombination(settings.current())
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }

        assertTrue("no tool-call thread may throw under concurrent access", crashed.get() == 0)
        assertLegalCombination(settings.current())
    }

    /**
     * PRODUCTION DEFECT PROBE (report, not a required-green regression guard): both
     * `applyTextMode` and `applyHintsVisible` do a plain `AtomicReference.get()` (read) then
     * later an unconditional `.set()` (write) -- classic check-then-act, not a
     * compare-and-swap retry loop. Neither method's write is conditioned on the reference
     * still holding what it read.
     *
     * This cannot produce a *torn* individual State (each call always constructs one fully
     * consistent State object and publishes it atomically), but it CAN silently lose one
     * side's effect entirely: if `applyHintsVisible(true)` reads the pre-`small` state, and a
     * concurrent `applyTextMode("small")` finishes and publishes AFTER that read but BEFORE
     * `applyHintsVisible` publishes, the hints-visible write clobbers the text-mode change
     * back to the stale subtitleSize/echoVisible it read -- even though `set_text_display`
     * already answered the model "ok". That is a truthfulness violation of the tool-call
     * contract (a tool call answered "ok" whose effect is then silently discarded).
     *
     * This is a genuine data race (two threads doing get()+set() with no synchronization
     * between them), so it is demonstrated with a stress loop rather than a single
     * deterministic interleaving -- there is no hook in production code to pause a thread
     * between its read and its write. Kept non-fatal (reports via assertion message on
     * failure, does not flake the suite) because the race window is inherently probabilistic;
     * see the review note in the ultragoal report for the concrete file:line and severity.
     */
    @Test
    fun applyHintsVisibleCanLoseAConcurrentApplyTextMode_raceProbe() {
        var lostTextModeEffect = 0
        var lostHintsVisibleEffect = 0
        val trials = 200_000
        var settings = DisplaySettings()

        // Two long-lived threads, resynchronized every round through a pair of barriers, so
        // the per-trial cost is barrier hand-off (cheap) rather than Thread creation (which
        // dominated the window enough that no interleaving was ever observed across 4,000
        // freshly-spawned-thread trials).
        val roundStart = CyclicBarrier(3)
        val roundEnd = CyclicBarrier(3)
        var localSettings = settings
        val running = AtomicInteger(1)

        val tA = Thread {
            while (running.get() == 1) {
                roundStart.await()
                if (running.get() != 1) return@Thread
                localSettings.applyTextMode("small")
                roundEnd.await()
            }
        }
        val tB = Thread {
            while (running.get() == 1) {
                roundStart.await()
                if (running.get() != 1) return@Thread
                localSettings.applyHintsVisible(true)
                roundEnd.await()
            }
        }
        tA.isDaemon = true
        tB.isDaemon = true
        tA.start()
        tB.start()

        repeat(trials) {
            settings = DisplaySettings()
            localSettings = settings
            roundStart.await()
            roundEnd.await()

            val s = settings.current()
            if (s.subtitleSize != DisplaySettings.SubtitleSize.SMALL || s.echoVisible) lostTextModeEffect++
            if (!s.hintsVisible) lostHintsVisibleEffect++
        }
        running.set(0)
        roundStart.await()

        println(
            "[race-probe] lostTextModeEffect=$lostTextModeEffect lostHintsVisibleEffect=$lostHintsVisibleEffect " +
                "out of $trials racing (applyTextMode(\"small\") vs applyHintsVisible(true)) trials",
        )
        // This test used to ASSERT the loss (13-24 per 200k trials): get()-then-set() let one
        // answered tool call silently revert the other's effect, so a wearer's command was
        // confirmed to the model and then undone. Both methods moved to updateAndGet, deriving
        // the next state from the lambda's prev — the same probe must now find ZERO losses.
        assertEquals(
            "an answered tool call's effect was lost to a concurrent one — the updateAndGet " +
                "fix in DisplaySettings has regressed to a get()-then-set()",
            0,
            lostTextModeEffect + lostHintsVisibleEffect,
        )
    }
}
