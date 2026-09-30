package com.krafttools.pulsekraft.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.krafttools.pulsekraft.core.Link
import com.krafttools.pulsekraft.core.LinkState

/**
 * Reads the connection facts the app needs, and nothing else.
 *
 * Every decision about what those facts mean is in [com.krafttools.pulsekraft.core.Policy],
 * which is pure and unit-tested. This class exists only to turn a
 * platform API into a [LinkState], so that the part of the app capable
 * of refusing to spend somebody's data is the part a test can reach.
 *
 * ## Why the app asks for `ACCESS_NETWORK_STATE` at all
 *
 * Because measuring a connection means spending data, and the cost of
 * that data depends on which network is carrying it — 100 megabytes on
 * Wi-Fi and 100 megabytes on mobile data are not the same decision. The
 * permission is what lets the app say so *before* the bytes go, rather
 * than afterwards. It is the only reason this file exists.
 *
 * ## Failure is treated as "metered"
 *
 * A revoked permission throws, and some devices return no capabilities
 * at all. Both cases produce [LinkState]'s defaults: not connected,
 * metered. That is deliberate. If the app cannot tell what network it
 * is on, it must not assume the cheaper answer — the failure mode of
 * guessing wrong is somebody's data bill, and the failure mode of
 * guessing conservatively is one extra tap.
 */
class Reachability(context: Context) {

    private val manager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    fun current(): LinkState {
        val connectivity = manager ?: return LinkState()
        val network = runCatching { connectivity.activeNetwork }.getOrNull()
            ?: return LinkState()
        val capabilities = runCatching {
            connectivity.getNetworkCapabilities(network)
        }.getOrNull() ?: return LinkState()

        val connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_VALIDATED,
        )
        val metered = !capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        )
        // There is no NET_CAPABILITY_ROAMING. A capability that says
        // "not roaming" was added in API 33, so below that the honest
        // answer is "cannot tell", and the policy treats an unknown
        // roaming state as not-roaming rather than inventing a refusal
        // on a device that simply has no way to report one.
        val roaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            false
        }

        return LinkState(
            // VALIDATED as well as INTERNET: a captive portal declares
            // itself an internet provider and is one. Reporting
            // "connected" for a hotel wifi login page means the test
            // runs, fails at the socket, and reports a transport error
            // instead of saying the page needs signing in to.
            connected = connected && validated,
            link = transportOf(capabilities),
            metered = metered,
            roaming = roaming,
        )
    }

    private fun transportOf(capabilities: NetworkCapabilities): Link = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Link.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Link.CELLULAR
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Link.OTHER
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Link.OTHER
        else -> Link.OTHER
    }
}