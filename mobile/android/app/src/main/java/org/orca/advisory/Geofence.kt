package org.orca.advisory

import kotlin.math.abs

/**
 * Everywhere a boat should not be, checked against where it is.
 *
 * <p>Pure logic, no Android, no network. The service holds the state and
 * does the shouting; every rule that decides whether a crew gets warned
 * lives here where it can be tested without a boat.
 *
 * <p>WHY ONE CLASS AND NOT FOUR SERVICES. Until now the boundary watch
 * only knew about the India-Sri Lanka line. But a crew can be arrested
 * for the treaty line, fined for the marine national park, sunk by a
 * storm, and cut off entirely past the edge of mobile coverage -- and all
 * four are the same question asked of the same GPS fix. Answering them
 * separately meant four alarms with four different tones and no ranking
 * between them.
 *
 * <p>THE FOUR FENCES, most severe first:
 *
 * <ol>
 *  <li><b>IMBL</b> -- the India-Sri Lanka maritime boundary. Crossing it
 *      gets boats seized and crews jailed; 17 separate arrest incidents
 *      of Tamil Nadu fishermen in 2025.
 *  <li><b>Marine national park</b> -- fishing inside the Gulf of Mannar
 *      core zone is prohibited outright.
 *  <li><b>Live IMD warning</b> -- a storm polygon the boat has entered.
 *  <li><b>Edge of coverage</b> -- not a prohibition, a deadline. Mobile
 *      signal dies around 15 km offshore while boats work 120-150 km out,
 *      so this fires ONCE, while there is still signal, to say "download
 *      now or you have what you have".
 * </ol>
 *
 * <p>REPEAT AND RESET. A hazard that is still true in fifteen minutes is
 * still worth saying, because a crew steering a course does not remember
 * a single chime. But an alarm that cannot be silenced is an alarm that
 * gets the phone put in a locker, so every warning can be acknowledged,
 * and an acknowledged warning stays quiet until the situation CHANGES --
 * a worse band, or a different fence. See {@link #decide}.
 */
object Geofence {

    /**
     * Mobile coverage dies here.
     *
     * Measured claim, not a guess: Indian offshore mobile coverage is
     * reported to end around 15 km from shore, while fishermen work
     * 120-150 km out on five-to-seven-day trips. That gap is the reason
     * ISRO built a dedicated hardware receiver, and the reason ORCA
     * carries its advisory rather than fetching it.
     */
    const val COVERAGE_EDGE_KM = 15.0

    /** How long before an unacknowledged warning speaks again. */
    const val REPEAT_MS = 15 * 60 * 1000L

    enum class Kind { IMBL, PARK, STORM, COVERAGE }

    enum class Band { CLEAR, ADVISORY, WARNING, URGENT, INSIDE }

    data class Hazard(
        val kind: Kind,
        val band: Band,
        /** Kilometres to the hazard. 0 when inside it. */
        val distanceKm: Double,
        /** What it is called, already in the user's language. */
        val label: String = "",
    )

    /**
     * A fence's state as the service last announced it.
     *
     * Keyed by [Kind], so acknowledging the boundary does not silence a
     * storm.
     */
    data class Ack(val band: Band, val atMs: Long)

    data class Decision(
        val hazard: Hazard,
        val announce: Boolean,
        val why: String,
    )

    fun bandForDistance(km: Double, urgentKm: Double, warningKm: Double, advisoryKm: Double): Band = when {
        km <= urgentKm -> Band.URGENT
        km <= warningKm -> Band.WARNING
        km <= advisoryKm -> Band.ADVISORY
        else -> Band.CLEAR
    }

    /** Severity order, worst first, so one warning can outrank another. */
    fun severity(band: Band): Int = when (band) {
        Band.INSIDE -> 0
        Band.URGENT -> 1
        Band.WARNING -> 2
        Band.ADVISORY -> 3
        Band.CLEAR -> 4
    }

    /**
     * The single most serious hazard, or null if everything is clear.
     *
     * COVERAGE can never be the headline while a real fence is raised. It
     * is a deadline, not a prohibition, and letting it outrank the treaty
     * line because it happened to be "INSIDE" would put "download soon" in
     * red above "you are about to be arrested".
     */
    fun worst(hazards: List<Hazard>): Hazard? {
        val raised = hazards.filter { it.band != Band.CLEAR }
        val real = raised.filter { it.kind != Kind.COVERAGE }
        return (real.ifEmpty { raised }).minByOrNull { severity(it.band) }
    }

