package org.orca.advisory

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Whether an IMD storm warning is actually over THIS boat.
 *
 * <p>A port of orca/alerts.py, and the reason it is a port rather than a
 * server call is the whole point of the feature: the phone tests the
 * warning polygon against its OWN GPS fix, with no signal. A boat 40 km
 * offshore is not at a zone centroid, and the centroid is the only thing a
 * pre-matched server answer could have used.
 *
 * <p>The geometry and the severities are IMD's, shipped whole in
 * /bundle. This class decides nothing: it runs a containment test on a
 * polygon someone else drew and signed.
 *
 * <p>THE THREE BUCKETS, kept apart on purpose:
 * <ul>
 *  <li>{@code covering}     -- unexpired, has a polygon, contains you. The
 *      only bucket that may raise an alarm.
 *  <li>{@code ungeolocated} -- unexpired, but IMD named no polygon. The app
 *      cannot tell whether it covers you and says exactly that.
 *  <li>{@code elsewhere}    -- unexpired, real polygon, does not contain
 *      you. Carried with a real distance so the screen can say where the
 *      nearest live warning is instead of looking broken.
 * </ul>
 *
 * <p>And a fourth state that is not a bucket: NOT CHECKED. A bundle that
 * carries no alerts block at all means ORCA never fetched the feed. That
 * is different from "IMD has published nothing", and the two must never
 * render the same way. Crews died in Ockhi not having been told a warning
 * was missing -- they were told nothing at all.
 */
object StormAlerts {

    data class Alert(
        val identifier: String,
        val event: String,
        val headline: String,
        val description: String,
        val instruction: String,
        val severity: String,
        val urgency: String,
        val certainty: String,
        val areaDesc: String,
        val expires: String?,
        val senderName: String,
        val web: String?,
        val provenance: String,
        val signed: Boolean,
        val polygon: List<Pair<Double, Double>>?,
        /** Kilometres to the nearest polygon vertex. 0 when covering. */
        val distanceKm: Double = 0.0,
    )

    data class Feed(
        val source: String,
        val provenance: String,
        val fetchedAt: String,
        val alerts: List<Alert>,
    )

    data class Match(
        /** False means ORCA never fetched the feed -- NOT "all clear". */
        val checked: Boolean,
        val reason: String? = null,
        val source: String? = null,
        val fetchedAt: String? = null,
        val covering: List<Alert> = emptyList(),
        val ungeolocated: List<Alert> = emptyList(),
        val elsewhere: List<Alert> = emptyList(),
    ) {
        val worstSeverity: String?
            get() = covering.minByOrNull { severityRank(it.severity) }?.severity
    }

    private val SEVERITY_ORDER = mapOf(
        "Extreme" to 0, "Severe" to 1, "Moderate" to 2, "Minor" to 3, "Unknown" to 4,
    )

    fun severityRank(severity: String): Int = SEVERITY_ORDER[severity] ?: 99

    /** Parse the `alerts` block of /bundle. Null if it is absent. */
    fun parseFeed(root: JSONObject): Feed? {
        val o = root.optJSONObject("alerts") ?: return null
        val arr = o.optJSONArray("alerts") ?: JSONArray()
        val alerts = (0 until arr.length()).mapNotNull { i ->
            try { parseAlert(arr.getJSONObject(i)) } catch (e: Exception) { null }
        }
        return Feed(
            source = o.optString("source"),
            provenance = o.optString("provenance"),
            fetchedAt = o.optString("fetched_at"),
            alerts = alerts,
        )
    }

