package org.orca.advisory

/**
 * When the boundary warning should actually SPEAK.
 *
 * <p>Pure logic, no Android, so the rules that decide whether a crew gets
 * shouted at can be tested without a boat.
 *
 * <p>WHY THIS EXISTS. The watch service used to alarm on distance alone:
 * cross into the 5 km band, get a warning. Two independent reviews of the
 * research flagged the same failure, and it is the one that kills adoption:
 *
 * <blockquote>"Constant false alarms during parallel fishing lead to alarm
 * fatigue and app muting." — and separately, on why apps get abandoned:
 * "Triggering a danger voice message too often will make fishers ignore
 * it."</blockquote>
 *
 * <p>Distance alone is the wrong signal, in both directions:
 * <ul>
 *  <li>A boat working a net PARALLEL to the line at 4.9 km is not going to
 *      cross. It gets warned anyway, wobbles across the band edge on GPS
 *      noise, and gets warned again. After the third time the phone goes in
 *      a pocket.
 *  <li>A boat at 11 km running straight at the line at 12 km/h crosses in
 *      under an hour and gets NOTHING, because 11 km is outside every band.
 * </ul>
 *
 * <p>What matters is whether the gap is CLOSING, and how fast. So:
 *
 * <ol>
 *  <li>URGENT always speaks. At 2 km the reason you are there stops
 *      mattering -- this is never suppressed, whatever the heading.
 *  <li>WARNING and ADVISORY speak only while the boat is actually closing.
 *      Holding station or opening the range stays silent.
 *  <li>A band must hold for two consecutive fixes before it speaks, so GPS
 *      noise at a band edge cannot chatter.
 *  <li>Any band re-speaks if time-to-boundary falls under
 *      {@link #URGENT_MINUTES}, because a fast approach inside an
 *      already-announced band is new information.
 * </ol>
 *
 * <p>It reports a distance, a closing speed and a time. It never says GO or
 * DO NOT GO -- that stays orca/policy.py's, on shore.
 */
object BoundaryAlarm {

    /** Below this, the boat is holding station or opening the range. */
    const val CLOSING_FLOOR_KMH = 1.0

    /** A re-announce threshold, in minutes to the line. */
    const val URGENT_MINUTES = 15.0

    /** Fixes older than this are not evidence of the current heading. */
    const val WINDOW_MS = 5 * 60 * 1000L

    /** A band must survive this many consecutive fixes to speak. */
    const val STABLE_FIXES = 2

    data class Fix(val timeMs: Long, val distanceKm: Double)

    data class Bands(
        val urgentKm: Double,
        val warningKm: Double,
        val advisoryKm: Double,
    )

    data class Decision(
        val band: String,
        val announce: Boolean,
        /** km/h the gap is closing at. Negative means opening. Null if unknown. */
        val closingKmh: Double?,
        /** Minutes to the line at the current closing rate. Null if not closing. */
        val minutesToBoundary: Double?,
        /** Plain-language reason, logged and shown in the ongoing notification. */
        val why: String,
    )

    fun bandFor(distanceKm: Double, bands: Bands): String = when {
        distanceKm <= bands.urgentKm -> "urgent"
        distanceKm <= bands.warningKm -> "warning"
        distanceKm <= bands.advisoryKm -> "advisory"
        else -> "clear"
    }

    /**
     * Closing speed in km/h over the fixes inside the window.
     *
     * Positive means the gap is shrinking. Null when there are not two
     * usable fixes -- a single fix says nothing about a heading, and
     * guessing one would be inventing the input the whole rule turns on.
     */
    fun closingKmh(history: List<Fix>, nowMs: Long): Double? {
        val recent = history.filter { nowMs - it.timeMs <= WINDOW_MS }.sortedBy { it.timeMs }
        if (recent.size < 2) return null
        val first = recent.first()
        val last = recent.last()
        val hours = (last.timeMs - first.timeMs) / 3_600_000.0
        if (hours <= 0.0) return null
        return (first.distanceKm - last.distanceKm) / hours
    }

