package org.orca.advisory

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
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
 * <p>HOW ANDROID LETS US SEE THE KEY. Two paths, because neither alone
 * covers the whole job and they fail in different places:
 *
 * <ol>
 *  <li><b>PanicKeyService, an accessibility service.</b> The accurate one.
 *      It receives real key down/up events with timestamps, so a hold is
 *      measured rather than inferred, and it works over other apps and on
 *      the lock screen. It needs the crew to switch it on once in Android's
 *      own settings, and it stops receiving anything once the screen is
 *      genuinely off -- measured on an OPPO CPH2591: screen asleep, ORCA
 *      backgrounded, a full five-second hold produced no callback at all.
 *  <li><b>The system's volume-changed broadcast.</b> The only signal left
 *      once the screen is off. It reports that a volume VALUE moved rather
 *      than that a key was pressed, so it says nothing when the stream is
 *      already at its floor -- which is what {@link #keepAlive} works
 *      around, by lifting the level back up mid-hold so the presses keep
 *      producing changes.
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

        /** Abort a countdown that is already running. */
        const val ACTION_CANCEL = "org.orca.advisory.PANIC_CANCEL"

        /** Raise the alarm now. Sent by PanicKeyService when the volume
         *  key has genuinely been held for five seconds. */
        const val ACTION_TRIGGER = "org.orca.advisory.PANIC_TRIGGER"

        /**
         * Fire the panic sequence from outside this service.
         *
         * Used by PanicKeyService, which measures the hold from real key
         * up/down timestamps and so does not need this service's detector
         * at all -- only its countdown, its alarm and its dispatch.
         */
        fun trigger(context: Context) {
            val i = Intent(context, PanicService::class.java).setAction(ACTION_TRIGGER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        /** Stop the pending SOS. Safe to call when nothing is pending. */
        fun cancel(context: Context) {
            context.startService(
                Intent(context, PanicService::class.java).setAction(ACTION_CANCEL),
            )
        }
        /**
         * v2 because the importance changed, and Android ignores changes
         * to a channel that already exists. The original channel was
         * IMPORTANCE_LOW, which put ORCA's SOS notification in the
         * collapsed "Silent" pile at the bottom of the shade -- observed
         * on the test handset, below a Discord message. A distress control
         * that has to be hunted for underneath chat notifications is not a
         * distress control. A new id is the only way to raise it.
         */
        private const val CHANNEL_ONGOING = "orca_panic_watch_v2"
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

    private var volumeReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var powerState = PowerPressDetector.State()
    private var tts: TextToSpeech? = null
    /** Last seen level per watched stream, keyed by stream id. */
    private val lastVolume = mutableMapOf<Int, Int>()

    /** Steps ORCA borrowed to keep the key repeating, per stream, so the
     *  crew's own volume can be handed back exactly. */
    private val borrowed = mutableMapOf<Int, Int>()
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
            abortCountdown()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TRIGGER) {
            // The accessibility service measured a real five-second hold.
            // Make sure the notification channels exist before the alarm
            // tries to use one, then raise it.
            createChannels()
            if (!running) startForeground(NOTIF_ONGOING, ongoingNotification())
            fire()
            return START_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            abortCountdown()
            // The watch itself keeps running: a crew who cancelled a false
            // alarm still wants the key to work the next time.
            return START_STICKY
        }
        createChannels()
        startForeground(NOTIF_ONGOING, ongoingNotification())

        // ARM ONCE. onStartCommand runs again on every start request, and
        // there are several: the launch auto-arm, the BootReceiver (which
        // also fires on package replace), the settings toggle, and START_STICKY
        // restarts. Each pass used to build a NEW MediaSession and overwrite
        // the reference without releasing the old one, leaving orphaned
        // sessions competing for the volume keys -- after three arms in one
        // second, `dumpsys media_session` reported "Media button session is
        // null" and the feature was dead precisely because it had been
        // started too often.
        if (running) {
            Log.i(TAG, "Panic watch already armed; ignoring duplicate start")
            return START_STICKY
        }

        armVolumeObserver()
        armScreenWatcher()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { tts?.language = Locale("ta", "IN") }
            }
        }
        handler.postDelayed(expiry, 400)
        running = true
        PanicStatus.onArm(true)
        Log.i(TAG, "Panic watch armed")
        // STICKY: if Android reclaims this service under memory pressure it
        // must come back. A panic watch that quietly stopped is worse than
        // one that was never started, because the crew believes it is on.
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        restoreVolume()
        volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
        volumeReceiver = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        tts?.run { stop(); shutdown() }
        tts = null
        PanicStatus.onDisarm()
        Log.i(TAG, "Panic watch stopped")
        super.onDestroy()
    }

    // --- REMOVED: the MediaSession + VolumeProvider path -----------------
    //
    // It is gone on purpose, and it must not come back. Three measured
    // reasons, in order of how badly each one hurt:
    //
    //  1. IT BLOCKED THE VOLUME FROM CHANGING. Registering a remote
    //     VolumeProvider makes ORCA's session the "media button session",
    //     and every volume adjustment then routes to that provider instead
    //     of the audio stream. Measured: with the watch armed, five
    //     lower-volume commands left STREAM_MUSIC sitting at 110/160,
    //     unmoved. The crew's volume keys were being eaten by the feature
    //     that was supposed to be watching them.
    //
    //  2. BECAUSE OF (1), IT KILLED THE PATH THAT DOES WORK. If the volume
    //     never changes, VOLUME_CHANGED_ACTION never fires, so the one
    //     mechanism that still functions with the screen off was being
    //     suppressed by the one that does not.
    //
    //  3. IT NEVER DELIVERED ANYTHING ANYWAY. onAdjustVolume is not called
    //     when the app is backgrounded with the screen off -- a known
    //     framework defect across Android 12-15. ORCA held the media button
    //     session (confirmed in dumpsys) and received zero callbacks.
    //
    // It also required a silent AudioTrack looping forever to keep the
    // session eligible, which cost battery for nothing.
    //
    // The two paths that remain are PanicKeyService (an accessibility
    // service; works whenever the screen is on, including on the lock
    // screen and over other apps) and the volume broadcast below (the only
    // thing that still reports a press once the screen is off).

    // --- path 2: the volume setting -------------------------------------

    /**
     * Streams a volume key might actually be driving.
     *
     * <p>Whichever one moves, a press happened. Which stream it is depends
     * on what the phone is doing: media while something plays, the ringer
     * when nothing does.
     */
    private val watchedStreams = listOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_NOTIFICATION,
    )

    /**
     * The system's volume-changed broadcast.
     *
     * <h3>WHY NOT A ContentObserver, WHICH IS WHAT THIS USED TO BE</h3>
     * The old code watched {@code Settings.System.CONTENT_URI} for volume
     * changes. That has not worked since Android 7: stream volumes moved
     * out of Settings.System into AudioService's own storage years ago, so
     * the observer was registered against a URI that never fires for
     * volume. It was dead code that looked like a safety net. Verified on
     * an OPPO CPH2591 running Android 15 -- setting the music volume from
     * 5 to 8 produced no callback at all.
     *
     * <p>{@code android.media.VOLUME_CHANGED_ACTION} is what the system
     * actually broadcasts. It is not in the public SDK, which is why the
     * action is written out as a string here rather than referenced as a
     * constant, and it carries the stream, the new value and the previous
     * value -- so the DIRECTION is read from the payload rather than
     * inferred from a cached number that could drift.
     *
     * <p>This is the backstop, not the primary path. The MediaSession is
     * more accurate because it keeps delivering events after the volume
     * has bottomed out; this one goes quiet at the floor, which is what
     * {@link #keepAlive} exists to work around.
     */
    private fun armVolumeObserver() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            watchedStreams.forEach { lastVolume[it] = am.getStreamVolume(it) }
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // Logged BEFORE any filtering. Every early return below
                    // used to be silent, so a receiver that was firing and
                    // discarding looked identical to one that never fired
                    // at all -- and those are opposite problems.
                    val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    Log.d(
                        TAG,
                        "VOLUME broadcast: stream=$stream extras=${intent.extras?.keySet()}",
                    )
                    if (stream !in watchedStreams) return
                    val now = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    if (now < 0 || prev < 0 || now == prev) return
                    lastVolume[stream] = now
                    val key = if (now < prev) PanicDetector.Key.DOWN else PanicDetector.Key.UP
                    Log.d(TAG, "volume broadcast stream=$stream $prev->$now")
                    onKey(key, "broadcast")
                    if (key == PanicDetector.Key.DOWN) keepAlive(am, stream, now)
                }
            }
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            // RECEIVER_EXPORTED, and it has to be. VOLUME_CHANGED_ACTION is
            // not a protected system broadcast, so NOT_EXPORTED restricts
            // delivery to broadcasts sent by ORCA itself -- and the sender
            // here is the system. Registered NOT_EXPORTED first time round,
            // this receiver never fired once: the ring volume was driven
            // from 16 to 0 and nothing arrived.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, filter)
            }
            volumeReceiver = r
            Log.i(TAG, "Volume broadcast receiver armed")
        } catch (e: Exception) {
            Log.w(TAG, "Volume broadcast unavailable: ${e.message}")
        }
    }

    /**
     * Keep the key repeating after the volume hits the floor.
     *
     * <p>THE PHYSICS PROBLEM. This path sees volume CHANGES, not key
     * presses. A held volume-down key auto-repeats down the scale and then
     * the stream reaches zero, where nothing changes any more and the
     * observer goes deaf -- so a fifteen-step stream runs out in about two
     * seconds and a five-second hold can never be measured. That is not a
     * tuning problem, it is the path running out of room.
     *
     * <p>THE FIX. When a run is under way and the stream lands on its
     * minimum, ORCA lifts it a few steps back up, silently. The key is
     * still down, so it keeps stepping back to zero, and the events keep
     * arriving for as long as the crew is actually holding. Every step
     * borrowed is counted and handed back in [restoreVolume], so a crew
     * who cancels finds their volume exactly where they left it.
     *
     * <p>Only while a run is genuinely in progress. Somebody quietly
     * muting their phone gets no interference at all.
     */
    private fun keepAlive(am: AudioManager, stream: Int, now: Int) {
        if (state.run.size < 2) return          // a single tap is not a hold
        if (now > am.getStreamMinVolume(stream)) return
        val lift = 4
        runCatching {
            repeat(lift) {
                // No FLAG_SHOW_UI: the crew is holding a key, not asking to
                // see a slider, and a bouncing volume panel would be the
                // most visible possible bug.
                am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            }
            borrowed[stream] = (borrowed[stream] ?: 0) + lift
            lastVolume[stream] = am.getStreamVolume(stream)
        }.onFailure { Log.w(TAG, "Could not lift $stream: ${it.message}") }
    }

    /** Give back every step borrowed to keep the key repeating. */
    private fun restoreVolume() {
        if (borrowed.isEmpty()) return
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        borrowed.forEach { (stream, steps) ->
            runCatching {
                repeat(steps) { am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0) }
                lastVolume[stream] = am.getStreamVolume(stream)
            }
        }
        borrowed.clear()
    }

    /**
     * Watch the screen going on and off -- the power button, indirectly.
     *
     * <h3>THE ONE TRIGGER THAT WORKS WITH THE SCREEN OFF</h3>
     * Everything else ORCA tried needed a key event, and no app on stock
     * Android is given one while the display sleeps. This does not ask for
     * the key. It listens for ACTION_SCREEN_ON and ACTION_SCREEN_OFF, which
     * the system BROADCASTS to registered receivers whatever the display is
     * doing, and infers the press from the consequence.
     *
     * <p>Registered at runtime on purpose: both actions are exempt from
     * manifest registration and will not be delivered to a manifest-declared
     * receiver at all. It lives as long as this foreground service does.
     */
    private fun armScreenWatcher() {
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action ?: return
                    if (action != Intent.ACTION_SCREEN_ON && action != Intent.ACTION_SCREEN_OFF) return
                    val result = PowerPressDetector.accept(powerState, SystemClock.elapsedRealtime())
                    powerState = result.state
                    PanicStatus.onPowerPress(PowerPressDetector.progress(powerState))
                    Log.d(
                        TAG,
                        "screen $action -- ${powerState.times.size}/${PowerPressDetector.PRESSES}",
                    )
                    if (result.fire) {
                        Log.i(TAG, "PANIC: power button pressed ${PowerPressDetector.PRESSES} times")
                        fire()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            registerReceiver(r, filter)
            screenReceiver = r
            Log.i(TAG, "Power-button watcher armed (${PowerPressDetector.PRESSES} presses)")
        } catch (e: Exception) {
            Log.w(TAG, "Screen watcher unavailable: ${e.message}")
        }
    }

    // --- the decision ----------------------------------------------------

    private fun onKey(key: PanicDetector.Key, path: String = "session") {
        val event = PanicDetector.Event(SystemClock.elapsedRealtime(), key)
        val result = PanicDetector.accept(state, event)
        state = result.state
        // Surfaced on the SOS screen. This is the only instrument that can
        // tell "the keys never arrive" from "they arrive but the hold is
        // short", and those need different fixes.
        PanicStatus.onKey(path, state.progress)
        Log.d(
            TAG,
            "panic key=$key run=${state.run.size} progress=${"%.2f".format(state.progress)}",
        )
        if (result.fire) {
            restoreVolume()
            fire()
        } else if (state.run.isEmpty()) {
            // The run closed without firing -- a tap, or a hold let go
            // early. Give the crew their volume back straight away.
            restoreVolume()
        }
    }

    /**
     * What happens when the hold completes: the message goes out.
     *
     * <p>This used to raise an alarm and wait for the crew to confirm.
     * That was the wrong call. The five-second hold IS the confirmation --
     * it is a deliberate, sustained action that no pocket performs, which
     * is exactly why the threshold is five seconds and why a volume-UP
     * press cancels it. Making somebody who has already held a button for
     * five seconds then find, unlock and tap the phone is asking them to
     * confirm twice, and the second one happens at the worst moment of
     * their day.
     *
     * <p>So it sends, and THEN makes itself impossible to ignore: long
     * vibration, the torch flashing SOS, the Tamil announcement, and a
     * full-screen notification that now reports what was sent rather than
     * asking permission to send it.
     */
    private fun fire() {
        if (SosCountdown.running) {
            Log.i(TAG, "PANIC: already counting down, ignoring")
            return
        }
        Log.i(TAG, "PANIC raised -- ${SosCountdown.SECONDS}s to cancel")
        SosCountdown.start()

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

        // The torch starts NOW rather than after the countdown. It is the
        // one signal that costs nothing and works with a dead network, and
        // if the countdown is cancelled it is turned straight back off.
        runCatching { TorchSos.start(this) }
            .onFailure { Log.w(TAG, "Torch failed at panic: ${it.message}") }

        // Said out loud, because the crew this is for cannot see a screen.
        runCatching {
            tts?.speak(
                "அவசர அழைப்பு. பத்து விநாடிகளில் அனுப்பப்படும். நிறுத்த திரையை தொடவும்.",
                TextToSpeech.QUEUE_FLUSH, null, "orca-panic",
            )
        }

        countdown()
    }

    /**
     * Tick the abort window down, then send.
     *
     * <p>The notification is rewritten every second so a locked screen
     * shows the same number the app does, and its one action is CANCEL.
     */
    private fun countdown() {
        val left = SosCountdown.secondsLeft
        if (left == null) return
        showCountdownNotification(left)
        handler.postDelayed({
            if (!SosCountdown.running) return@postDelayed
            if (SosCountdown.tick() == null) sendNow() else countdown()
        }, 1000)
    }

    /** A person stopped it. Nothing is sent, and it says so. */
    private fun abortCountdown() {
        if (!SosCountdown.running) return
        Log.i(TAG, "PANIC: cancelled by the crew -- nothing sent")
        SosCountdown.cancel()
        runCatching { TorchSos.stop(this) }
        runCatching {
            tts?.speak(
                "அவசர அழைப்பு நிறுத்தப்பட்டது.",
                TextToSpeech.QUEUE_FLUSH, null, "orca-panic-cancel",
            )
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ALARM)
    }

    /** The window closed with nobody stopping it. */
    private fun sendNow() {
        SosCountdown.finish()
        // Fully qualified: this file imports android.provider.Settings for
        // the volume ContentObserver, which shadows ORCA's own Settings.
        val cfg = org.orca.advisory.Settings.load(this)
        val report = SosDispatch.fire(
            this, cfg.contacts,
            zoneHint = cfg.homeZone,
            boat = cfg.boatId.takeIf { it.isNotBlank() },
        )
        Log.i(TAG, "PANIC send outcome=${report.outcome}")
        if (report.outcome == SosDispatch.Outcome.SENT ||
            report.outcome == SosDispatch.Outcome.PARTIAL
        ) {
            // Chase a live fix and follow up. The first message may have
            // carried an hours-old position from a phone that was in a
            // pocket; this is the one that gets the boat found.
            SosDispatch.requestUpdate(
                this, cfg.contacts, cfg.homeZone,
                cfg.boatId.takeIf { it.isNotBlank() }, report.fix,
            ) { Log.i(TAG, "PANIC update outcome=${it.outcome}") }
        }

        runCatching {
            tts?.speak(
                if (report.outcome == SosDispatch.Outcome.FAILED ||
                    report.outcome == SosDispatch.Outcome.NO_CONTACT ||
                    report.outcome == SosDispatch.Outcome.NO_PERMISSION
                ) {
                    "அவசர அழைப்பு அனுப்ப முடியவில்லை. திரையைப் பாருங்கள்."
                } else {
                    "அவசர அழைப்பு அனுப்பப்பட்டது. உங்கள் இடம் அனுப்பப்பட்டது."
                },
                TextToSpeech.QUEUE_FLUSH, null, "orca-panic-sent",
            )
        }

        val sent = report.outcome == SosDispatch.Outcome.SENT ||
            report.outcome == SosDispatch.Outcome.PARTIAL
        val n = Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(if (sent) "SOS SENT — அவசர அழைப்பு" else "SOS NOT SENT — அவசர அழைப்பு")
            .setContentText(report.detail)
            .setStyle(Notification.BigTextStyle().bigText(report.detail))
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openSosIntent())
            .setFullScreenIntent(openSosIntent(), true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALARM, n)
    }

    private fun openSosIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SOS, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun showCountdownNotification(secondsLeft: Int) {
        val cancel = PendingIntent.getService(
            this, 2,
            Intent(this, PanicService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("SOS in $secondsLeft s — அவசர அழைப்பு")
            .setContentText("Sending your position. Tap CANCEL to stop it.")
            .setPriority(Notification.PRIORITY_MAX)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSosIntent())
            // Full-screen so it takes over a locked screen rather than
            // queueing behind whatever else is on it.
            .setFullScreenIntent(openSosIntent(), true)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "CANCEL", cancel,
                ).build(),
            )
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALARM, n)
    }

    // --- plumbing ---------------------------------------------------------

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING, "SOS button", NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "The always-available SOS button on your lock screen."
                // Silent: it is permanent, so a tone every time ORCA starts
                // would be pure noise. DEFAULT importance is for placement,
                // not for sound.
                setSound(null, null)
                enableVibration(false)
                // Show the content on the lock screen rather than hiding it,
                // which is the whole point of this notification.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
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

    /**
     * The permanent notification, which is also the screen-off SOS.
     *
     * <h3>WHY THIS CARRIES AN SOS BUTTON</h3>
     * With the screen genuinely off, no app on stock Android receives a
     * volume key. Measured on this handset: accessibility gets nothing
     * while the display sleeps, the MediaSession path is a known framework
     * defect, and this ROM never delivers the volume broadcast to a third
     * party at all. Three paths, three dead ends, so the honest conclusion
     * is that a hardware key cannot be the screen-off trigger here.
     *
     * <p>What IS always reachable is this notification. It is ongoing, so
     * it is always present; VISIBILITY_PUBLIC, so its content shows on the
     * lock screen rather than being hidden; and CATEGORY_ALARM so it sorts
     * to the top. Waking the phone with any button and tapping SOS is two
     * actions with no unlock, no PIN and no app to find -- which is worse
     * than a pocket key-hold and far better than nothing.
     *
     * <p>The action fires the same countdown as everything else, so a
     * mis-tap is still cancellable for ten seconds.
     */
    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, PanicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val sos = PendingIntent.getService(
            this, 3,
            Intent(this, PanicService::class.java).setAction(ACTION_TRIGGER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ORCA அவசர கண்காணிப்பு · SOS ready")
            .setContentText(
                "Press the power button ${PowerPressDetector.PRESSES} times, " +
                    "or tap SEND SOS. No unlocking needed.",
            )
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "Press the power button ${PowerPressDetector.PRESSES} times quickly — " +
                        "this works with the screen off and the phone in your pocket. " +
                        "Or tap SEND SOS below. Either way you get " +
                        "${SosCountdown.SECONDS} seconds to cancel.",
                ),
            )
            .setOngoing(true)
            // Content must be readable ON the lock screen. The default
            // hides it there, which would make this useless for the one
            // moment it exists for.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(
                Notification.Action.Builder(
                    null as android.graphics.drawable.Icon?, "SEND SOS", sos,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop).build(),
            )
            .build()
    }

}
