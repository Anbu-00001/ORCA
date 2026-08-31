package org.orca.advisory

/**
 * "Press the power button five times" — the trigger that survives a
 * sleeping screen.
 *
 * <h3>WHY THE POWER BUTTON AND NOT THE VOLUME KEY</h3>
 * Measured on the test handset, with the screen genuinely off and ORCA in
 * the background, a five-second volume-down hold produced <b>nothing</b> on
 * any of three paths: the accessibility service is not sent key events
 * while the display sleeps, MediaSession's VolumeProvider is a known
 * framework defect on Android 12-15, and this ROM never delivers
 * VOLUME_CHANGED_ACTION to a third-party app at all. No app on stock
 * Android receives a volume key with the screen off. That is not a bug to
 * fix; it is the platform.
 *
 * <p>The power button is different in kind. It does not deliver a KEY
 * event to apps either -- but it changes the display state, and Android
 * <b>broadcasts</b> that change to every registered receiver, screen off
 * or not. So the press is observed indirectly, through its consequence,
 * and the consequence is one the system is obliged to announce.
 *
 * <p>This is the same shape as the trick that makes a notification-driven
 * alarm work on a locked phone: do not try to catch the hardware, wait for
 * the software event the system pushes at you.
 *
 * <h3>WHY FIVE, AND WHY A WINDOW</h3>
 * A screen going on and off is the single most ordinary thing a phone
 * does, so one toggle means nothing. Five inside [WINDOW_MS] is a
 * deliberate act: a person checking the time produces one, a pocket
 * produces none, and nothing in normal use produces five in four seconds.
 * Android's own emergency gesture uses the same count for the same reason.
 *
 * <p>Pure logic, no Android imports, so the rule that decides whether a
 * crew's phone calls for help can be tested without a phone.
 */
object PowerPressDetector {

    /** Presses required. */
    const val PRESSES = 5

    /** They must all land inside this window. */
    const val WINDOW_MS = 4_000L

    /**
     * Quiet period after firing.
     *
     * Without it the alarm and the screen-on it causes would feed straight
     * back into the detector and fire again.
     */
    const val REARM_MS = 30_000L

    /**
     * @param times when each recent toggle happened, oldest first.
     * @param firedAtMs when the alarm last fired, or 0 if never.
     */
    data class State(
        val times: List<Long> = emptyList(),
        val firedAtMs: Long = 0L,
    )

    data class Result(val state: State, val fire: Boolean)

    /** How close the crew is to triggering, 0..1. Drives the UI only. */
    fun progress(state: State): Float =
        (state.times.size.toFloat() / PRESSES).coerceIn(0f, 1f)

    /**
     * Record one screen on/off transition.
     *
     * Both directions count. A press while the screen is off turns it on;
     * the next press turns it off. Counting only one direction would halve
     * the rate and make five presses need ten.
     */
    fun accept(state: State, atMs: Long): Result {
        // Still cooling down: keep swallowing events so the alarm's own
        // screen changes cannot retrigger it.
        if (state.firedAtMs != 0L && atMs - state.firedAtMs < REARM_MS) {
            return Result(state.copy(times = emptyList()), false)
        }
        // Drop anything that has aged out, then add this one. A slow,
        // ordinary sequence of screen wakes therefore never accumulates.
        val kept = state.times.filter { atMs - it < WINDOW_MS } + atMs
        return if (kept.size >= PRESSES) {
            Result(State(times = emptyList(), firedAtMs = atMs), true)
        } else {
            Result(state.copy(times = kept), false)
        }
    }

    /** Forget a partial run. */
    fun reset(state: State): State = state.copy(times = emptyList())
}