    /**
     * @param history every fix seen, newest last. Only the last WINDOW_MS
     *   is used; the caller does not need to prune.
     * @param lastAnnouncedBand the band last spoken, or null if nothing has.
     * @param lastAnnouncedMinutes minutes-to-boundary at that announcement,
     *   so a fast approach inside the same band can re-speak.
     */
    fun decide(
        history: List<Fix>,
        bands: Bands,
        lastAnnouncedBand: String?,
        lastAnnouncedMinutes: Double? = null,
        nowMs: Long = history.lastOrNull()?.timeMs ?: 0L,
    ): Decision {
        val current = history.lastOrNull()
            ?: return Decision("clear", false, null, null, "No position yet.")

        val band = bandFor(current.distanceKm, bands)
        val closing = closingKmh(history, nowMs)
        val minutes = if (closing != null && closing > CLOSING_FLOOR_KMH) {
            current.distanceKm / closing * 60.0
        } else null

        if (band == "clear") {
            return Decision(band, false, closing, minutes,
                "Boundary ${Units.distance(current.distanceKm)} away — outside every warning band.")
        }

        // Rule 3: a band must hold before it speaks. GPS noise at a band
        // edge would otherwise chatter the alarm on and off.
        val stable = history.takeLast(STABLE_FIXES)
            .let { it.size >= STABLE_FIXES && it.all { f -> bandFor(f.distanceKm, bands) == band } }

        // Rule 1: URGENT is never suppressed, for any reason.
        if (band == "urgent") {
            val announce = band != lastAnnouncedBand
            return Decision(band, announce, closing, minutes,
                "Inside the urgent band at ${fmt1(current.distanceKm)} km. " +
                    "This one speaks whatever the heading.")
        }

        if (!stable) {
            return Decision(band, false, closing, minutes,
                "Just entered the $band band — waiting for a second fix before speaking.")
        }

        // Rule 2: only speak while the gap is actually closing.
        if (closing == null) {
            return Decision(band, false, closing, minutes,
                "Only one fix so far — cannot tell whether you are closing on the line.")
        }
        if (closing <= CLOSING_FLOOR_KMH) {
            return Decision(band, false, closing, minutes,
                if (closing < 0)
                    "Boundary ${Units.distance(current.distanceKm)} away and opening — silent."
                else
                    "Boundary ${Units.distance(current.distanceKm)} away, holding station — silent.")
        }

        // Rule 4: re-speak inside the same band if the approach has become
        // urgent in time even though it has not in distance.
        val newBand = band != lastAnnouncedBand
        val becameUrgent = minutes != null && minutes <= URGENT_MINUTES &&
            (lastAnnouncedMinutes == null || lastAnnouncedMinutes > URGENT_MINUTES)

        return Decision(
            band = band,
            announce = newBand || becameUrgent,
            closingKmh = closing,
            minutesToBoundary = minutes,
            why = "Closing at ${Units.speed(closing)} — " +
                (minutes?.let { "about ${it.toInt()} min to the line." } ?: "time unknown."),
        )
    }

    /**
     * The spoken warning, now carrying the number that is actually
     * actionable: minutes, not kilometres. A crew steering cannot convert
     * "5 km" into a decision without also knowing their own speed.
     *
     * Tamil is the same wording as orca/phrase_ta.py's IMBL table,
     * duplicated only because a background service cannot reach Python.
     */
    fun message(d: Decision, distanceKm: Double, tamil: Boolean): String {
        // NAUTICAL MILES, not kilometres. This string is spoken aloud to a
        // crew who are steering, and every chart, forecast and radio call
        // they have ever heard uses miles. Making them convert in their
        // head is the opposite of what a spoken warning is for.
        val km = Units.distance(distanceKm)
        val mins = d.minutesToBoundary?.toInt()
        return when (d.band) {
            "urgent" ->
                if (tamil) "ஆபத்து. இலங்கை கடல் எல்லைக்கு மிக அருகில் இருக்கிறீர்கள். இப்போதே திரும்பிச் செல்லுங்கள்."
                else "Danger. You are very close to the Sri Lanka maritime boundary. Turn back now."
            "warning" ->
                if (tamil)
                    "எச்சரிக்கை. கடல் எல்லை $km தொலைவில் உள்ளது." +
                        (mins?.let { " இந்த வேகத்தில் $it நிமிடத்தில் எல்லையை அடைவீர்கள்." } ?: "") +
                        " மேற்கு நோக்கித் திரும்புங்கள்."
                else
                    "Warning. The maritime boundary is $km away." +
                        (mins?.let { " At this speed you reach it in $it minutes." } ?: "") +
                        " Turn west."
            else ->
                if (tamil)
                    "கடல் எல்லை $km தொலைவில் உள்ளது." +
                        (mins?.let { " இந்த வேகத்தில் $it நிமிடம்." } ?: "") +
                        " கவனமாக இருங்கள்."
                else
                    "The maritime boundary is $km away." +
                        (mins?.let { " About $it minutes at this speed." } ?: "") +
                        " Be careful."
        }
    }

    private fun fmt1(v: Double) = String.format("%.1f", v)
}
