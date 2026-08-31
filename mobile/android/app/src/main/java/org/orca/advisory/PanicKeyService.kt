package org.orca.advisory

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The volume-key panic trigger that actually works with the screen off.
 *
 * <h3>WHY THIS REPLACES THE MEDIASESSION APPROACH</h3>
 * ORCA first tried to catch the volume key two other ways. Both are dead
 * ends, and it is worth writing down why so nobody rebuilds them:
 *
 * <ol>
 *  <li><b>MediaSession + VolumeProvider.</b> The documented behaviour is
 *      that {@code setPlaybackToRemote} makes a session receive volume
 *      button events. In practice {@code onAdjustVolume} is <i>not</i>
 *      delivered when the app is backgrounded and the screen is off -- a
 *      known framework defect reported across Android 12 through 15. ORCA
 *      got as far as owning the media button session (confirmed in
 *      {@code dumpsys media_session}) and still received nothing. That is
 *      exactly the state a phone in a pocket is in, so the one scenario
 *      the feature exists for is the one scenario it never worked in.
 *  <li><b>The VOLUME_CHANGED_ACTION broadcast.</b> It fires only when a
 *      stream volume actually CHANGES. The test handset sits with media
 *      volume at 0 and the ringer on vibrate, so pressing volume-down
 *      changes nothing and broadcasts nothing. Worse, even with headroom
 *      the events stop the moment the stream bottoms out, so a five-second
 *      hold cannot be measured -- only the first two seconds of it.
 * </ol>
 *
 * <h3>WHY AN ACCESSIBILITY SERVICE IS THE RIGHT ANSWER</h3>
 * This is the same layer Android's own volume-key shortcut runs on -- the
 * one the system offers as "hold both volume keys", explicitly including
 * from the lock screen. Key events reach an accessibility service through
 * the input filter chain, ahead of ordinary dispatch, which is why it sees
 * them when a backgrounded app cannot.
 *
 * <p>It is also strictly more accurate. The old paths INFERRED a hold from
 * a stream of volume steps and had to guess when the key was released,
 * because a released key sends nothing. Here the real
 * {@link KeyEvent#ACTION_DOWN} and {@link KeyEvent#ACTION_UP} arrive with
 * their own timestamps, so a five-second hold is measured, not estimated.
 * No floor-bouncing, no borrowed volume steps, no heuristics.
 *
 * <h3>THE COST, STATED PLAINLY</h3>
 * The crew has to switch this on once, by hand, in Android's own
 * Settings → Accessibility → ORCA. Android deliberately makes that
 * deliberate, because a service that can see key events is powerful. ORCA
 * does not use that power for anything else: {@link #onAccessibilityEvent}
 * is empty by design, the service requests no window content, and
 * {@link #onKeyEvent} always returns false so every key it sees continues
 * to its normal destination. The volume keys keep changing the volume.
 *
 * <p>That one-time toggle buys the thing nothing else on stock Android
 * can: a hardware button that calls for help from a locked phone in a
 * pocket, with no screen, no unlock, and no app to find.
 */
class PanicKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "ORCA"

        /** How long volume-down must be held. Matches PanicDetector. */
        const val HOLD_MS = 5_000L

        /** Live, so the SOS screen can show whether this service is on. */
        @Volatile
        var connected: Boolean = false
            private set

        /**
         * Ask ANDROID whether this service is enabled.
         *
         * <p>The in-process [connected] flag is not enough on its own: it
         * is set in {@link #onServiceConnected}, so it reads false in a
         * freshly started process until the system gets round to rebinding
         * the service. The SOS screen showed "one-time setup needed" for a
         * service that was already switched on, which would have sent a
         * crew to configure something twice.
         *
         * <p>The secure setting is the system's own record and survives
         * process death, so it is the honest source.
         */
        fun isEnabled(context: android.content.Context): Boolean {
            if (connected) return true
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = android.content.ComponentName(context, PanicKeyService::class.java)
            return enabled.split(':').any {
                val c = android.content.ComponentName.unflattenFromString(it.trim())
                c != null && c.packageName == me.packageName &&
                    c.className == me.className
            }
        }
    }

    /** When the current volume-down press began, or 0 if it is not down. */
    private var downAtMs = 0L
    private var fired = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fires the alarm if the key is still down after [HOLD_MS].
     *
     * SCHEDULED, not counted. An earlier version only advanced the hold on
     * further ACTION_DOWN events, which assumes the volume key auto-repeats
     * while held. Not every device or ROM repeats it, and on one that does
     * not, a genuine thirty-second hold would produce exactly one event and
     * never fire. A timer started on key-down and cancelled on key-up
     * measures the hold correctly either way.
     */
    private val fireIfStillHeld = Runnable {
        if (downAtMs != 0L && !fired) {
            fired = true
            val held = SystemClock.uptimeMillis() - downAtMs
            Log.i(TAG, "PANIC: volume-down held ${held}ms")
            PanicStatus.onKey("accessibility", 1f)
            PanicService.trigger(this)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        PanicStatus.onAccessibilityConnected(true)
        Log.i(TAG, "Panic key service connected -- volume keys are visible now")
    }

    override fun onDestroy() {
        connected = false
        PanicStatus.onAccessibilityConnected(false)
        Log.i(TAG, "Panic key service disconnected")
        super.onDestroy()
    }

    /**
     * Not used, and deliberately so.
     *
     * ORCA reads no screen content and follows no other app. The service
     * exists purely for {@link #onKeyEvent}. Leaving this empty is the
     * honest expression of that.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * Watch volume-down, and never swallow it.
     *
     * @return always false. Returning true would consume the key and the
     *   crew's volume buttons would stop working, which is the fastest way
     *   to get a safety app uninstalled before the emergency it was
     *   installed for.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            // A volume-UP press cancels an in-progress hold. Pressing both
            // is how somebody takes a screenshot or reaches for the volume,
            // not how they call for help.
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) cancel()
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Auto-repeat fires ACTION_DOWN many times while held; only
                // the first one starts the clock and schedules the alarm.
                if (downAtMs == 0L) {
                    downAtMs = SystemClock.uptimeMillis()
                    fired = false
                    PanicStatus.onKey("accessibility", 0f)
                    Log.d(TAG, "volume-down pressed; ${HOLD_MS}ms to fire")
                    handler.postDelayed(fireIfStillHeld, HOLD_MS)
                } else if (!fired) {
                    // Repeats only move the progress bar the screen shows.
                    val held = SystemClock.uptimeMillis() - downAtMs
                    PanicStatus.onKey(
                        "accessibility",
                        (held.toFloat() / HOLD_MS).coerceIn(0f, 1f),
                    )
                }
            }

            KeyEvent.ACTION_UP -> cancel()
        }
        return false
    }

    private fun cancel() {
        if (downAtMs != 0L) Log.d(TAG, "volume hold released")
        handler.removeCallbacks(fireIfStillHeld)
        downAtMs = 0L
        fired = false
        PanicStatus.clearProgress()
    }
}
