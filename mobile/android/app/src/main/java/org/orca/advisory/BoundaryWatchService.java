package org.orca.advisory;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The maritime boundary watch. THIS is what the APK is for.
 *
 * <p>Tamil Nadu fishermen are detained, and have been shot at, for
 * drifting across the India-Sri Lanka maritime boundary in the Palk
 * Strait -- a line that is invisible, unmarked, and in places only a few
 * kilometres from productive water. ORCA already knows exactly where it
 * runs: data/cache/imbl/imbl_boundary.json holds the real geometry from
 * Marine Regions (Flanders Marine Institute / IOC-UNESCO), and
 * orca/agents.py already checks distance to it when answering a question.
 *
 * <p>But answering a question is not the problem. Nobody opens an app
 * while their net is out. The problem is a boat drifting north-east at
 * three knots for two hours with the phone in someone's pocket.
 *
 * <p>So this runs as a foreground service: GPS, sampled, against the real
 * boundary, alerting <em>while the app is closed and the screen is off</em>,
 * with no network at all. A web page cannot do any part of that. There is
 * no background geolocation for a page that is not open, no wakelock, no
 * notification, no way to speak when the browser is not running. This is
 * the honest answer to "what can a mobile app do that a web app never
 * can" -- and it happens to be the feature most likely to keep someone
 * out of a Sri Lankan jail.
 *
 * <h3>What it does NOT do</h3>
 * It does not decide anything and it does not own a single number.
 *
 * <ul>
 *   <li>The GEOMETRY arrives from GET /bundle, read out of ORCA's cache.</li>
 *   <li>The BANDS (2 km / 5 km / 10 km) arrive in the same payload, read
 *       out of orca/agents.py's IMBL_URGENT_KM, IMBL_WARNING_KM and
 *       IMBL_ADVISORY_KM. Hardcoding them here would be a second copy of
 *       a safety constant, which docs/MOBILE_APP.md §2 forbids for
 *       exactly the reason that matters: the day the two copies disagree,
 *       ORCA has no defensible answer about which was right.</li>
 *   <li>It reports a DISTANCE and a warning band. It never says GO or
 *       DO NOT GO. That verdict is orca/policy.py's and reaches the phone
 *       only inside the bundle's zone entries.</li>
 * </ul>
 *
 * <p>The arithmetic below (haversine, point-to-segment) is a port of
 * orca/agents.py's _haversine_km and _point_to_segment_km. That is
 * geometry, not policy -- the same computation a map makes to draw the
 * line -- and it is the one thing that cannot be shipped as data.
 */
public class BoundaryWatchService extends Service implements LocationListener {

    private static final String TAG = "ORCA";
    static final String EXTRA_BOUNDARY = "boundary";
    static final String EXTRA_LANG = "lang";

    private static final String CHANNEL_ONGOING = "orca_boundary_watch";
    private static final String CHANNEL_ALERT = "orca_boundary_alert";
    private static final int NOTIF_ONGOING = 1;
    private static final int NOTIF_ALERT = 2;

    /** Sampling. A drifting boat does not need per-second GPS, and the
     *  battery has to last the trip -- an alert that stops because the
     *  phone died is worse than no alert. 30 s / 100 m is frequent enough
     *  that a 6-knot boat cannot cross a 2 km band unwarned. */
    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final float MIN_DISTANCE_M = 100f;

    private static volatile boolean running = false;

    private final List<double[][]> segments = new ArrayList<>();
    private double urgentKm = 2.0, warningKm = 5.0, advisoryKm = 10.0;
    private String lang = "ta";
    private String lastBand = "";
    /** Minutes-to-boundary at the last announcement, so a fast approach
     *  inside an already-announced band can speak again. */
    private Double lastAnnouncedMinutes = null;
    /** Every fix seen. BoundaryAlarm prunes to its own window. */
    private final List<BoundaryAlarm.Fix> history = new ArrayList<>();
    private TextToSpeech tts;
    private LocationManager locationManager;

