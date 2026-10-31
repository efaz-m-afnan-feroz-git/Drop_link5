package app.droplink.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.R
import app.droplink.core.protocol.ConnectionMode
import app.droplink.di.AppContainer
import app.droplink.storage.InstalledApp
import app.droplink.storage.OutgoingItem
import app.droplink.transfer.NearbyPeer
import app.droplink.ui.components.Asks
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.PermissionFlow
import app.droplink.ui.components.Pulse
import app.droplink.ui.components.SectionTitle
import app.droplink.ui.components.label
import app.droplink.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    container: AppContainer,
    permissions: PermissionFlow,
    onBack: () -> Unit,
    onScanQr: () -> Unit,
    onSending: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val send = container.send
    val selection by send.selection.collectAsStateWithLifecycle()
    val peers by send.peers.collectAsStateWithLifecycle()
    val discovering by send.discovering.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }

    fun addUris(uris: List<Uri>, origin: OutgoingItem.Origin) {
        if (uris.isEmpty()) return
        scope.launch {
            resolving = true
            val items = withContext(Dispatchers.IO) { container.sources.fromUris(uris, origin) }
            send.addItems(items)
            resolving = false
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { addUris(it, OutgoingItem.Origin.MEDIA) }
    val pickDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addUris(it, OutgoingItem.Origin.DOCUMENT) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree ?: return@rememberLauncherForActivityResult
        scope.launch {
            resolving = true
            val items = withContext(Dispatchers.IO) { runCatching { container.sources.fromTree(tree) }.getOrDefault(emptyList()) }
            send.addItems(items)
            resolving = false
        }
    }

    // Discover receivers while this screen is visible (after the Wi-Fi permission rationale).
    DisposableEffect(Unit) {
        permissions.run(context, listOf(Asks.nearbyWifi(forReceive = false), Asks.locationSwitch)) { send.startDiscovery() }
        onDispose { if (!send.isBusy) send.stopDiscovery() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.send_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = { ModeMenu(settings.forcedMode) { container.settings.setForcedMode(it) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.send_tab_files)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.send_tab_apps)) })
                }
            }
            if (tab == 0) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        PickerButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.send_pick_media)) {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        }
                        PickerButton(Icons.Outlined.Description, stringResource(R.string.send_pick_files)) { pickDocs.launch(arrayOf("*/*")) }
                        PickerButton(Icons.Outlined.Folder, stringResource(R.string.send_pick_folder)) { pickFolder.launch(null) }
                    }
                }
            } else {
                item { AppPicker(container) { send.addItems(listOf(it)) } }
            }
            if (resolving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }

            // Selection summary
            item {
                SectionTitle(
                    if (selection.isEmpty()) stringResource(R.string.send_nothing_selected)
                    else stringResource(
                        R.string.send_selected_summary,
                        pluralStringResource(R.plurals.file_count, selection.size, selection.size),
                        Format.bytes(context, selection.sumOf { it.entry.size }),
                    ),
                )
            }
            items(selection.take(MAX_SHOWN), key = { it.key }) { item ->
                ListItem(
                    headlineContent = { Text(item.entry.path, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                    supportingContent = { Text(Format.bytes(context, item.entry.size)) },
                    trailingContent = {
                        IconButton(onClick = { send.removeItem(item.key) }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_remove_item, item.entry.name))
                        }
                    },
                )
            }
            if (selection.size > MAX_SHOWN) {
                item { Text(stringResource(R.string.send_more_files, selection.size - MAX_SHOWN), style = MaterialTheme.typography.bodySmall) }
            }
            if (selection.isNotEmpty()) {
                item { TextButton(onClick = { send.clearSelection() }) { Text(stringResource(R.string.action_clear)) } }
            }

            // Nearby receivers
            item { SectionTitle(stringResource(R.string.send_nearby)) }
            item { UsbStatusCard(container) }
            item {
                OutlinedButton(
                    onClick = onScanQr,
                    enabled = selection.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.QrCodeScanner, null)
                    Text(stringResource(R.string.send_scan_qr), Modifier.padding(start = 8.dp))
                }
            }
            if (settings.forcedMode != ConnectionMode.QR_HOTSPOT) {
                if (peers.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (discovering) Pulse(Modifier.size(140.dp), stringResource(R.string.send_searching))
                            Text(
                                stringResource(R.string.send_no_peers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(peers, key = { it.key }) { peer ->
                        PeerCard(peer, enabled = selection.isNotEmpty()) {
                            send.send(peer)
                            onSending()
                        }
                    }
                }
            }
            if (selection.isEmpty()) {
                item { InfoCard(Icons.Outlined.Description, stringResource(R.string.send_pick_first), null) }
            }
        }
    }
}

