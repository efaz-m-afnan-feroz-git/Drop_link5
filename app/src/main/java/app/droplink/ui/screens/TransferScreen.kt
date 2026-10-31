package app.droplink.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droplink.R
import app.droplink.di.AppContainer
import app.droplink.transfer.FileProgress
import app.droplink.transfer.FileStatus
import app.droplink.transfer.Phase
import app.droplink.transfer.Role
import app.droplink.transfer.TransferUi
import app.droplink.ui.components.BigCode
import app.droplink.ui.components.InfoCard
import app.droplink.ui.components.Tone
import app.droplink.ui.components.label
import app.droplink.ui.components.text
import app.droplink.ui.theme.FixedColors
import app.droplink.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(container: AppContainer, onBack: () -> Unit, onScanQr: () -> Unit, onHistory: () -> Unit) {
    val ui by container.tracker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        val s = ui
        if (s == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.transfer_none))
                TextButton(onClick = onHistory) { Text(stringResource(R.string.history_title)) }
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Header(s) }
            if (s.phase == Phase.NEEDS_QR) {
                item {
                    InfoCard(Icons.Outlined.QrCodeScanner, stringResource(R.string.transfer_needs_qr_title), stringResource(R.string.transfer_needs_qr_body), tone = Tone.WARN) {
                        s.error?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = onScanQr, modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.send_scan_qr))
                        }
                    }
                }
            }
            val code = s.pairingCode
            if (code != null && (s.phase == Phase.WAITING_APPROVAL || s.phase == Phase.TRANSFERRING || s.phase == Phase.RECONNECTING)) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        BigCode(code, stringResource(R.string.transfer_code_label))
                        Text(
                            stringResource(if (s.role == Role.SEND) R.string.transfer_code_hint_sender else R.string.transfer_code_hint_receiver),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Overall(s) }
            if (s.phase.isFinished) item { Outcome(s) }
            itemsIndexed(s.files, key = { i, f -> "$i:${f.path}" }) { _, f -> FileRow(f, s.role) { loc -> open(context, loc) } }
            item {
                Spacer(Modifier.padding(4.dp))
                if (s.phase.isActive) {
                    OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Cancel, null)
                        Text(stringResource(R.string.action_cancel), Modifier.padding(start = 8.dp))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { container.tracker.clear(); onBack() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_done))
                        }
                        OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.history_title))
                        }
                    }
                }
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_confirm_title)) },
            text = { Text(stringResource(R.string.cancel_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    if (container.tracker.current?.role == Role.SEND) container.send.cancel() else container.receive.cancelTransfer()
                }) { Text(stringResource(R.string.action_cancel_transfer)) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.action_keep_going)) } },
        )
    }
}

