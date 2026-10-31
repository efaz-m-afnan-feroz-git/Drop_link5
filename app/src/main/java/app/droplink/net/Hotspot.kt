package app.droplink.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import app.droplink.core.protocol.QrPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HotspotInfo(val ssid: String, val passphrase: String, val security: QrPayload.Security, val ip: String)

/** Receiver side of QR mode: a local-only hotspot (no internet, no SIM needed). */
@SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES (33+) / ACCESS_FINE_LOCATION (≤32) checked by callers
class LocalHotspot(context: Context) {
    private val appContext = context.applicationContext
    // Nullable: a handful of devices (some TV boxes/tablets) have no Wi-Fi service at all.
    private val wifiOrNull: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val wifi: WifiManager get() = wifiOrNull ?: throw LinkException(LinkProblem.WIFI_OFF, "No Wi-Fi on this device")
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    @Volatile
    var onStoppedBySystem: (() -> Unit)? = null

    suspend fun start(): HotspotInfo {
        stop()
        val before = NetUtils.privateIpv4ByInterface()
        val res = withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<WifiManager.LocalOnlyHotspotReservation> { cont ->
                val callback = object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                        if (cont.isActive) cont.resume(r) else r.close()
                    }
                    override fun onStopped() {
                        reservation = null
                        onStoppedBySystem?.invoke()
                    }
                    override fun onFailed(reason: Int) {
                        if (!cont.isActive) return
                        val problem = when (reason) {
                            ERROR_INCOMPATIBLE_MODE -> LinkProblem.HOTSPOT_INCOMPATIBLE
                            ERROR_TETHERING_DISALLOWED -> LinkProblem.HOTSPOT_NOT_ALLOWED
                            else -> LinkProblem.HOTSPOT_FAILED
                        }
                        cont.resumeWithException(LinkException(problem, "LocalOnlyHotspot failed: $reason"))
                    }
                }
                try {
                    wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
                } catch (e: SecurityException) {
                    cont.resumeWithException(LinkException(LinkProblem.PERMISSION_DENIED, e.message))
                } catch (e: IllegalStateException) {
                    // "Caller already has an active LocalOnlyHotspot request"
                    cont.resumeWithException(LinkException(LinkProblem.HOTSPOT_FAILED, e.message))
                }
            }
        } ?: throw LinkException(LinkProblem.HOTSPOT_FAILED, "Timed out starting the hotspot")
        reservation = res
        val (ssid, pass, security) = credentials(res)
        val ip = findHotspotIp(before)
        return HotspotInfo(ssid, pass, security, ip)
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    private fun credentials(r: WifiManager.LocalOnlyHotspotReservation): Triple<String, String, QrPayload.Security> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = r.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) {
                c.wifiSsid?.toString()?.let(::unquote) ?: ""
            } else {
                @Suppress("DEPRECATION")
                c.ssid?.let(::unquote) ?: ""
            }
            val security = when (c.securityType) {
                SoftApConfiguration.SECURITY_TYPE_OPEN -> QrPayload.Security.OPEN
                SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> QrPayload.Security.WPA3
                else -> QrPayload.Security.WPA2 // WPA2 and WPA3 transition mode both accept WPA2 clients
            }
            return Triple(ssid, c.passphrase ?: "", security)
        }
        @Suppress("DEPRECATION")
        val c: WifiConfiguration = r.wifiConfiguration ?: throw LinkException(LinkProblem.HOTSPOT_FAILED, "No hotspot config")
        @Suppress("DEPRECATION")
        val pass = c.preSharedKey?.let(::unquote) ?: ""
        @Suppress("DEPRECATION")
        val ssid = unquote(c.SSID ?: "")
        return Triple(ssid, pass, if (pass.isEmpty()) QrPayload.Security.OPEN else QrPayload.Security.WPA2)
    }

    /** The hotspot interface appears a moment after onStarted: wait for a new private IPv4 interface. */
    private suspend fun findHotspotIp(before: Map<String, String>): String {
        repeat(25) {
            val now = NetUtils.privateIpv4ByInterface()
            now.entries.firstOrNull { (name, ip) -> before[name] != ip }?.let { return it.value }
            delay(200)
        }
        val now = NetUtils.privateIpv4ByInterface()
        now.entries.firstOrNull { (name, _) -> HOTSPOT_IFACE.containsMatchIn(name) }?.let { return it.value }
        return "192.168.43.1" // classic Android soft-AP gateway; the sender also tries the network's gateway
    }

    private companion object {
        val HOTSPOT_IFACE = Regex("^(ap|swlan|softap|wlan1|wlan2|wigig)")
    }
}

