package app.droplink.transfer

import android.content.Context
import android.os.Build
import app.droplink.core.protocol.ConnectionMode
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.IncompatibleVersionException
import app.droplink.core.protocol.ModeInputs
import app.droplink.core.protocol.ModeSelector
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.QrPayload
import app.droplink.core.protocol.RemoteErrorException
import app.droplink.core.security.IdentityStore
import app.droplink.core.security.PeerNotTrustedException
import app.droplink.core.transfer.SendListener
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.Endpoint
import app.droplink.core.transfer.FatalTransferException
import app.droplink.core.transfer.FileResult
import app.droplink.core.transfer.SendOutcome
import app.droplink.core.transfer.SenderSession
import app.droplink.data.AppDatabase
import app.droplink.data.Direction
import app.droplink.data.RecentDeviceEntity
import app.droplink.data.SettingsRepository
import app.droplink.net.DirectGroup
import app.droplink.net.HotspotJoiner
import app.droplink.net.LanDiscovery
import app.droplink.net.LanPeer
import app.droplink.net.LinkException
import app.droplink.net.LinkProblem
import app.droplink.net.P2pPeer
import app.droplink.net.WifiDirectLink
import app.droplink.service.Notifications
import app.droplink.service.TransferService
import app.droplink.storage.OutgoingItem
import app.droplink.storage.SourceResolver
import app.droplink.util.DiagLog
import app.droplink.util.Perms
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/** A receiver found nearby, possibly reachable in more than one way. */
data class NearbyPeer(
    val key: String,
    val name: String,
    val deviceId: String?,
    val lan: LanPeer?,
    val p2p: P2pPeer?,
    /** The phone on the other end of a USB cable (fastest). */
    val usb: app.droplink.core.transfer.UsbBridge.Peer? = null,
)

sealed class SendEvent {
    /** Direct modes failed (or weren't possible): ask the user to scan the receiver's QR code. */
    data object NeedQrScan : SendEvent()
}

private class OutgoingTransfer(val id: String, val items: List<OutgoingItem>)

private enum class Attempt { DONE, NOT_CONNECTED }

