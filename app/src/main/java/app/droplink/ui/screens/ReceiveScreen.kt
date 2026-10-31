package app.droplink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.R
import app.droplink.di.AppContainer
import app.droplink.net.LinkProblem
import app.droplink.transfer.LinkState
import app.droplink.transfer.QrState
import app.droplink.ui.components.Asks
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.PermissionFlow
import app.droplink.ui.components.Pulse
import app.droplink.ui.components.QrCode
import app.droplink.ui.components.Tone
import app.droplink.ui.components.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(container: AppContainer, permissions: PermissionFlow, onBack: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val receive = container.receive
    val status by receive.status.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // Ask for what receiving needs, then start (LAN works even if Wi-Fi permissions are refused).
    LaunchedEffect(Unit) {
        if (!receive.status.value.active) {
            permissions.run(
                context,
                listOf(Asks.notifications, Asks.nearbyWifi(forReceive = true), Asks.locationSwitch, Asks.legacyStorage),
            ) { receive.start() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.receive_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.receive_visible_as), style = MaterialTheme.typography.labelLarge)
            Text(settings.deviceName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)

            if (!status.active) {
                Text(stringResource(R.string.receive_off), style = MaterialTheme.typography.bodyMedium)
                Button(onClick = {
                    permissions.run(context, listOf(Asks.notifications, Asks.nearbyWifi(true), Asks.locationSwitch, Asks.legacyStorage)) { receive.start() }
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.receive_start)) }
                return@Column
            }

            // QR toggle
            ListItem(
                modifier = Modifier.semantics(mergeDescendants = true) {
                    role = Role.Switch
                    stateDescription = context.getString(if (status.qrEnabled) R.string.state_on else R.string.state_off)
                },
                leadingContent = { Icon(Icons.Outlined.QrCode2, null) },
                headlineContent = { Text(stringResource(R.string.receive_qr_toggle)) },
                supportingContent = { Text(stringResource(R.string.receive_qr_toggle_hint)) },
                trailingContent = { Switch(checked = status.qrEnabled, onCheckedChange = { receive.setQrEnabled(it) }) },
            )

            if (status.qrEnabled) {
                when (val qr = status.qr) {
                    is QrState.Ready -> {
                        QrCode(qr.payload.encode(), 240.dp, stringResource(R.string.receive_qr_cd))
                        Text(stringResource(R.string.receive_qr_instructions), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                    }
                    is QrState.Failed -> {
                        InfoCard(Icons.Outlined.ErrorOutline, stringResource(R.string.receive_qr_failed), qr.problem.text(), tone = Tone.BAD) {
                            Row {
                                TextButton(onClick = { receive.setQrEnabled(true) }) { Text(stringResource(R.string.action_retry)) }
                                if (qr.problem == LinkProblem.PERMISSION_DENIED) {
                                    TextButton(onClick = {
                                        permissions.run(context, listOf(Asks.nearbyWifi(true), Asks.locationSwitch)) { receive.setQrEnabled(true) }
                                    }) { Text(stringResource(R.string.action_grant)) }
                                }
                            }
                        }
                    }
                    else -> CircularProgressIndicator(Modifier.padding(24.dp))
                }
            } else {
                Pulse(Modifier.size(200.dp), stringResource(R.string.receive_waiting))
                Text(stringResource(R.string.receive_waiting), style = MaterialTheme.typography.titleMedium)
            }

            // Channel status
            val usbState by container.usbLink.state.collectAsStateWithLifecycle()
            StatusRow(
                stringResource(R.string.mode_usb),
                when (usbState) {
                    is app.droplink.net.UsbState.Connected -> LinkState.ON
                    app.droplink.net.UsbState.Connecting, app.droplink.net.UsbState.NeedsPermission -> LinkState.STARTING
                    is app.droplink.net.UsbState.Failed -> LinkState.FAILED
                    app.droplink.net.UsbState.NotConnected -> LinkState.OFF
                },
                when (val u = usbState) {
                    is app.droplink.net.UsbState.Connected -> u.peer?.let { stringResource(R.string.usb_connected_to, it.name) }
                    is app.droplink.net.UsbState.Failed -> u.reason
                    app.droplink.net.UsbState.NotConnected -> stringResource(R.string.usb_not_connected)
                    else -> null
                },
            )
            StatusRow(stringResource(R.string.mode_lan), if (status.lanAdvertised) LinkState.ON else LinkState.STARTING, null)
            StatusRow(
                status.directBand?.let { stringResource(R.string.receive_direct_band, it) } ?: stringResource(R.string.mode_wifi_direct),
                status.wifiDirect,
                status.wifiDirectProblem?.text(),
            )
            if (status.directBand == "2.4 GHz") {
                Text(stringResource(R.string.receive_direct_slow_band), style = MaterialTheme.typography.bodySmall)
            }

            InfoCard(Icons.Outlined.WifiTethering, stringResource(R.string.receive_tip_title), stringResource(R.string.receive_tip_body))
            TextButton(onClick = onSetup) { Text(stringResource(R.string.receive_setup_link)) }
            OutlinedButton(onClick = { receive.stop(); onBack() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.receive_stop))
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, state: LinkState, problem: String?) {
    val (icon, text) = when (state) {
        LinkState.ON -> Icons.Outlined.CheckCircle to (problem ?: stringResource(R.string.state_ready))
        LinkState.STARTING -> Icons.Outlined.HourglassTop to stringResource(R.string.state_starting)
        LinkState.FAILED -> Icons.Outlined.ErrorOutline to (problem ?: stringResource(R.string.state_unavailable))
        LinkState.OFF -> Icons.Outlined.HourglassTop to (problem ?: stringResource(R.string.state_off))
    }
    ListItem(
        leadingContent = { Icon(icon, null, tint = if (state == LinkState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(label) },
        supportingContent = { Text(text) },
        modifier = Modifier.fillMaxWidth(),
    )
}
