package org.orca.advisory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.media.VolumeProvider
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import java.util.Locale

/**
 * Holding the volume key down calls for help, with the app closed.
 *
 * <p>WHY THIS IS THE FEATURE THAT MATTERS MOST. Every other emergency
 * control in ORCA assumes the crew can look at a screen: unlock, find the
 * app, find the button. The moments this exists for are the ones where
 * they cannot. A phone in a pocket, one hand on a rail, a boat taking
 * water, or after dark with wet hands. A hardware key held for five
 * seconds needs no screen, no unlock, and no sight.
 *
 * <p>NO WEB APP CAN DO THIS, AT ANY EFFORT. A browser cannot see the
 * volume keys, cannot run once its tab is closed, and is killed outright
 * when the screen locks.
 *
 * <p>HOW ANDROID LETS US SEE THE KEY. Two paths, because neither alone is
 * enough and they fail in different places:
 *
 * <ol>
 *  <li><b>MediaSession + VolumeProvider.</b> Registering a remote volume
 *      provider routes every volume key press to
 *      {@link VolumeProvider#onAdjustVolume} instead of the audio stream.
 *      This is the accurate path: it keeps delivering events even once
 *      the stream would have hit zero, so a genuine five-second hold is
 *      seen as five seconds.
 *  <li><b>A ContentObserver on the system volume.</b> The backstop for
 *      when another app owns the media session -- a crew playing music
 *      or a call in progress. It sees the volume VALUE change, which is
 *      a real signal, but it goes deaf once the volume reaches zero and
 *      there is nothing left to change.
 * </ol>
 *
 * <p>Neither path is a guarantee, and this is the honest statement of the
 * limit: if another app holds the session AND the volume is already at
 * zero, ORCA sees nothing. The screen says so rather than implying a
 * watch that is always listening. The rules that decide whether a press
 * run counts as a hold live in {@link PanicDetector}, tested without a
 * phone.
 */
class PanicService : Service() {

