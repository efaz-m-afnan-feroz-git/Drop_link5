package app.droplink.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import app.droplink.core.protocol.ModeSelector
import app.droplink.core.protocol.Protocol
import app.droplink.core.transfer.CoreLog as CoreLogBridge
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The receiver's own Wi-Fi Direct network. Any phone can join it like a normal Wi-Fi network
 * (SSID + passphrase), so data goes phone → phone directly, preferably on 5 GHz.
 */
data class DirectGroup(
    val ssid: String,
    val passphrase: String,
    val ip: String,
    val frequencyMhz: Int?,
) {
    val bandLabel: String? get() = frequencyMhz?.let { if (it >= 5925) "6 GHz" else if (it >= 4900) "5 GHz" else "2.4 GHz" }

    /** Compact form for discovery TXT records. */
    fun toTxt(): Map<String, String> = mapOf("ws" to ssid, "wp" to passphrase, "wi" to ip)

    companion object {
        private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

        fun fromTxt(txt: Map<String, String>?): DirectGroup? {
            val ssid = txt?.get("ws")?.takeIf { it.startsWith("DIRECT-") } ?: return null
            val pass = txt["wp"]?.takeIf { it.length in 8..63 } ?: return null
            val ip = txt["wi"]?.takeIf { IPV4.matches(it) } ?: return null
            return DirectGroup(ssid, pass, ip, null)
        }
    }
}

data class P2pPeer(
    val address: String,
    val deviceName: String,
    /** From the DropLink DNS-SD record, when the OEM stack delivered it. */
    val dropLinkName: String?,
    val deviceId: String?,
    val port: Int?,
    val isGroupOwner: Boolean,
    val direct: DirectGroup? = null,
) {
    val displayName: String get() = dropLinkName ?: deviceName
    val isDropLink: Boolean get() = deviceId != null
}

/**
 * Wi-Fi Direct mode. The receiver creates the group (so it is always the group owner) and
 * advertises a DNS-SD record; the sender discovers it and joins with groupOwnerIntent = 0.
 * OEM stacks are flaky, so every step has a timeout and the caller falls back to QR mode.
 */
@SuppressLint("MissingPermission") // callers check NEARBY_WIFI_DEVICES / location first (see Perms)
class WifiDirectLink(context: Context) {
    private val appContext = context.applicationContext
    private val manager: WifiP2pManager? = appContext.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null

    val isSupported: Boolean
        get() = manager != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    private fun mgr(): WifiP2pManager = manager ?: throw LinkException(LinkProblem.WIFI_DIRECT_UNSUPPORTED)

    @Synchronized
    private fun ch(): WifiP2pManager.Channel = channel ?: mgr().initialize(appContext, Looper.getMainLooper()) {
        synchronized(this) { channel = null } // framework dropped the channel; re-init next time
    }?.also { channel = it } ?: throw LinkException(LinkProblem.WIFI_DIRECT_UNSUPPORTED)

