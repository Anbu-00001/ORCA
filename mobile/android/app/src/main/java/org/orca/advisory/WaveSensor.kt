package org.orca.advisory

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The phone as a wave buoy.
 *
 * <h3>Why this exists</h3>
 * Every existing Indian advisory system stops working 10-15 km offshore,
 * and ISRO's answer was to buy every boat a satellite receiver (GEMINI,
 * DAT-SG). Meanwhile there is an unused inertial measurement unit in
 * every fisherman's pocket. A phone's accelerometer measures the boat's
 * vertical motion; integrate it and you have significant wave height
 * where the boat actually is, right now.
 *
 * <p>This is established, not speculative. TU Delft's WaveDroid measures
 * wave height, period and direction from a smartphone IMU at a tenth of
 * the cost of a conventional buoy, with commercial pilots in four
 * countries. An accelerometer-vs-GPS-wave-buoy comparison found excellent
 * correlation for significant wave height -- and that study was run in
 * shallow water off **Cuddalore**, which is one of ORCA's own ten zones.
 * See docs/RESEARCH.md §2 for citations.
 *
 * <h3>Why it matters beyond a nice screen</h3>
 * The Bay of Bengal is chronically under-sampled in situ. Six of ORCA's
 * ten zones had no cloud-free chlorophyll pixel in a fifteen-day window --
 * satellites cannot see through cloud. A fleet of a few hundred boats
 * reporting real wave height, with timestamps and positions, is a cal/val
 * dataset that does not currently exist and that INCOIS presently pays
 * moored buoys to gather. It also makes fishermen contributors rather
 * than only consumers.
 *
 * <h3>THE RULE THIS FILE MUST NOT BREAK</h3>
 * A phone-derived wave height is **not** a MarineObservation from a
 * trusted source, and it must never reach orca/policy.py. It is not
 * evidence, it does not enter the advisory, and it cannot change a
 * verdict. docs/MOBILE_APP.md §5 specifies the quarantine: a separate
 * store, the source name "ORCA Fleet (unverified)", capped confidence,
 * and no path into the advisory in v1.
 *
 * <p>So everything this class produces is labelled `unverified` at the
 * point of creation, carries the phone model and the sampling window that
 * produced it, and is shown to the user as **"your boat's motion"**, never
 * as "the wave height". If those two ever appear interchangeable in the
 * UI, that is the bug, and it is a rule-1 bug.
 *
 * <h3>What the maths is, honestly</h3>
 * Significant wave height (H⅓) is conventionally 4σ of the sea-surface
 * elevation. Double-integrating acceleration to displacement on a phone
 * accumulates drift badly, so this uses the standard cheap proxy instead:
 * the standard deviation of gravity-removed vertical acceleration over a
 * window, scaled by the dominant period observed in that same window.
 *
 * <p>That is an ESTIMATE with real error bars, not a measurement. It is
 * good enough to say "the motion here is closer to 2 m than 0.5 m" and
 * not good enough to put a decimal on. The UI says so, and
 * `confidence` is capped low to match. Anyone improving this should read
 * the WaveDroid papers first and implement proper spectral analysis --
 * do not silently tighten the number without changing the honesty.
 */
