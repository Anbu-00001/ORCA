package org.orca.advisory

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the volume-key watch is actually seeing, live.
 *
 * <h3>WHY A DIAGNOSTIC IS PART OF THE FEATURE, NOT DEBUG SCAFFOLDING</h3>
 * This is the one thing in ORCA that cannot be verified from a laptop.
 * `adb input keyevent` is injected into the input dispatcher and does not
 * travel the media-session route a physical volume key takes, so no amount
 * of scripting proves the watch works — the only instrument is a thumb on
 * the side of the phone.
 *
 * <p>That left the crew, and the person testing before a demo, with a
 * feature that either worked or did not with no way to tell which and no
 * way to tell WHY. "It does not work" could mean the keys never arrive,
 * or that they arrive and the hold is a second too short, or that the run
 * is being broken by a gap. Those need three different fixes.
 *
 * <p>So the watch counts what it sees and shows it. Press the key once and
 * the counter moves: the routing works. Hold it and watch the progress
 * climb: the timing works. Nothing moves at all: the keys are not reaching
 * ORCA on this handset, which is a real answer and a different problem.
 *
 * <p>It costs one line of screen and it is the difference between a
 * feature you can debug in the field and one you can only pray about.
 */
object PanicStatus {

    /** Is the watch service running right now? */
    var armed by mutableStateOf(false)
        internal set

    /** Total volume-key events the watch has seen since it was armed. */
    var keyEvents by mutableIntStateOf(0)
        internal set

    /** Which path delivered the last event, for telling the two apart. */
    var lastPath by mutableStateOf<String?>(null)
        internal set

    /** How far through a hold the current run is, 0..1. */
    var progress by mutableStateOf(0f)
        internal set

    /** Did the silent keep-alive start? Without it the keys never route. */
    var keepAlive by mutableStateOf(false)
        internal set

    /**
     * Is the accessibility key service switched on?
     *
     * This is the one that works with the screen off, so the SOS screen
     * leads with it: everything else is a fallback for when it is not on.
     */
    var accessibilityOn by mutableStateOf(false)
        internal set

    private var lastKeyAtMs by mutableLongStateOf(0L)

    /** Seconds since the last key event, or null if there has never been one. */
    fun secondsSinceLastKey(): Long? =
        if (lastKeyAtMs == 0L) null else (SystemClock.elapsedRealtime() - lastKeyAtMs) / 1000L

    internal fun onKey(path: String, progress: Float) {
        keyEvents += 1
        lastPath = path
        lastKeyAtMs = SystemClock.elapsedRealtime()
        this.progress = progress
    }

    internal fun onArm(keepAliveStarted: Boolean) {
        armed = true
        keepAlive = keepAliveStarted
        keyEvents = 0
        lastPath = null
        lastKeyAtMs = 0L
        progress = 0f
    }

    internal fun onAccessibilityConnected(on: Boolean) {
        accessibilityOn = on
        if (on) { keyEvents = 0; lastKeyAtMs = 0L; progress = 0f }
    }

    internal fun clearProgress() { progress = 0f }

    /** Power-button presses counted so far, 0..1. */
    var powerProgress by mutableStateOf(0f)
        internal set

    internal fun onPowerPress(p: Float) { powerProgress = p }

    internal fun onDisarm() {
        armed = false
        keepAlive = false
        progress = 0f
    }
}