    /** Runs a WifiP2pManager action; returns null on success or the failure reason code (BUSY on timeout). */
    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit): Int? {
        val code = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    block(object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { if (cont.isActive) cont.resume(SUCCESS) }
                        override fun onFailure(reason: Int) { if (cont.isActive) cont.resume(reason) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(WifiP2pManager.ERROR) }
            }
        } ?: WifiP2pManager.BUSY
        return if (code == SUCCESS) null else code
    }

    private fun problemFor(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> LinkProblem.WIFI_DIRECT_UNSUPPORTED
        WifiP2pManager.BUSY -> LinkProblem.WIFI_DIRECT_BUSY
        else -> LinkProblem.WIFI_DIRECT_FAILED
    }

    // ---------------------------------------------------------------------------------- receiver

    /** Stable network name + passphrase per install, so Android can remember the user's approval. */
    private fun credentials(): Pair<String, String> {
        val prefs = appContext.getSharedPreferences("wifi_direct", Context.MODE_PRIVATE)
        val alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        fun random(n: Int) = String(CharArray(n) { alphabet[rnd.nextInt(alphabet.length)] })
        val ssid = prefs.getString("ssid", null) ?: "DIRECT-${random(2)}-DropLink-${random(4)}".also {
            prefs.edit().putString("ssid", it).apply()
        }
        val pass = prefs.getString("pass", null) ?: random(16).also { prefs.edit().putString("pass", it).apply() }
        return ssid to pass
    }

    /**
     * Receiver: creates this phone's own Wi-Fi Direct network (5 GHz when possible) and advertises it.
     * Returns its SSID, passphrase and IP so the sender can join it like a normal Wi-Fi network.
     */
    suspend fun startGroup(name: String, deviceId: String, port: Int): DirectGroup {
        val m = mgr()
        val c = ch()
        action { m.stopPeerDiscovery(c, it) }
        action { m.removeGroup(c, it) } // ignore: there may be no group
        delay(300)

        var created: Int? = WifiP2pManager.ERROR
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (ssid, pass) = credentials()
            // 1. If this phone is on a router, use the router's channel: one radio can't be on two
            //    channels at once, and switching between them halves throughput.
            // 2. Otherwise prefer 5 GHz (often 3–10× faster than 2.4 GHz), then let the system choose.
            val staFreq = currentWifiFrequency()
            CoreLogBridge.log("Wi-Fi Direct: this phone's router connection is on ${staFreq ?: "none"} MHz")
            val attempts = buildList<(WifiP2pConfig.Builder) -> WifiP2pConfig.Builder> {
                if (staFreq != null) add { b -> b.setGroupOperatingFrequency(staFreq) }
                add { b -> b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) }
                add { b -> b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO) }
            }
            for ((i, apply) in attempts.withIndex()) {
                val config = runCatching { apply(WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)).build() }.getOrNull() ?: continue
                created = action { m.createGroup(c, config, it) }
                CoreLogBridge.log("Wi-Fi Direct createGroup attempt ${i + 1}/${attempts.size} -> ${created ?: "ok"}")
                if (created == null) break
                delay(500)
            }
        }
        if (created != null) {
            created = action { m.createGroup(c, it) } // Android 8–9, or the configured group was refused
            CoreLogBridge.log("Wi-Fi Direct createGroup (default) -> ${created ?: "ok"}")
        }
        if (created != null) throw LinkException(problemFor(created), "createGroup failed: $created")

        val group = awaitGroupInfo(m, c) ?: throw LinkException(LinkProblem.WIFI_DIRECT_FAILED, "Group info not available")
        val ip = interfaceIp(group.`interface`) ?: "192.168.49.1" // Android's fixed group-owner address
        val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) group.frequency.takeIf { it > 0 } else null
        val direct = DirectGroup(group.networkName, group.passphrase, ip, freq)
        CoreLogBridge.log("Wi-Fi Direct group up: ${direct.ssid} on ${direct.bandLabel ?: "unknown band"}, ip $ip")

        action { m.clearLocalServices(c, it) }
        val record = WifiP2pDnsSdServiceInfo.newInstance(
            "DropLink",
            Protocol.NSD_SERVICE_TYPE,
            mapOf("id" to deviceId, "n" to name.take(24), "port" to port.toString(), "v" to Protocol.VERSION.toString()) + direct.toTxt(),
        )
        action { m.addLocalService(c, record, it) }
        action { m.discoverPeers(c, it) } // keeps this phone visible to scanners on many ROMs
        return direct
    }

    /** Frequency (MHz) of this phone's current Wi-Fi router connection, or null if not connected. */
    @Suppress("DEPRECATION")
    fun currentWifiFrequency(): Int? = runCatching {
        val wm = appContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val info = wm.connectionInfo ?: return null
        if (info.networkId == -1 && info.bssid == null) return null
        info.frequency.takeIf { it > 0 }
    }.getOrNull()

    private suspend fun awaitGroupInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pGroup? {
        repeat(20) {
            val g = withTimeoutOrNull(2_000) {
                suspendCancellableCoroutine<WifiP2pGroup?> { cont ->
                    runCatching { m.requestGroupInfo(c) { g -> if (cont.isActive) cont.resume(g) } }
                        .onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            if (g != null && g.isGroupOwner && !g.networkName.isNullOrEmpty() && !g.passphrase.isNullOrEmpty()) return g
            delay(500)
        }
        return null
    }

    private fun interfaceIp(name: String?): String? = runCatching {
        val nif = java.net.NetworkInterface.getByName(name ?: return null) ?: return null
        nif.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().firstOrNull()?.hostAddress
    }.getOrNull()

    suspend fun stopGroup() {
        val m = manager ?: return
        val c = channel ?: return
        action { m.clearLocalServices(c, it) }
        action { m.stopPeerDiscovery(c, it) }
        action { m.removeGroup(c, it) }
    }

    // ------------------------------------------------------------------------------------ sender

    fun discover(selfDeviceId: String): Flow<List<P2pPeer>> = callbackFlow {
        val m = manager
        if (m == null || !isSupported) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val c = ch()
        val devices = HashMap<String, WifiP2pDevice>()
        val records = HashMap<String, Map<String, String>>()

        fun publish() {
            val list = devices.values.map { d ->
                val txt = records[d.deviceAddress]
                P2pPeer(
                    address = d.deviceAddress,
                    deviceName = d.deviceName?.takeIf { it.isNotBlank() } ?: d.deviceAddress,
                    dropLinkName = txt?.get("n"),
                    deviceId = txt?.get("id"),
                    port = txt?.get("port")?.toIntOrNull(),
                    isGroupOwner = d.isGroupOwner,
                    direct = DirectGroup.fromTxt(txt),
                )
            }.filter { it.deviceId != selfDeviceId && (it.isDropLink || it.isGroupOwner) }
            trySend(list)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                    runCatching {
                        m.requestPeers(c) { list ->
                            val seen = list?.deviceList?.associateBy { it.deviceAddress } ?: emptyMap()
                            devices.keys.retainAll(seen.keys)
                            devices.putAll(seen)
                            publish()
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        m.setDnsSdResponseListeners(
            c,
            { _, _, _ -> },
            { _, txt, device ->
                if (txt != null && txt.containsKey("id")) {
                    records[device.deviceAddress] = txt
                    devices[device.deviceAddress] = device
                    publish()
                }
            },
        )
        val request = WifiP2pDnsSdServiceRequest.newInstance()
        action { m.addServiceRequest(c, request, it) }

        trySend(emptyList())
        // Discovery silently stops after ~2 minutes on most stacks: keep re-arming it.
        launch {
            while (isActive) {
                action { m.discoverPeers(c, it) }
                action { m.discoverServices(c, it) }
                delay(12_000)
            }
        }

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { m.removeServiceRequest(c, request, null) }
            runCatching { m.stopPeerDiscovery(c, null) }
        }
    }

    /**
     * Joins the receiver's group and returns the group owner's IP. Tries [ModeSelector.WIFI_DIRECT_ATTEMPTS]
     * times, each bounded by [ModeSelector.WIFI_DIRECT_TIMEOUT_MS].
     */
    suspend fun connect(address: String): String {
        val m = mgr()
        val c = ch()
        var lastProblem = LinkProblem.WIFI_DIRECT_TIMEOUT
        repeat(ModeSelector.WIFI_DIRECT_ATTEMPTS) { attempt ->
            try {
                val ip = withTimeoutOrNull(ModeSelector.WIFI_DIRECT_TIMEOUT_MS) { connectOnce(m, c, address) }
                if (ip != null) return ip
                lastProblem = LinkProblem.WIFI_DIRECT_TIMEOUT
            } catch (e: LinkException) {
                if (e.problem == LinkProblem.WIFI_DIRECT_UNSUPPORTED) throw e
                lastProblem = e.problem
            }
            action { m.cancelConnect(c, it) }
            if (attempt + 1 < ModeSelector.WIFI_DIRECT_ATTEMPTS) delay(1_500)
        }
        throw LinkException(lastProblem)
    }

    /**
     * Android 10+: joins the receiver's Wi-Fi Direct network as a P2P client using its name and
     * passphrase. No system dialog, and the phone stays connected to its normal Wi-Fi. Returns the
     * group owner's IP.
     */
    suspend fun joinGroup(net: DirectGroup): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw LinkException(LinkProblem.WIFI_DIRECT_UNSUPPORTED, "Needs Android 10+")
        val m = mgr()
        val c = ch()
        action { m.stopPeerDiscovery(c, it) }
        action { m.cancelConnect(c, it) }
        action { m.removeGroup(c, it) } // leave any group this phone is still in
        delay(300)
        var lastProblem = LinkProblem.WIFI_DIRECT_TIMEOUT
        repeat(ModeSelector.WIFI_DIRECT_ATTEMPTS) { attempt ->
            val config = WifiP2pConfig.Builder()
                .setNetworkName(net.ssid)
                .setPassphrase(net.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                .build()
            val failed = action { m.connect(c, config, it) }
            CoreLogBridge.log("Wi-Fi Direct join ${net.ssid} attempt ${attempt + 1} -> ${failed ?: "requested"} (this phone's router link: ${currentWifiFrequency() ?: "none"} MHz)")
            if (failed == null) {
                val ip = withTimeoutOrNull(ModeSelector.WIFI_DIRECT_TIMEOUT_MS + 5_000) {
                    var found: String? = null
                    while (found == null) {
                        delay(700)
                        found = groupOwnerIp(connectionInfo(m, c))
                    }
                    found
                }
                if (ip != null) {
                    CoreLogBridge.log("Wi-Fi Direct joined ${net.ssid}, group owner $ip")
                    return ip
                }
                lastProblem = LinkProblem.WIFI_DIRECT_TIMEOUT
            } else {
                if (failed == WifiP2pManager.P2P_UNSUPPORTED) throw LinkException(LinkProblem.WIFI_DIRECT_UNSUPPORTED)
                lastProblem = problemFor(failed)
            }
            action { m.cancelConnect(c, it) }
            delay(1_500)
        }
        throw LinkException(lastProblem, "Couldn't join ${net.ssid}")
    }

    private suspend fun connectOnce(m: WifiP2pManager, c: WifiP2pManager.Channel, address: String): String {
        // Already connected to this group (e.g. reconnect after a dropped socket)?
        groupOwnerIp(connectionInfo(m, c))?.let { return it }
        val config = WifiP2pConfig().apply {
            deviceAddress = address
            groupOwnerIntent = 0 // the receiver must stay group owner
        }
        val failed = action { m.connect(c, config, it) }
        if (failed != null) throw LinkException(problemFor(failed), "connect failed: $failed")
        while (true) {
            delay(700)
            groupOwnerIp(connectionInfo(m, c))?.let { return it }
        }
    }

    private fun groupOwnerIp(info: WifiP2pInfo?): String? =
        if (info != null && info.groupFormed && !info.isGroupOwner) info.groupOwnerAddress?.hostAddress else null

    private suspend fun connectionInfo(m: WifiP2pManager, c: WifiP2pManager.Channel): WifiP2pInfo? =
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                runCatching { m.requestConnectionInfo(c) { info -> if (cont.isActive) cont.resume(info) } }
                    .onFailure { if (cont.isActive) cont.resume(null) }
            }
        }

    /** Leaves the group after a transfer (sender side). */
    suspend fun disconnect() {
        val m = manager ?: return
        val c = channel ?: return
        action { m.removeGroup(c, it) }
    }

    private companion object {
        const val SUCCESS = -1
    }
}
