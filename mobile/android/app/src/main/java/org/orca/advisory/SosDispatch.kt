package org.orca.advisory

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import kotlin.math.abs

/**
 * The one place ORCA sends a distress message, and the only one.
 *
 * <h3>ONE PRESS. ALREADY SENT.</h3>
 * Until now the SOS handed a pre-filled message to the phone's messaging
 * app: the crew then had to pick a recipient and press send. That is three
 * actions, on a pitching deck, possibly one-handed, possibly in the dark.
 * This sends on the press. There is no confirmation dialog and there is
 * deliberately no undo, because an SMS cannot be recalled and pretending
 * otherwise would be theatre.
 *
 * <h3>THE BUG THIS FILE EXISTS TO KILL</h3>
 * The old message read <i>"Position 13.1251, 80.2955"</i> — and those were
 * the coordinates of <b>Chennai harbour</b>, taken from the advisory zone,
 * not of the boat. A crew forty miles out who pressed SOS would have sent
 * the rescue to the pier they left from. That is a fabricated number
 * presented as a measurement, which is CLAUDE.md rule 1, and in this one
 * screen it is also the difference between being found and not.
 *
 * <p>So: a position in an ORCA distress message comes from the GNSS
 * receiver or it does not appear. When there is no fix the message says
 * POSITION UNKNOWN in those words. A rescue coordinator who reads
 * "position unknown" starts a search pattern; one who reads a confident
 * wrong number searches the wrong water.
 *
 * <h3>TWO MESSAGES, NOT ONE</h3>
 * The last known fix can be hours old. Waiting for a fresh one before
 * sending anything would put a 30-second GNSS cold start between a crew
 * and their only call for help. So ORCA sends immediately with whatever it
 * has — clearly stamped with that fix's age — and then asks for a live
 * fix. If a better one arrives within [FRESH_WINDOW_MS], it sends a second
 * message marked UPDATE. Two messages is the correct cost for this.
 *
 * <h3>WHY SMS AND NOT DATA</h3>
 * SMS rides the control channel and gets through at signal levels where a
 * data connection is already dead, which is exactly the condition a boat
 * offshore is in. It is not a distress beacon and ORCA never claims to be
 * one; it is the best thing a phone that is already in a pocket can do.
 */
object SosDispatch {

    private const val TAG = "ORCA"

    /** How long to wait for a live fix before giving up on the update. */
    const val FRESH_WINDOW_MS = 25_000L

    /** A last-known fix older than this is reported but not trusted to
     *  stand alone -- the update attempt matters more the staler it is. */
    const val STALE_MINUTES = 10

    /**
     * The Indian Coast Guard's maritime distress line.
     *
     * <p>It is a TELEPHONE number. Every source describing it -- the ICG's
     * own promulgation and the press coverage of it -- describes calling
     * it for search and rescue; none describes an SMS gateway behind it.
     * ORCA previously offered a "Coast Guard 1554" button that opened an
     * SMS to 1554, which most likely delivered to nothing at all. It is a
     * dial now, because a distress channel that silently drops messages is
     * worse than no button.
     */
    const val COAST_GUARD = "1554"

    // --- what happened ---------------------------------------------------

