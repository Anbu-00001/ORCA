package org.orca.advisory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Everything the app knows, and the only place it talks to ORCA.
 *
 * THE ONE RULE THIS FILE OBEYS: it decides nothing. Every `action`,
 * `severity`, threshold and number here was computed by orca/policy.py on
 * shore and travelled inside `GET /bundle`. A second implementation of the
 * safety rules is a second thing that can disagree with the first, and the
 * day they disagree ORCA has no defensible answer about which was right
 * (docs/MOBILE_APP.md §2).
 *
 * A reviewer should be able to grep this file for `2.5`, `0.6`, `> ` on a
 * wave height, or the string "GO" used as a decision rather than a label,
 * and find nothing.
 *
 * THREE SOURCES, in strict priority order:
 *   1. a live GET /bundle, if a backend is reachable
 *   2. the last bundle stored on this device
 *   3. the seed bundle inside the APK, for a phone that has never had
 *      signal -- labelled as shipped, never as downloaded
 */
class OrcaRepository(private val context: Context) {

    companion object {
        private const val TAG = "ORCA"
        private const val PREFS = "orca.store"
        private const val KEY_BUNDLE = "bundle.v1"
        private const val KEY_DOWNLOADED = "downloaded_at"
        private const val KEY_FROM_SEED = "from_seed"
        /** `adb reverse tcp:8000 tcp:8000` makes this the developer laptop. */
        const val DEFAULT_API = "http://127.0.0.1:8000"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- state the UI renders -------------------------------------------

    data class Reading(
        val variable: String,
        val value: Double,
        val unit: String,
        val source: String,
        val validTime: String,
        val confidence: Double,
        val id: String,
    )

    data class ZoneAdvisory(
        val zone: String,
        val lat: Double,
        val lon: Double,
        /** policy.py's verdict, passed through untouched. */
        val action: String,
        val severity: String,
        val reason: String,
        /** Where to go instead, on a SAFER ALTERNATIVE. Null otherwise. */
        val alternative: String?,
        val readings: List<Reading>,
    ) {
        fun reading(variable: String): Reading? = readings.firstOrNull { it.variable == variable }
    }

    data class Boundary(
        val segments: List<List<Pair<Double, Double>>>,
        val urgentKm: Double,
        val warningKm: Double,
        val advisoryKm: Double,
        val source: String,
    )

    /**
     * The four numbers DriftModel needs, per zone.
     *
     * Nullable one by one, on purpose: a zone with a wind speed but no
     * wind direction can produce no drift box at all, and the screen has
     * to be able to say WHICH reading is missing rather than showing a
     * box built on a guess.
     */
    data class DriftInputs(
        val zone: String,
        val lat: Double,
        val lon: Double,
        val windSpeedKmh: Double?,
        val windDirectionDeg: Double?,
        val currentSpeedKmh: Double?,
        val currentDirectionDeg: Double?,
        val validTime: String?,
        val source: String?,
    )

    data class Advisory(
        val zones: List<ZoneAdvisory>,
        val boundary: Boundary?,
        /**
         * IMD's warning feed, shipped whole. Null means ORCA never fetched
         * it -- which the storm screen renders as "not checked", never as
         * "all clear". Those are different facts.
         */
        val alerts: StormAlerts.Feed?,
        val driftInputs: List<DriftInputs>,
        /** When ORCA collected the newest reading. Server-stated. */
        val collectedAt: Instant?,
        /** When this device received the bundle. Null for the seed. */
        val downloadedAt: Instant?,
        val fromSeed: Boolean,
        val raw: String,
    ) {
        fun zone(name: String): ZoneAdvisory? = zones.firstOrNull { it.zone == name }

        /**
         * How old the READINGS are, by the device clock.
         *
         * `freshness_min` on each observation is computed at FETCH time and
         * DOES NOT GROW while the bundle sits on a phone -- measured on the
         * web client, a two-day-old cache still displayed "14 h old". At
         * sea that is the difference between usable and dangerous, so this
         * is computed here from collectedAt against the device clock.
         *
         * Returns null when the clock cannot support the claim (unset, or
         * reading before the collection). "Age unknown" is a correct
         * answer; an invented one is not.
         */
        fun readingAgeMinutes(now: Instant = Instant.now()): Long? {
            val at = collectedAt ?: return null
            val minutes = ChronoUnit.MINUTES.between(at, now)
            return if (minutes < 0) null else minutes
        }

        fun downloadAgeMinutes(now: Instant = Instant.now()): Long? {
            val at = downloadedAt ?: return null
            val minutes = ChronoUnit.MINUTES.between(at, now)
            return if (minutes < 0) null else minutes
        }
    }

    // --- loading ---------------------------------------------------------

    /** The stored bundle, or the seed, or null. Never blocks on network. */
    fun loadLocal(): Advisory? {
        val stored = prefs.getString(KEY_BUNDLE, null)
        if (stored != null) {
            parse(stored,
                downloadedAt = prefs.getString(KEY_DOWNLOADED, null)?.let(::parseInstant),
                fromSeed = prefs.getBoolean(KEY_FROM_SEED, false))?.let { return it }
            Log.w(TAG, "Stored bundle unreadable; falling back to the seed")
        }
        return loadSeed()
    }

    /** The advisory shipped inside the APK. */
    private fun loadSeed(): Advisory? = try {
        val text = context.assets.open("bundle.json").bufferedReader().use { it.readText() }
        // downloadedAt stays NULL: the seed was never downloaded to this
        // device, and stamping install time would be inventing a moment
        // nothing here knows (CLAUDE.md rule 1). The UI says "shipped with
        // the app" instead of a false age.
        parse(text, downloadedAt = null, fromSeed = true)
    } catch (e: Exception) {
        Log.w(TAG, "No seed advisory in assets: ${e.message}")
        null
    }

    /** Pull a fresh advisory. Throws on any failure -- the caller keeps
     *  whatever it already had rather than showing nothing. */
    suspend fun refresh(apiBase: String = DEFAULT_API): Advisory = withContext(Dispatchers.IO) {
        val connection = (URL("$apiBase/bundle").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 8000
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode != 200) {
                throw IllegalStateException("bundle HTTP ${connection.responseCode}")
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val now = Instant.now().toString()
            prefs.edit()
                .putString(KEY_BUNDLE, text)
                .putString(KEY_DOWNLOADED, now)
                .putBoolean(KEY_FROM_SEED, false)
                .apply()
            // Push the new verdict to the home screen straight away. Without
            // this the widget waits for its own 30-minute tick and can sit a
            // whole half-hour behind the app it lives beside.
            VerdictWidget.refreshAll(context)
            parse(text, downloadedAt = Instant.parse(now), fromSeed = false)
                ?: throw IllegalStateException("bundle carried no zones")
        } finally {
            connection.disconnect()
        }
    }

    /** Potential Fishing Zones. Network only -- there is deliberately no
     *  cached fallback, because a PFZ is a statement about *today's*
     *  satellite pass and a stale one is worse than none. */
    suspend fun fetchPfz(apiBase: String = DEFAULT_API): List<PfzEntry> = withContext(Dispatchers.IO) {
        val connection = (URL("$apiBase/pfz").openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 8000; requestMethod = "GET"
        }
        try {
            if (connection.responseCode != 200) throw IllegalStateException("pfz HTTP ${connection.responseCode}")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val array = root.getJSONArray("zones")
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                PfzEntry(
                    zone = o.getString("zone"),
                    // `productive` is a TRISTATE and must stay one. JSON
                    // null means the satellite could not see through cloud
                    // -- six of ten zones had no usable pixel in a 15-day
                    // window. "We could not see" and "there are no fish"
                    // are different statements.
                    productive = if (o.isNull("productive")) null else o.getBoolean("productive"),
                    why = o.getString("why"),
                    chlorophyll = o.optJSONObject("chlorophyll")?.optDouble("value"),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    data class PfzEntry(
        val zone: String,
        val productive: Boolean?,
        val why: String,
        val chlorophyll: Double?,
    )

    // --- parsing ---------------------------------------------------------

    private fun parseInstant(text: String): Instant? = try { Instant.parse(text) } catch (e: Exception) { null }

    private fun parse(text: String, downloadedAt: Instant?, fromSeed: Boolean): Advisory? = try {
        val root = JSONObject(text)
        val zonesArray = root.optJSONArray("zones") ?: JSONArray()
        val zones = (0 until zonesArray.length()).mapNotNull { i ->
            parseZone(zonesArray.getJSONObject(i))
        }
        if (zones.isEmpty()) null
        else Advisory(
            zones = zones,
            boundary = root.optJSONObject("boundary")?.let(::parseBoundary),
            alerts = StormAlerts.parseFeed(root),
            driftInputs = parseDriftInputs(root),
            collectedAt = root.optString("cache_fetched_at").takeIf { it.isNotEmpty() }?.let(::parseInstant),
            downloadedAt = downloadedAt,
            fromSeed = fromSeed,
            raw = text,
        )
    } catch (e: Exception) {
        Log.w(TAG, "Bundle parse failed: ${e.message}")
        null
    }

    private fun parseZone(o: JSONObject): ZoneAdvisory? {
        val primary = o.optJSONObject("primary_zone") ?: return null
        val chosen = o.optJSONObject("chosen_zone")
        val evidence = o.optJSONArray("evidence") ?: JSONArray()
        val readings = (0 until evidence.length()).mapNotNull { i ->
            val e = evidence.getJSONObject(i)
            try {
                Reading(
                    variable = e.getString("variable"),
                    value = e.getDouble("value"),
                    unit = e.optString("unit"),
                    source = e.optString("source"),
                    validTime = e.optString("valid_time"),
                    confidence = e.optDouble("confidence", 0.0),
                    id = e.optString("id"),
                )
            } catch (ex: Exception) { null }
        }
        val primaryName = primary.getString("name")
        val chosenName = chosen?.optString("name")
        return ZoneAdvisory(
            zone = primaryName,
            lat = primary.optDouble("lat"),
            lon = primary.optDouble("lon"),
            action = o.optString("action"),
            severity = o.optString("severity", "none"),
            reason = o.optString("reason"),
            alternative = chosenName?.takeIf { it.isNotEmpty() && it != primaryName },
            readings = readings,
        )
    }

    private fun parseDriftInputs(root: JSONObject): List<DriftInputs> {
        val arr = root.optJSONArray("drift_inputs") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.getJSONObject(i)
                // Each reading arrives as an object carrying its own source
                // and valid_time, or as JSON null when ORCA has no reading.
                // A null must stay null all the way to DriftModel, which
                // refuses on it -- CLAUDE.md rule 1.
                fun value(key: String): Double? =
                    o.optJSONObject(key)?.let { if (it.has("value")) it.getDouble("value") else null }
                fun meta(key: String, field: String): String? =
                    o.optJSONObject(key)?.optString(field)?.takeIf { it.isNotEmpty() }

                DriftInputs(
                    zone = o.getString("zone"),
                    lat = o.getDouble("lat"),
                    lon = o.getDouble("lon"),
                    windSpeedKmh = value("wind_speed_kmh"),
                    windDirectionDeg = value("wind_direction_deg"),
                    currentSpeedKmh = value("current_speed_kmh"),
                    currentDirectionDeg = value("current_direction_deg"),
                    validTime = meta("wind_speed_kmh", "valid_time"),
                    source = meta("wind_speed_kmh", "source"),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed drift_inputs entry: ${e.message}")
                null
            }
        }
    }

    private fun parseBoundary(o: JSONObject): Boundary? {
        val segsArray = o.optJSONArray("segments") ?: return null
        val bands = o.optJSONObject("bands_km")
        val segments = (0 until segsArray.length()).map { i ->
            val line = segsArray.getJSONArray(i)
            (0 until line.length()).map { j ->
                val p = line.getJSONArray(j)
                p.getDouble(0) to p.getDouble(1)
            }
        }
        if (segments.isEmpty()) return null
        // Bands come from the SERVER, read out of orca/agents.py. The
        // defaults below exist only so a malformed payload cannot crash the
        // app -- they are never the source of truth.
        return Boundary(
            segments = segments,
            urgentKm = bands?.optDouble("urgent", 2.0) ?: 2.0,
            warningKm = bands?.optDouble("warning", 5.0) ?: 5.0,
            advisoryKm = bands?.optDouble("advisory", 10.0) ?: 10.0,
            source = o.optString("source"),
        )
    }

    /** The boundary payload as JSON, for handing to BoundaryWatchService. */
    fun boundaryJson(): String? = try {
        val stored = prefs.getString(KEY_BUNDLE, null)
            ?: context.assets.open("bundle.json").bufferedReader().use { it.readText() }
        JSONObject(stored).optJSONObject("boundary")?.toString()
    } catch (e: Exception) {
        Log.w(TAG, "No boundary geometry available: ${e.message}")
        null
    }
}
