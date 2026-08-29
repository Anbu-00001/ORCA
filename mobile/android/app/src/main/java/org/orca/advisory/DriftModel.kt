package org.orca.advisory

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a boat with a dead engine ends up.
 *
 * <p>A line-for-line port of orca/drift.py. It is duplicated onto the phone
 * for one reason: the moment this is needed there is, by definition, a
 * problem, and quite possibly no signal. A drift box that requires a server
 * is a drift box you do not have when the engine stops 40 km out.
 *
 * <p>THE MODEL. Leeway, as used by IAMSAR and by INCOIS SARAT:
 *
 * <pre>   drift = ambient surface current  +  wind-induced leeway</pre>
 *
 * Leeway is decomposed relative to the WIND, not the compass: a downwind
 * component parallel to it and a crosswind component across it, both linear
 * in the 10 m wind speed. The coefficients are empirical, per hull type,
 * from Allen &amp; Plourde (1999), "Review of Leeway; Field Experiments and
 * Implementation", USCG R&amp;D Center Technical Report CG-D-08-99. The
 * numbers below are that report's FISHING-VESSEL-1 row. They are not
 * ORCA's numbers, they are not tuned, and if they are ever edited the
 * citation must be edited with them.
 *
 * <p>WHY THERE IS NO RANDOMNESS. Operational SAR runs a Monte Carlo
 * ensemble. This does not, deliberately: a crew reading a position to the
 * Coast Guard over a dying phone needs the same answer twice, and the
 * published coefficients already carry their own standard deviations, so
 * sweeping +/-1 sigma analytically gives the envelope the ensemble
 * converges to for no CPU and no RNG. Which side of the wind a hull crabs
 * to is genuinely unknown, so BOTH sides are always in the box.
 *
 * <p>WHAT IT IS NOT. Wind and current are held at the values measured now.
 * Real drift runs through a moving field. Over 6 hours near the coast that
 * is defensible; at 24 hours it is a sketch, and the note says so.
 */
object DriftModel {

    /**
     * Allen &amp; Plourde (1999), CG-D-08-99, FISHING-VESSEL-1,
     * "Fishing vessel, general (mean values)". Slopes are percent of the
     * 10 m wind speed; offsets and standard deviations are cm/s.
     * Transcribed from OpenDrift's OBJECTPROP.DAT line:
     *     2.47  0.00  12.00   2.76  0.00  9.40   -2.76  0.00  9.40
     */
    const val DWL_SLOPE = 2.47
    const val DWL_OFFSET = 0.0
    const val DWL_STD = 12.0
    const val CWL_SLOPE = 2.76
    const val CWL_OFFSET = 0.0
    const val CWL_STD = 9.4

    const val SOURCE =
        "Allen & Plourde (1999), USCG R&D Center Technical Report CG-D-08-99, " +
        "table FISHING-VESSEL-1, via OpenDrift OBJECTPROP.DAT"

    private const val M_PER_DEG_LAT = 111_320.0

    /** A drift box, or a stated reason there isn't one. */
    data class Result(
        val ok: Boolean,
        val reason: String? = null,
        val missing: List<String> = emptyList(),
        val hours: Double = 0.0,
        val originLat: Double = 0.0,
        val originLon: Double = 0.0,
        val centreLat: Double = 0.0,
        val centreLon: Double = 0.0,
        /** Four corners, (lat, lon), of the +/-1 sigma envelope. */
        val box: List<Pair<Double, Double>> = emptyList(),
        val distanceKm: Double = 0.0,
        val bearingDeg: Double = 0.0,
        val confidenceNote: String = "",
    )

