package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When holding the volume key counts as a call for help.
 *
 * Both failure directions are expensive here, so both are tested: a hold
 * that does not fire makes the feature decoration, and a stray press that
 * does fire spends a rescue somebody else needed.
 */
class PanicDetectorTest {

    private fun downs(vararg atMs: Long) = atMs.map { PanicDetector.Event(it, PanicDetector.Key.DOWN) }

    /** Feed events in order, returning (final state, did it fire). */
    private fun feed(
        events: List<PanicDetector.Event>,
        start: PanicDetector.State = PanicDetector.State(),
    ): Pair<PanicDetector.State, Boolean> {
        var s = start
        var fired = false
        events.forEach {
            val r = PanicDetector.accept(s, it)
            s = r.state
            if (r.fire) fired = true
        }
        return s to fired
    }

    /**
     * A real hold: auto-repeat every `everyMs` for `ms`, INCLUDING an
     * event at exactly `ms`.
     *
     * The endpoint matters. `(0..5000 step 150)` stops at 4950, which is a
     * 4.95-second hold, and the detector correctly refuses to fire on it.
     * Modelling "held for five seconds" as anything less was a bug in the
     * test, not in the rule.
     */
    private fun hold(ms: Long, everyMs: Long = 150): List<PanicDetector.Event> {
        val ticks = (0..ms step everyMs).toMutableList()
        if (ticks.last() != ms) ticks.add(ms)
        return ticks.map { PanicDetector.Event(it, PanicDetector.Key.DOWN) }
    }

    // --- it fires when it should -----------------------------------------

    @Test
    fun `holding the volume key for five seconds fires`() {
        assertTrue(feed(hold(5_000)).second)
    }

    @Test
    fun `holding well past five seconds still fires`() {
        assertTrue(feed(hold(9_000)).second)
    }

    @Test
    fun `a slow auto-repeat still fires, as long as gaps stay under the limit`() {
        // Some handsets repeat lazily. 800 ms is under MAX_GAP_MS, so this
        // is one hold, not eight presses.
        assertTrue(feed(hold(6_400, everyMs = 800)).second)
    }

    // --- it does NOT fire when it should not -------------------------------

    @Test
    fun `four seconds is not enough`() {
        assertFalse(feed(hold(4_000)).second)
    }

    @Test
    fun `a single press never fires`() {
        assertFalse(feed(downs(0)).second)
    }

    @Test
    fun `two lone presses five seconds apart do NOT fire`() {
        // The exact false positive this is built to reject: turning the
        // volume down, pausing, turning it down again.
        assertFalse(feed(downs(0, 5_000)).second)
    }

    @Test
    fun `a gap longer than the limit restarts the hold`() {
        // Four seconds of holding, a one-second pause, then two more
        // seconds. Neither run reaches five, so nothing fires.
        val broken = hold(4_000) + hold(2_000).map {
            PanicDetector.Event(it.atMs + 5_200, it.key)
        }
        assertFalse(feed(broken).second)
    }

    @Test
    fun `enough events but not enough time does not fire`() {
        // A very fast repeat: 30 events in one second.
        assertFalse(feed(hold(1_000, everyMs = 33)).second)
    }

    @Test
    fun `enough time but too few events does not fire`() {
        // Two events spanning six seconds is a long gap, not a hold --
        // and MIN_EVENTS rejects it even if the gap rule somehow did not.
        assertFalse(feed(downs(0, 6_000)).second)
    }

    // --- volume UP is the way out ------------------------------------------

    @Test
    fun `pressing volume up cancels a hold in progress`() {
        var s = feed(hold(4_500)).first
        assertTrue("hold should be well advanced", s.progress > 0.8f)
        s = PanicDetector.accept(s, PanicDetector.Event(4_600, PanicDetector.Key.UP)).state
        assertEquals(0f, s.progress, 0f)
        // Continuing to hold down now starts from scratch.
        val (_, fired) = feed(
            hold(3_000).map { PanicDetector.Event(it.atMs + 4_700, it.key) }, s,
        )
        assertFalse(fired)
    }

    @Test
    fun `volume up alone never fires`() {
        val ups = (0L..6_000L step 150L).map { PanicDetector.Event(it, PanicDetector.Key.UP) }
        assertFalse(feed(ups).second)
    }

    // --- one hold is one SOS -----------------------------------------------

    @Test
    fun `it does not fire twice for the same emergency`() {
        val (state, fired) = feed(hold(5_000))
        assertTrue(fired)
        // Keep holding for another six seconds; still inside the re-arm window.
        val (_, again) = feed(
            hold(6_000).map { PanicDetector.Event(it.atMs + 5_100, it.key) }, state,
        )
        assertFalse(again)
    }

    @Test
    fun `it re-arms after the cooldown so a second emergency still works`() {
        val (state, _) = feed(hold(5_000))
        // The cooldown is measured from the moment it FIRED (t = 5000),
        // not from t = 0, so the second hold has to start after that.
        val afterCooldown = 5_000 + PanicDetector.REARM_MS + 1_000
        val later = hold(5_000).map { PanicDetector.Event(it.atMs + afterCooldown, it.key) }
        assertTrue(feed(later, state).second)
    }

    // --- the progress ring --------------------------------------------------

    @Test
    fun `progress runs zero to one across the hold and never past it`() {
        assertEquals(0f, PanicDetector.State().progress, 0f)
        assertEquals(0.5f, feed(hold(2_500)).first.progress, 0.05f)
        // A run cannot exceed 1: it fires at 1 and resets, so build one by hand.
        val over = PanicDetector.State(run = downs(0, 9_000))
        assertEquals(1f, over.progress, 0f)
    }

    // --- releasing the key --------------------------------------------------

    @Test
    fun `a released key expires the run, so the next press starts fresh`() {
        // Nothing is sent when a key is RELEASED -- silence is the only
        // signal. Without expiry, a press minutes later would look like
        // the tail of a very long hold.
        val s = feed(hold(3_000)).first
        assertTrue(s.progress > 0f)
        val expired = PanicDetector.expire(s, nowMs = 3_000 + PanicDetector.MAX_GAP_MS + 1)
        assertEquals(0f, expired.progress, 0f)
    }

    @Test
    fun `expiry leaves an in-progress hold alone`() {
        val s = feed(hold(3_000)).first
        val kept = PanicDetector.expire(s, nowMs = 3_100)
        assertEquals(s.progress, kept.progress, 0f)
    }

    @Test
    fun `expiry preserves the cooldown, so it cannot be used to re-arm early`() {
        val (state, _) = feed(hold(5_000))
        val expired = PanicDetector.expire(state, nowMs = 6_000)
        assertEquals(state.firedAtMs, expired.firedAtMs)
    }
}
