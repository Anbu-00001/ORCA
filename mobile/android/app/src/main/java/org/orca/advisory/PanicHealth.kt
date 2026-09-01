package org.orca.advisory

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Whether the panic watch will actually still be alive when it is needed.
 *
 * <h3>WHY THIS EXISTS</h3>
 * The volume/power trigger was repeatedly reported as "not working" while
 * every on-device test passed. The tests all ran seconds after launching
 * the app, which is the one moment a foreground service is guaranteed
 * healthy. Real use is the opposite: a phone idle in a pocket for hours.
 *
 * <p>ORCA is running on an OPPO A18 / ColorOS, and dontkillmyapp.com's
 * OPPO entry states plainly that OPPO kills background services <i>"every
 * time you turn the screen off"</i>, accessibility services included, and
 * that a foreground notification alone is NOT sufficient -- the app must
 * also be granted autostart and exempted from battery optimisation, by
 * hand, in OEM settings that no API can read or set.
 *
 * <p>So a safety feature that silently stops existing is the real failure
 * mode, and it is invisible: the app opens normally, the setting still
 * reads "on", and only the service is gone. This class makes that visible
 * and, where Android allows, fixable in one tap.
 *
 * <h3>WHAT CAN AND CANNOT BE DETECTED</h3>
 * Honest about the limits. Android exposes the AOSP battery allowlist and
 * the user-set background restriction, and since API 30 it can report why
 * the process died last time. It exposes NOTHING about ColorOS's own
 * autostart list or its "sleep standby optimization", which are the two
 * most likely culprits -- those can only be opened for the user, never
 * queried. Anything this class cannot verify, it says it cannot verify
 * rather than reporting a reassuring green tick.
 */
object PanicHealth {

    private const val TAG = "ORCA"

    /** One thing the crew may need to fix, in plain words. */
    data class Check(
        val label: String,
        /** true = known good, false = known bad, null = cannot be checked. */
        val ok: Boolean?,
        val detail: String,
    )

    /**
     * Is ORCA exempt from Doze / battery optimisation?
     *
     * The one restriction Android both exposes and lets an app ask about.
     */
    fun ignoringBatteryOptimisations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read battery optimisation state: ${e.message}")
        false
    }

    /**
     * Has the user put ORCA in the restricted bucket?
     *
     * When true, a foreground service cannot even be started unless an
     * activity is already showing -- which for a panic watch means it
     * cannot be armed at all.
     */
    fun backgroundRestricted(context: Context): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) am.isBackgroundRestricted else false
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read background restriction: ${e.message}")
        false
    }

    /**
     * Why the process died last time, if Android will say.
     *
     * <p>The only evidence available after the fact that the watch stopped
     * running while nobody was looking. REASON_USER_REQUESTED means somebody
     * hit Stop in the Android 13+ Task Manager; the OEM-kill cases show up as
     * low memory or "other". Either way the crew deserves to be told that
     * the thing they were relying on was not running.
     */
    fun lastExitReason(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
                ?: return null
            when (info.reason) {
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED ->
                    "Someone stopped ORCA by hand. The panic watch was off until now."
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY ->
                    "The phone ran out of memory and closed ORCA. The panic watch was off."
                android.app.ApplicationExitInfo.REASON_USER_STOPPED ->
                    "ORCA was force-stopped. The panic watch was off until now."
                android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ->
                    "ORCA crashed last time. Please tell the team."
                android.app.ApplicationExitInfo.REASON_OTHER ->
                    "The phone closed ORCA in the background. The panic watch was off."
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read exit reasons: ${e.message}")
            null
        }
    }

    /** Everything worth showing the crew, worst first. */
    fun checks(context: Context): List<Check> = listOf(
        Check(
            "Battery optimisation off",
            ignoringBatteryOptimisations(context),
            "Android may stop the panic watch to save battery unless ORCA is exempt.",
        ),
        Check(
            "Background use allowed",
            !backgroundRestricted(context),
            "If ORCA is restricted, the watch cannot run at all when the app is closed.",
        ),
        Check(
            "Auto-launch on (ColorOS)",
            null,
            "Android cannot tell ORCA whether this is on. On OPPO phones the panic " +
                "watch is killed when the screen goes off unless Auto-launch is enabled " +
                "by hand. Tap to open the settings page.",
        ),
    )

    // --- taking the crew to the right screen ------------------------------

    /**
     * Ask Android for the battery exemption.
     *
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is the documented way and shows a
     * system dialog. It is only ever used here, for the panic watch, which is
     * exactly the kind of always-on safety service the exemption is meant for.
     */
    fun requestBatteryExemption(context: Context) {
        if (ignoringBatteryOptimisations(context)) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            Log.w(TAG, "Battery exemption dialog unavailable: ${it.message}")
            openAppSettings(context)
        }
    }

    /**
     * Open ColorOS's autostart list, falling back to the app info page.
     *
     * <p>These component names are undocumented and OEM-private -- there is
     * no supported API for the autostart list, and the names move between
     * ColorOS versions. So every one is tried in turn and the whole thing
     * degrades to the standard app-info screen, which always exists. A
     * hardcoded component that no longer resolves must never crash the app.
     */
    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            ),
            ComponentName(
                "com.oplus.safecenter",
                "com.oplus.safecenter.permission.startup.StartupAppListActivity",
            ),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        for (c in candidates) {
            val ok = runCatching {
                context.startActivity(
                    Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
            if (ok) return
        }
        Log.i(TAG, "No ColorOS autostart activity resolved; opening app settings")
        openAppSettings(context)
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
