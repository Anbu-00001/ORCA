package org.orca.advisory

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Today's verdict on the home screen, without opening anything.
 *
 * <p>THE POINT OF THIS FEATURE. A crew decides whether to sail at four in
 * the morning, in the dark, usually while carrying something. Asking them
 * to unlock a phone, find an icon and wait for a screen is three actions
 * too many. A widget is zero actions: the answer is already there when
 * the screen lights up.
 *
 * <p>AND IT IS THE CLEANEST THING A WEB APP CANNOT DO. There is no
 * browser API that puts live content on the Android home screen. A PWA
 * shortcut is an icon that opens a browser — it cannot render a value, it
 * cannot update itself, and it shows nothing until it is tapped. This
 * updates whether or not anyone opens ORCA.
 *
 * <p>It reads the SAME stored bundle the app reads, so the widget and the
 * app can never disagree. It computes nothing: `action` came from
 * `orca/policy.py` on shore, and the widget is a label for it.
 *
 * <p>It always shows the reading's AGE. A widget that shows a stale GO
 * with no date is worse than a blank widget, because it looks current.
 */
class VerdictWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, manager, it) }
    }

    companion object {
        // These MUST match OrcaRepository's constants. A widget reading a
        // different key would quietly show the shipped seed for ever while
        // the app showed fresh data -- two answers, no way to tell which.
        private const val PREFS = "orca.store"
        private const val KEY_BUNDLE = "bundle.v1"

        /** Called by the app after every successful refresh. */
        fun refreshAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, VerdictWidget::class.java)
                )
                ids.forEach { render(context, manager, it) }
            } catch (e: Exception) {
                Log.w("ORCA", "Widget refresh failed: ${e.message}")
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_verdict)

            val stored = try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_BUNDLE, null)
                    ?: context.assets.open("bundle.json").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.w("ORCA", "Widget has no advisory to show: ${e.message}")
                null
            }

            if (stored == null) {
                views.setTextViewText(R.id.widget_verdict, "—")
                views.setTextViewText(R.id.widget_zone, "ORCA")
                views.setTextViewText(R.id.widget_age, "Open ORCA once to load an advisory")
                views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor("#37474F"))
            } else {
                try {
                    val root = JSONObject(stored)
                    val zone = root.getJSONArray("zones").getJSONObject(0)
                    val action = zone.optString("action", "UNKNOWN")
                    val name = zone.optJSONObject("primary_zone")?.optString("name") ?: ""

                    views.setTextViewText(R.id.widget_verdict, tamilFor(action))
                    views.setTextViewText(R.id.widget_zone, "$name · $action")
                    views.setTextViewText(R.id.widget_age, ageLine(root.optString("cache_fetched_at")))
                    views.setInt(R.id.widget_root, "setBackgroundColor", colourFor(action))
                } catch (e: Exception) {
                    // A malformed bundle must not leave a stale verdict on
                    // the home screen looking valid.
                    Log.w("ORCA", "Widget could not read the advisory: ${e.message}")
                    views.setTextViewText(R.id.widget_verdict, "—")
                    views.setTextViewText(R.id.widget_zone, "ORCA")
                    views.setTextViewText(R.id.widget_age, "Stored advisory unreadable")
                    views.setInt(R.id.widget_root, "setBackgroundColor", Color.parseColor("#37474F"))
                }
            }

            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(id, views)
        }

        /** Mirrors orca/phrase_ta.py's ACTION table. Tamil leads, as everywhere. */
        private fun tamilFor(action: String) = when (action) {
            "GO" -> "போகலாம்"
            "DO NOT GO" -> "போக வேண்டாம்"
            "SAFER ALTERNATIVE" -> "வேறு இடம்"
            "CANNOT ASSESS" -> "தெரியவில்லை"
            else -> "—"
        }

        private fun colourFor(action: String) = when (action) {
            "GO" -> Color.parseColor("#1B5E20")
            "DO NOT GO" -> Color.parseColor("#B71C1C")
            "SAFER ALTERNATIVE" -> Color.parseColor("#E65100")
            else -> Color.parseColor("#37474F")
        }

        /**
         * Age by the DEVICE clock, never the `freshness_min` baked into the
         * bundle — that is computed at fetch time and does not grow while
         * the phone is at sea. A widget is exactly where that bug would do
         * the most damage.
         */
        private fun ageLine(collectedAt: String?): String {
            if (collectedAt.isNullOrEmpty()) return "age unknown"
            return try {
                val mins = ChronoUnit.MINUTES.between(Instant.parse(collectedAt), Instant.now())
                when {
                    mins < 0 -> "age unknown"
                    mins < 60 -> "$mins min old"
                    mins < 60 * 48 -> "${mins / 60} h old"
                    else -> "${mins / 1440} days old — refresh before sailing"
                }
            } catch (e: Exception) {
                "age unknown"
            }
        }
    }
}