    private fun parseAlert(o: JSONObject): Alert {
        val polyArray = o.optJSONArray("polygon")
        val polygon = if (polyArray == null || polyArray.length() == 0) null else
            (0 until polyArray.length()).map { i ->
                val p = polyArray.getJSONArray(i)
                p.getDouble(0) to p.getDouble(1)
            }
        return Alert(
            identifier = o.optString("identifier"),
            event = o.optString("event"),
            headline = o.optString("headline"),
            description = o.optString("description"),
            instruction = o.optString("instruction"),
            severity = o.optString("severity", "Unknown"),
            urgency = o.optString("urgency"),
            certainty = o.optString("certainty"),
            areaDesc = o.optString("area_desc"),
            expires = o.optString("expires").takeIf { it.isNotEmpty() && it != "null" },
            senderName = o.optString("sender_name"),
            web = o.optString("web").takeIf { it.isNotEmpty() && it != "null" },
            provenance = o.optString("provenance"),
            signed = o.optBoolean("signed", false),
            polygon = polygon,
        )
    }

    /**
     * Sort a feed into the three buckets, against one position and one clock.
     *
     * @param feed null when the bundle carried no alerts block at all.
     */
    fun match(lat: Double, lon: Double, feed: Feed?, now: Instant = Instant.now()): Match {
        if (feed == null) {
            return Match(
                checked = false,
                reason = "ORCA has not fetched IMD's warning feed onto this phone. " +
                    "That is not the same as there being no warning.",
            )
        }

        val covering = mutableListOf<Alert>()
        val ungeolocated = mutableListOf<Alert>()
        val elsewhere = mutableListOf<Alert>()

        for (alert in feed.alerts) {
            if (hasExpired(alert.expires, now)) continue
            val polygon = alert.polygon
            when {
                polygon == null || polygon.size < 3 -> ungeolocated += alert
                pointInPolygon(lat, lon, polygon) -> covering += alert.copy(distanceKm = 0.0)
                else -> elsewhere += alert.copy(
                    distanceKm = distanceToPolygonKm(lat, lon, polygon),
                )
            }
        }

        return Match(
            checked = true,
            source = feed.source,
            fetchedAt = feed.fetchedAt,
            covering = covering.sortedBy { severityRank(it.severity) },
            ungeolocated = ungeolocated,
            elsewhere = elsewhere.sortedBy { it.distanceKm },
        )
    }

    /**
     * An unparseable expiry is NOT treated as expired.
     *
     * Dropping a storm warning because its timestamp did not parse is the
     * one failure mode here that could kill someone.
     */
    fun hasExpired(expires: String?, now: Instant): Boolean {
        if (expires.isNullOrEmpty()) return false
        return try {
            OffsetDateTime.parse(expires).toInstant() <= now
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ray casting, counting crossings of the horizontal line y = lat.
     *
     * Vertices are (lat, lon), matching MarineObservation's field order.
     * CAP polygons are closed (the last vertex repeats the first), which
     * this handles without special-casing. Planar, and deliberately so: at
     * ~10 degrees N the curvature error over a warning polygon is far
     * below IMD's own resolution -- these are drawn to state boundaries,
     * not to the metre.
     */
    fun pointInPolygon(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val (latI, lonI) = polygon[i]
            val (latJ, lonJ) = polygon[j]
            if ((latI > lat) != (latJ > lat)) {
                val crossingLon = lonI + (lat - latI) * (lonJ - lonI) / (latJ - latI)
                if (lon < crossingLon) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Distance to the nearest VERTEX, not the nearest edge.
     *
     * An over-estimate of how close the warning area is -- the safe
     * direction to err in for a number only ever used to say "the nearest
     * live warning is far away". Labelled approximate wherever it shows.
     */
    fun distanceToPolygonKm(lat: Double, lon: Double, polygon: List<Pair<Double, Double>>): Double =
        polygon.minOf { haversineKm(lat, lon, it.first, it.second) }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** Tamil for CAP's severity levels. IMD states these; ORCA never invents one. */
    fun severityTamil(severity: String): String = when (severity) {
        "Extreme" -> "மிக மோசம்"
        "Severe" -> "கடுமை"
        "Moderate" -> "மிதமானது"
        "Minor" -> "லேசானது"
        else -> "தெரியவில்லை"
    }
}
