package app.droplink.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {

    /** True if the phone is joined to any Wi-Fi network (internet not required). */
    @Suppress("DEPRECATION")
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** interface name → IPv4 address, for up, non-loopback, private (site-local) addresses. */
    fun privateIpv4ByInterface(): Map<String, String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nif.name to it.hostAddress!! }
            }
            .toMap()
    }.getOrDefault(emptyMap())

    /** Gateway (= hotspot owner) of a joined network, as a fallback when the QR's IP doesn't answer. */
    fun gatewayOf(context: Context, network: Network): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.dhcpServerAddress?.hostAddress?.let { return it }
        }
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }
}

/** Why a connection mode isn't usable, mapped to a human-readable string resource in the UI. */
enum class LinkProblem {
    WIFI_OFF,
    LOCATION_OFF,
    PERMISSION_DENIED,
    WIFI_DIRECT_UNSUPPORTED,
    WIFI_DIRECT_BUSY,
    WIFI_DIRECT_FAILED,
    WIFI_DIRECT_TIMEOUT,
    HOTSPOT_INCOMPATIBLE, // e.g. the regular tethering hotspot is on
    HOTSPOT_NOT_ALLOWED,  // tethering disallowed by policy
    HOTSPOT_FAILED,
    JOIN_REJECTED,        // user dismissed the "connect to device" dialog
    JOIN_FAILED,
    NO_PEER_ADDRESS,
}

class LinkException(val problem: LinkProblem, detail: String? = null) : Exception(detail ?: problem.name)