private const val MAX_SHOWN = 50

@Composable
private fun PickerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Text(text, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PeerCard(peer: NearbyPeer, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.send_to, peer.name), onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(32.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (peer.usb != null) SuggestionChip(onClick = onClick, enabled = enabled, label = { Text(ConnectionMode.USB.label()) })
                    if (peer.lan != null) SuggestionChip(onClick = onClick, enabled = enabled, label = { Text(ConnectionMode.LAN.label()) })
                    if (peer.p2p != null) SuggestionChip(onClick = onClick, enabled = enabled, label = { Text(ConnectionMode.WIFI_DIRECT.label()) })
                }
            }
        }
    }
}

@Composable
private fun ModeMenu(current: ConnectionMode?, onSelect: (ConnectionMode?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text(current.label()) }, modifier = Modifier.padding(end = 8.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf<ConnectionMode?>(null, ConnectionMode.USB, ConnectionMode.WIFI_DIRECT, ConnectionMode.LAN, ConnectionMode.QR_HOTSPOT).forEach { mode ->
                DropdownMenuItem(text = { Text(mode.label()) }, onClick = { onSelect(mode); open = false })
            }
        }
    }
}

@Composable
private fun AppPicker(container: AppContainer, onPick: (OutgoingItem) -> Unit) {
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    val context = LocalContext.current
    val selection by container.send.selection.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { container.sources.installedApps() } }
    val list = apps
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.send_apps_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (list == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
            return@Column
        }
        Column(Modifier.heightIn(max = 420.dp)) {
            LazyColumn(Modifier.height(420.dp)) {
                items(list, key = { it.packageName }) { app ->
                    val checked = selection.any { it.key == "app:${app.packageName}" }
                    val icon = remember(app.packageName) { runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
                    ListItem(
                        modifier = Modifier.clickable(role = Role.Checkbox) {
                            if (checked) container.send.removeItem("app:${app.packageName}")
                            else runCatching { container.sources.fromApp(app) }.getOrNull()?.let(onPick)
                        },
                        leadingContent = {
                            if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Icon(Icons.Outlined.Android, null, Modifier.size(40.dp))
                        },
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(listOfNotNull(app.versionName, Format.bytes(context, app.sizeBytes)).joinToString(" · ")) },
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                    )
                }
            }
        }
    }
}

/** Shows what the USB cable is doing, so the user knows the fastest path is (or isn't) ready. */
@Composable
private fun UsbStatusCard(container: AppContainer) {
    val state by container.usbLink.state.collectAsStateWithLifecycle()
    when (val s = state) {
        app.droplink.net.UsbState.NotConnected -> InfoCard(
            Icons.Outlined.Usb, stringResource(R.string.usb_tip_title), stringResource(R.string.usb_tip_body),
        )
        app.droplink.net.UsbState.NeedsPermission -> InfoCard(
            Icons.Outlined.Usb, stringResource(R.string.usb_permission_title), stringResource(R.string.usb_permission_body),
            tone = app.droplink.ui.components.Tone.WARN,
        )
        app.droplink.net.UsbState.Connecting -> InfoCard(
            Icons.Outlined.Usb, stringResource(R.string.usb_connecting), stringResource(R.string.usb_connecting_body),
        )
        is app.droplink.net.UsbState.Connected -> {
            val p = s.peer
            when {
                p == null -> InfoCard(Icons.Outlined.Usb, stringResource(R.string.usb_connecting), stringResource(R.string.usb_open_app_body))
                !p.receiving -> InfoCard(
                    Icons.Outlined.Usb, stringResource(R.string.usb_connected_to, p.name), stringResource(R.string.usb_tap_receive_body),
                    tone = app.droplink.ui.components.Tone.WARN,
                )
                else -> InfoCard(
                    Icons.Outlined.Usb, stringResource(R.string.usb_connected_to, p.name), stringResource(R.string.usb_ready_body),
                    tone = app.droplink.ui.components.Tone.GOOD,
                )
            }
        }
        is app.droplink.net.UsbState.Failed -> InfoCard(
            Icons.Outlined.Usb, stringResource(R.string.usb_failed), s.reason,
            tone = app.droplink.ui.components.Tone.BAD,
        )
    }
}
