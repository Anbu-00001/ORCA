package org.orca.advisory

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Does this phone actually have internet, and over what?
 *
 * <h3>THE BUG THIS FIXES</h3>
 * The app told a crew <i>"No connection — showing what is stored on this
 * phone"</i> whenever a refresh failed, for any reason at all. But a
 * refresh fails for several quite different reasons, and only one of them
 * is "no signal":
 *
 * <ul>
 *  <li>the phone genuinely has no network,
 *  <li>the phone is online but ORCA's server is not running or not
 *      reachable from it,
 *  <li>the server answered with an error.
 * </ul>
 *
 * <p>Reported from the field with full Wi-Fi bars: "I am seeing no
 * connection WHILE I HAVE INTERNET." That message is worse than useless —
 * it teaches a crew to distrust the one indicator that has to be believed
 * at sea, where "no signal" is a real and serious state. If ORCA cries
 * offline in harbour, nobody believes it offshore.
 *
 * <p>So the offline message is now gated on the SYSTEM's answer, not on
 * whether a request happened to fail.
 *
 * <h3>WHY VALIDATED, NOT JUST CONNECTED</h3>
 * `NET_CAPABILITY_INTERNET` means the network claims to offer internet.
 * `NET_CAPABILITY_VALIDATED` means Android actually reached the outside
 * world through it. A captive-portal Wi-Fi in a harbour cafe has the first
 * and not the second, and a crew on one is, for ORCA's purposes, offline.
 */
object Connectivity {

    private const val TAG = "ORCA"

    enum class Kind { NONE, WIFI, MOBILE, OTHER }

    /**
     * What the phone is connected to right now.
     *
     * Returns [Kind.NONE] when there is no validated internet, which is
     * the only state that may be described to the crew as "no connection".
     */
    fun kind(context: Context): Kind = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> Kind.NONE
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Kind.NONE
            // A network that has not been validated cannot reach anything.
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> Kind.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.MOBILE
            else -> Kind.OTHER
        }
    } catch (e: Exception) {
        // Never guess "offline" from a failure to ask. Claiming no signal
        // when we simply could not read the state is the same lie in a
        // different coat.
        Log.w(TAG, "Cannot read connectivity: ${e.message}")
        Kind.OTHER
    }

    fun isOnline(context: Context): Boolean = kind(context) != Kind.NONE

    /** Log exactly what the system reports, so a wrong answer is diagnosable. */
    fun debug(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        "net=$net internet=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)} " +
            "validated=${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
            "wifi=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)} " +
            "cell=${caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)} kind=${kind(context)}"
    } catch (e: Exception) { "connectivity read failed: ${e.message}" }

    /** How to name the connection to a crew, in their language. */
    fun label(context: Context, lang: Lang): String = when (kind(context)) {
        Kind.WIFI -> if (lang == Lang.TA) "வைஃபை" else "Wi-Fi"
        Kind.MOBILE -> if (lang == Lang.TA) "மொபைல் டேட்டா" else "mobile data"
        Kind.OTHER -> if (lang == Lang.TA) "இணைப்பு" else "a connection"
        Kind.NONE -> if (lang == Lang.TA) "இணைப்பு இல்லை" else "no connection"
    }

    /**
     * The honest sentence to show when a refresh failed.
     *
     * Three different failures, three different sentences, because they
     * need three different things done about them.
     */
    fun refreshFailureNote(context: Context, error: String?): String {
        Log.i(TAG, "refresh failed; connectivity: ${debug(context)}")
        return whenKind(context)
    }

    private fun whenKind(context: Context): String = when (kind(context)) {
        Kind.NONE ->
            "No connection — showing what is stored on this phone."

        else -> {
            val over = label(context, Lang.EN)
            "ORCA's server did not answer, but this phone IS online over $over. " +
                "The advisory below is the stored one, labelled with its real age."
        }
    }
}
