package app.droplink.transfer

import app.droplink.core.protocol.ConnectionMode
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.SpeedMeter
import app.droplink.net.LinkProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Role { SEND, RECEIVE }

enum class Phase {
    CONNECTING,
    /** Sender: direct modes failed, the user must scan the receiver's QR code. */
    NEEDS_QR,
    WAITING_APPROVAL,
    TRANSFERRING,
    RECONNECTING,
    COMPLETED,
    /** Finished, but at least one file failed its checksum. */
    PARTIAL,
    FAILED,
    CANCELLED,
    DECLINED;

    val isActive: Boolean get() = this == CONNECTING || this == NEEDS_QR || this == WAITING_APPROVAL || this == TRANSFERRING || this == RECONNECTING
    val isFinished: Boolean get() = !isActive
}

enum class FileStatus { WAITING, ACTIVE, DONE, FAILED, SKIPPED }

data class FileProgress(
    val path: String,
    val size: Long,
    val position: Long = 0,
    val status: FileStatus = FileStatus.WAITING,
    val reason: String? = null,
    /** Received file location (content Uri / path). */
    val location: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
}

/** Why a transfer stopped, rendered with string resources in the UI. */
sealed class TransferError {
    data class Link(val problem: LinkProblem) : TransferError()
    data object PeerCancelled : TransferError()
    data object NoAnswer : TransferError()
    data object ConnectionLost : TransferError()
    data object CouldNotConnect : TransferError()
    data object KeyMismatch : TransferError()
    data object Busy : TransferError()
    data object Incompatible : TransferError()
    data class Other(val detail: String) : TransferError()
}

