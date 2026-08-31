package org.orca.advisory

/**
 * Deciding that someone is holding the volume key down for help.
 *
 * <p>Pure logic, no Android, because the cost of getting it wrong runs
 * both ways and neither way is cheap: miss a real hold and the feature is
 * decoration, fire on a stray press and ORCA spends a rescue somebody
 * else needed.
 *
 * <p>WHY A HOLD AND NOT A TAP OR A DOUBLE-PRESS. The phone lives in a
 * pocket or a plastic bag on a wet deck and gets knocked constantly. A
 * count of presses is reachable by accident; five continuous seconds of
 * one key is not. It is also the one gesture a crew can perform without
 * looking, with the screen off, wearing gloves.
 *
 * <p>WHAT A HOLD LOOKS LIKE FROM ANDROID. Holding a volume key makes the
 * system auto-repeat it roughly every 100-200 ms. So a hold is not one
 * event; it is a dense RUN of events. This class watches for a run whose
 * gaps stay short and whose span reaches {@link #HOLD_MS}.
 *
 * <p>THE LIMIT THAT MATTERS, stated rather than hidden: once the stream
 * volume hits zero the system stops changing it, so a detector watching
 * only the volume SETTING goes deaf part-way through a hold. That is why
 * PanicService also holds a MediaSession, which keeps receiving key
 * events at zero. Where neither path sees enough events, this class
 * refuses to fire rather than guessing at the gap.
 */
object PanicDetector {

    /** How long the key must be held. The user-facing promise is "5 seconds". */
    const val HOLD_MS = 5_000L

    /**
     * The longest gap still counted as the same hold.
     *
     * Auto-repeat lands every 100-200 ms, so 900 ms tolerates a stalled
     * frame or a slow ContentObserver without joining two separate
     * presses into one imaginary hold.
     */
    const val MAX_GAP_MS = 900L

    /**
     * A hold must produce at least this many events.
     *
     * Guards the case that would otherwise be indistinguishable from a
     * hold: two lone presses five seconds apart, which is exactly what
     * "turn it down, then turn it down again" looks like.
     */
    const val MIN_EVENTS = 5

    /** After firing, ignore everything for this long so one hold is one SOS. */
    const val REARM_MS = 20_000L

    enum class Key { DOWN, UP }

    data class Event(val atMs: Long, val key: Key)

    data class State(
        /** Events in the current run, oldest first. Empty when idle. */
        val run: List<Event> = emptyList(),
        /** When the last SOS fired, or null. */
        val firedAtMs: Long? = null,
    ) {
        /** How far through the hold, 0..1, for the on-screen ring. */
        val progress: Float
            get() {
                if (run.size < 2) return 0f
                return ((run.last().atMs - run.first().atMs).toFloat() / HOLD_MS).coerceIn(0f, 1f)
            }
    }

    data class Result(val state: State, val fire: Boolean)

    /**
     * Fold one key event into the state.
     *
     * Deliberately a pure function of (state, event): the service holds
     * the state and does the shouting, and every rule that decides
     * whether a distress call happens is testable without a phone.
     */
    fun accept(state: State, event: Event): Result {
        // Still cooling down from the last SOS.
        state.firedAtMs?.let { fired ->
            if (event.atMs - fired < REARM_MS) return Result(state, false)
        }

        // Volume UP is not the panic key. It also cancels a hold in
        // progress, which gives a crew who started one by accident a way
        // out that needs no screen.
        if (event.key == Key.UP) return Result(State(firedAtMs = state.firedAtMs), false)

        val last = state.run.lastOrNull()
        val run = if (last == null || event.atMs - last.atMs > MAX_GAP_MS) {
            listOf(event)                    // a new hold begins
        } else {
            state.run + event
        }

        val span = run.last().atMs - run.first().atMs
        val ready = span >= HOLD_MS && run.size >= MIN_EVENTS
        return if (ready) {
            Result(State(run = emptyList(), firedAtMs = event.atMs), true)
        } else {
            Result(State(run = run, firedAtMs = state.firedAtMs), false)
        }
    }

    /**
     * Drop a run that simply stopped.
     *
     * The service calls this on a timer, because a released key sends no
     * event at all -- silence is the only signal that the hold ended, and
     * a run left open would let the next press five minutes later look
     * like the end of a very long hold.
     */
    fun expire(state: State, nowMs: Long): State {
        val last = state.run.lastOrNull() ?: return state
        return if (nowMs - last.atMs > MAX_GAP_MS) State(firedAtMs = state.firedAtMs) else state
    }
}
