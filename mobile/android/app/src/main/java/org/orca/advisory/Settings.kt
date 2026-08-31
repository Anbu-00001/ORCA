package org.orca.advisory

import android.content.Context

/**
 * The handful of things that make ORCA one crew's app rather than
 * everyone's.
 *
 * <p>WHAT THE RESEARCH ACTUALLY SAID, because these are not preferences
 * invented to fill a screen. Studies of app adoption among older and
 * low-literacy users in India converge on four things, and every one of
 * them is a setting here:
 *
 * <ul>
 *  <li><b>Text size.</b> "Enlarged text and touch targets" is the single
 *      most-cited requirement. A fixed 15sp body is a decision made for
 *      someone whose eyes are not fifty years old and wet.
 *  <li><b>Audio instead of reading.</b> "Textual interfaces are unusable
 *      by first-time low-literacy users." ORCA can already speak Tamil;
 *      this makes it do so without being asked.
 *  <li><b>Battery.</b> Named repeatedly as a barrier, and a trip is five
 *      to seven days. GPS is the expensive part, so its rate is a choice.
 *  <li><b>Fewer, flatter choices.</b> So this screen is one list, no
 *      sub-pages, no tabs.
 * </ul>
 *
 * <p>And two that are specific to this app rather than to usability:
 *
 * <ul>
 *  <li><b>Home harbour.</b> ORCA opens on Chennai for everybody, which is
 *      wrong for nine crews in ten. The verdict a fisherman wants is for
 *      the water they actually work.
 *  <li><b>Who to call.</b> The SOS screen hands over a pre-written
 *      message with no number in it. In an emergency, typing one is the
 *      step that does not happen.
 * </ul>
 */
object Settings {

    private const val FILE = "orca.settings"

    // --- text size ---------------------------------------------------------

    enum class TextSize(val scale: Float, val label: String) {
        NORMAL(1.0f, "Normal"),
        LARGE(1.25f, "Large"),
        HUGE(1.5f, "Very large"),
    }

    // --- GPS rate ----------------------------------------------------------

    /**
     * How often the background watch takes a fix.
     *
     * BALANCED is the default rather than SAVER because the boundary watch
     * is the feature that keeps crews out of jail, and a five-minute fix
     * interval at 6 knots is nearly a nautical mile of travel between
     * checks. SAVER exists for a long trip where the boundary is far away
     * and the battery has to last six days.
     */
    enum class GpsRate(val seconds: Long, val label: String) {
        ALERT(15, "Every 15 s — near the boundary"),
        BALANCED(60, "Every minute — normal"),
        SAVER(300, "Every 5 min — save battery"),
    }

    // --- hull --------------------------------------------------------------

    /**
     * Which Allen & Plourde category the boat matches.
     *
     * These are the PUBLISHED SAR categories, named as the report names
     * them. ORCA does not offer "vallam" or "FRP boat" because nobody has
     * measured the leeway of those hulls and inventing a coefficient for
     * one would be inventing the position it produces (CLAUDE.md rule 1).
     * The general row is the honest default and is what the drift model
     * used before this setting existed.
     */
    enum class Hull(
        val label: String,
        val dwlSlope: Double,
        val dwlStd: Double,
        val cwlSlope: Double,
        val cwlStd: Double,
    ) {
        GENERAL("Fishing vessel — general", 2.47, 12.00, 2.76, 9.40),
        GILL_NETTER("Gill-netter with a rear reel", 3.72, 3.33, 1.41, 3.36),
        SMALL("Small fishing vessel (Korean study)", 1.80, 3.79, 2.01, 3.30),
    }

    // --- the whole thing ----------------------------------------------------

    data class Values(
        val homeZone: String? = null,
        /**
         * Everyone who gets the distress SMS, in order.
         *
         * A crew does not have one contact. The boat owner, a son ashore,
         * the harbour office and a neighbouring skipper are four different
         * people who each do something different with the message, and the
         * one who happens to have signal is the one that matters. ORCA
         * sends to all of them on one press.
         *
         * Empty by default and never seeded: ORCA does not invent a
         * number to send a distress call to.
         */
        val contacts: List<String> = emptyList(),
        /**
         * The boat's registration, carried in a distress SMS.
         *
         * Optional, and blank by default: ORCA will not invent a boat
         * name any more than it invents a position. When it IS set, a
         * rescue coordinator reading the message knows which hull to look
         * for, and the harbour knows whose family to call.
         */
        val boatId: String = "",
        val textSize: TextSize = TextSize.NORMAL,
        val gpsRate: GpsRate = GpsRate.BALANCED,
        val hull: Hull = Hull.GENERAL,
        val speakAloud: Boolean = false,
        val keepScreenOn: Boolean = false,
        /**
         * The volume-key panic watch, ON by default.
         *
         * <p>It used to default OFF and had to be found and switched on
         * inside the app. That inverts the whole point: the crew this is
         * for is one who cannot reach the screen, and asking them to have
         * prepared the phone correctly beforehand is asking them to have
         * predicted the emergency. A safety device that is off until
         * configured is off when it matters.
         *
         * <p>The cost is a foreground service and a silent audio track
         * whenever ORCA is installed. That is a permanent notification the
         * crew can see and stop, which is the honest trade.
         */
        val panicWatch: Boolean = true,
    )

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return Values(
            homeZone = p.getString("home_zone", null),
            contacts = readContacts(p),
            boatId = p.getString("boat_id", "") ?: "",
            textSize = enumOr(p.getString("text_size", null), TextSize.entries, TextSize.NORMAL),
            gpsRate = enumOr(p.getString("gps_rate", null), GpsRate.entries, GpsRate.BALANCED),
            hull = enumOr(p.getString("hull", null), Hull.entries, Hull.GENERAL),
            speakAloud = p.getBoolean("speak_aloud", false),
            keepScreenOn = p.getBoolean("keep_screen_on", false),
            panicWatch = p.getBoolean("panic_watch", true),
        )
    }

    fun save(context: Context, v: Values) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("home_zone", v.homeZone)
            .putString("contacts", v.contacts.joinToString(SEP))
            .putString("boat_id", v.boatId)
            .putString("text_size", v.textSize.name)
            .putString("gps_rate", v.gpsRate.name)
            .putString("hull", v.hull.name)
            .putBoolean("speak_aloud", v.speakAloud)
            .putBoolean("keep_screen_on", v.keepScreenOn)
            .putBoolean("panic_watch", v.panicWatch)
            .apply()
    }

    /** Numbers cannot contain a comma, so this separator is unambiguous. */
    private const val SEP = ","

    /**
     * Reads the contact list, carrying forward the single number that
     * earlier builds stored.
     *
     * A phone that was set up before the list existed must not silently
     * lose the one number on it -- that number is the whole distress path,
     * and losing it would be discovered at the worst possible moment.
     */
    private fun readContacts(p: android.content.SharedPreferences): List<String> {
        val stored = p.getString("contacts", null)
        if (stored != null) {
            return stored.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
        }
        val legacy = p.getString("emergency_number", "")?.trim().orEmpty()
        return if (legacy.isEmpty()) emptyList() else listOf(legacy)
    }

    /**
     * A stored value that no longer exists falls back to the default
     * rather than crashing. Enum names get renamed; a phone that was set
     * up before the rename must still open.
     */
    private fun <T : Enum<T>> enumOr(name: String?, all: List<T>, fallback: T): T =
        all.firstOrNull { it.name == name } ?: fallback
}