data class TransferUi(
    val transferId: String,
    val role: Role,
    val peerName: String,
    val peerDeviceId: String?,
    val mode: ConnectionMode?,
    val phase: Phase,
    val pairingCode: String? = null,
    val files: List<FileProgress>,
    val bytesPerSecond: Double = 0.0,
    val averageBytesPerSecond: Double = 0.0,
    val etaSeconds: Long? = null,
    val error: TransferError? = null,
    /** Technical detail of the error (exception text), shown small under the message. */
    val errorDetail: String? = null,
    val declineReason: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    val doneBytes: Long get() = files.sumOf { if (it.status == FileStatus.DONE || it.status == FileStatus.SKIPPED) it.size else it.position }
    val progress: Float get() = if (totalBytes == 0L) (if (phase == Phase.COMPLETED) 1f else 0f) else (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val okCount: Int get() = files.count { it.status == FileStatus.DONE || it.status == FileStatus.SKIPPED }
    val failedCount: Int get() = files.count { it.status == FileStatus.FAILED }
}

/**
 * The single source of truth for the transfer on screen (one at a time, send or receive).
 * Byte-level updates arrive at network speed, so they are coalesced and emitted at most ~5×/s.
 */
class TransferTracker {
    private val lock = Any()
    private val _state = MutableStateFlow<TransferUi?>(null)
    val state: StateFlow<TransferUi?> = _state.asStateFlow()
    private var meter = SpeedMeter()
    private var lastEmit = 0L
    private var positions = LongArray(0)
    /** Bytes counted towards speed: excludes what was already there when a resume started. */
    private var baseline = 0L

    val current: TransferUi? get() = _state.value

    fun begin(transferId: String, role: Role, peerName: String, peerDeviceId: String?, files: List<FileEntry>, mode: ConnectionMode?) =
        synchronized(lock) {
            meter = SpeedMeter()
            positions = LongArray(files.size)
            baseline = 0
            _state.value = TransferUi(
                transferId = transferId,
                role = role,
                peerName = peerName,
                peerDeviceId = peerDeviceId,
                mode = mode,
                phase = Phase.CONNECTING,
                files = files.map { FileProgress(it.path, it.size) },
            )
        }

    fun update(transform: (TransferUi) -> TransferUi) {
        synchronized(lock) {
            _state.value?.let { _state.value = transform(it) }
        }
    }

    fun phase(phase: Phase, error: TransferError? = null) = update {
        it.copy(
            phase = phase,
            error = error ?: it.error.takeIf { phase.isFinished },
            finishedAt = if (phase.isFinished) System.currentTimeMillis() else null,
            bytesPerSecond = if (phase.isFinished) 0.0 else it.bytesPerSecond,
            etaSeconds = if (phase.isFinished) null else it.etaSeconds,
        )
    }

    /** Called when (re)starting the data phase; [offsets]/[done] come from the receiver's resume plan. */
    fun startData(offsets: List<Long>, done: List<Boolean>, pairingCode: String?) = synchronized(lock) {
        val s = _state.value ?: return@synchronized
        positions = LongArray(s.files.size) { i -> offsets.getOrElse(i) { 0 } }
        val files = s.files.mapIndexed { i, f ->
            when {
                done.getOrElse(i) { false } -> f.copy(position = f.size, status = if (f.status == FileStatus.DONE) FileStatus.DONE else FileStatus.SKIPPED)
                f.status == FileStatus.DONE || f.status == FileStatus.FAILED -> f
                else -> f.copy(position = positions[i], status = FileStatus.WAITING)
            }
        }
        val next = s.copy(phase = Phase.TRANSFERRING, files = files, pairingCode = pairingCode ?: s.pairingCode, error = null)
        baseline = next.doneBytes
        meter = SpeedMeter().also { it.update(0, System.currentTimeMillis()) }
        _state.value = next
    }

    fun fileStarted(index: Int, offset: Long) = synchronized(lock) {
        if (index !in positions.indices) return@synchronized
        positions[index] = offset
        update { s -> s.copy(files = s.files.replace(index) { it.copy(status = FileStatus.ACTIVE, position = offset) }) }
    }

    fun bytes(index: Int, position: Long) {
        synchronized(lock) {
            if (index !in positions.indices) return
            positions[index] = position
            val now = System.currentTimeMillis()
            if (now - lastEmit < 200) return
            lastEmit = now
            val s = _state.value ?: return
            val files = s.files.mapIndexed { i, f -> if (f.status == FileStatus.ACTIVE || f.status == FileStatus.WAITING) f.copy(position = positions[i]) else f }
            val withFiles = s.copy(files = files)
            val moved = withFiles.doneBytes - baseline
            meter.update(moved, now)
            _state.value = withFiles.copy(
                bytesPerSecond = meter.bytesPerSecond,
                averageBytesPerSecond = meter.averageBytesPerSecond(moved, now),
                etaSeconds = meter.etaSeconds(withFiles.totalBytes - withFiles.doneBytes),
            )
        }
    }

    fun fileFinished(index: Int, ok: Boolean, reason: String?, location: String? = null, skipped: Boolean = false) = synchronized(lock) {
        if (index in positions.indices && ok) positions[index] = _state.value?.files?.getOrNull(index)?.size ?: positions[index]
        update { s ->
            s.copy(files = s.files.replace(index) {
                it.copy(
                    status = when {
                        skipped -> FileStatus.SKIPPED
                        ok -> FileStatus.DONE
                        else -> FileStatus.FAILED
                    },
                    position = if (ok) it.size else it.position,
                    reason = reason,
                    location = location ?: it.location,
                )
            })
        }
    }

    /** Final average speed for the history/throughput log. */
    fun finish(phase: Phase, error: TransferError? = null, detail: String? = null) = synchronized(lock) {
        val s = _state.value ?: return@synchronized
        val now = System.currentTimeMillis()
        val moved = s.doneBytes - baseline
        val avg = meter.averageBytesPerSecond(moved, now).takeIf { it > 0 } ?: s.averageBytesPerSecond
        val finalPhase = if (phase == Phase.COMPLETED && s.failedCount > 0) Phase.PARTIAL else phase
        _state.value = s.copy(
            phase = finalPhase,
            error = error,
            errorDetail = detail,
            averageBytesPerSecond = avg,
            bytesPerSecond = 0.0,
            etaSeconds = null,
            finishedAt = now,
            files = s.files.map { if (it.status == FileStatus.ACTIVE) it.copy(status = FileStatus.FAILED, reason = it.reason) else it },
        )
    }

    fun clear() = synchronized(lock) {
        if (_state.value?.phase?.isActive != true) _state.value = null
    }
}

private inline fun <T> List<T>.replace(index: Int, f: (T) -> T): List<T> =
    if (index !in indices) this else toMutableList().also { it[index] = f(it[index]) }
