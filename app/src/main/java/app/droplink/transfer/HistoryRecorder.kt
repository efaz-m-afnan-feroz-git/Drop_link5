package app.droplink.transfer

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.droplink.data.AppDatabase
import app.droplink.data.Direction
import app.droplink.data.HistoryEntity
import app.droplink.data.ItemStatus
import app.droplink.storage.Sinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** Writes finished transfers to Room and logs measured throughput. */
class HistoryRecorder(private val context: Context, private val scope: CoroutineScope, private val db: () -> AppDatabase) {

    fun record(ui: TransferUi, direction: Direction, sourceLocations: Map<String, String> = emptyMap()) {
        val avg = ui.averageBytesPerSecond.toLong()
        Log.i(
            TAG,
            "Transfer ${ui.transferId} ${direction.name} ${ui.phase} via ${ui.mode}: ${ui.doneBytes} bytes, " +
                "avg ${"%.2f".format(java.util.Locale.ROOT, avg / 1_048_576.0)} MiB/s",
        )
        val now = System.currentTimeMillis()
        val rows = ui.files.filter { it.status != FileStatus.WAITING || ui.phase != Phase.DECLINED }.map { f ->
            HistoryEntity(
                transferId = ui.transferId,
                direction = direction,
                peerName = ui.peerName,
                peerDeviceId = ui.peerDeviceId,
                fileName = f.name,
                relativePath = f.path,
                location = if (direction == Direction.RECEIVED) f.location else sourceLocations[f.path],
                sizeBytes = f.size,
                mime = Sinks.mimeFor(f.name, "application/octet-stream"),
                status = when (f.status) {
                    FileStatus.DONE, FileStatus.SKIPPED -> ItemStatus.OK
                    FileStatus.FAILED -> ItemStatus.FAILED
                    else -> if (ui.phase == Phase.CANCELLED) ItemStatus.CANCELLED else ItemStatus.FAILED
                },
                reason = f.reason,
                mode = ui.mode?.name ?: "-",
                bytesPerSecond = avg,
                timestamp = now,
            )
        }
        if (rows.isEmpty()) return
        scope.launch(Dispatchers.IO) { runCatching { db().history().insertAll(rows) }.onFailure { Log.w(TAG, "history insert failed", it) } }
    }

    /** Deletes a received file from storage (sent files are only removed from the list). */
    fun deleteReceivedFile(location: String): Boolean = runCatching {
        val resolver: ContentResolver = context.contentResolver
        when {
            location.startsWith("content://") -> {
                val uri = Uri.parse(location)
                if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(resolver, uri)
                else resolver.delete(uri, null, null) > 0
            }
            else -> File(location).delete()
        }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "DropLinkHistory"
    }
}
