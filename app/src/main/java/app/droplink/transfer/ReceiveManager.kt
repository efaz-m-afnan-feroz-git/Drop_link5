package app.droplink.transfer

import android.content.Context
import app.droplink.core.protocol.ConnectionMode
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.QrPayload
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.IdentityStore
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.Decision
import app.droplink.core.transfer.IncomingRequest
import app.droplink.core.transfer.ReceiveCallbacks
import app.droplink.core.transfer.ReceiveOutcome
import app.droplink.core.transfer.ReceiverServer
import app.droplink.data.AppDatabase
import app.droplink.data.Direction
import app.droplink.data.RecentDeviceEntity
import app.droplink.data.SettingsRepository
import app.droplink.data.TrustedDeviceEntity
import app.droplink.net.DirectGroup
import app.droplink.net.LanDiscovery
import app.droplink.net.LinkException
import app.droplink.net.LinkProblem
import app.droplink.net.LocalHotspot
import app.droplink.net.WifiDirectLink
import app.droplink.service.Notifications
import app.droplink.service.TransferService
import app.droplink.storage.Sinks
import app.droplink.util.DiagLog
import app.droplink.util.Perms
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { OFF, STARTING, ON, FAILED }

sealed class QrState {
    data object Off : QrState()
    data object Starting : QrState()
    data class Ready(val payload: QrPayload) : QrState()
    data class Failed(val problem: LinkProblem) : QrState()
}

data class ReceiveStatus(
    val active: Boolean = false,
    val port: Int = -1,
    val lanAdvertised: Boolean = false,
    val wifiDirect: LinkState = LinkState.OFF,
    val wifiDirectProblem: LinkProblem? = null,
    val qrEnabled: Boolean = false,
    val qr: QrState = QrState.Off,
    /** Band of this phone's direct network, e.g. "5 GHz". */
    val directBand: String? = null,
)

data class PendingRequest(
    val sessionId: Long,
    val senderName: String,
    val deviceId: String,
    val fingerprint: String,
    val fileCount: Int,
    val totalBytes: Long,
    val pairingCode: String,
    val sampleNames: List<String>,
    val alreadyTrusted: Boolean,
)

