package app.droplink.di

import android.app.Application
import app.droplink.core.security.IdentityStore
import app.droplink.core.transfer.UsbBridge
import app.droplink.data.AppDatabase
import app.droplink.data.SettingsRepository
import app.droplink.net.HotspotJoiner
import app.droplink.net.LanDiscovery
import app.droplink.net.LocalHotspot
import app.droplink.net.UsbLink
import app.droplink.net.WifiDirectLink
import app.droplink.service.ReceiveTileService
import app.droplink.storage.SourceResolver
import app.droplink.transfer.HistoryRecorder
import app.droplink.transfer.ReceiveManager
import app.droplink.transfer.SendManager
import app.droplink.transfer.TransferTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import java.io.File

/** Manual dependency injection: one instance of everything, created with the Application. */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(app)
    val identity = IdentityStore(File(app.filesDir, "identity.p12"))
    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val tracker = TransferTracker()
    val sources = SourceResolver(app)
    val history = HistoryRecorder(app, appScope) { database }

    /** USB cable mode: AOA link + the bridge that carries normal DropLink connections over it. */
    val usbBridge = UsbBridge({ settings.current.deviceName }, settings.deviceId, { identity.get().fingerprint })
    val usbLink = UsbLink(app, usbBridge)

    private val lan = LanDiscovery(app)
    private val wifiDirect = WifiDirectLink(app)

    val receive = ReceiveManager(
        context = app,
        scope = appScope,
        settings = settings,
        identity = identity,
        db = { database },
        tracker = tracker,
        history = history,
        lan = lan,
        p2p = wifiDirect,
        hotspot = LocalHotspot(app),
        usb = usbBridge,
    )

    val send = SendManager(
        context = app,
        scope = appScope,
        settings = settings,
        identity = identity,
        db = { database },
        tracker = tracker,
        history = history,
        lan = lan,
        p2p = wifiDirect,
        joiner = HotspotJoiner(app),
        usbBridge = usbBridge,
        usbLink = usbLink,
    )

    val wifiDirectSupported: Boolean get() = wifiDirect.isSupported

    init {
        // Generate the TLS identity early (first launch) so the first transfer doesn't wait for it.
        appScope.launch(Dispatchers.IO) { runCatching { identity.get() } }
        runCatching { usbLink.start() }
        // Keep the Quick Settings tile in sync.
        appScope.launch {
            receive.status.distinctUntilChangedBy { it.active }.collect { ReceiveTileService.refresh(app) }
        }
    }
}