    /**
     * Is this position at sea at all?
     *
     * The coverage fence was firing for a phone sitting in the middle of
     * Chennai with full bars, because "far from an ORCA harbour" is not
     * the same as "offshore" -- everywhere inland is far from a harbour.
     * ETOPO soundings are already on the device, so the honest test is
     * whether there is water under the boat.
     *
     * Returns null when there is no sounding near enough to say, and the
     * caller then raises no coverage warning at all: not knowing is not a
     * reason to guess.
     */
    fun isAtSea(lat: Double, lon: Double, grid: Bathymetry.Grid?): Boolean? {
        if (grid == null) return null
        var bestD = Double.MAX_VALUE
        var bestE = 0
        for (i in grid.elevM.indices) {
            val dLat = grid.lat[i] - lat
            val dLon = grid.lon[i] - lon
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) { bestD = d; bestE = grid.elevM[i] }
        }
        // The grid is ~0.2 degrees; anything further than one cell away is
        // not evidence about this position.
        if (bestD > 0.09) return null
        return bestE < 0
    }

    /**
     * Should this hazard speak now?
     *
     * @param acks what has already been acknowledged, per fence.
     * @param nowMs device clock.
     *
     * Rules, in order:
     *  1. A clear fence never speaks.
     *  2. A fence never announced before speaks immediately.
     *  3. A fence that got WORSE speaks immediately, acknowledged or not
     *     -- an acknowledgement covers the situation the crew saw, not a
     *     situation that has since deteriorated.
     *  4. Otherwise it waits {@link #REPEAT_MS}.
     *
     * COVERAGE is the exception and is handled by the caller: it is a
     * deadline, not a state, and speaking it every fifteen minutes for a
     * six-day trip would be exactly the alarm fatigue this file exists to
     * avoid.
     */
    fun decide(
        hazard: Hazard,
        acks: Map<Kind, Ack>,
        nowMs: Long,
    ): Decision {
        if (hazard.band == Band.CLEAR) {
            return Decision(hazard, false, "clear of ${hazard.kind}")
        }
        val ack = acks[hazard.kind]
            ?: return Decision(hazard, true, "first warning for ${hazard.kind}")

        if (severity(hazard.band) < severity(ack.band)) {
            return Decision(
                hazard, true,
                "${hazard.kind} worsened from ${ack.band} to ${hazard.band}",
            )
        }
        val since = nowMs - ack.atMs
        return if (since >= REPEAT_MS) {
            Decision(hazard, true, "${hazard.kind} still ${hazard.band} after ${since / 60000} min")
        } else {
            Decision(
                hazard, false,
                "${hazard.kind} acknowledged ${since / 60000} min ago, quiet until 15",
            )
        }
    }

    /**
     * Ray casting, identical to StormAlerts.pointInPolygon.
     *
     * Duplicated deliberately rather than shared: this file is the fence
     * logic and must stay free of the CAP parsing that class carries, and
     * both are covered by their own tests.
     */
    fun pointInPolygon(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (latI, lonI) = polygon[i]
            val (latJ, lonJ) = polygon[j]
            if ((latI > lat) != (latJ > lat)) {
                val x = lonI + (lat - latI) * (lonJ - lonI) / (latJ - latI)
                if (lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Shortest distance from a point to a polyline, in km.
     *
     * Used for the IMBL, which is an open line rather than an area, so
     * containment is meaningless and only the gap matters.
     */
    fun distanceToLineKm(
        lat: Double,
        lon: Double,
        segments: List<List<Pair<Double, Double>>>,
    ): Double? {
        var best: Double? = null
        segments.forEach { seg ->
            for (i in 0 until seg.size - 1) {
                val d = pointToSegmentKm(lat, lon, seg[i], seg[i + 1])
                if (best == null || d < best!!) best = d
            }
        }
        return best
    }

    private fun pointToSegmentKm(
        lat: Double, lon: Double,
        a: Pair<Double, Double>, b: Pair<Double, Double>,
    ): Double {
        // Project into local km, where the segment maths is ordinary
        // planar geometry. Over a few km at 10 N the error is metres.
        val kmPerLat = 111.32
        val kmPerLon = 111.32 * Math.cos(Math.toRadians(lat))
        val px = (lon - a.second) * kmPerLon
        val py = (lat - a.first) * kmPerLat
        val bx = (b.second - a.second) * kmPerLon
        val by = (b.first - a.first) * kmPerLat
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return Math.sqrt(dx * dx + dy * dy)
    }

    /**
     * The Gulf of Mannar Marine National Park core zone, as ORCA holds it.
     *
     * A box around the published island coordinate, NOT the park's full
     * 560 km2 / 21-island boundary -- that exact geometry is not publicly
     * downloadable without a WDPA account. It mirrors PROHIBITED_ZONE in
     * orca/agents.py exactly, and it is an approximation of one real,
     * verifiable restricted feature rather than an invented one. The
     * screen says so, because a crew told "you are clear of the park" by
     * a box that is smaller than the park would be told a dangerous lie.
     */
    val MARINE_PARK: List<Pair<Double, Double>> = listOf(
        9.175 to 79.145,
        9.175 to 79.195,
        9.225 to 79.195,
        9.225 to 79.145,
    )
}
