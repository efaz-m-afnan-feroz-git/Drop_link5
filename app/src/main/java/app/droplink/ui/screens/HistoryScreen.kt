package app.droplink.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.droplink.R
import app.droplink.core.protocol.ConnectionMode
import app.droplink.data.Direction
import app.droplink.data.HistoryEntity
import app.droplink.data.ItemStatus
import app.droplink.data.ThroughputRow
import app.droplink.di.AppContainer
import app.droplink.ui.components.label
import app.droplink.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val items: StateFlow<List<HistoryEntity>> =
        container.database.history().observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val speeds: StateFlow<List<ThroughputRow>> =
        container.database.history().throughputLog().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(item: HistoryEntity, alsoFile: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (alsoFile && item.direction == Direction.RECEIVED) item.location?.let { container.history.deleteReceivedFile(it) }
        container.database.history().delete(item.id)
    }

    fun clear() = viewModelScope.launch(Dispatchers.IO) { container.database.history().clear() }
}

object HistoryActions {
    /** content:// Uri for a received file path on Android 8–9 (FileProvider), so other apps can open it. */
    fun fileUri(context: Context, path: String): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", File(path))

    private fun uriFor(context: Context, location: String): Uri =
        if (location.startsWith("content://")) Uri.parse(location) else fileUri(context, location)

    fun open(context: Context, item: HistoryEntity): Boolean = runCatching {
        val uri = uriFor(context, item.location ?: return false)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun share(context: Context, item: HistoryEntity): Boolean = runCatching {
        val uri = uriFor(context, item.location ?: return false)
        val intent = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: HistoryViewModel = viewModel(factory = viewModelFactory { initializer { HistoryViewModel(container) } })
    val items by vm.items.collectAsStateWithLifecycle()
    val speeds by vm.speeds.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<HistoryEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) { Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.history_clear)) }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                PrimaryTabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.history_tab_files)) })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history_tab_speed)) })
                }
            }
            if (tab == 0) {
                if (items.isEmpty()) item { Text(stringResource(R.string.history_empty), Modifier.padding(24.dp)) }
                items(items, key = { it.id }) { item ->
                    HistoryRow(
                        item,
                        onOpen = { if (!HistoryActions.open(context, item)) message = context.getString(R.string.history_cant_open) },
                        onShare = { if (!HistoryActions.share(context, item)) message = context.getString(R.string.history_cant_open) },
                        onDelete = { toDelete = item },
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.history_speed_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (speeds.isEmpty()) item { Text(stringResource(R.string.history_empty), Modifier.padding(24.dp)) }
                items(speeds, key = { it.transferId }) { row ->
                    ListItem(
                        leadingContent = { Icon(Icons.Outlined.Speed, null) },
                        headlineContent = { Text(Format.megabytesPerSecond(context, row.bytesPerSecond)) },
                        supportingContent = {
                            Text(
                                listOf(
                                    runCatching { ConnectionMode.valueOf(row.mode) }.getOrNull().label(),
                                    row.peerName,
                                    Format.bytes(context, row.totalBytes),
                                    Format.time(context, row.timestamp),
                                ).joinToString(" · "),
                            )
                        },
                        trailingContent = {
                            Icon(
                                if (row.direction == Direction.SENT) Icons.Outlined.Upload else Icons.Outlined.Download,
                                stringResource(if (row.direction == Direction.SENT) R.string.history_sent else R.string.history_received),
                            )
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear)) },
            text = { Text(stringResource(R.string.history_clear_body)) },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text(stringResource(R.string.action_clear)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_not_now)) } },
        )
    }
    toDelete?.let { item ->
        val received = item.direction == Direction.RECEIVED && item.status == ItemStatus.OK && item.location != null
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.history_delete_title, item.fileName)) },
            text = { Text(stringResource(if (received) R.string.history_delete_file_body else R.string.history_delete_entry_body)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(item, alsoFile = received); toDelete = null }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (received) vm.delete(item, alsoFile = false)
                    toDelete = null
                }) { Text(stringResource(if (received) R.string.history_remove_entry_only else R.string.action_not_now)) }
            },
        )
    }
    message?.let { m ->
        AlertDialog(onDismissRequest = { message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_ok)) } })
    }
}

@Composable
private fun HistoryRow(item: HistoryEntity, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val failed = item.status != ItemStatus.OK
    ListItem(
        leadingContent = {
            Icon(
                if (item.direction == Direction.SENT) Icons.Outlined.Upload else Icons.Outlined.Download,
                stringResource(if (item.direction == Direction.SENT) R.string.history_sent else R.string.history_received),
            )
        },
        headlineContent = { Text(item.relativePath, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            val status = when (item.status) {
                ItemStatus.OK -> stringResource(R.string.file_verified)
                ItemStatus.FAILED -> stringResource(R.string.file_failed, item.reason ?: stringResource(R.string.file_failed_unknown))
                ItemStatus.CANCELLED -> stringResource(R.string.phase_cancelled)
            }
            Text(
                listOf(Format.bytes(context, item.sizeBytes), item.peerName, Format.time(context, item.timestamp), status).joinToString(" · "),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box(contentAlignment = Alignment.Center) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.history_actions, item.fileName)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!failed && item.location != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_open)) }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_share)) }, onClick = { menu = false; onShare() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