/** Send side: selection, discovery, mode auto-selection, the resume/reconnect loop. */
class SendManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val identity: IdentityStore,
    private val db: () -> AppDatabase,
    private val tracker: TransferTracker,
    private val history: HistoryRecorder,
    private val lan: LanDiscovery,
    private val p2p: WifiDirectLink,
    private val joiner: HotspotJoiner,
    private val usbBridge: app.droplink.core.transfer.UsbBridge,
    private val usbLink: app.droplink.net.UsbLink,
) {
    private val _selection = MutableStateFlow<List<OutgoingItem>>(emptyList())
    val selection: StateFlow<List<OutgoingItem>> = _selection.asStateFlow()

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val _discovering = MutableStateFlow(false)
    val discovering: StateFlow<Boolean> = _discovering.asStateFlow()

    private val _events = MutableSharedFlow<SendEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SendEvent> = _events.asSharedFlow()

    private var discoveryJob: Job? = null
    @Volatile private var lastDetail: String? = null
    private var sendJob: Job? = null
    @Volatile private var token: CancelToken? = null
    @Volatile private var waitingForQr: OutgoingTransfer? = null

    // ------------------------------------------------------------------------------ selection

    fun addItems(items: List<OutgoingItem>) = _selection.update { (it + items).distinctBy { i -> i.key } }
    fun removeItem(key: String) = _selection.update { list -> list.filterNot { it.key == key } }
    fun clearSelection() = _selection.update { emptyList() }

    // ------------------------------------------------------------------------------ discovery

    fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        val self = settings.deviceId
        val p2pFlow = if (p2p.isSupported && Perms.wifiDirectReady(context)) p2p.discover(self) else flowOf(emptyList())
        _discovering.value = true
        discoveryJob = scope.launch {
            try {
                val usbPeer = usbLink.state.map { (it as? app.droplink.net.UsbState.Connected)?.peer?.takeIf { p -> p.receiving } }
                combine(lan.browse(self), p2pFlow, usbPeer) { l, p, u -> merge(l, p, u) }.collect { _peers.value = it }
            } finally {
                _discovering.value = false
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _peers.value = emptyList()
    }

    private fun merge(lanPeers: List<LanPeer>, p2pPeers: List<P2pPeer>, usbPeer: app.droplink.core.transfer.UsbBridge.Peer?): List<NearbyPeer> {
        val out = LinkedHashMap<String, NearbyPeer>()
        usbPeer?.let { u -> out[u.deviceId] = NearbyPeer(u.deviceId, u.name, u.deviceId, null, null, u) }
        lanPeers.forEach { l -> out[l.deviceId] = out[l.deviceId]?.copy(lan = l) ?: NearbyPeer(l.deviceId, l.name, l.deviceId, l, null) }
        p2pPeers.forEach { p ->
            val key = p.deviceId ?: "p2p:${p.address}"
            val existing = out[key]
            out[key] = existing?.copy(p2p = p) ?: NearbyPeer(key, p.displayName, p.deviceId, null, p)
        }
        // Cable first, then alphabetical.
        return out.values.sortedWith(compareBy<NearbyPeer> { it.usb == null }.thenBy { it.name.lowercase() })
    }

    // ------------------------------------------------------------------------------ sending

    val isBusy: Boolean get() = tracker.current?.phase?.isActive == true

    /** Sends the current selection to [peer], choosing LAN → Wi-Fi Direct → QR automatically. */
    fun send(peer: NearbyPeer) {
        val transfer = newTransfer(peer.name, peer.deviceId) ?: return
        val tok = CancelToken().also { token = it }
        lastDetail = null
        sendJob = scope.launch(Dispatchers.IO + crashGuard(transfer)) {
            // P2P scanning competes with the data link for airtime and confuses some stacks.
            discoveryJob?.cancel()
            val wifiDirectUsable = p2p.isSupported && Perms.wifiDirectReady(context)
            val directNet = peer.lan?.direct ?: peer.p2p?.direct
            val plan = ModeSelector.plan(
                ModeInputs(settings.current.forcedMode, peer.lan != null, peer.p2p != null, wifiDirectUsable, directNet != null, peer.usb != null),
            )
            DiagLog.log("Send: plan $plan for ${peer.name} (lan=${peer.lan != null}, p2p=${peer.p2p != null}, direct=${directNet?.ssid})")
            var lastError: TransferError = TransferError.CouldNotConnect
            for (mode in plan) {
                if (tok.isCancelled) break
                tracker.update { it.copy(mode = mode, phase = Phase.CONNECTING) }
                val result = when (mode) {
                    ConnectionMode.LAN -> peer.lan?.let { l ->
                        runSession(transfer, mode, tok) {
                            listOf(Endpoint(l.host, l.port, connectTimeoutMs = ModeSelector.LAN_CONNECT_TIMEOUT_MS))
                        }
                    } ?: Attempt.NOT_CONNECTED
                    ConnectionMode.WIFI_DIRECT -> if (directNet != null) {
                        val r = sendOverDirectNetwork(transfer, tok, directNet, peer.lan?.port ?: peer.p2p?.port ?: Protocol.DEFAULT_PORT)
                        if (r != Attempt.DONE) {
                            lastError = TransferError.Link(LinkProblem.JOIN_FAILED)
                            delay(3_000) // let the phone reconnect to its normal Wi-Fi before trying the router path
                        }
                        r ?: Attempt.NOT_CONNECTED
                    } else peer.p2p?.let { w ->
                        try {
                            runSession(transfer, mode, tok) {
                                listOf(Endpoint(p2p.connect(w.address), w.port ?: Protocol.DEFAULT_PORT))
                            }
                        } catch (e: LinkException) {
                            DiagLog.log("Send: Wi-Fi Direct failed", e)
                            lastError = TransferError.Link(e.problem)
                            lastDetail = describe(e)
                            Attempt.NOT_CONNECTED
                        } finally {
                            runCatching { p2p.disconnect() }
                        }
                    } ?: Attempt.NOT_CONNECTED
                    ConnectionMode.USB -> peer.usb?.let { u ->
                        runSession(transfer, mode, tok) {
                            // Pinned: the receiver's key arrived over the cable itself.
                            listOf(Endpoint("127.0.0.1", usbBridge.localPort(), expectedFingerprint = u.fingerprint, connectTimeoutMs = 5_000))
                        }
                    } ?: Attempt.NOT_CONNECTED
                    ConnectionMode.QR_HOTSPOT -> {
                        waitingForQr = transfer
                        tracker.update { it.copy(mode = mode, phase = Phase.NEEDS_QR, error = lastError) }
                        _events.tryEmit(SendEvent.NeedQrScan)
                        return@launch
                    }
                }
                if (result == Attempt.DONE) return@launch
            }
            if (tok.isCancelled) finish(transfer, Phase.CANCELLED) else finish(transfer, Phase.FAILED, lastError, lastDetail)
        }
    }

    /**
     * Sends over the receiver's own Wi-Fi Direct network (phone → phone, no router). Two ways in:
     *  1. Android 10+: join it as a Wi-Fi Direct client (no dialog, normal Wi-Fi stays connected);
     *  2. otherwise / if that fails: join it like a normal Wi-Fi network (Android shows "Connect?").
     * Returns DONE when the transfer ran (whatever its result), or null/NOT_CONNECTED to fall back.
     */
    private suspend fun sendOverDirectNetwork(
        transfer: OutgoingTransfer,
        tok: CancelToken,
        net: DirectGroup,
        port: Int,
        pinnedFingerprint: String? = null,
    ): Attempt? {
        tracker.update { it.copy(mode = ConnectionMode.WIFI_DIRECT, phase = Phase.CONNECTING) }

        // 1. Wi-Fi Direct client join.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && p2p.isSupported && Perms.wifiDirectReady(context)) {
            try {
                val goIp = p2p.joinGroup(net)
                val result = try {
                    runSession(transfer, ConnectionMode.WIFI_DIRECT, tok) {
                        listOf(goIp, net.ip).distinct().map { Endpoint(it, port, expectedFingerprint = pinnedFingerprint) }
                    }
                } finally {
                    runCatching { p2p.disconnect() }
                }
                if (result == Attempt.DONE) return result
                DiagLog.log("Send: joined ${net.ssid} via Wi-Fi Direct but couldn't reach the receiver")
            } catch (e: LinkException) {
                DiagLog.log("Send: Wi-Fi Direct join of ${net.ssid} failed: ${e.problem}", e)
                lastDetail = describe(e)
            }
            if (tok.isCancelled) return null
        }

        // 2. Join it like a normal Wi-Fi network.
        val qr = QrPayload(net.ssid, net.passphrase, QrPayload.Security.WPA2, net.ip, port, "0".repeat(64), "")
        val joined = try {
            withTimeoutOrNull(75_000) { joiner.join(qr) } ?: run {
                DiagLog.log("Send: joining ${net.ssid} as Wi-Fi timed out")
                return null
            }
        } catch (e: Exception) {
            DiagLog.log("Send: couldn't join ${net.ssid} as Wi-Fi", e)
            lastDetail = describe(e)
            return null
        }
        DiagLog.log("Send: joined ${net.ssid} as Wi-Fi (gateway ${joined.gateway})")
        return try {
            runSession(transfer, ConnectionMode.WIFI_DIRECT, tok) {
                listOfNotNull(
                    Endpoint(net.ip, port, joined.socketFactory, pinnedFingerprint),
                    joined.gateway?.takeIf { it != net.ip }?.let { Endpoint(it, port, joined.socketFactory, pinnedFingerprint) },
                )
            }
        } finally {
            joined.release()
        }
    }

    /** QR mode: join the receiver's hotspot from the scanned code and send. */
    fun sendViaQr(payload: QrPayload) {
        val transfer = waitingForQr ?: newTransfer(payload.deviceName, null) ?: return
        waitingForQr = null
        val tok = token?.takeIf { !it.isCancelled } ?: CancelToken().also { token = it }
        tracker.update {
            it.copy(mode = ConnectionMode.QR_HOTSPOT, phase = Phase.CONNECTING, peerName = payload.deviceName.ifBlank { it.peerName }, error = null)
        }
        if (payload.protocolVersion < Protocol.MIN_COMPATIBLE_VERSION) {
            finish(transfer, Phase.FAILED, TransferError.Incompatible)
            return
        }
        lastDetail = null
        sendJob = scope.launch(Dispatchers.IO + crashGuard(transfer)) {
            // The receiver's own Wi-Fi Direct network (the normal case): fastest way in.
            if (payload.ssid.startsWith("DIRECT-")) {
                val net = DirectGroup(payload.ssid, payload.passphrase, payload.ip, null)
                val result = sendOverDirectNetwork(transfer, tok, net, payload.port, payload.fingerprint)
                if (result != Attempt.DONE) {
                    finish(
                        transfer, if (tok.isCancelled) Phase.CANCELLED else Phase.FAILED,
                        TransferError.Link(LinkProblem.JOIN_FAILED), lastDetail,
                    )
                }
                return@launch
            }
            // Fallback hotspot (receiver couldn't start Wi-Fi Direct).
            val joined = try {
                joiner.join(payload)
            } catch (e: LinkException) {
                DiagLog.log("Send: couldn't join hotspot ${payload.ssid}", e)
                finish(transfer, Phase.FAILED, TransferError.Link(e.problem), describe(e))
                return@launch
            } catch (e: Exception) {
                DiagLog.log("Send: couldn't join hotspot ${payload.ssid}", e)
                finish(transfer, Phase.FAILED, TransferError.Other(e.message ?: "Couldn't join the hotspot"), describe(e))
                return@launch
            }
            try {
                val result = runSession(transfer, ConnectionMode.QR_HOTSPOT, tok) {
                    listOfNotNull(
                        Endpoint(payload.ip, payload.port, joined.socketFactory, payload.fingerprint),
                        joined.gateway?.takeIf { it != payload.ip }?.let { Endpoint(it, payload.port, joined.socketFactory, payload.fingerprint) },
                    )
                }
                if (result == Attempt.NOT_CONNECTED) {
                    finish(transfer, if (tok.isCancelled) Phase.CANCELLED else Phase.FAILED, TransferError.CouldNotConnect, lastDetail)
                }
            } finally {
                joined.release()
            }
        }
    }

    fun cancel() {
        token?.cancel("Cancelled on the sending phone")
        val waiting = waitingForQr
        if (waiting != null && sendJob?.isActive != true) {
            waitingForQr = null
            finish(waiting, Phase.CANCELLED)
        }
    }

    /** Last-resort handler: an unexpected crash in a send job becomes a visible, logged failure. */
    private fun crashGuard(transfer: OutgoingTransfer) = CoroutineExceptionHandler { _, e ->
        DiagLog.log("Send: crashed", e)
        runCatching { finish(transfer, Phase.FAILED, TransferError.Other(e.javaClass.simpleName), describe(e)) }
    }

    private fun newTransfer(peerName: String, peerDeviceId: String?): OutgoingTransfer? {
        if (isBusy) return null
        val items = SourceResolver.uniquify(_selection.value)
        if (items.isEmpty()) return null
        val transfer = OutgoingTransfer(UUID.randomUUID().toString(), items)
        tracker.begin(transfer.id, Role.SEND, peerName, peerDeviceId, items.map { it.entry }, null)
        TransferService.start(context)
        return transfer
    }

    /**
     * Runs the transfer over endpoints from [endpoints], reconnecting and resuming after drops.
     * Returns NOT_CONNECTED if no endpoint could be reached before the receiver accepted
     * (so the caller can fall back to the next mode).
     */
    private suspend fun runSession(
        transfer: OutgoingTransfer,
        mode: ConnectionMode,
        tok: CancelToken,
        endpoints: suspend () -> List<Endpoint>,
    ): Attempt {
        var accepted = false
        var retries = 0
        while (true) {
            var connected = false
            val candidates = try {
                endpoints()
            } catch (e: LinkException) {
                if (!accepted) throw e
                emptyList()
            }
            for (ep in candidates) {
                if (tok.isCancelled) {
                    finish(transfer, Phase.CANCELLED)
                    return Attempt.DONE
                }
                val listener = object : SendListener {
                    override fun onConnected(peerFingerprint: String, pairingCode: String) {
                        connected = true
                        tracker.update { it.copy(pairingCode = pairingCode, mode = mode, phase = if (accepted) Phase.RECONNECTING else Phase.WAITING_APPROVAL) }
                    }
                    override fun onAccepted(accept: app.droplink.core.protocol.AcceptMsg) {
                        accepted = true
                        tracker.startData(accept.offsets, accept.done, null)
                    }
                    override fun onFileStarted(index: Int, offset: Long) = tracker.fileStarted(index, offset)
                    override fun onBytesSent(index: Int, position: Long) = tracker.bytes(index, position)
                    override fun onFileFinished(result: FileResult) =
                        tracker.fileFinished(result.index, result.ok, result.reason, skipped = result.skipped)
                }
                val hello = Hello(
                    protocolVersion = Protocol.VERSION,
                    transferId = transfer.id,
                    senderName = settings.current.deviceName,
                    deviceId = settings.deviceId,
                    mode = mode.toTransferMode(),
                    resume = accepted,
                    files = transfer.items.map { it.entry },
                )
                try {
                    val outcome = SenderSession(identity.get(), ep, hello, transfer.items.map { it.file }, listener, tok).run()
                    when (outcome) {
                        is SendOutcome.Completed -> finish(transfer, Phase.COMPLETED)
                        SendOutcome.CancelledLocally -> finish(transfer, Phase.CANCELLED)
                        is SendOutcome.CancelledByPeer -> finish(transfer, Phase.CANCELLED, TransferError.PeerCancelled)
                        is SendOutcome.Declined -> {
                            tracker.update { it.copy(declineReason = outcome.reason) }
                            finish(transfer, Phase.DECLINED)
                        }
                    }
                    rememberPeer(mode)
                    return Attempt.DONE
                } catch (e: FatalTransferException) {
                    DiagLog.log("Send: fatal error over $mode", e)
                    finish(transfer, Phase.FAILED, classify(e), describe(e))
                    return Attempt.DONE
                } catch (e: IOException) {
                    if (tok.isCancelled) {
                        finish(transfer, Phase.CANCELLED)
                        return Attempt.DONE
                    }
                    lastDetail = describe(e)
                    DiagLog.log("Send: session over $mode to ${ep.host}:${ep.port} failed (connected=$connected, accepted=$accepted)", e)
                    if (connected && !accepted) {
                        finish(transfer, Phase.FAILED, if (e is SocketTimeoutException) TransferError.NoAnswer else TransferError.ConnectionLost, lastDetail)
                        return Attempt.DONE
                    }
                    if (accepted) break // reconnect + resume below
                    // otherwise: try the next endpoint
                } catch (e: Exception) {
                    DiagLog.log("Send: unexpected error over $mode", e)
                    finish(transfer, Phase.FAILED, TransferError.Other(e.javaClass.simpleName), describe(e))
                    return Attempt.DONE
                }
            }
            if (!accepted) return Attempt.NOT_CONNECTED
            retries++
            if (retries > MAX_RESUME_ATTEMPTS) {
                finish(transfer, Phase.FAILED, TransferError.ConnectionLost, lastDetail)
                return Attempt.DONE
            }
            tracker.phase(Phase.RECONNECTING)
            delay(minOf(1_000L shl minOf(retries - 1, 3), 8_000L))
        }
    }

    private fun classify(e: FatalTransferException): TransferError {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            chain.any { it is PeerNotTrustedException } -> TransferError.KeyMismatch
            chain.any { it is IncompatibleVersionException } -> TransferError.Incompatible
            chain.any { it is RemoteErrorException && it.message?.contains("busy", ignoreCase = true) == true } -> TransferError.Busy
            chain.any { it is RemoteErrorException && it.message?.contains("Incompatible", ignoreCase = true) == true } -> TransferError.Incompatible
            else -> TransferError.Other(e.message ?: "Transfer failed")
        }
    }

    private fun rememberPeer(mode: ConnectionMode) {
        val ui = tracker.current ?: return
        val id = ui.peerDeviceId ?: return
        scope.launch(Dispatchers.IO) {
            runCatching { db().recent().upsert(RecentDeviceEntity(id, ui.peerName, mode.name, System.currentTimeMillis())) }
        }
    }

    private fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(4).toList()
        return chain.joinToString(" ← ") { "${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun finish(transfer: OutgoingTransfer, phase: Phase, error: TransferError? = null, detail: String? = null) {
        if (phase == Phase.FAILED) DiagLog.log("Send ${transfer.id} failed: $error ${detail ?: ""}")
        else DiagLog.log("Send ${transfer.id} finished: $phase")
        tracker.finish(phase, error, detail)
        token = null
        val ui = tracker.current ?: return
        if (phase != Phase.DECLINED || ui.okCount > 0) {
            history.record(ui, Direction.SENT, transfer.items.associate { it.entry.path to it.key })
        }
        Notifications.showFinished(context, ui)
        if (ui.phase == Phase.COMPLETED) clearSelection()
    }

    private companion object {
        const val TAG = "DropLinkSend"
        const val MAX_RESUME_ATTEMPTS = 10
    }
}
