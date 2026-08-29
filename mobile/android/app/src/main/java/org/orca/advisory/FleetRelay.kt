package org.orca.advisory

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Boat-to-boat advisory relay. Store, carry, forward.
 *
 * THE PROBLEM THIS EXISTS TO SOLVE
 * --------------------------------
 * Every marine advisory system in India works to about 15 km offshore and
 * then stops. INCOIS reaches roughly 7 lakh fishermen -- by SMS. "Due to
 * limited range of mobile networks and VHFs, the erstwhile information
 * system was not able to communicate disaster warnings to fishermen when
 * they moved away from the coast beyond 10-12 km." ISRO's answer was
 * hardware: GEMINI, then DAT-SG, a satellite receiver issued per boat.
 *
 * Meanwhile there are phones on every one of those boats, and they can
 * see each other.
 *
 * HOW IT WORKS
 * ------------
 * Boat A pulls the advisory in harbour and sails. Boat B left two days ago
 * with nothing. When they pass within BLE range, A advertises what it is
 * carrying -- how old, how many zones -- and B decides whether that beats
 * what it has. If it does, B takes it.
 *
 * Quantified in the literature: "any boat within range of the land base
 * station can serve as a range extender or relay, with each additional
 * level of range extension adding about 15-20 km to the achievable range"
 * (arXiv 2502.13559). Delay-tolerant forwarding improves delivery "albeit
 * with a delay" -- and a six-hour-old storm warning that arrives is worth
 * infinitely more than a live one that does not.
 *
 * WHY NOBODY HAS BUILT IT
 * -----------------------
 * Existing maritime mesh work assumes infrastructure: buoys as relays,
 * uninhabited islands, or a LoRa radio per boat (Meshtastic, Signal K).
 * Using the phones already aboard needs a payload small enough to move in
 * seconds of proximity and self-describing enough to trust from a
 * stranger. ORCA's bundle is both: 52 KB measured, and every reading
 * inside carries source, valid_time, confidence and provenance.
 *
 * THE SAFETY ARGUMENT, WHICH IS THE WHOLE DESIGN
 * ----------------------------------------------
 * A relayed advisory arrives from an untrusted phone. So:
 *
 *   - Nothing is trusted because it was received. A bundle is accepted
 *     only if it parses, carries real zones, and is NEWER than what this
 *     phone holds -- compared on the server's own cache_fetched_at.
 *   - No verdict is ever recomputed. A relayed bundle carries policy.py's
 *     decisions exactly as the server issued them. This class moves bytes;
 *     it does not reason.
 *   - Relayed data is labelled, with its age and hop count. An advisory
 *     whose origin is hidden is one nobody can judge.
 *   - A relayed bundle never overwrites a fresher direct download.
 *
 * STATUS
 * ------
 * The advertisement and scan below are real and complete: two phones
 * running this WILL discover each other and exchange manifests. The bulk
 * transfer (GATT characteristic, 52 KB in ~20 chunks) is the remaining
 * work -- see docs/HANDOFF.md. NOT tested on two devices, because only one
 * was available. Do not claim it works until it has been.
 */
class FleetRelay(private val context: Context) {

    data class Manifest(
        /** Minutes since the SERVER collected the readings -- not since
         *  the bundle was relayed. Hops must not reset the clock, or a
         *  five-hop advisory would look fresh. */
        val ageMinutes: Int,
        val zoneCount: Int,
        /** Advisory only; never used to reject. */
        val hops: Int,
    )

    companion object {
        private const val TAG = "ORCA"

        /** ORCA's own service id, carried in the advertisement so scanning
         *  filters in hardware rather than waking the CPU for every beacon
         *  in the harbour. */
        val SERVICE_UUID: UUID = UUID.fromString("0000face-0000-1000-8000-00805f9b34fb")
        val BUNDLE_CHARACTERISTIC: UUID = UUID.fromString("0000fac1-0000-1000-8000-00805f9b34fb")

        /** BLE advertisements are tiny, so we do NOT try to send the
         *  bundle in one. We send a MANIFEST -- what this phone carries and
         *  how old it is, in 8 bytes -- and the receiver decides whether
         *  it is worth connecting for the full 52 KB. That decision is the
         *  whole point: a boat that already has something newer should
         *  never spend battery on the transfer. */
        private const val MANIFEST_BYTES = 8

        // ---- PURE LOGIC ------------------------------------------------
        //
        // The four functions below touch no radio, no Context and no
        // Android API beyond Log. They are the accept/reject rules
        // standing between the fleet and a stale or malformed safety
        // verdict propagating through it, so they live here where they can
        // be unit-tested on the JVM without two phones and an ocean.
        // See app/src/test/java/org/orca/advisory/FleetRelayTest.kt.

        /** Pack what this phone holds into the 8 bytes an advertisement
         *  can carry alongside the service UUID. */
        @JvmStatic
        fun encodeManifest(m: Manifest): ByteArray = byteArrayOf(
            1,                                              // protocol version
            (m.ageMinutes shr 8).toByte(),                  // age, big-endian
            (m.ageMinutes and 0xFF).toByte(),
            m.zoneCount.toByte(),
            m.hops.toByte(),
            0, 0, 0,                                        // reserved
        )

        @JvmStatic
        fun decodeManifest(bytes: ByteArray?): Manifest? {
            if (bytes == null || bytes.size < MANIFEST_BYTES || bytes[0].toInt() != 1) return null
            val age = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)
            return Manifest(
                ageMinutes = age,
                zoneCount = bytes[3].toInt() and 0xFF,
                hops = bytes[4].toInt() and 0xFF,
            )
        }

