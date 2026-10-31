package app.droplink.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.droplink.R
import app.droplink.core.protocol.QrPayload
import app.droplink.di.AppContainer
import app.droplink.storage.OutgoingItem
import app.droplink.transfer.Phase
import app.droplink.transfer.Role
import app.droplink.transfer.SendEvent
import app.droplink.ui.components.Asks
import app.droplink.ui.components.rememberPermissionFlow
import app.droplink.ui.screens.HistoryScreen
import app.droplink.ui.screens.HomeScreen
import app.droplink.ui.screens.IncomingRequestDialog
import app.droplink.ui.screens.PhoneSetupScreen
import app.droplink.ui.screens.ReceiveScreen
import app.droplink.ui.screens.SendScreen
import app.droplink.ui.screens.SettingsScreen
import app.droplink.ui.screens.TransferScreen
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class StartRequest {
    data class Share(val uris: List<Uri>) : StartRequest()
    data object UnsupportedText : StartRequest()
    data object Receive : StartRequest()
    data object ShowTransfer : StartRequest()
}

object Routes {
    const val HOME = "home"
    const val SEND = "send"
    const val RECEIVE = "receive"
    const val TRANSFER = "transfer"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val SETUP = "setup"
}

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }

@Composable
fun AppRoot(container: AppContainer, startRequest: StateFlow<StartRequest?>, consumeStartRequest: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val permissions = rememberPermissionFlow()

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        val payload = QrPayload.parse(contents)
        if (payload == null) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.qr_invalid)) }
        } else {
            container.send.sendViaQr(payload)
            nav.go(Routes.TRANSFER)
        }
    }
    val scanQr: () -> Unit = {
        permissions.run(context, listOf(Asks.camera, Asks.nearbyWifi(forReceive = false), Asks.locationSwitch)) {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(context.getString(R.string.scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
    }

    // Direct modes failed: go straight to the scanner (the transfer screen also offers a button).
    LaunchedEffect(Unit) {
        container.send.events.collect { event ->
            if (event == SendEvent.NeedQrScan) {
                nav.go(Routes.TRANSFER)
                scanQr()
            }
        }
    }

    // An incoming transfer started: show it, whatever screen we're on.
    val ui by container.tracker.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui?.transferId, ui?.phase) {
        val s = ui ?: return@LaunchedEffect
        if (s.role == Role.RECEIVE && s.phase == Phase.TRANSFERRING && nav.currentDestination?.route != Routes.TRANSFER) {
            nav.go(Routes.TRANSFER)
        }
    }

    // Share sheet, Quick Settings tile, notification taps.
    val request by startRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        when (val r = request ?: return@LaunchedEffect) {
            is StartRequest.Share -> {
                val items = withContext(Dispatchers.IO) { container.sources.fromUris(r.uris, OutgoingItem.Origin.SHARED) }
                container.send.addItems(items)
                if (items.isEmpty()) snackbar.showSnackbar(context.getString(R.string.share_nothing))
                nav.go(Routes.SEND)
            }
            StartRequest.UnsupportedText -> snackbar.showSnackbar(context.getString(R.string.share_text_unsupported))
            StartRequest.Receive -> nav.go(Routes.RECEIVE)
            StartRequest.ShowTransfer -> if (container.tracker.current != null) nav.go(Routes.TRANSFER)
        }
        consumeStartRequest()
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) {
                HomeScreen(container, onSend = { nav.go(Routes.SEND) }, onReceive = { nav.go(Routes.RECEIVE) },
                    onHistory = { nav.go(Routes.HISTORY) }, onSettings = { nav.go(Routes.SETTINGS) },
                    onSetup = { nav.go(Routes.SETUP) }, onTransfer = { nav.go(Routes.TRANSFER) })
            }
            composable(Routes.SEND) {
                SendScreen(container, permissions, onBack = { nav.popBackStack() }, onScanQr = scanQr,
                    onSending = { nav.go(Routes.TRANSFER) })
            }
            composable(Routes.RECEIVE) {
                ReceiveScreen(container, permissions, onBack = { nav.popBackStack() }, onSetup = { nav.go(Routes.SETUP) })
            }
            composable(Routes.TRANSFER) {
                TransferScreen(container, onBack = { nav.popBackStack() }, onScanQr = scanQr, onHistory = { nav.go(Routes.HISTORY) })
            }
            composable(Routes.HISTORY) { HistoryScreen(container, onBack = { nav.popBackStack() }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(container, onBack = { nav.popBackStack() }, onSetup = { nav.go(Routes.SETUP) })
            }
            composable(Routes.SETUP) { PhoneSetupScreen(container, permissions, onBack = { nav.popBackStack() }) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        IncomingRequestDialog(container)
    }
}