    static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("ta", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Boundary watch: no Tamil voice, alerts will be spoken in English");
                    tts.setLanguage(Locale.UK);
                    lang = "en";
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_BOUNDARY);
            String requested = intent.getStringExtra(EXTRA_LANG);
            if (requested != null) lang = requested;
            if (!parseBoundary(json)) {
                // No geometry means no warning, NOT a guessed one
                // (CLAUDE.md rule 1). Stopping is the honest response.
                Log.w(TAG, "Boundary watch: no usable boundary geometry; not starting");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startForeground(NOTIF_ONGOING, ongoingNotification(
                lang.equals("ta") ? "கடல் எல்லை கண்காணிப்பு இயங்குகிறது"
                                  : "Boundary watch running",
                lang.equals("ta") ? "GPS-ஐப் பயன்படுத்தி எல்லைத் தூரம் கவனிக்கப்படுகிறது"
                                  : "Watching your distance from the maritime boundary"));

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, MIN_INTERVAL_MS, MIN_DISTANCE_M, this);
        } catch (SecurityException e) {
            Log.w(TAG, "Boundary watch: location permission missing at start");
            stopSelf();
            return START_NOT_STICKY;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Boundary watch: GPS provider unavailable: " + e.getMessage());
        }

        running = true;
        // START_STICKY: if Android kills this under memory pressure it
        // comes back. A safety watch that quietly stops is the failure
        // mode that matters here.
        return START_STICKY;
    }

    private boolean parseBoundary(String json) {
        if (json == null || json.isEmpty() || "null".equals(json)) return false;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject bands = root.optJSONObject("bands_km");
            if (bands != null) {
                urgentKm = bands.optDouble("urgent", urgentKm);
                warningKm = bands.optDouble("warning", warningKm);
                advisoryKm = bands.optDouble("advisory", advisoryKm);
            }
            JSONArray segs = root.optJSONArray("segments");
            if (segs == null) return false;
            segments.clear();
            for (int i = 0; i < segs.length(); i++) {
                JSONArray pts = segs.getJSONArray(i);
                double[][] line = new double[pts.length()][2];
                for (int j = 0; j < pts.length(); j++) {
                    JSONArray p = pts.getJSONArray(j);
                    line[j][0] = p.getDouble(0);   // lat
                    line[j][1] = p.getDouble(1);   // lon
                }
                segments.add(line);
            }
            Log.i(TAG, "Boundary watch armed: " + segments.size() + " segments, bands "
                    + urgentKm + "/" + warningKm + "/" + advisoryKm + " km");
            return !segments.isEmpty();
        } catch (Exception e) {
            Log.w(TAG, "Boundary watch: boundary payload unreadable: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        double km = distanceToBoundaryKm(location.getLatitude(), location.getLongitude());
        if (km < 0) return;

        // Distance alone is the wrong signal in BOTH directions: a boat
        // working a net parallel to the line at 4.9 km is not going to
        // cross but gets warned every time GPS noise nudges it over the
        // band edge, and a boat at 11 km running straight at the line gets
        // nothing. What matters is whether the gap is CLOSING. The rules
        // live in BoundaryAlarm, where they are unit-tested without a boat.
        history.add(new BoundaryAlarm.Fix(System.currentTimeMillis(), km));
        // Keep the list from growing without bound on a multi-day trip;
        // BoundaryAlarm only ever looks at its own window anyway.
        while (history.size() > 240) history.remove(0);

        BoundaryAlarm.Decision d = BoundaryAlarm.INSTANCE.decide(
                history,
                new BoundaryAlarm.Bands(urgentKm, warningKm, advisoryKm),
                lastBand.isEmpty() ? null : lastBand,
                lastAnnouncedMinutes,
                System.currentTimeMillis());

        if (!d.getAnnounce()) {
            Log.d(TAG, "Boundary silent: " + d.getWhy());
            updateOngoing(km, d.getBand());
            return;
        }

        lastBand = d.getBand();
        lastAnnouncedMinutes = d.getMinutesToBoundary();

        String spoken = BoundaryAlarm.INSTANCE.message(d, km, "ta".equals(lang));
        Log.i(TAG, "Boundary " + d.getBand() + " at "
                + String.format(Locale.US, "%.1f", km) + " km — " + d.getWhy());
        if (tts != null) tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "orca-boundary");
        alert(spoken, d.getBand());
        updateOngoing(km, d.getBand());
    }

    // The spoken wording now lives in BoundaryAlarm.message(), with the
    // rest of the alarm rules. It was duplicated here until the closing-
    // speed logic moved out, and two copies of a safety string is exactly
    // the second-copy-that-can-disagree problem the bands were kept out of
    // this file to avoid.

    // --- geometry (ported from orca/agents.py) --------------------------

    private static final double EARTH_KM = 6371.0088;

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                 + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Shortest distance from a point to one segment, projected onto a
     *  local plane. Over segments of tens of km at this latitude the
     *  planar approximation is well inside GPS error. */
    private static double pointToSegmentKm(double lat, double lon,
                                           double aLat, double aLon,
                                           double bLat, double bLon) {
        double kx = Math.cos(Math.toRadians(lat)) * 111.320;   // km per degree lon
        double ky = 110.574;                                    // km per degree lat
        double px = (lon - aLon) * kx, py = (lat - aLat) * ky;
        double vx = (bLon - aLon) * kx, vy = (bLat - aLat) * ky;
        double len2 = vx * vx + vy * vy;
        if (len2 == 0) return haversineKm(lat, lon, aLat, aLon);
        double t = Math.max(0, Math.min(1, (px * vx + py * vy) / len2));
        double cx = aLon + (t * vx) / kx, cy = aLat + (t * vy) / ky;
        return haversineKm(lat, lon, cy, cx);
    }

    private double distanceToBoundaryKm(double lat, double lon) {
        double best = Double.MAX_VALUE;
        for (double[][] line : segments) {
            for (int i = 0; i + 1 < line.length; i++) {
                best = Math.min(best, pointToSegmentKm(
                        lat, lon, line[i][0], line[i][1], line[i + 1][0], line[i + 1][1]));
            }
        }
        return best == Double.MAX_VALUE ? -1 : best;
    }

    // --- notifications --------------------------------------------------

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ongoing = new NotificationChannel(
                CHANNEL_ONGOING, "Boundary watch", NotificationManager.IMPORTANCE_LOW);
        ongoing.setDescription("Shows that ORCA is watching your distance from the maritime boundary");
        nm.createNotificationChannel(ongoing);

        // A separate HIGH channel so the warning can wake the screen and
        // sound even while the ongoing one stays silent.
        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "Boundary alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("Warns when you approach the India-Sri Lanka maritime boundary");
        alert.enableVibration(true);
        alert.setVibrationPattern(new long[]{0, 600, 200, 600});
        alert.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        nm.createNotificationChannel(alert);
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification ongoingNotification(String title, String text) {
        return new Notification.Builder(this, CHANNEL_ONGOING)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(openApp())
                .build();
    }

    private void updateOngoing(double km, String band) {
        boolean ta = "ta".equals(lang);
        String text = ta
                ? "கடல் எல்லை " + String.format(Locale.US, "%.1f", km) + " கிமீ தொலைவில்"
                : String.format(Locale.US, "Maritime boundary %.1f km away", km);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ONGOING, ongoingNotification(
                ta ? "கடல் எல்லை கண்காணிப்பு" : "Boundary watch", text));
    }

    private void alert(String text, String band) {
        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setContentTitle("urgent".equals(band)
                        ? ("ta".equals(lang) ? "ஆபத்து — கடல் எல்லை" : "DANGER — maritime boundary")
                        : ("ta".equals(lang) ? "எச்சரிக்கை — கடல் எல்லை" : "Warning — maritime boundary"))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ALERT, n);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (SecurityException ignored) { }
        }
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) {
        Log.w(TAG, "Boundary watch: GPS turned off -- no distance can be computed");
    }
}
