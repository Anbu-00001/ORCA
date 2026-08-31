package org.orca.advisory

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The camera flash, blinking SOS in Morse.
 *
 * <h3>Why this is a real feature and not a gimmick</h3>
 * A light is a recognised distress signal at sea, not a metaphor for one.
 * COLREGS Annex IV lists "signals made by … flashes" among the signals of
 * distress, and a small boat that has lost its engine after dark
 * frequently has no flare, no working VHF, and a phone with 4% battery.
 * The nearest boat is often two kilometres away and looking in the wrong
 * direction. A phone torch is visible for a mile on a dark sea and costs
 * almost nothing to run.
 *
 * <h3>Why a web app cannot do this</h3>
 * Torch control is a camera capability. In a browser it requires an HTTPS
 * origin, a camera permission prompt, an open MediaStream held for the
 * duration, and `ImageCapture` torch support that Safari does not have at
 * all. Even where it works, a backgrounded or screen-locked tab is
 * suspended and the flashing stops. Here it is
 * `CameraManager.setTorchMode` — no permission, no camera stream, and it
 * keeps running with the screen off.
 *
 * <h3>THE FOUR BUGS THIS REWRITE FIXES</h3>
 * The torch is started from four places — the signal screen, the SOS
 * send, the home-screen hold and the volume-key watch — and it behaved
 * differently depending on which one started it:
 *
 * <ol>
 *  <li><b>State was invisible.</b> `running` was a plain Boolean, so every
 *      screen kept its own cached copy. Start the torch from the SOS send
 *      and the signal screen's toggle still read "off", because nothing
 *      told it otherwise. It is Compose state now: one truth, and every
 *      screen recomposes from it.
 *  <li><b>It never stopped.</b> Nothing called {@link #stop} after a
 *      distress send. Observed still flashing four minutes after a test,
 *      unnoticed, burning the battery the emergency would need.
 *  <li><b>It held an Activity.</b> `start()` captured whatever context was
 *      passed — usually a composable's Activity — inside a Handler that
 *      outlives the screen. It takes the application context now.
 *  <li><b>Two accessors disagreed.</b> There was an `isRunning()` function
 *      AND an `isRunning` property, which is a JVM signature clash and was
 *      a symptom of nobody owning this file.
 * </ol>
 *
 * <h3>WHY IT DOES NOT AUTO-STOP</h3>
 * A timeout was tempting and is wrong. The case this exists for is a boat
 * adrift after dark, and a distress light that quietly switches itself off
 * after N minutes fails at exactly the hour it is needed. So it runs until
 * a person stops it — and in exchange it is impossible to miss: every
 * screen that can start it shows that it is running, and for how long.
 *
 * <p>Timing is International Morse at 300 ms per dit: three dits, three
 * dahs, three dits, then a gap. That is deliberately not "fast blinking" —
 * a rhythm someone recognises is worth far more than brightness.
 */
object TorchSos {

    private const val TAG = "ORCA"

    /** One Morse unit. 300 ms is slow enough to read across open water. */
    const val DIT_MS = 300L
    const val DAH_MS = DIT_MS * 3
    const val SYMBOL_GAP_MS = DIT_MS
    const val LETTER_GAP_MS = DIT_MS * 3
    const val WORD_GAP_MS = DIT_MS * 7

    /**
     * S O S, as alternating on/off durations starting with ON.
     *
     * Even indices are lit, odd are dark, which is the whole reason the
     * runner can just alternate rather than carry a separate table.
     */
    private val PATTERN: List<Long> = buildList {
        val letters = listOf(
            listOf(DIT_MS, DIT_MS, DIT_MS),   // S
            listOf(DAH_MS, DAH_MS, DAH_MS),   // O
            listOf(DIT_MS, DIT_MS, DIT_MS),   // S
        )
        letters.forEachIndexed { li, letter ->
            letter.forEachIndexed { si, on ->
                add(on)
                add(
                    if (si == letter.lastIndex) {
                        if (li == letters.lastIndex) WORD_GAP_MS else LETTER_GAP_MS
                    } else {
                        SYMBOL_GAP_MS
                    },
                )
            }
        }
    }

    // --- observable state -------------------------------------------------

    /**
     * Whether the light is signalling, readable from any screen.
     *
     * Compose state on purpose: four different places start this, and a
     * screen showing a stale "off" beside a flashing torch is how a crew
     * ends up unable to turn it off.
     */
    var running by mutableStateOf(false)
        private set

    /**
     * Why the last start failed, or null.
     *
     * A control that does nothing is worse than an absent one, so a phone
     * with no flash gets a sentence rather than a dead button.
     */
    var problem by mutableStateOf<String?>(null)
        private set

    private var startedAtMs = 0L

    /** How long the light has been signalling, in seconds. 0 when off. */
    fun runningSeconds(): Long =
        if (!running) 0L else (SystemClock.elapsedRealtime() - startedAtMs) / 1000L

    // --- hardware ---------------------------------------------------------

    private var handler: Handler? = null
    private var cameraId: String? = null
    private var torchCallback: CameraManager.TorchCallback? = null

    /**
     * Track what the HARDWARE is doing, not what we think we asked for.
     *
     * <h3>THE DESYNC THIS FIXES</h3>
     * `running` is ORCA's own intention. The torch is a shared system
     * resource, so intention and reality drift apart constantly: another
     * app opens the camera and takes it, or ORCA's process is killed mid
     * flash and the light is left on with nobody owning it. Observed on the
     * test handset -- the camera service showed the torch being driven by a
     * PID that no longer existed, while the freshly started app reported
     * "SIGNALLING SOS" and produced no light at all. Both halves wrong, in
     * opposite directions.
     *
     * <p>`CameraManager.registerTorchCallback` reports the real state, and
     * it fires once on registration with the current value. So ORCA learns
     * the truth at startup rather than assuming.
     */
    private fun watchHardware(app: Context) {
        if (torchCallback != null) return
        try {
            val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(id: String, enabled: Boolean) {
                    if (id != resolveCameraId(app)) return
                    hardwareOn = enabled
                }

                override fun onTorchModeUnavailable(id: String) {
                    if (id != resolveCameraId(app)) return
                    // Another app has the camera. Stop claiming to signal.
                    hardwareOn = false
                    if (running) {
                        problem = "Another app took the camera, so the light stopped."
                        running = false
                        handler?.removeCallbacksAndMessages(null)
                        handler = null
                        Log.w(TAG, "Torch became unavailable; signalling stopped")
                    }
                }
            }
            cm.registerTorchCallback(cb, Handler(Looper.getMainLooper()))
            torchCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Cannot watch the torch: ${e.message}")
        }
    }

    /** The light as the system reports it, independent of [running]. */
    var hardwareOn by mutableStateOf(false)
        private set

    /**
     * Force the light off, whoever left it on.
     *
     * Called at startup because a killed process leaves the torch burning
     * and the next launch has no idea it is on. `setTorchMode(false)` works
     * regardless of which process turned it on, so this is the one call
     * that can clear a zombie.
     */
    fun forceOff(context: Context) {
        val app = context.applicationContext
        watchHardware(app)
        running = false
        startedAtMs = 0L
        handler?.removeCallbacksAndMessages(null)
        handler = null
        try {
            resolveCameraId(app)?.let { id ->
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(id, false)
                Log.i(TAG, "Torch forced off")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not force the torch off: ${e.message}")
        }
    }

    /** True if this phone has a flash ORCA can drive. Checked, never assumed. */
    fun isAvailable(context: Context): Boolean = resolveCameraId(context) != null

    private fun resolveCameraId(context: Context): String? {
        cameraId?.let { return it }
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { cameraId = it }
        } catch (e: Exception) {
            Log.w(TAG, "No usable camera flash: ${e.message}")
            null
        }
    }

    // --- control ----------------------------------------------------------

    /**
     * Start blinking. Idempotent: calling it while already running is a
     * no-op rather than a second, interleaved blink loop.
     *
     * @return false if this phone has no flash, so the caller can say so
     *   rather than showing a control that does nothing. [problem] carries
     *   the reason.
     */
    fun start(context: Context): Boolean {
        if (running) return true
        // The application context, never the caller's. This Handler can
        // outlive any screen that started it, and holding an Activity here
        // leaks the whole view tree.
        val app = context.applicationContext
        val id = resolveCameraId(app)
        if (id == null) {
            problem = "This phone has no camera flash ORCA can control."
            return false
        }
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        watchHardware(app)
        val h = Handler(Looper.getMainLooper())
        handler = h
        problem = null
        running = true
        startedAtMs = SystemClock.elapsedRealtime()

        var index = 0
        val step = object : Runnable {
            override fun run() {
                if (!running) return
                try {
                    cm.setTorchMode(id, index % 2 == 0)
                } catch (e: Exception) {
                    // The camera app can take the torch mid-signal. Stop
                    // cleanly and SAY SO, rather than looping on an
                    // exception nobody sees or leaving the UI claiming the
                    // light is flashing when it is not.
                    Log.w(TAG, "Torch taken mid-signal: ${e.message}")
                    problem = "Another app took the camera, so the light stopped."
                    running = false
                    handler = null
                    return
                }
                val wait = PATTERN[index % PATTERN.size]
                index++
                h.postDelayed(this, wait)
            }
        }
        h.post(step)
        Log.i(TAG, "Torch SOS started")
        return true
    }

    /** Stop and switch the flash off. Safe to call when already stopped. */
    fun stop(context: Context) {
        val wasRunning = running
        running = false
        startedAtMs = 0L
        handler?.removeCallbacksAndMessages(null)
        handler = null
        val app = context.applicationContext
        try {
            resolveCameraId(app)?.let { id ->
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(id, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not switch the torch off: ${e.message}")
        }
        if (wasRunning) Log.i(TAG, "Torch SOS stopped")
    }

    /** Flip it. Returns the state it ended up in. */
    fun toggle(context: Context): Boolean {
        if (running) stop(context) else start(context)
        return running
    }

    /** One full SOS cycle, in seconds — shown so the crew knows the rhythm. */
    fun cycleSeconds(): Double = PATTERN.sum() / 1000.0
}