    /**
     * @param windDirectionDeg METEOROLOGICAL -- the direction the wind
     *   blows FROM (Open-Meteo `wind_direction_10m`).
     * @param currentDirectionDeg OCEANOGRAPHIC -- the direction the current
     *   flows TOWARD (Open-Meteo `ocean_current_direction`, documented as
     *   "where the current is heading towards").
     *
     * The two conventions are opposite, and getting either backwards sends
     * a search 180 degrees the wrong way, so both are converted here and
     * asserted in DriftModelTest.
     *
     * A null input is REFUSED, never defaulted. A drift box built on an
     * assumed wind direction is a fabricated position, and this one gets
     * read out to a rescue.
     */
    fun forecast(
        lat: Double,
        lon: Double,
        windSpeedKmh: Double?,
        windDirectionDeg: Double?,
        currentSpeedKmh: Double?,
        currentDirectionDeg: Double?,
        hours: Double,
    ): Result {
        val missing = buildList {
            if (windSpeedKmh == null) add("wind speed")
            if (windDirectionDeg == null) add("wind direction")
            if (currentSpeedKmh == null) add("current speed")
            if (currentDirectionDeg == null) add("current direction")
        }
        if (missing.isNotEmpty()) {
            return Result(
                ok = false,
                missing = missing,
                reason = "Cannot work out drift: no reading for " +
                    missing.joinToString(", ") +
                    ". ORCA will not guess a direction for a position that " +
                    "gets passed to a rescue.",
            )
        }

        val windMs = windSpeedKmh!! / 3.6
        val currentMs = currentSpeedKmh!! / 3.6
        val seconds = hours * 3600.0

        // Wind blows TOWARD (from + 180).
        val windToward = Math.toRadians((windDirectionDeg!! + 180.0) % 360.0)
        val dwEast = sin(windToward)
        val dwNorth = cos(windToward)
        // 90 degrees right of the wind.
        val cwEast = cos(windToward)
        val cwNorth = -sin(windToward)

        // The current already points where it is going.
        val cur = Math.toRadians(((currentDirectionDeg!! % 360.0) + 360.0) % 360.0)
        val curEast = currentMs * sin(cur)
        val curNorth = currentMs * cos(cur)

        // OpenDrift's leeway.py update(): the epsilon term enters as
        // eps/20 * windspeed + eps/2, and the bracket is cm/s -> m/s.
        fun component(slope: Double, offset: Double, eps: Double) =
            ((slope + eps / 20.0) * windMs + offset + eps / 2.0) * 0.01

        val dwMean = component(DWL_SLOPE, DWL_OFFSET, 0.0)
        // A hull always drifts downwind; a negative bound would be
        // unphysical, so the low side is floored rather than reversed.
        val dwLow = max(0.0, component(DWL_SLOPE, DWL_OFFSET, -DWL_STD))
        val dwHigh = component(DWL_SLOPE, DWL_OFFSET, +DWL_STD)
        val cwHigh = abs(component(CWL_SLOPE, CWL_OFFSET, +CWL_STD))

        fun corner(downwind: Double, crosswind: Double): Pair<Double, Double> {
            val east = (curEast + downwind * dwEast + crosswind * cwEast) * seconds
            val north = (curNorth + downwind * dwNorth + crosswind * cwNorth) * seconds
            return offsetPosition(lat, lon, east, north)
        }

        val centre = corner(dwMean, 0.0)
        val box = listOf(
            corner(dwLow, +cwHigh),
            corner(dwHigh, +cwHigh),
            corner(dwHigh, -cwHigh),
            corner(dwLow, -cwHigh),
        )

        val meanEast = (curEast + dwMean * dwEast) * seconds
        val meanNorth = (curNorth + dwMean * dwNorth) * seconds
        val distanceKm = hypot(meanEast, meanNorth) / 1000.0
        val bearing = (Math.toDegrees(atan2(meanEast, meanNorth)) + 360.0) % 360.0

        return Result(
            ok = true,
            hours = hours,
            originLat = lat,
            originLon = lon,
            centreLat = centre.first,
            centreLon = centre.second,
            box = box,
            distanceKm = distanceKm,
            bearingDeg = bearing,
            confidenceNote = confidenceNote(hours),
        )
    }

    private fun offsetPosition(
        lat: Double,
        lon: Double,
        eastM: Double,
        northM: Double,
    ): Pair<Double, Double> {
        val dLat = northM / M_PER_DEG_LAT
        val dLon = eastM / (M_PER_DEG_LAT * cos(Math.toRadians(lat)))
        return (lat + dLat) to (lon + dLon)
    }

    private fun confidenceNote(hours: Double): String = when {
        hours <= 6 ->
            "Wind and current are held at what they are now. Over 6 hours near " +
            "the coast that is a reasonable assumption."
        hours <= 12 ->
            "Wind and current are held at what they are now, which they will not " +
            "be for 12 hours. Treat this as a search area, not a position."
        else ->
            "Over 24 hours the wind and current WILL change and this does not know " +
            "how. It is a direction, not a forecast. Give the Coast Guard the " +
            "6-hour box and your last known position."
    }

    private val COMPASS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** 16-point compass name -- what gets said out loud, in any language. */
    fun compass(bearingDeg: Double): String =
        COMPASS[(((bearingDeg % 360.0) / 22.5 + 0.5).toInt()) % 16]

    /** Tamil for the 16-point compass, for the spoken and printed alert. */
    fun compassTamil(bearingDeg: Double): String = when (compass(bearingDeg)) {
        "N" -> "வடக்கு"
        "NNE", "NE", "ENE" -> "வடகிழக்கு"
        "E" -> "கிழக்கு"
        "ESE", "SE", "SSE" -> "தென்கிழக்கு"
        "S" -> "தெற்கு"
        "SSW", "SW", "WSW" -> "தென்மேற்கு"
        "W" -> "மேற்கு"
        else -> "வடமேற்கு"
    }

    /** Great-circle distance, for reporting how far the box has moved. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }
}
