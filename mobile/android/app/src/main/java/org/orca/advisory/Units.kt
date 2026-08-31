package org.orca.advisory

import kotlin.math.roundToInt

/**
 * The units fishermen actually use.
 *
 * <p>ORCA's sources publish metric: Open-Meteo gives km/h, Marine Regions
 * gives degrees, ETOPO gives metres. Nobody at sea talks that way. A
 * distance offshore is nautical miles, a speed is knots, and a boat's
 * range and fuel arithmetic are both done in them. Showing "18.5 km to
 * the boundary" to a crew who navigate in miles is asking them to do
 * mental arithmetic in an emergency.
 *
 * <p>WHAT STAYS METRIC, and why:
 * <ul>
 *  <li><b>Wave height in metres.</b> Universal at sea, including in
 *      India: the Douglas scale, INCOIS bulletins and IMD warnings are
 *      all in metres. Feet would be the odd one out.
 *  <li><b>Sea temperature in Celsius.</b> Same reason.
 *  <li><b>Depth in metres.</b> Indian charts are metric.
 * </ul>
 *
 * <p>THE CONVERSION IS EXACT, NOT ROUNDED. A nautical mile is DEFINED as
 * exactly 1852 m, and a knot as exactly one nautical mile per hour. These
 * are definitions, not measurements, so converting introduces no error
 * and nothing here is a new claim about the world -- CLAUDE.md rule 3 is
 * about the provenance of the READING, which travels unchanged. Only the
 * presentation moves.
 */
object Units {

    /** Exact, by definition (1929 International Hydrographic Conference). */
    const val METRES_PER_NM = 1852.0
    const val KM_PER_NM = 1.852

    fun kmToNm(km: Double): Double = km / KM_PER_NM
    fun nmToKm(nm: Double): Double = nm * KM_PER_NM
    fun kmhToKnots(kmh: Double): Double = kmh / KM_PER_NM
    fun knotsToKmh(kn: Double): Double = kn * KM_PER_NM
    fun metresToNm(m: Double): Double = m / METRES_PER_NM

    /**
     * A distance, as a crew would say it.
     *
     * Under a mile is given in cables (tenths of a nautical mile), which
     * is the real unit for close quarters and avoids "0.3 NM" -- a
     * decimal nobody reads quickly when the number matters.
     */
    fun distance(km: Double): String {
        val nm = kmToNm(km)
        return when {
            nm < 0.1 -> "${(nm * 10).roundToInt()} cables"
            nm < 1.0 -> String.format("%.1f NM", nm)
            nm < 10.0 -> String.format("%.1f NM", nm)
            else -> "${nm.roundToInt()} NM"
        }
    }

    /** A speed, in knots. */
    fun speed(kmh: Double): String {
        val kn = kmhToKnots(kmh)
        return if (kn < 10) String.format("%.1f kn", kn) else "${kn.roundToInt()} kn"
    }

    /** Just the number, for a metric tile that shows its unit separately. */
    fun distanceValue(km: Double): String {
        val nm = kmToNm(km)
        return if (nm < 10) String.format("%.1f", nm) else nm.roundToInt().toString()
    }

    fun speedValue(kmh: Double): String {
        val kn = kmhToKnots(kmh)
        return if (kn < 10) String.format("%.1f", kn) else kn.roundToInt().toString()
    }

    /**
     * Convert a reading whose variable name says it is metric.
     *
     * Returns null for anything that should stay as published, so a
     * caller cannot accidentally "convert" a wave height into knots.
     */
    fun convertedValue(variable: String, value: Double): Pair<String, String>? = when (variable) {
        "wind_speed_kmh", "wind_gusts_kmh", "ocean_current_velocity_kmh" ->
            speedValue(value) to "kn"
        "wave_height_m" -> String.format("%.1f", value) to "m"
        "sst_c" -> String.format("%.1f", value) to "°C"
        else -> null
    }
}
