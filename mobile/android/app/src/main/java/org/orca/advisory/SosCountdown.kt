package org.orca.advisory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The abort window between the volume-key hold and the message going out.
 *
 * <h3>WHY THIS IS NOT A CONFIRMATION STEP</h3>
 * A confirmation asks "are you sure?" and does nothing until somebody
 * answers. This is the opposite: the SOS is already happening, and doing
 * nothing at all sends it. The only thing a person can do here is STOP it.
 * A crew who is actually in trouble does not have to touch the phone
 * again; a crew whose phone did this in a pocket has ten seconds to
 * notice the alarm and stop it.
 *
 * <h3>WHY THE VOLUME KEY GETS ONE AND THE SOS BUTTON DOES NOT</h3>
 * They have different failure modes. The SOS screen's button is reached by
 * unlocking the phone, opening ORCA and navigating to the distress screen:
 * nothing does that by accident, so it sends on the press with no window.
 * A volume key held down is exactly what a phone wedged against a thwart
 * does on its own, so that path gets the window. The guard is matched to
 * the risk rather than applied uniformly.
 *
 * <h3>TEN SECONDS</h3>
 * Long enough to hear the alarm, register what it is and reach the phone;
 * short enough that a real emergency is not sitting on its hands. It is
 * also long enough for the announcement to finish speaking, which is how
 * a crew who cannot see the screen learns what is happening.
 *
 * <p>Held here rather than inside the service so that the SOS screen can
 * render the same countdown the notification is showing -- one source of
 * truth, so the screen can never disagree with the alarm about whether a
 * message is about to go out.
 */
object SosCountdown {

    const val SECONDS = 10

    /** Seconds remaining, or null when nothing is pending. */
    var secondsLeft by mutableStateOf<Int?>(null)
        internal set

    /**
     * True when the last countdown was stopped by a person.
     *
     * The screen says so explicitly. A crew that hit cancel needs to see
     * "cancelled -- nothing was sent" in those words, because the only
     * other way to find out is for help not to arrive.
     */
    var lastCancelled by mutableStateOf(false)
        internal set

    val running: Boolean get() = secondsLeft != null

    internal fun start() {
        lastCancelled = false
        secondsLeft = SECONDS
    }

    internal fun tick(): Int? {
        val n = secondsLeft ?: return null
        secondsLeft = if (n <= 1) null else n - 1
        return secondsLeft
    }

    internal fun cancel() {
        secondsLeft = null
        lastCancelled = true
    }

    internal fun finish() {
        secondsLeft = null
    }
}
