package app.droplink.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.droplink.core.protocol.Protocol
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class LanPeer(
    val serviceName: String,
    val deviceId: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprintPrefix: String?,
    /** The receiver's own Wi-Fi Direct network, for a direct (router-free) link. */
    val direct: DirectGroup? = null,
)

/** LAN mode: advertise / discover "_droplink._tcp" with NsdManager (mDNS). */
class LanDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsd: NsdManager = appContext.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var registration: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int, deviceId: String, fingerprint: String, direct: DirectGroup? = null) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "DropLink ${name.take(30)}"
            serviceType = Protocol.NSD_SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("n", name.take(40))
            setAttribute("fp", fingerprint.take(16))
            setAttribute("v", Protocol.VERSION.toString())
            direct?.toTxt()?.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: $errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "NSD register threw", it) }
    }

    fun unregister() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    /** Emits the current list of DropLink receivers on the same network (excluding this phone). */
    fun browse(selfDeviceId: String): Flow<List<LanPeer>> = callbackFlow {
        val peers = LinkedHashMap<String, LanPeer>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val wifi = appContext.getSystemService(WifiManager::class.java)
        // Some OEM ROMs drop multicast without a MulticastLock.
        val multicast = wifi?.createMulticastLock("droplink-mdns")?.apply { setReferenceCounted(false); runCatching { acquire() } }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "NSD discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                toResolve.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(peers) {
                    peers.remove(serviceInfo.serviceName)
                    trySend(peers.values.toList())
                }
            }
        }

        // Resolve one at a time: older NsdManager only allows a single resolve in flight.
        launch {
            for (info in toResolve) {
                val resolved = resolve(info) ?: continue
                val attrs = resolved.attributes
                fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }
                val id = attr("id") ?: continue
                if (id == selfDeviceId) continue
                val host = hostOf(resolved) ?: continue
                val peer = LanPeer(
                    serviceName = info.serviceName,
                    deviceId = id,
                    name = attr("n") ?: info.serviceName.removePrefix("DropLink "),
                    host = host,
                    port = resolved.port,
                    fingerprintPrefix = attr("fp"),
                    direct = DirectGroup.fromTxt(listOf("ws", "wp", "wi").mapNotNull { k -> attr(k)?.let { k to it } }.toMap()),
                )
                synchronized(peers) {
                    peers[info.serviceName] = peer
                    trySend(peers.values.toList())
                }
            }
        }

        trySend(emptyList())
        runCatching { nsd.discoverServices(Protocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
            .onFailure { Log.w(TAG, "discoverServices threw", it) }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            toResolve.close()
            runCatching { multicast?.release() }
        }
    }

    private fun hostOf(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addrs = info.hostAddresses
            return (addrs.firstOrNull { it is Inet4Address } ?: addrs.firstOrNull())?.hostAddress
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = withTimeoutOrNull(6_000) {
        suspendCancellableCoroutine { cont ->
            val done = AtomicBoolean(false)
            fun finish(result: NsdServiceInfo?) {
                if (done.compareAndSet(false, true) && cont.isActive) cont.resume(result)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = finish(null)
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                        if (serviceInfo.hostAddresses.isNotEmpty()) {
                            finish(serviceInfo)
                            runCatching { nsd.unregisterServiceInfoCallback(this) }
                        }
                    }
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                }
                runCatching { nsd.registerServiceInfoCallback(info, executor, callback) }.onFailure { finish(null) }
                cont.invokeOnCancellation { runCatching { nsd.unregisterServiceInfoCallback(callback) } }
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = finish(null)
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
                    })
                }.onFailure { finish(null) }
            }
        }
    }

    private companion object {
        const val TAG = "DropLinkLan"
    }
}
