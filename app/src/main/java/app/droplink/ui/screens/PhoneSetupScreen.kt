package app.droplink.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.droplink.R
import app.droplink.di.AppContainer
import app.droplink.oem.OemSetup
import app.droplink.ui.components.Asks
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.PermissionFlow
import app.droplink.ui.components.Tone
import app.droplink.util.Perms

/** Brand-specific steps so OEM battery managers don't kill transfers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneSetupScreen(container: AppContainer, permissions: PermissionFlow, onBack: () -> Unit) {
    val context = LocalContext.current
    val oem = remember { OemSetup.detect() }
    var batteryOk by remember { mutableStateOf(OemSetup.isIgnoringBatteryOptimizations(context)) }
    var notificationsOk by remember { mutableStateOf(Perms.granted(context, Perms.notifications())) }
    var wifiOk by remember { mutableStateOf(Perms.wifiDirectReady(context)) }
    LifecycleResumeEffect(Unit) {
        batteryOk = OemSetup.isIgnoringBatteryOptimizations(context)
        notificationsOk = Perms.granted(context, Perms.notifications())
        wifiOk = Perms.wifiDirectReady(context)
        onPauseOrDispose { }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard(
                Icons.Outlined.PhoneAndroid,
                stringResource(R.string.setup_detected, stringResource(oem.label)),
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            )
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyMedium)

            // 1. Battery optimization
            InfoCard(
                if (batteryOk) Icons.Outlined.CheckCircle else Icons.Outlined.BatteryFull,
                stringResource(R.string.setup_battery_title),
                stringResource(if (batteryOk) R.string.setup_battery_done else R.string.setup_battery_body),
                tone = if (batteryOk) Tone.GOOD else Tone.WARN,
            ) {
                if (!batteryOk) {
                    Button(onClick = { OemSetup.requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.setup_battery_action))
                    }
                }
            }

            // 2. Autostart / background launch (OEM screen)
            InfoCard(Icons.Outlined.RocketLaunch, stringResource(R.string.setup_autostart_title), stringResource(oem.steps)) {
                FilledTonalButton(onClick = { OemSetup.openAutostart(context, oem) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (OemSetup.hasAutostartScreen(oem)) R.string.setup_autostart_action else R.string.setup_app_settings_action))
                }
            }

            // 3. Lock in recents
            InfoCard(Icons.Outlined.Lock, stringResource(R.string.setup_lock_title), stringResource(R.string.setup_lock_body))

            // 4. Notifications (progress + incoming requests)
            if (Perms.notifications().isNotEmpty()) {
                InfoCard(
                    if (notificationsOk) Icons.Outlined.CheckCircle else Icons.Outlined.Notifications,
                    stringResource(R.string.perm_notifications_title),
                    stringResource(R.string.perm_notifications_body),
                    tone = if (notificationsOk) Tone.GOOD else Tone.WARN,
                ) {
                    if (!notificationsOk) {
                        FilledTonalButton(onClick = {
                            permissions.run(context, listOf(Asks.notifications)) { notificationsOk = Perms.granted(context, Perms.notifications()) }
                        }) { Text(stringResource(R.string.action_grant)) }
                    }
                }
            }

            // 5. Wi-Fi Direct / hotspot permission
            InfoCard(
                if (wifiOk) Icons.Outlined.CheckCircle else Icons.Outlined.Wifi,
                stringResource(R.string.perm_nearby_title),
                stringResource(if (container.wifiDirectSupported) R.string.setup_wifi_body else R.string.err_wfd_unsupported),
                tone = if (wifiOk) Tone.GOOD else Tone.NEUTRAL,
            ) {
                if (!wifiOk) {
                    FilledTonalButton(onClick = {
                        permissions.run(context, listOf(Asks.nearbyWifi(forReceive = true), Asks.locationSwitch)) { wifiOk = Perms.wifiDirectReady(context) }
                    }) { Text(stringResource(R.string.action_grant)) }
                }
            }
            Text(
                stringResource(R.string.setup_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
