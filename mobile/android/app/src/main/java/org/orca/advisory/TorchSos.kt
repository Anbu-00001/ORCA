package org.orca.advisory

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The camera flash, blinking SOS in Morse.
 *
 * <p>WHY THIS IS A REAL FEATURE AND NOT A GIMMICK. A light is a
 * recognised distress signal at sea, not a metaphor for one. COLREGS
 * Annex IV lists "signals made by … flashes" among the signals of
 * distress, and a small boat that has lost its engine after dark
 * frequently has no flare, no working VHF, and a phone with 4% battery.
 * The nearest boat is often two kilometres away and looking in the wrong
 * direction. A phone torch is visible for a mile on a dark sea and costs
 * almost nothing to run.
 *
 * <p>WHY A WEB APP CANNOT DO THIS. Torch control is a camera capability.
 * In a browser it requires an HTTPS origin, a camera permission prompt,
 * an open MediaStream held for the duration, and `ImageCapture` torch
 * support that Safari does not have at all. Even where it works, a
 * backgrounded or screen-locked tab is suspended and the flashing stops.
 * Here it is `CameraManager.setTorchMode` — no permission, no camera
 * stream, and it keeps running with the screen off.
 *
 * <p>Timing is International Morse at 300 ms per dit: three dits, three
 * dahs, three dits, then a gap. That is deliberately not "fast blinking"
 * — a rhythm someone recognises is worth far more than brightness.
 */
object TorchSos {

    /** One Morse unit. 300 ms is slow enough to read across open water. */
    const val DIT_MS = 300L
    const val DAH_MS = DIT_MS * 3
    const val SYMBOL_GAP_MS = DIT_MS
    const val LETTER_GAP_MS = DIT_MS * 3
    const val WORD_GAP_MS = DIT_MS * 7

    /** S O S — three short, three long, three short. */
    private val PATTERN: List<Long> = buildList {
        val letters = listOf(
            listOf(DIT_MS, DIT_MS, DIT_MS),   // S
            listOf(DAH_MS, DAH_MS, DAH_MS),   // O
            listOf(DIT_MS, DIT_MS, DIT_MS),   // S
        )
        letters.forEachIndexed { li, letter ->
            letter.forEachIndexed { si, on ->
                add(on)
                add(if (si == letter.lastIndex) {
                    if (li == letters.lastIndex) WORD_GAP_MS else LETTER_GAP_MS
                } else SYMBOL_GAP_MS)
            }
        }
    }

    private var handler: Handler? = null
    private var cameraId: String? = null
    private var running = false

    /** True if this phone has a flash ORCA can drive. Checked, never assumed. */
    fun isAvailable(context: Context): Boolean = resolveCameraId(context) != null

    private fun resolveCameraId(context: Context): String? {
        cameraId?.let { return it }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { cameraId = it }
        } catch (e: Exception) {
            Log.w("ORCA", "No usable camera flash: ${e.message}")
            null
        }
    }

    fun isRunning(): Boolean = running

    /**
     * Start blinking. Safe to call twice; the second call is ignored.
     *
     * @return false if this phone has no flash, so the UI can say so
     *   rather than showing a button that does nothing.
     */
    fun start(context: Context): Boolean {
        if (running) return true
        val id = resolveCameraId(context) ?: return false
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val h = Handler(Looper.getMainLooper())
        handler = h
        running = true

        var index = 0
        var on = false

        val step = object : Runnable {
            override fun run() {
                if (!running) return
                on = index % 2 == 0
                try {
                    cm.setTorchMode(id, on)
                } catch (e: Exception) {
                    // The torch can be taken by the camera app mid-signal.
                    // Stop cleanly and let the screen report it, rather
                    // than looping on an exception nobody sees.
                    Log.w("ORCA", "Torch unavailable mid-signal: ${e.message}")
                    running = false
                    return
                }
                val wait = PATTERN[index % PATTERN.size]
                index++
                h.postDelayed(this, wait)
            }
        }
        h.post(step)
        return true
    }

    /**
     * Is the light signalling right now?
     *
     * <p>Exposed because the SOS screen starts the torch automatically
     * when a distress message goes out, and a crew that cannot see it is
     * flashing has no way to turn it off again. It ran for four minutes
     * unnoticed during testing and flattened battery the emergency needed.
     */
    val isRunning: Boolean get() = running

    fun stop(context: Context) {
        running = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
        try {
            resolveCameraId(context)?.let { id ->
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                cm.setTorchMode(id, false)
            }
        } catch (e: Exception) {
            Log.w("ORCA", "Could not switch the torch off: ${e.message}")
        }
    }

    /** One full SOS cycle, in seconds — shown on screen so the crew knows the rhythm. */
    fun cycleSeconds(): Double = PATTERN.sum() / 1000.0
}
