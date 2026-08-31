package org.orca.advisory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen-off panic trigger.
 *
 * <p>Two failure modes matter and they pull in opposite directions: a
 * trigger that does not fire when a crew needs it, and one that fires in a
 * pocket and spends a rescue somebody else needed. The tests below pin
 * both ends.
 */
class PowerPressDetectorTest {

    private fun run(vararg times: Long): Pair<PowerPressDetector.State, Int> {
        var s = PowerPressDetector.State()
        var fires = 0
        times.forEach {
            val r = PowerPressDetector.accept(s, it)
            s = r.state
            if (r.fire) fires++
        }
        return s to fires
    }

    // --- it fires when it should -----------------------------------------

    @Test
    fun fivePressesInsideTheWindowFire() {
        val (_, fires) = run(0, 400, 800, 1200, 1600)
        assertEquals(1, fires)
    }

    @Test
    fun fiveRightAtTheWindowEdgeStillFire() {
        // Last press 3999 ms after the first: inside 4000.
        val (_, fires) = run(0, 1000, 2000, 3000, 3999)
        assertEquals(1, fires)
    }

    @Test
    fun aSixthPressDoesNotFireASecondTime() {
        val (_, fires) = run(0, 400, 800, 1200, 1600, 2000)
        assertEquals("the cooldown must hold", 1, fires)
    }

    // --- it stays quiet when it should ------------------------------------

    @Test
    fun fourPressesDoNothing() {
        val (_, fires) = run(0, 400, 800, 1200)
        assertEquals(0, fires)
    }

    @Test
    fun ordinaryScreenWakesNeverAccumulate() {
        // Somebody checking the time every half minute, twenty times over.
        val times = (0 until 20).map { it * 30_000L }.toLongArray()
        val (_, fires) = run(*times)
        assertEquals("a phone in normal use must never call the coastguard", 0, fires)
    }

    @Test
    fun pressesSpreadTooWideDoNotFire() {
        // Five presses, but over ten seconds.
        val (_, fires) = run(0, 2500, 5000, 7500, 10_000)
        assertEquals(0, fires)
    }

    @Test
    fun anOldRunIsForgottenNotCarriedForward() {
        // Four presses, a long gap, then four more: eight in total, but no
        // five of them are close together.
        val (_, fires) = run(0, 300, 600, 900, 60_000, 60_300, 60_600, 60_900)
        assertEquals(0, fires)
    }

    // --- the cooldown ------------------------------------------------------

    @Test
    fun theAlarmsOwnScreenChangesCannotRetrigger() {
        var s = PowerPressDetector.State()
        listOf(0L, 400, 800, 1200, 1600).forEach { s = PowerPressDetector.accept(s, it).state }
        // The alarm fires and lights the screen; those toggles arrive next.
        var refired = false
        listOf(1700L, 1800, 1900, 2000, 2100, 2200).forEach {
            val r = PowerPressDetector.accept(s, it)
            s = r.state
            if (r.fire) refired = true
        }
        assertFalse(refired)
    }

    @Test
    fun itArmsAgainAfterTheCooldown() {
        var s = PowerPressDetector.State()
        listOf(0L, 400, 800, 1200, 1600).forEach { s = PowerPressDetector.accept(s, it).state }
        // Well past REARM_MS, measured from when it fired (1600).
        val base = 1600L + PowerPressDetector.REARM_MS + 1
        var fires = 0
        listOf(base, base + 400, base + 800, base + 1200, base + 1600).forEach {
            val r = PowerPressDetector.accept(s, it)
            s = r.state
            if (r.fire) fires++
        }
        assertEquals("a second real emergency must still work", 1, fires)
    }

    // --- progress ----------------------------------------------------------

    @Test
    fun progressClimbsWithEachPress() {
        var s = PowerPressDetector.State()
        assertEquals(0f, PowerPressDetector.progress(s), 0.001f)
        s = PowerPressDetector.accept(s, 0).state
        assertEquals(0.2f, PowerPressDetector.progress(s), 0.001f)
        s = PowerPressDetector.accept(s, 200).state
        assertEquals(0.4f, PowerPressDetector.progress(s), 0.001f)
    }

    @Test
    fun resetClearsAPartialRun() {
        var s = PowerPressDetector.State()
        listOf(0L, 200, 400).forEach { s = PowerPressDetector.accept(s, it).state }
        assertTrue(PowerPressDetector.progress(s) > 0f)
        assertEquals(0f, PowerPressDetector.progress(PowerPressDetector.reset(s)), 0.001f)
    }
}
