package app.droplink.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.droplink.DropLinkApp
import app.droplink.MainActivity
import app.droplink.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: toggles receive mode. */
class ReceiveTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val container get() = (application as DropLinkApp).container

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { container.receive.status.collect { render(it.active) } }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val receive = container.receive
        if (receive.status.value.active) {
            receive.stop()
            return
        }
        // Android 12+ may refuse to start a foreground service from here; then open the app instead.
        if (TransferService.start(this)) {
            receive.start()
        } else {
            openApp()
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_RECEIVE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (active) R.string.tile_on else R.string.tile_off)
        }
        tile.contentDescription = getString(if (active) R.string.tile_on_cd else R.string.tile_off_cd)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, ReceiveTileService::class.java)) }
        }
    }
}