        /**
         * Is a neighbour's advisory worth taking?
         *
         *   - anything beats nothing
         *   - newer readings win, by the SERVER's collection time
         *   - a tie is NOT taken: 52 KB over BLE costs battery that has to
         *     last the trip, and it would buy nothing
         *   - hop count never rejects; a five-hop bundle newer than yours
         *     is still better than yours
         */
        @JvmStatic
        fun isBetter(theirs: Manifest, ourAgeMinutes: Int?): Boolean {
            if (theirs.zoneCount <= 0) return false          // empty is not an advisory
            if (ourAgeMinutes == null) return true           // we have nothing
            return theirs.ageMinutes < ourAgeMinutes
        }

        /**
         * Validate a bundle received from another boat.
         *
         * NOTHING is trusted because it arrived. Accepted only if it
         * parses, carries real zones, and is genuinely newer than what we
         * hold. Stamped with its hop count so the UI can say where it came
         * from. Returns the JSON to store, or null to reject.
         */
        @JvmStatic
        fun validateReceived(json: String, ourCollectedAt: Instant?): String? = try {
            val root = JSONObject(json)
            val zones = root.optJSONArray("zones")
            val collected = root.optString("cache_fetched_at").takeIf { it.isNotEmpty() }
                ?.let { Instant.parse(it) }
            when {
                zones == null || zones.length() == 0 -> {
                    Log.w(TAG, "Fleet relay: rejected a bundle with no zones"); null
                }
                collected == null -> {
                    // Without a collection time the advisory cannot be
                    // aged, and one of unknowable age must never be shown
                    // as if it were current.
                    Log.w(TAG, "Fleet relay: rejected a bundle with no cache_fetched_at"); null
                }
                ourCollectedAt != null && !collected.isAfter(ourCollectedAt) -> {
                    Log.i(TAG, "Fleet relay: ignored a bundle no newer than ours"); null
                }
                else -> {
                    root.put("relayed", true)
                    root.put("hops", root.optInt("hops", 0) + 1)
                    root.put("relayed_at", Instant.now().toString())
                    root.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fleet relay: rejected an unparseable bundle: ${e.message}")
            null
        }
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    val available: Boolean
        get() = adapter?.isEnabled == true && adapter?.bluetoothLeAdvertiser != null

    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    /**
     * Announce what this boat is carrying.
     *
     * TX_POWER_LOW and MODE_BALANCED on purpose: this runs for a whole
     * trip on a battery that also has to last the trip. Range is traded
     * for endurance, and the relay only needs the seconds of proximity
     * that two passing boats actually give.
     */
    fun startAdvertising(manifest: Manifest): Boolean {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "Fleet relay: BLE advertising unavailable on this device")
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)     // a boat is not a name
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .addServiceData(ParcelUuid(SERVICE_UUID), encodeManifest(manifest))
            .build()

        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                // Never swallowed: a relay that silently failed to start
                // looks identical to one with no boats nearby.
                Log.w(TAG, "Fleet relay: advertising failed, code $errorCode")
            }
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(TAG, "Fleet relay: advertising ${manifest.zoneCount} zones, " +
                        "${manifest.ageMinutes} min old, ${manifest.hops} hops")
            }
        }
        advertiseCallback = callback
        return try {
            advertiser.startAdvertising(settings, data, callback); true
        } catch (e: SecurityException) {
            Log.w(TAG, "Fleet relay: Bluetooth advertise permission missing")
            false
        }
    }

    /** Look for boats carrying something newer. `onBetter` fires only when
     *  a neighbour's manifest beats ours. */
    fun startScanning(ourAgeMinutes: Int?, onBetter: (Manifest, String) -> Unit): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            Log.w(TAG, "Fleet relay: BLE scanning unavailable")
            return false
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            // LOW_POWER: this runs all trip. Two boats in proximity stay
            // there for minutes, so a slow duty cycle still catches them.
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
                val theirs = decodeManifest(bytes) ?: return
                if (!isBetter(theirs, ourAgeMinutes)) return
                Log.i(TAG, "Fleet relay: neighbour has a newer advisory " +
                        "(${theirs.ageMinutes} min vs ${ourAgeMinutes ?: "none"})")
                onBetter(theirs, result.device.address)
            }
            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Fleet relay: scan failed, code $errorCode")
            }
        }
        scanCallback = callback
        return try {
            scanner.startScan(filters, settings, callback); true
        } catch (e: SecurityException) {
            Log.w(TAG, "Fleet relay: Bluetooth scan permission missing")
            false
        }
    }

    fun stop() {
        try {
            advertiseCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
            scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "Fleet relay: could not stop cleanly: ${e.message}")
        }
        advertiseCallback = null; scanCallback = null
    }
}
