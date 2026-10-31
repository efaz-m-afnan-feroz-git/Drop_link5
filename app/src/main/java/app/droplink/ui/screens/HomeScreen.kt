package app.droplink.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.droplink.R
import app.droplink.data.RecentDeviceEntity
import app.droplink.di.AppContainer
import app.droplink.net.NetUtils
import app.droplink.oem.Oem
import app.droplink.oem.OemSetup
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.SectionTitle
import app.droplink.ui.components.Tone
import app.droplink.ui.components.label
import app.droplink.util.Format
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(container: AppContainer) : ViewModel() {
    val recent: StateFlow<List<RecentDeviceEntity>> =
        container.database.recent().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings = container.settings.settings
    val tracker = container.tracker.state
    val receive = container.receive.status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onSetup: () -> Unit,
    onTransfer: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val recent by vm.recent.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ui by vm.tracker.collectAsStateWithLifecycle()
    val receive by vm.receive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var onWifi by remember { mutableStateOf(NetUtils.isOnWifi(context)) }
    var batteryOk by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        onWifi = NetUtils.isOnWifi(context)
        batteryOk = OemSetup.isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!settings.setupShown) container.settings.markSetupShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onHistory) { Icon(Icons.Outlined.History, stringResource(R.string.history_title)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings_title)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui?.takeIf { it.phase.isActive }?.let { active ->
                InfoCard(
                    Icons.Outlined.SwapVert,
                    stringResource(if (active.role == app.droplink.transfer.Role.SEND) R.string.home_active_send else R.string.home_active_receive, active.peerName),
                    stringResource(R.string.home_active_progress, Format.bytes(context, active.doneBytes), Format.bytes(context, active.totalBytes)),
                    tone = Tone.GOOD,
                ) {
                    TextButton(onClick = onTransfer) { Text(stringResource(R.string.action_show)) }
                }
            }
            BigActionButton(
                text = stringResource(R.string.action_send),
                description = stringResource(R.string.home_send_hint),
                icon = { Icon(Icons.Outlined.Upload, null, Modifier.size(36.dp)) },
                onClick = onSend,
                primary = true,
            )
            BigActionButton(
                text = stringResource(if (receive.active) R.string.home_receiving_on else R.string.action_receive),
                description = stringResource(R.string.home_receive_hint),
                icon = { Icon(Icons.Outlined.Download, null, Modifier.size(36.dp)) },
                onClick = onReceive,
                primary = false,
            )

            // Current mode indicator
            InfoCard(
                if (onWifi) Icons.Outlined.Wifi else Icons.Outlined.WifiOff,
                stringResource(R.string.home_mode_title, settings.forcedMode.label()),
                stringResource(if (onWifi) R.string.home_mode_wifi else R.string.home_mode_no_wifi),
            )

            if (!batteryOk && OemSetup.detect() != Oem.OTHER) {
                InfoCard(
                    Icons.Outlined.BatteryAlert,
                    stringResource(R.string.home_setup_title),
                    stringResource(R.string.home_setup_body, stringResource(OemSetup.detect().label)),
                    tone = Tone.WARN,
                ) {
                    TextButton(onClick = onSetup) { Text(stringResource(R.string.setup_title)) }
                }
            }

            if (recent.isNotEmpty()) {
                SectionTitle(stringResource(R.string.home_recent))
                recent.forEach { d ->
                    ListItem(
                        headlineContent = { Text(d.name) },
                        supportingContent = { Text(Format.time(context, d.lastSeen)) },
                        leadingContent = { Icon(Icons.Outlined.PhoneAndroid, null) },
                        trailingContent = {
                            Text(
                                runCatching { app.droplink.core.protocol.ConnectionMode.valueOf(d.lastMode) }.getOrNull().label(),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun BigActionButton(text: String, description: String, icon: @Composable () -> Unit, onClick: () -> Unit, primary: Boolean) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            icon()
            Column(Modifier.padding(horizontal = 16.dp).weight(1f)) {
                Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
        }
    }
    val modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
    val shape = RoundedCornerShape(24.dp)
    if (primary) {
        Button(onClick = onClick, modifier = modifier, shape = shape, contentPadding = ButtonDefaults.ContentPadding) { content() }
    } else {
        FilledTonalButton(onClick = onClick, modifier = modifier, shape = shape) { content() }
    }
}