    /** A real fix, or nothing. Never a substituted or default position. */
    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Float?,
        val ageMinutes: Long,
        val provider: String,
    )

    enum class Outcome {
        /** Handed to the radio. */
        SENT,

        /** No number configured -- nothing to send to. */
        NO_CONTACT,

        /** SEND_SMS not granted. On Android 15 a sideloaded app needs
         *  "Allow restricted settings" before the toggle can even be set. */
        NO_PERMISSION,

        /** The radio refused it, for every number. */
        FAILED,

        /** Some numbers went, some did not. Help may still be coming. */
        PARTIAL,
    }

    data class Report(
        val outcome: Outcome,
        /** Numbers the radio accepted. */
        val sentTo: List<String>,
        /** Numbers it refused, named so they can be fixed or re-tried. */
        val failedTo: List<String>,
        val message: String,
        val fix: Fix?,
        val detail: String,
    )

    /**
     * The most recent send.
     *
     * <p>The SOS can be fired from three places -- the home screen's hold,
     * the SOS screen's button, and the volume-key watch running with the
     * app closed. Whichever one fired it, the SOS screen opened afterwards
     * must show what actually happened rather than a blank page, so the
     * outcome lives here rather than in any one screen's state.
     */
    @Volatile
    var lastReport: Report? = null
        private set

    // --- position --------------------------------------------------------

    /**
     * The best fix the phone already holds.
     *
     * Returns null rather than anything else. There is no fallback to a
     * zone centroid, a harbour, or a last-resort constant: see the class
     * comment.
     */
    @SuppressLint("MissingPermission")
    fun lastFix(context: Context): Fix? {
        if (!hasLocation(context)) return null
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let(::toFix)
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission vanished mid-call: ${e.message}")
            null
        }
    }

    private fun toFix(l: Location): Fix = Fix(
        lat = l.latitude,
        lon = l.longitude,
        accuracyM = if (l.hasAccuracy()) l.accuracy else null,
        ageMinutes = ((System.currentTimeMillis() - l.time) / 60_000L).coerceAtLeast(0),
        provider = l.provider ?: "gnss",
    )

    fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasSms(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Degrees and decimal minutes -- what a marine VHF call and a Coast
     * Guard plot both use. Decimal degrees is what a phone produces and is
     * fine to read, but a rescue coordinator transcribing a position works
     * in DDM, and the message is for them.
     */
    fun formatPosition(lat: Double, lon: Double): String {
        // Latitude is padded to two digits and longitude to three, which is
        // the marine convention and not a cosmetic choice: longitude runs to
        // 180 and a coordinate written "79" where "079" is expected is the
        // classic way a transcribed position lands in the wrong ocean.
        fun part(v: Double, width: Int, pos: String, neg: String): String {
            val hemi = if (v >= 0) pos else neg
            val a = abs(v)
            val deg = a.toInt()
            val min = (a - deg) * 60.0
            return String.format("%0${width}d %05.2f%s", deg, min, hemi)
        }
        return "${part(lat, 2, "N", "S")} ${part(lon, 3, "E", "W")}"
    }

    // --- the message -----------------------------------------------------

    /**
     * The distress text.
     *
     * Position first: an SMS can be truncated by a gateway, and the one
     * thing that must survive truncation is where to look.
     *
     * @param zoneHint the nearest ORCA harbour, as a NAME only. It is a
     *        landmark to orient a reader, never a coordinate, and it is
     *        never the source of the numbers above it.
     */
    fun compose(fix: Fix?, zoneHint: String?, boat: String?, update: Boolean = false): String {
        val head = if (update) "ORCA SOS UPDATE." else "ORCA SOS."
        return buildString {
            append(head).append(' ')
            if (fix != null) {
                append(formatPosition(fix.lat, fix.lon))
                fix.accuracyM?.let { append(" +-").append(it.toInt()).append("m") }
                append(
                    when {
                        fix.ageMinutes <= 0 -> " (now)"
                        fix.ageMinutes == 1L -> " (1 min old)"
                        else -> " (${fix.ageMinutes} min old)"
                    },
                )
                append(". ")
            } else {
                // Said in these words on purpose. A coordinator who reads
                // this runs a search pattern; there is no number here to
                // send them to the wrong place.
                append("POSITION UNKNOWN - no GPS fix. ")
            }
            if (!zoneHint.isNullOrBlank()) append("Nearest port: ").append(zoneHint).append(". ")
            if (!boat.isNullOrBlank()) append("Boat: ").append(boat).append(". ")
            append("Need help.")
        }
    }

    // --- sending ---------------------------------------------------------

    /**
     * Send, now.
     *
     * Synchronous and immediate: the caller has already decided, and this
     * asks nothing further. Call [requestUpdate] straight after to chase a
     * live fix.
     */
    fun fire(context: Context, contacts: List<String>, zoneHint: String?, boat: String?): Report =
        record(send(context, contacts, zoneHint, boat))

    private fun record(r: Report): Report { lastReport = r; return r }

    private fun send(
        context: Context,
        contacts: List<String>,
        zoneHint: String?,
        boat: String?,
    ): Report {
        val fix = lastFix(context)
        val message = compose(fix, zoneHint, boat)
        val numbers = contacts.map { it.trim() }.filter { it.isNotEmpty() }

        if (numbers.isEmpty()) {
            return Report(
                Outcome.NO_CONTACT, emptyList(), emptyList(), message, fix,
                "No emergency number is saved. Open Settings and add one — " +
                    "ORCA will not guess a number to send a distress call to.",
            )
        }
        if (!hasSms(context)) {
            return Report(
                Outcome.NO_PERMISSION, emptyList(), numbers, message, fix,
                "ORCA does not have permission to send SMS.",
            )
        }

        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val errors = mutableListOf<String>()
        numbers.forEach { number ->
            // Each number is attempted independently. One bad entry in the
            // list must not stop the message reaching the other three.
            try {
                sendTo(context, number, message)
                sent += number
            } catch (e: Exception) {
                Log.e(TAG, "SOS to $number failed: ${e.message}")
                failed += number
                errors += "$number: ${e.message}"
            }
        }

        val outcome = when {
            sent.isEmpty() -> Outcome.FAILED
            failed.isEmpty() -> Outcome.SENT
            // Some got through. That is a SEND, and the screen names who
            // did not -- reporting the whole thing as failed would send a
            // crew looking for another way out when help is already coming.
            else -> Outcome.PARTIAL
        }
        val detail = buildString {
            if (sent.isNotEmpty()) append("Sent to ").append(sent.joinToString(", ")).append(". ")
            if (failed.isNotEmpty()) {
                append("Did NOT reach ").append(failed.joinToString(", ")).append(". ")
                append(errors.joinToString("; "))
            }
        }.trim()
        Log.i(TAG, "SOS sent=${sent.size} failed=${failed.size}")
        return Report(outcome, sent, failed, message, fix, detail)
    }

    private fun sendTo(context: Context, number: String, message: String) {
        val sms = smsManager(context)
        // Multipart because a position plus a port name can exceed a single
        // 160-character segment, and a distress message must not arrive
        // with the coordinates cut in half.
        val parts = sms.divideMessage(message)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(number, null, parts, null, null)
        } else {
            sms.sendTextMessage(number, null, message, null, null)
        }
    }

    /**
     * Ask the GNSS for a live fix and send an UPDATE if a better one
     * arrives inside [FRESH_WINDOW_MS].
     *
     * "Better" means genuinely newer than what the first message carried.
     * Sending a second message with the same stale coordinates would cost
     * the crew's credit and tell the reader nothing.
     */
    @SuppressLint("MissingPermission")
    fun requestUpdate(
        context: Context,
        contacts: List<String>,
        zoneHint: String?,
        boat: String?,
        firstFix: Fix?,
        onUpdate: (Report) -> Unit,
    ) {
        val numbers = contacts.map { it.trim() }.filter { it.isNotEmpty() }
        if (numbers.isEmpty() || !hasLocation(context) || !hasSms(context)) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        try {
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    val fresh = toFix(location)
                    if (firstFix != null && fresh.ageMinutes >= firstFix.ageMinutes) return
                    val text = compose(fresh, zoneHint, boat, update = true)
                    val sent = mutableListOf<String>()
                    val failed = mutableListOf<String>()
                    numbers.forEach { n ->
                        try { sendTo(context, n, text); sent += n }
                        catch (e: Exception) {
                            Log.e(TAG, "SOS update to $n failed: ${e.message}"); failed += n
                        }
                    }
                    val report = Report(
                        when {
                            sent.isEmpty() -> Outcome.FAILED
                            failed.isEmpty() -> Outcome.SENT
                            else -> Outcome.PARTIAL
                        },
                        sent, failed, text, fresh,
                        if (sent.isEmpty()) "Updated position could not be sent."
                        else "Updated position sent to ${sent.joinToString(", ")}.",
                    )
                    onUpdate(record(report))
                }

                @Deprecated("Required by the pre-30 interface")
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                override fun onProviderEnabled(p: String) = Unit
                override fun onProviderDisabled(p: String) = Unit
            }
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            // Stop listening either way. A distress screen that leaves the
            // GNSS running flattens the battery the crew needs for the rest
            // of the emergency.
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                runCatching { lm.removeUpdates(listener) }
            }, FRESH_WINDOW_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot request a live fix: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
}