/** Receive mode: TLS server + LAN advertisement + Wi-Fi Direct group + optional QR hotspot. */
class ReceiveManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val identity: IdentityStore,
    private val db: () -> AppDatabase,
    private val tracker: TransferTracker,
    private val history: HistoryRecorder,
    private val lan: LanDiscovery,
    private val p2p: WifiDirectLink,
    private val hotspot: LocalHotspot,
    private val usb: app.droplink.core.transfer.UsbBridge,
) {
    private val _status = MutableStateFlow(ReceiveStatus())
    val status: StateFlow<ReceiveStatus> = _status.asStateFlow()

    private val _pending = MutableStateFlow<PendingRequest?>(null)
    val pending: StateFlow<PendingRequest?> = _pending.asStateFlow()

    private val decisions = ConcurrentHashMap<Long, CompletableDeferred<Pair<Boolean, Boolean>>>()
    private var server: ReceiverServer? = null
    private var activeToken: CancelToken? = null
    private var reconnectWatchdog: Job? = null
    private var startJob: Job? = null

    /** This phone's own Wi-Fi Direct network while receiving (null if it couldn't be created). */
    @Volatile private var direct: DirectGroup? = null

    /**
     * Starts receive mode: TLS server + LAN advertisement + this phone's own 5 GHz Wi-Fi Direct
     * network (the fast, router-free path). The QR code shows that network; if it can't be
     * created, a local-only hotspot is used for the QR code instead.
     */
    @Synchronized
    fun start(withQr: Boolean? = null) {
        if (_status.value.active) {
            withQr?.let { setQrEnabled(it) }
            return
        }
        _status.value = ReceiveStatus(active = true, qrEnabled = withQr ?: true)
        TransferService.start(context)
        startJob = scope.launch(Dispatchers.IO) {
            try {
                val id = identity.get()
                val srv = ReceiverServer(
                    identity = id,
                    sinks = Sinks.createStaged(context, settings.current.saveTreeUri),
                    ledgers = LedgerStore(File(context.filesDir, "resume")),
                    callbacks = callbacks,
                )
                val port = srv.start()
                server = srv
                _status.update { it.copy(port = port) }
                usb.receiverPort = port // a phone on the other end of a USB cable can send now
                startDirect()
                lan.register(settings.current.deviceName, port, settings.deviceId, id.fingerprint, direct)
                _status.update { it.copy(lanAdvertised = true) }
                if (direct == null && _status.value.qrEnabled) startHotspotQr()
            } catch (e: Exception) {
                DiagLog.log("Receive: couldn't start receiving", e)
                stop()
            }
        }
    }

    @Synchronized
    fun stop() {
        startJob?.cancel()
        server?.stop(keepResumeState = false)
        server = null
        usb.receiverPort = 0
        direct = null
        lan.unregister()
        hotspot.stop()
        decisions.values.forEach { it.complete(false to false) }
        decisions.clear()
        _pending.value = null
        Notifications.cancelIncoming(context)
        _status.value = ReceiveStatus()
        scope.launch(Dispatchers.IO) { runCatching { p2p.stopGroup() } }
    }

    /** Shows or hides the QR code. The direct network keeps running either way. */
    fun setQrEnabled(enabled: Boolean) {
        if (!_status.value.active) return
        _status.update { it.copy(qrEnabled = enabled) }
        scope.launch(Dispatchers.IO) {
            val d = direct
            when {
                !enabled -> {
                    hotspot.stop()
                    _status.update { it.copy(qr = QrState.Off) }
                }
                d != null -> _status.update { it.copy(qr = QrState.Ready(payloadFor(d))) }
                else -> startHotspotQr()
            }
        }
    }

    private suspend fun payloadFor(d: DirectGroup) = QrPayload(
        ssid = d.ssid,
        passphrase = d.passphrase,
        security = QrPayload.Security.WPA2,
        ip = d.ip,
        port = _status.value.port,
        fingerprint = identity.get().fingerprint,
        deviceName = settings.current.deviceName,
    )

    /** Creates this phone's own Wi-Fi Direct network (5 GHz preferred). */
    private suspend fun startDirect() {
        if (!p2p.isSupported) {
            _status.update { it.copy(wifiDirect = LinkState.FAILED, wifiDirectProblem = LinkProblem.WIFI_DIRECT_UNSUPPORTED) }
            return
        }
        if (!Perms.wifiDirectReady(context)) {
            _status.update { it.copy(wifiDirect = LinkState.FAILED, wifiDirectProblem = LinkProblem.PERMISSION_DENIED) }
            return
        }
        _status.update { it.copy(wifiDirect = LinkState.STARTING, wifiDirectProblem = null, qr = if (it.qrEnabled) QrState.Starting else it.qr) }
        try {
            val d = p2p.startGroup(settings.current.deviceName, settings.deviceId, _status.value.port)
            direct = d
            val payload = payloadFor(d)
            _status.update {
                if (!it.active) it
                else it.copy(
                    wifiDirect = LinkState.ON,
                    directBand = d.bandLabel,
                    qr = if (it.qrEnabled) QrState.Ready(payload) else it.qr,
                )
            }
        } catch (e: LinkException) {
            DiagLog.log("Receive: Wi-Fi Direct network failed: ${e.problem}", e)
            _status.update { it.copy(wifiDirect = LinkState.FAILED, wifiDirectProblem = e.problem) }
        } catch (e: Exception) {
            DiagLog.log("Receive: Wi-Fi Direct network failed", e)
            _status.update { it.copy(wifiDirect = LinkState.FAILED, wifiDirectProblem = LinkProblem.WIFI_DIRECT_FAILED) }
        }
    }

    /** Fallback when Wi-Fi Direct is unavailable: a local-only hotspot for the QR code. */
    private suspend fun startHotspotQr() {
        if (!Perms.hotspotReady(context)) {
            _status.update { it.copy(qr = QrState.Failed(LinkProblem.PERMISSION_DENIED)) }
            return
        }
        _status.update { it.copy(qr = QrState.Starting) }
        try {
            val info = hotspot.start()
            hotspot.onStoppedBySystem = { _status.update { s -> if (s.qrEnabled) s.copy(qr = QrState.Failed(LinkProblem.HOTSPOT_FAILED)) else s } }
            val payload = QrPayload(
                ssid = info.ssid,
                passphrase = info.passphrase,
                security = info.security,
                ip = info.ip,
                port = _status.value.port,
                fingerprint = identity.get().fingerprint,
                deviceName = settings.current.deviceName,
            )
            _status.update { if (it.qrEnabled) it.copy(qr = QrState.Ready(payload)) else it }
            if (!_status.value.qrEnabled) hotspot.stop()
        } catch (e: LinkException) {
            DiagLog.log("Receive: hotspot failed: ${e.problem}", e)
            _status.update { it.copy(qr = QrState.Failed(e.problem)) }
        } catch (e: Exception) {
            DiagLog.log("Receive: hotspot failed", e)
            _status.update { it.copy(qr = QrState.Failed(LinkProblem.HOTSPOT_FAILED)) }
        }
    }

    /** Answer from the accept/decline dialog or notification. */
    fun respond(sessionId: Long, accept: Boolean, trust: Boolean) {
        decisions[sessionId]?.complete(accept to trust)
    }

    fun cancelTransfer() {
        activeToken?.cancel("Cancelled on the receiving phone")
        server?.cancelAll("Cancelled on the receiving phone")
    }

    private val callbacks = object : ReceiveCallbacks {
        override fun onIncoming(request: IncomingRequest): Decision {
            // A reconnect of a transfer the user already accepted (same transfer, same sender key).
            if (request.isResume) return Decision.Accept
            val hello = request.hello
            val trusted = runBlocking { runCatching { db().trusted().get(hello.deviceId) }.getOrNull() }
            val trustedMatch = trusted != null && trusted.fingerprint.equals(request.peerFingerprint, ignoreCase = true)
            if (trustedMatch && settings.current.autoAcceptTrusted) return Decision.Accept

            val deferred = CompletableDeferred<Pair<Boolean, Boolean>>()
            decisions[request.sessionId] = deferred
            val pending = PendingRequest(
                sessionId = request.sessionId,
                senderName = hello.senderName,
                deviceId = hello.deviceId,
                fingerprint = request.peerFingerprint,
                fileCount = hello.files.size,
                totalBytes = hello.totalBytes,
                pairingCode = request.pairingCode,
                sampleNames = hello.files.take(3).map { it.name },
                alreadyTrusted = trustedMatch,
            )
            _pending.value = pending
            Notifications.showIncoming(context, pending)
            val answer = runBlocking { withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() } }
            decisions.remove(request.sessionId)
            if (_pending.value?.sessionId == request.sessionId) _pending.value = null
            Notifications.cancelIncoming(context)
            return when {
                answer == null -> Decision.Decline("No answer on the receiving phone")
                !answer.first -> Decision.Decline("Declined")
                else -> {
                    if (answer.second) {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                db().trusted().upsert(TrustedDeviceEntity(hello.deviceId, hello.senderName, request.peerFingerprint, System.currentTimeMillis()))
                            }
                        }
                    }
                    Decision.Accept
                }
            }
        }

        override fun onSessionStarted(request: IncomingRequest, token: CancelToken) {
            DiagLog.log("Receive: accepted ${request.hello.files.size} file(s), ${request.hello.totalBytes} bytes from ${request.remoteAddress} via ${request.hello.mode} (resume=${request.isResume})")
            reconnectWatchdog?.cancel()
            activeToken = token
            val hello = request.hello
            val current = tracker.current
            if (current == null || current.transferId != hello.transferId || current.role != Role.RECEIVE) {
                tracker.begin(hello.transferId, Role.RECEIVE, hello.senderName, hello.deviceId, hello.files, hello.mode.toConnectionMode())
            }
            tracker.startData(request.plan.offsets, request.plan.done, request.pairingCode)
            TransferService.start(context)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    db().recent().upsert(RecentDeviceEntity(hello.deviceId, hello.senderName, hello.mode.name, System.currentTimeMillis()))
                }
            }
        }

        override fun onFileStarted(sessionId: Long, index: Int, offset: Long) = tracker.fileStarted(index, offset)
        override fun onBytesReceived(sessionId: Long, index: Int, position: Long) = tracker.bytes(index, position)
        override fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) =
            tracker.fileFinished(index, ok, reason, location)

        override fun onSessionEnded(sessionId: Long, request: IncomingRequest?, outcome: ReceiveOutcome) {
            if (request == null || outcome == ReceiveOutcome.Declined) return
            val current = tracker.current ?: return
            if (current.transferId != request.hello.transferId) return
            when (outcome) {
                ReceiveOutcome.Completed -> finish(Phase.COMPLETED, null)
                is ReceiveOutcome.Cancelled -> finish(Phase.CANCELLED, if (outcome.byPeer) TransferError.PeerCancelled else null)
                is ReceiveOutcome.Failed -> finish(Phase.FAILED, TransferError.Other(outcome.message), outcome.message)
                is ReceiveOutcome.Interrupted -> {
                    if (!_status.value.active) {
                        finish(Phase.FAILED, TransferError.ConnectionLost)
                        return
                    }
                    // Wait for the sender to reconnect and resume; give up after a while.
                    tracker.phase(Phase.RECONNECTING)
                    reconnectWatchdog?.cancel()
                    reconnectWatchdog = scope.launch {
                        delay(RECONNECT_GRACE_MS)
                        val s = tracker.current
                        if (s != null && s.transferId == request.hello.transferId && s.phase == Phase.RECONNECTING) {
                            finish(Phase.FAILED, TransferError.ConnectionLost)
                        }
                    }
                }
                ReceiveOutcome.Declined -> Unit
            }
        }

        override fun onServerError(error: Throwable) {
            DiagLog.log("Receive: server error", error)
        }
    }

    private fun finish(phase: Phase, error: TransferError?, detail: String? = null) {
        DiagLog.log("Receive finished: $phase ${error ?: ""} ${detail ?: ""}")
        activeToken = null
        tracker.finish(phase, error, detail)
        tracker.current?.let { ui ->
            history.record(ui, Direction.RECEIVED)
            Notifications.showFinished(context, ui)
        }
    }

    private companion object {
        const val TAG = "DropLinkReceive"
        const val APPROVAL_TIMEOUT_MS = 110_000L
        const val RECONNECT_GRACE_MS = 120_000L
    }
}

fun TransferMode.toConnectionMode(): ConnectionMode? = when (this) {
    TransferMode.LAN -> ConnectionMode.LAN
    TransferMode.WIFI_DIRECT -> ConnectionMode.WIFI_DIRECT
    TransferMode.QR_HOTSPOT -> ConnectionMode.QR_HOTSPOT
    TransferMode.USB -> ConnectionMode.USB
    TransferMode.LOOPBACK -> null
}

fun ConnectionMode.toTransferMode(): TransferMode = when (this) {
    ConnectionMode.LAN -> TransferMode.LAN
    ConnectionMode.WIFI_DIRECT -> TransferMode.WIFI_DIRECT
    ConnectionMode.QR_HOTSPOT -> TransferMode.QR_HOTSPOT
    ConnectionMode.USB -> TransferMode.USB
}
