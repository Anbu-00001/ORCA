package org.orca.advisory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Bring the panic watch back after a reboot.
 *
 * <h3>WHY THIS IS NOT OPTIONAL</h3>
 * A phone that has been restarted looks identical to one that has not.
 * Without this, a crew who rebooted their handset three weeks before a bad
 * night would hold the volume key and nothing at all would happen, and
 * there is no way for them to discover that in advance — the app opens
 * normally, the setting still reads "on", and only the service is gone.
 *
 * <p>A safety watch that silently does not survive a restart is worse than
 * one that was never installed, because the crew is relying on it.
 *
 * <p>It re-arms only if the crew left it armed. LOCKED_BOOT_COMPLETED is
 * accepted as well as BOOT_COMPLETED so the watch is running before the
 * phone is first unlocked — the emergency does not wait for a PIN.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        // Read the crew's own choice. Never re-arm something they turned off.
        val wanted = Settings.load(context).panicWatch
        Log.i("ORCA", "Boot: panic watch wanted=$wanted")
        if (wanted) PanicService.start(context)
    }
}