    companion object {
        const val ACTION_START = "org.orca.advisory.PANIC_WATCH_START"
        const val ACTION_STOP = "org.orca.advisory.PANIC_WATCH_STOP"
        private const val CHANNEL_ONGOING = "orca_panic_watch"
        private const val CHANNEL_ALARM = "orca_panic_alarm"
        private const val NOTIF_ONGOING = 4101
        private const val NOTIF_ALARM = 4102
        private const val TAG = "ORCA"

        /** Whether the watch is currently armed, for the UI to reflect. */
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, PanicService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PanicService::class.java).setAction(ACTION_STOP))
        }
    }

    private var session: MediaSession? = null
    private var observer: ContentObserver? = null
    private var tts: TextToSpeech? = null
    private var lastVolume = -1
    private var state = PanicDetector.State()
    private val handler = Handler(Looper.getMainLooper())

    // A released key sends nothing at all, so an open run has to be timed
    // out rather than closed by an event. See PanicDetector.expire().
    private val expiry = object : Runnable {
        override fun run() {
            state = PanicDetector.expire(state, SystemClock.elapsedRealtime())
            handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannels()
        startForeground(NOTIF_ONGOING, ongoingNotification())
        armMediaSession()
        armVolumeObserver()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale("ta", "IN") }
            }
        }
        handler.postDelayed(expiry, 400)
        running = true
        Log.i(TAG, "Panic watch armed")
        // STICKY: if Android reclaims this service under memory pressure it
        // must come back. A panic watch that quietly stopped is worse than
        // one that was never started, because the crew believes it is on.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        session?.run { isActive = false; release() }
        session = null
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        tts?.run { stop(); shutdown() }
        tts = null
        Log.i(TAG, "Panic watch stopped")
        super.onDestroy()
    }

    // --- path 1: the media session --------------------------------------

    private fun armMediaSession() {
        try {
            // Platform MediaSession, not MediaSessionCompat: the compat class
            // lives in androidx.media, and CLAUDE.md rule 6 forbids adding a
            // dependency for one class. minSdk is 24; this API is 21+.
            val s = MediaSession(this, "orca-panic")
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            s.setPlaybackToRemote(object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 50) {
                override fun onAdjustVolume(direction: Int) {
                    // PASS THE PRESS THROUGH, then listen.
                    //
                    // A VolumeProvider does not observe the volume key, it
                    // TAKES it: with the watch armed, the volume slider stopped
                    // controlling the music stream and the system volume dialog
                    // relabelled itself "ORCA". Breaking the volume button to
                    // watch the volume button is not a trade a crew would accept,
                    // and it is the kind of thing that gets an app uninstalled
                    // before the emergency it was installed for.
                    //
                    // So the adjustment is forwarded to the real stream first and
                    // the phone behaves exactly as it always did. Interception is
                    // still what buys the accuracy: the stream stops changing once
                    // it reaches zero, but this callback keeps firing, so a hold
                    // that runs past silence is still measured as a hold.
                    if (direction != 0) {
                        runCatching {
                            am.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                if (direction < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                                AudioManager.FLAG_SHOW_UI,
                            )
                        }
                    }
                    when {
                        direction < 0 -> onKey(PanicDetector.Key.DOWN)
                        direction > 0 -> onKey(PanicDetector.Key.UP)
                    }
                }
            })
            // Registering a VolumeProvider is NOT enough. Android only
            // routes volume keys to a session it considers ACTIVE, and
            // "active" means it has a PlaybackState that is playing.
            // Measured on an OPPO CPH2591: with setActive(true) alone the
            // session appeared in `dumpsys media_session` with
            // volumeType=REMOTE and never received a single onAdjustVolume.
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build(),
            )
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "ORCA emergency watch")
                    .build(),
            )
            s.isActive = true
            session = s
        } catch (e: Exception) {
            // Not fatal: the observer below still works. Logged loudly so
            // a device that never takes this path is visible in a bug
            // report rather than silently degraded.
            Log.w(TAG, "MediaSession path unavailable: ${e.message}")
        }
    }

    // --- path 2: the volume setting -------------------------------------

    private fun armVolumeObserver() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val o = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (now == lastVolume) return
                    val key = if (now < lastVolume) PanicDetector.Key.DOWN else PanicDetector.Key.UP
                    lastVolume = now
                    onKey(key)
                }
            }
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, o)
            observer = o
        } catch (e: Exception) {
            Log.w(TAG, "Volume observer unavailable: ${e.message}")
        }
    }

    // --- the decision ----------------------------------------------------

    private fun onKey(key: PanicDetector.Key) {
        val event = PanicDetector.Event(SystemClock.elapsedRealtime(), key)
        val result = PanicDetector.accept(state, event)
        state = result.state
        Log.d(
            TAG,
            "panic key=$key run=${state.run.size} progress=${"%.2f".format(state.progress)}",
        )
        if (result.fire) fire()
    }

    /**
     * What happens when the hold completes.
     *
     * <p>It does NOT silently send anything. Android 15 hard-restricts
     * SEND_SMS for a sideloaded app, and ORCA would not want it anyway: a
     * distress message that goes out with nobody having seen it is how a
     * pocket sends the Coast Guard to an empty patch of sea. So the phone
     * makes itself impossible to ignore -- long vibration, a full-screen
     * alarm notification, the Tamil announcement, and the torch already
     * flashing SOS -- and the crew confirms the message with one tap.
     */
    private fun fire() {
        Log.i(TAG, "PANIC: volume key held")

        // Long, unmistakable, and distinct from every notification pattern.
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 600, 200, 600, 200, 900), -1,
                ),
            )
        }.onFailure { Log.w(TAG, "No vibrator: ${it.message}") }

        // Start the light immediately. It costs almost nothing and it is
        // the one signal that works with a dead network.
        runCatching { TorchSos.start(this) }
            .onFailure { Log.w(TAG, "Torch failed at panic: ${it.message}") }

        runCatching {
            tts?.speak(
                "அவசர அழைப்பு. உங்கள் இடம் அனுப்பத் தயார். திரையைப் பாருங்கள்.",
                TextToSpeech.QUEUE_FLUSH, null, "orca-panic",
            )
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("SOS — அவசர அழைப்பு")
            .setContentText("Volume key held. Tap to send your position.")
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            // Full-screen so it takes over a locked screen rather than
            // queueing behind whatever else is on it.
            .setFullScreenIntent(open, true)
            .build()

        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ALARM, n)
    }

    // --- plumbing ---------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING, "Panic watch", NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while ORCA is watching the volume key." },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM, "SOS alarm", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Fires when the volume key is held for five seconds."
                enableVibration(true)
                // An emergency must survive Do Not Disturb. This is exactly
                // the case the override exists for, and the only channel in
                // ORCA that asks for it.
                setBypassDnd(true)
            },
        )
    }

    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PanicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ORCA அவசர கண்காணிப்பு")
            .setContentText("Hold the volume key 5 seconds to send an SOS.")
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build(),
            )
            .build()
    }
}