private fun open(context: android.content.Context, location: String) {
    val uri = if (location.startsWith("content://")) Uri.parse(location) else app.droplink.ui.screens.HistoryActions.fileUri(context, location)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun Header(s: TransferUi) {
    Column {
        Text(
            stringResource(if (s.role == Role.SEND) R.string.notif_sending_to else R.string.notif_receiving_from, s.peerName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.transfer_via, s.mode.label()) + " · " + phaseText(s.phase),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun phaseText(p: Phase): String = stringResource(
    when (p) {
        Phase.CONNECTING -> R.string.phase_connecting
        Phase.NEEDS_QR -> R.string.phase_needs_qr
        Phase.WAITING_APPROVAL -> R.string.phase_waiting_approval
        Phase.TRANSFERRING -> R.string.phase_transferring
        Phase.RECONNECTING -> R.string.phase_reconnecting
        Phase.COMPLETED -> R.string.phase_completed
        Phase.PARTIAL -> R.string.phase_partial
        Phase.FAILED -> R.string.phase_failed
        Phase.CANCELLED -> R.string.phase_cancelled
        Phase.DECLINED -> R.string.phase_declined
    },
)

@Composable
private fun Overall(s: TransferUi) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (s.phase == Phase.CONNECTING || s.phase == Phase.WAITING_APPROVAL || s.phase == Phase.RECONNECTING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { s.progress },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${(s.progress * 100).toInt()}%" },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.transfer_bytes, Format.bytes(context, s.doneBytes), Format.bytes(context, s.totalBytes)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pluralStringResource(R.plurals.files_done, s.files.size, s.okCount, s.files.size),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (s.phase == Phase.TRANSFERRING) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Format.speed(context, s.bytesPerSecond), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(Format.eta(context, s.etaSeconds), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun Outcome(s: TransferUi) {
    val context = LocalContext.current
    val avg = Format.megabytesPerSecond(context, s.averageBytesPerSecond.toLong())
    val logButton: @Composable () -> Unit = {
        s.errorDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { app.droplink.ui.components.copyDiagLog(context) }) { Text(stringResource(R.string.diag_copy)) }
    }
    when (s.phase) {
        Phase.COMPLETED -> InfoCard(
            Icons.Outlined.CloudDone,
            stringResource(if (s.role == Role.SEND) R.string.done_sent else R.string.done_received),
            stringResource(R.string.transfer_average, avg) +
                if (s.role == Role.RECEIVE) "\n" + stringResource(R.string.transfer_saved_to) else "",
            tone = Tone.GOOD,
        )
        Phase.PARTIAL -> InfoCard(
            Icons.Outlined.ErrorOutline,
            stringResource(R.string.done_partial, s.failedCount),
            stringResource(R.string.transfer_partial_body) + "\n" + stringResource(R.string.transfer_average, avg),
            tone = Tone.WARN,
            content = logButton,
        )
        Phase.DECLINED -> InfoCard(Icons.Outlined.Cancel, stringResource(R.string.phase_declined), stringResource(R.string.transfer_declined_body), tone = Tone.WARN)
        Phase.CANCELLED -> InfoCard(
            Icons.Outlined.Cancel, stringResource(R.string.phase_cancelled), s.error?.text() ?: stringResource(R.string.transfer_cancelled_body), tone = Tone.WARN,
        )
        else -> InfoCard(
            Icons.Outlined.ErrorOutline, stringResource(R.string.phase_failed), s.error?.text() ?: stringResource(R.string.err_connection_lost), tone = Tone.BAD,
            content = logButton,
        )
    }
}

@Composable
private fun FileRow(f: FileProgress, role: Role, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val (icon, tint) = when (f.status) {
        FileStatus.DONE, FileStatus.SKIPPED -> Icons.Outlined.CheckCircle to FixedColors.Success
        FileStatus.FAILED -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
        FileStatus.ACTIVE -> Icons.Outlined.Sync to MaterialTheme.colorScheme.primary
        FileStatus.WAITING -> Icons.Outlined.HourglassEmpty to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (f.status) {
        FileStatus.DONE -> stringResource(R.string.file_verified)
        FileStatus.SKIPPED -> stringResource(R.string.file_already_there)
        FileStatus.FAILED -> stringResource(R.string.file_failed, f.reason ?: stringResource(R.string.file_failed_unknown))
        FileStatus.ACTIVE -> Format.bytes(context, f.position) + " / " + Format.bytes(context, f.size)
        FileStatus.WAITING -> Format.bytes(context, f.size)
    }
    ListItem(
        leadingContent = {
            if (f.status == FileStatus.ACTIVE && f.size > 0) {
                CircularProgressIndicator(progress = { (f.position.toFloat() / f.size).coerceIn(0f, 1f) }, modifier = Modifier.size(24.dp))
            } else {
                Icon(icon, contentDescription = statusText, tint = tint)
            }
        },
        headlineContent = { Text(f.path, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = { Text(statusText, color = if (f.status == FileStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = {
            val loc = f.location
            if (role == Role.RECEIVE && f.status == FileStatus.DONE && loc != null) {
                TextButton(onClick = { onOpen(loc) }) { Text(stringResource(R.string.action_open)) }
            }
        },
    )
}

/** Accept / decline dialog for incoming transfers; shown over any screen. */
@Composable
fun IncomingRequestDialog(container: AppContainer) {
    val pending by container.receive.pending.collectAsStateWithLifecycle()
    val p = pending ?: return
    val context = LocalContext.current
    var trust by remember(p.sessionId) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.incoming_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.incoming_summary, p.senderName,
                        pluralStringResource(R.plurals.file_count, p.fileCount, p.fileCount),
                        Format.bytes(context, p.totalBytes),
                    ),
                )
                if (p.sampleNames.isNotEmpty()) {
                    Text(p.sampleNames.joinToString("\n"), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                BigCode(p.pairingCode, stringResource(R.string.transfer_code_label), Modifier.fillMaxWidth())
                Text(stringResource(R.string.incoming_code_hint), style = MaterialTheme.typography.bodySmall)
                if (!p.alreadyTrusted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = trust, onCheckedChange = { trust = it })
                        Text(stringResource(R.string.incoming_trust))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { container.receive.respond(p.sessionId, accept = true, trust = trust) }) {
                Text(stringResource(R.string.action_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = { container.receive.respond(p.sessionId, accept = false, trust = false) }) {
                Text(stringResource(R.string.action_decline))
            }
        },
    )
}