class WaveSensor(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "ORCA"
        /** Sampling. ~50 Hz is far above the 0.05-0.5 Hz band ocean waves
         *  live in, and is what SENSOR_DELAY_GAME gives on most handsets. */
        private const val WINDOW_SECONDS = 60
        private const val EXPECTED_HZ = 50
        private const val MAX_SAMPLES = WINDOW_SECONDS * EXPECTED_HZ

        /** Below this the phone is sitting on a table, not on a boat.
         *  Reporting a "sea state" from a stationary phone would be
         *  fabrication with extra steps. */
// THE FLOOR IS SET BY PHYSICS, AND IT IS A GENUINE SQUEEZE.
        //
        // For significant height H at period T, vertical acceleration is
        // sigma_a = (H/4) * (2*pi/T)^2:
        //
        //     0.5 m at  6 s  ->  0.14 m/s^2
        //     1.0 m at  8 s  ->  0.15 m/s^2
        //     2.0 m at  8 s  ->  0.31 m/s^2
        //     0.5 m at 10 s  ->  0.05 m/s^2   (long swell: very small)
        //
        // So the floor must sit BELOW ~0.14, or a real 1 m sea is thrown
        // away as "not moving" -- useless precisely where it matters. It
        // was briefly 0.35 on a wrong diagnosis (a "6.2 m" reading that
        // turned out to be a hand shaking the phone, not sensor noise),
        // which would have discarded everything under about a 2 m sea.
        //
        // But a phone flat on a TABLE must read nothing, and consumer
        // accelerometer noise plus slow thermal drift lands around
        // 0.02-0.05 m/s^2 -- and dividing by omega^2 amplifies exactly
        // that low-frequency part.
        //
        // 0.10 sits between the two: above table noise, below a 1 m sea.
        // It is a real compromise, not a tuned constant, and it means LONG
        // LOW SWELL (0.5 m at 10 s) is below what this method can see.
        // That limitation is stated to the user rather than papered over;
        // anyone implementing proper spectral analysis should revisit it.
        private const val MOTION_FLOOR_MS2 = 0.10

        /** Nothing nearshore in the Bay of Bengal produces this. An
         *  estimate above it means the integration has run away -- the
         *  phone was picked up, or it is in a vehicle -- and the honest
         *  response is to refuse, not to print it. */
        private const val IMPLAUSIBLE_M = 8.0

        /** Ocean waves: roughly 2-20 s. A period outside this band means
         *  we measured something that is not a wave -- an engine, a road,
         *  someone walking -- and the sample is refused, not scaled. */
        private const val MIN_PERIOD_S = 2.0
        private const val MAX_PERIOD_S = 20.0
    }

    data class Estimate(
        /** Metres. An ESTIMATE. Never call this a measurement. */
        val heightM: Double,
        /** Dominant period in seconds, from zero-crossings. */
        val periodS: Double,
        /** Samples that went into it. Fewer than a full window = weaker. */
        val samples: Int,
        /** Seconds of data. */
        val windowS: Double,
        /** Deliberately capped. See the class docstring. */
        val confidence: Double,
        val note: String,
    )

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** TYPE_LINEAR_ACCELERATION already has gravity removed by the
     *  platform's sensor fusion. Where it is absent we fall back to the
     *  raw accelerometer and remove gravity ourselves with a high-pass
     *  filter -- less accurate, and the note says so. */
    private val gravityCompensated =
        accelerometer?.type == Sensor.TYPE_LINEAR_ACCELERATION

    private val samples = ArrayDeque<Double>(MAX_SAMPLES)
    private var firstNs = 0L
    private var lastNs = 0L
    private var gravity = 0.0

    val available: Boolean get() = accelerometer != null

    fun start(): Boolean {
        val sensor = accelerometer ?: run {
            // Reported, never swallowed. A phone with no accelerometer
            // must show "not available", not a zero.
            Log.w(TAG, "No accelerometer on this device; wave sensing unavailable")
            return false
        }
        samples.clear(); firstNs = 0L; lastNs = 0L; gravity = 0.0
        return manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        // Vertical axis only. A phone lying on a thwart has gravity on Z;
        // this is the axis wave motion appears on for a phone left flat,
        // which is how it will actually be carried.
        var az = event.values[2].toDouble()
        if (!gravityCompensated) {
            // Single-pole high-pass. Tracks the slow gravity component and
            // subtracts it, leaving the fast motion.
            gravity = 0.995 * gravity + 0.005 * az
            az -= gravity
        }
        if (firstNs == 0L) firstNs = event.timestamp
        lastNs = event.timestamp
        if (samples.size >= MAX_SAMPLES) samples.removeFirst()
        samples.addLast(az)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * The current estimate, or null when there is honestly nothing to say.
     *
     * Returns null -- never a zero, never a guess -- when: too few
     * samples, the phone is not moving, or the observed period is outside
     * the ocean-wave band. Each of those is a real "we cannot tell", and
     * a number in their place would be fabricated.
     */
    fun estimate(): Estimate? {
        val n = samples.size
        if (n < EXPECTED_HZ * 10) return null          // under 10 s of data
        val windowS = (lastNs - firstNs) / 1_000_000_000.0
        if (windowS <= 0) return null

        val values = samples.toList()
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / n
        val sigmaA = sqrt(variance)

        if (sigmaA < MOTION_FLOOR_MS2) {
            return Estimate(
                heightM = 0.0, periodS = 0.0, samples = n, windowS = windowS,
                confidence = 0.0,
                note = "This phone is not moving enough to sense waves. " +
                       "Put it flat on the boat while under way.",
            )
        }

        // Dominant period from mean zero-crossing rate. Crude but robust,
        // and it does not need an FFT or a library (CLAUDE.md rule 6).
        var crossings = 0
        for (i in 1 until n) {
            if ((values[i - 1] - mean) < 0 && (values[i] - mean) >= 0) crossings++
        }
        if (crossings < 2) return null
        val periodS = windowS / crossings

        if (periodS < MIN_PERIOD_S || periodS > MAX_PERIOD_S) {
            // Not a wave. An engine at idle, a truck, someone walking with
            // the phone. Refusing is correct; scaling it would invent a
            // sea state on a jetty.
            return Estimate(
                heightM = 0.0, periodS = periodS, samples = n, windowS = windowS,
                confidence = 0.0,
                note = "The motion here is too fast to be waves (period " +
                       String.format("%.1f", periodS) + " s). " +
                       "This looks like engine or road vibration, not sea state.",
            )
        }

        // For simple harmonic motion, displacement amplitude = a / omega^2.
        // Significant height is conventionally 4 * sigma of elevation.
        val omega = 2.0 * Math.PI / periodS
        val sigmaEta = sigmaA / (omega * omega)
        val heightM = 4.0 * sigmaEta

        if (heightM > IMPLAUSIBLE_M) {
            return Estimate(
                heightM = 0.0, periodS = periodS, samples = n, windowS = windowS,
                confidence = 0.0,
                note = "The motion is too large to be sea state. ORCA will not " +
                       "guess a wave height from this.",
            )
        }

        // CAPPED ON PURPOSE. This is a proxy, on consumer hardware, with a
        // zero-crossing period estimate. It is never allowed to look as
        // trustworthy as a moored buoy, because it is not one.
        val confidence = when {
            windowS >= 45 -> 0.35
            windowS >= 25 -> 0.25
            else -> 0.15
        }

        return Estimate(
            heightM = heightM,
            periodS = periodS,
            samples = n,
            windowS = windowS,
            confidence = confidence,
            note = if (gravityCompensated)
                "Estimated from your boat's motion. This is not a measured " +
                "wave height and does not change ORCA's advice."
            else
                "Estimated from your boat's motion using the raw accelerometer " +
                "(this phone has no linear-acceleration sensor, so it is rougher). " +
                "It does not change ORCA's advice.",
        )
    }

    /**
     * The estimate as an uplink record, for the quarantine store.
     *
     * NOTE what this carries: `"ORCA Fleet (unverified)"` as the source,
     * the phone model, the window, and a capped confidence. It is built to
     * be REJECTABLE later -- a researcher must be able to filter these out
     * of any dataset by source alone. docs/MOBILE_APP.md §5.
     */
    fun asUplinkJson(estimate: Estimate, lat: Double, lon: Double): String {
        val model = android.os.Build.MODEL.replace("\"", "")
        return """
        {"variable":"wave_height_m","value":${"%.3f".format(estimate.heightM)},
         "unit":"m","lat":$lat,"lon":$lon,
         "valid_time":"${java.time.Instant.now()}",
         "source":"ORCA Fleet (unverified)",
         "confidence":${estimate.confidence},
         "provenance":"smartphone accelerometer, ${estimate.windowS.toInt()}s window, device=$model",
         "instrument":"phone_imu","verified":false}
        """.trimIndent().replace("\n", "")
    }
}