internal fun unquote(s: String): String = if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

/** A joined hotspot network: sockets must be created with [socketFactory] so they go over it. */
class JoinedNetwork(
    val network: Network?,
    val socketFactory: SocketFactory,
    val gateway: String?,
    private val onRelease: () -> Unit,
) {
    fun release() = runCatching { onRelease() }
}

/** Sender side of QR mode: join the receiver's hotspot from the scanned QR code. */
@SuppressLint("MissingPermission")
class HotspotJoiner(context: Context) {
    private val appContext = context.applicationContext
    private val wifiOrNull: WifiManager? = appContext.getSystemService(WifiManager::class.java)
    private val wifi: WifiManager get() = wifiOrNull ?: throw LinkException(LinkProblem.WIFI_OFF, "No Wi-Fi on this device")
    private val cm: ConnectivityManager = appContext.getSystemService(ConnectivityManager::class.java)

    suspend fun join(qr: QrPayload): JoinedNetwork =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) joinWithSpecifier(qr) else joinLegacy(qr)

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun joinWithSpecifier(qr: QrPayload): JoinedNetwork {
        if (!wifi.isWifiEnabled) throw LinkException(LinkProblem.WIFI_OFF)
        val spec = WifiNetworkSpecifier.Builder().setSsid(qr.ssid).apply {
            when (qr.security) {
                QrPayload.Security.WPA3 -> setWpa3Passphrase(qr.passphrase)
                QrPayload.Security.WPA2 -> setWpa2Passphrase(qr.passphrase)
                QrPayload.Security.OPEN -> Unit
            }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        var registered: ConnectivityManager.NetworkCallback? = null
        val network = suspendCancellableCoroutine<Network> { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (cont.isActive) cont.resume(network)
                }
                override fun onUnavailable() {
                    if (cont.isActive) cont.resumeWithException(LinkException(LinkProblem.JOIN_REJECTED))
                }
            }
            registered = callback
            try {
                // The system shows a "Connect to <ssid>?" dialog; the user must tap Connect.
                cm.requestNetwork(request, callback, 90_000)
            } catch (e: Exception) {
                cont.resumeWithException(LinkException(LinkProblem.JOIN_FAILED, e.message))
            }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
        // Keep the callback registered for the whole transfer: unregistering drops the network.
        return JoinedNetwork(network, network.socketFactory, NetUtils.gatewayOf(appContext, network)) {
            registered?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun joinLegacy(qr: QrPayload): JoinedNetwork {
        if (!wifi.isWifiEnabled) {
            runCatching { wifi.isWifiEnabled = true }
            delay(2_000)
            if (!wifi.isWifiEnabled) throw LinkException(LinkProblem.WIFI_OFF)
        }
        val quoted = "\"${qr.ssid}\""
        val config = WifiConfiguration().apply {
            SSID = quoted
            if (qr.security == QrPayload.Security.OPEN) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"${qr.passphrase}\""
            }
        }
        var added = true
        var netId = wifi.addNetwork(config)
        if (netId == -1) {
            added = false
            netId = runCatching { wifi.configuredNetworks?.firstOrNull { it.SSID == quoted }?.networkId }.getOrNull() ?: -1
        }
        if (netId == -1) throw LinkException(LinkProblem.JOIN_FAILED, "addNetwork failed")
        wifi.disconnect()
        wifi.enableNetwork(netId, true)
        wifi.reconnect()

        val network: Network? = withTimeoutOrNull(30_000) {
            var found: Network? = null
            while (found == null) {
                found = cm.allNetworks.firstOrNull { n ->
                    val caps = cm.getNetworkCapabilities(n)
                    val lp = cm.getLinkProperties(n)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                        wifi.connectionInfo?.ssid == quoted &&
                        lp?.linkAddresses?.any { it.address is Inet4Address } == true
                }
                if (found == null) delay(500)
            }
            found
        }
        if (network == null) {
            if (added) wifi.removeNetwork(netId)
            throw LinkException(LinkProblem.JOIN_FAILED, "Timed out joining ${qr.ssid}")
        }
        return JoinedNetwork(network, network.socketFactory, NetUtils.gatewayOf(appContext, network)) {
            if (added) {
                wifi.removeNetwork(netId) // restores the previous Wi-Fi network
            }
        }
    }
}
