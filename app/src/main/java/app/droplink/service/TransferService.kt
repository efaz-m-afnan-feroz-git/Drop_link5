package app.droplink.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.droplink.DropLinkApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Foreground service (type dataSync) that keeps receive mode and transfers alive with the screen
 * off and the app in the background. It owns no transfer logic: it mirrors the managers' state in
 * its notification, holds the wake/Wi-Fi locks, and stops itself when there's nothing to do.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = ArrayList<WifiManager.WifiLock>()

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        val c = (application as DropLinkApp).container
        Notifications.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, Notifications.ID_SERVICE, Notifications.service(this, c.receive.status.value, c.tracker.current), type)
        acquireWifiLocks()

        scope.launch {
            combine(c.receive.status, c.tracker.state) { receive, ui -> receive to ui }
                .sample(500)
                .collect { (receive, ui) ->
                    val transferring = ui?.phase?.isActive == true
                    setWakeLock(transferring)
                    if (!receive.active && !transferring) {
                        stopSelfCompat()
                    } else {
                        Notifications.update(this@TransferService, Notifications.service(this@TransferService, receive, ui))
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Not sticky: after a process kill there is no in-memory transfer to continue; the sender's
        // resume logic reconnects when the user restarts receiving.
        return START_NOT_STICKY
    }

    /** Android 15+ caps dataSync services at 6 h per day; stop cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached")
        val c = (application as DropLinkApp).container
        c.receive.stop()
        stopSelfCompat()
    }

    private fun stopSelfCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setWakeLock(on: Boolean) {
        if (on) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DropLink:transfer").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L) // safety timeout
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLocks() {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DropLink:lowlatency")
        }
        if (Build.VERSION.SDK_INT < 34) {
            // Deprecated (ignored) on 14+, but the only option on 8–9 and still honoured on 10–13.
            wifiLocks += wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DropLink:highperf")
        }
        wifiLocks.forEach { lock ->
            lock.setReferenceCounted(false)
            runCatching { lock.acquire() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        setWakeLock(false)
        wifiLocks.forEach { runCatching { if (it.isHeld) it.release() } }
        wifiLocks.clear()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DropLinkService"

        /** Starts (or keeps) the service. Safe to call repeatedly; returns false if Android refused. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (12+) when started from the background.
            Log.w(TAG, "Couldn't start foreground service: ${e.message}")
            false
        }
    }
}
