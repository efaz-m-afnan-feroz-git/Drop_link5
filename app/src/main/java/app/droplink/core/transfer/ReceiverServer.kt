package app.droplink.core.transfer

import app.droplink.core.protocol.AcceptMsg
import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.FrameReader
import app.droplink.core.protocol.FrameType
import app.droplink.core.protocol.FrameWriter
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.IncompatibleVersionException
import app.droplink.core.protocol.Ledger
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Messages
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.ProtocolException
import app.droplink.core.protocol.ResumePlan
import app.droplink.core.protocol.ResumePlanner
import app.droplink.core.protocol.int
import app.droplink.core.protocol.long
import app.droplink.core.protocol.str
import app.droplink.core.security.Identity
import app.droplink.core.security.PairingCode
import app.droplink.core.security.Tls
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

data class IncomingRequest(
    val sessionId: Long,
    val hello: Hello,
    val peerFingerprint: String,
    val pairingCode: String,
    val remoteAddress: String,
    val plan: ResumePlan,
) {
    /** A reconnect of a transfer the user already accepted. */
    val isResume: Boolean get() = hello.resume && plan.isResume
}

sealed class Decision {
    data object Accept : Decision()
    data class Decline(val reason: String) : Decision()
}

sealed class ReceiveOutcome {
    data object Completed : ReceiveOutcome()
    data class Cancelled(val byPeer: Boolean) : ReceiveOutcome()
    /** Connection dropped mid-transfer; state is saved and the sender may reconnect and resume. */
    data class Interrupted(val message: String) : ReceiveOutcome()
    data class Failed(val message: String) : ReceiveOutcome()
    data object Declined : ReceiveOutcome()
}

interface ReceiveCallbacks {
    /** Blocking decision, called on a session thread. Should time out by itself (~2 min). */
    fun onIncoming(request: IncomingRequest): Decision
    fun onSessionStarted(request: IncomingRequest, token: CancelToken) {}
    fun onFileStarted(sessionId: Long, index: Int, offset: Long) {}
    fun onBytesReceived(sessionId: Long, index: Int, position: Long) {}
    fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) {}
    fun onSessionEnded(sessionId: Long, request: IncomingRequest?, outcome: ReceiveOutcome) {}
    fun onServerError(error: Throwable) {}
}

/**
 * TLS server that accepts incoming transfers. One transfer at a time; a reconnect of the same
 * transfer (resume) takes over the old, half-dead connection.
 */
class ReceiverServer(
    private val identity: Identity,
    private val sinks: SinkFactory,
    private val ledgers: LedgerStore,
    private val callbacks: ReceiveCallbacks,
    private val preferredPort: Int = Protocol.DEFAULT_PORT,
    private val bindAddress: InetAddress? = null,
) {
    @Volatile
    private var server: SSLServerSocket? = null
    private val sessionIds = AtomicLong(0)
    private val active = ConcurrentHashMap<String, ActiveSession>()

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = server?.isClosed == false

    private class ActiveSession(
        val sessionId: Long,
        val thread: Thread,
        val peerFingerprint: String,
        /** Stops this session without discarding its resume state (a reconnect is taking over). */
        val takeOver: () -> Unit,
        val cancel: (String?) -> Unit,
    )

    /** Binds (falls back to an ephemeral port if the default one is taken) and starts accepting. */
    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        pruneAbandoned()
        val context = Tls.context(identity, expectedPeerFingerprint = null)
        val ss = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        // Large receive window before bind, so accepted sockets inherit it (helps on fast 5 GHz links).
        runCatching { ss.receiveBufferSize = 2 shl 20 }
        runCatching { ss.receiveBufferSize = SenderSession.SOCKET_BUFFER } // must be set before bind
        try {
            ss.bind(InetSocketAddress(bindAddress, preferredPort), 8)
        } catch (e: BindException) {
            ss.bind(InetSocketAddress(bindAddress, 0), 8)
        }
        Tls.configure(ss)
        server = ss
        Thread({ acceptLoop(ss) }, "droplink-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    /**
     * Stops listening. With [keepResumeState] (default) a running transfer is cut but its partial
     * files and ledger are kept so the sender can resume later; otherwise it is cancelled and cleaned up.
     */
    @Synchronized
    fun stop(keepResumeState: Boolean = true) {
        runCatching { server?.close() }
        server = null
        active.values.forEach { if (keepResumeState) it.takeOver() else it.cancel("The receiver stopped receiving") }
    }

    /** Cancels the running transfer(s) from this side. */
    fun cancelAll(reason: String? = null) = active.values.forEach { it.cancel(reason) }

    private fun acceptLoop(ss: SSLServerSocket) {
        while (!ss.isClosed) {
            try {
                val socket = ss.accept() as SSLSocket
                Thread({ Session(socket).run() }, "droplink-recv").apply { isDaemon = true }.start()
            } catch (e: SocketException) {
                if (ss.isClosed) return
                callbacks.onServerError(e)
            } catch (e: IOException) {
                callbacks.onServerError(e)
            }
        }
    }

    private fun pruneAbandoned() {
        runCatching {
            ledgers.prune(ABANDONED_AFTER_MS).forEach { ledger ->
                ledger.files.filter { !it.done }.mapNotNull { it.destination }.forEach { dest -> runCatching { sinks.discard(dest) } }
            }
        }
    }

    private inner class Session(private val socket: SSLSocket) {
        private val sessionId = sessionIds.incrementAndGet()
        private val token = CancelToken()
        private var request: IncomingRequest? = null
        @Volatile
        private var ledger: Ledger? = null
        private val ledgerLock = Any()
        private var lastLedgerSave = 0L

        /** Verified files are copied to their final folder here, in order, while the next file streams in. */
        private val commits = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "droplink-save").apply { isDaemon = true }
        }

        private fun updateLedger(index: Int, change: (app.droplink.core.protocol.LedgerFile) -> app.droplink.core.protocol.LedgerFile) {
            synchronized(ledgerLock) {
                ledger = ledger?.let { it.withFile(index, change(it.files[index])) }
            }
        }

        /** Waits for background saves to finish (they are verified files: always let them complete). */
        private fun drainCommits() {
            commits.shutdown()
            while (!commits.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                if (commits.isTerminated) break
            }
        }
        private var current: Current? = null
        @Volatile
        private var takenOver = false

        private inner class Current(
            val index: Int,
            val entry: FileEntry,
            val sink: FileSink,
            val digest: MessageDigest,
            var written: Long,
            var lastSyncBytes: Long,
            var lastSyncAt: Long,
        )

        fun run() {
            token.register(socket)
            runCatching { socket.tcpNoDelay = true }
            var outcome: ReceiveOutcome = ReceiveOutcome.Failed("Connection closed")
            var writer: FrameWriter? = null
            try {
                socket.soTimeout = SenderSession.HANDSHAKE_TIMEOUT_MS
                socket.startHandshake()
                val peerFp = Tls.peerFingerprint(socket)
                val reader = FrameReader(socket.inputStream)
                val w = FrameWriter(socket.outputStream).also { writer = it }
                token.onCancel { w.writeJson(FrameType.CANCEL, Messages.cancel(token.reason)) }

                val hello = try {
                    Hello.parse(reader.expect(FrameType.HELLO))
                } catch (e: IncompatibleVersionException) {
                    w.writeJson(FrameType.ERROR, Messages.error("Incompatible DropLink version; please update both phones"))
                    throw e
                }
                if (!claim(hello.transferId, peerFp)) {
                    w.writeJson(FrameType.ERROR, Messages.error("The other phone is busy with another transfer"))
                    outcome = ReceiveOutcome.Failed("Busy")
                    return
                }
                val plan = ResumePlanner.plan(hello, ledgers.load(hello.transferId), peerFp) { sinks.lengthOf(it) }
                val req = IncomingRequest(
                    sessionId, hello, peerFp, PairingCode.of(peerFp, identity.fingerprint),
                    socket.inetAddress?.hostAddress ?: "?", plan,
                )
                request = req
                when (val decision = callbacks.onIncoming(req)) {
                    is Decision.Decline -> {
                        w.writeJson(FrameType.DECLINE, Messages.decline(decision.reason))
                        outcome = ReceiveOutcome.Declined
                        return
                    }
                    Decision.Accept -> Unit
                }
                token.throwIfCancelled()
                ledger = plan.ledger
                ledgers.save(plan.ledger)
                w.writeJson(FrameType.ACCEPT, AcceptMsg(plan.offsets, plan.done).toJson())
                callbacks.onSessionStarted(req, token)
                socket.soTimeout = SenderSession.IO_TIMEOUT_MS
                outcome = loop(reader, w, hello, plan)
            } catch (e: CancelledException) {
                outcome = if (takenOver) ReceiveOutcome.Interrupted("Reconnected") else ReceiveOutcome.Cancelled(byPeer = false)
            } catch (e: IOException) {
                if (!takenOver && !token.isCancelled) CoreLog.log("Receiver: session $sessionId I/O error", e)
                outcome = when {
                    takenOver -> ReceiveOutcome.Interrupted("Reconnected")
                    token.isCancelled -> ReceiveOutcome.Cancelled(byPeer = false)
                    e is ProtocolException -> {
                        runCatching { writer?.writeJson(FrameType.ERROR, Messages.error(e.message ?: "Protocol error")) }
                        ReceiveOutcome.Failed(e.message ?: "Protocol error")
                    }
                    request == null -> ReceiveOutcome.Failed(e.message ?: "Connection failed")
                    else -> ReceiveOutcome.Interrupted(e.message ?: "Connection lost")
                }
            } catch (e: Exception) {
                CoreLog.log("Receiver: unexpected error", e)
                outcome = ReceiveOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                if (outcome !is ReceiveOutcome.Completed && outcome !is ReceiveOutcome.Declined) {
                    CoreLog.log("Receiver: session $sessionId ended: $outcome")
                }
                finish(outcome)
                runCatching { socket.close() }
                request?.let { active.remove(it.hello.transferId, activeEntry) }
                callbacks.onSessionEnded(sessionId, request, outcome)
            }
        }

        private var activeEntry: ActiveSession? = null

        /** Registers this session; a resume of the same transfer from the same key replaces a stale one. */
        private fun claim(transferId: String, peerFp: String): Boolean {
            val me = ActiveSession(
                sessionId, Thread.currentThread(), peerFp,
                takeOver = { takenOver = true; runCatching { socket.close() } },
                cancel = { reason -> token.cancel(reason) },
            )
            synchronized(active) {
                val others = active.filterKeys { it != transferId }
                if (others.isNotEmpty()) return false
                val old = active[transferId]
                if (old != null) {
                    if (!old.peerFingerprint.equals(peerFp, ignoreCase = true)) return false
                    old.takeOver()
                    old.thread.join(60_000)
                }
                active[transferId] = me
                activeEntry = me
            }
            return true
        }

        private fun loop(reader: FrameReader, writer: FrameWriter, hello: Hello, plan: ResumePlan): ReceiveOutcome {
            val started = BooleanArray(hello.files.size)
            while (true) {
                val f = reader.next()
                when (f.type) {
                    FrameType.FILE_START -> {
                        if (current != null) throw ProtocolException("FILE_START inside a file")
                        val m = f.json()
                        val i = m.int("index")
                        if (i !in hello.files.indices || started[i] || plan.done[i]) throw ProtocolException("Bad file index $i")
                        val offset = m.long("offset")
                        if (offset != plan.offsets[i]) throw ProtocolException("Sender resumed at the wrong offset")
                        started[i] = true
                        openFile(i, hello.files[i], offset, writer)
                        callbacks.onFileStarted(sessionId, i, offset)
                    }
                    FrameType.DATA -> {
                        val cur = current ?: throw ProtocolException("DATA outside a file")
                        if (cur.written + f.length > cur.entry.size) throw ProtocolException("More data than announced")
                        cur.sink.write(f.payload, 0, f.length)
                        cur.digest.update(f.payload, 0, f.length)
                        cur.written += f.length
                        callbacks.onBytesReceived(sessionId, cur.index, cur.written)
                        val t = now()
                        if (cur.written - cur.lastSyncBytes >= SYNC_EVERY_BYTES || t - cur.lastSyncAt >= SYNC_EVERY_MS) {
                            checkpoint(cur, writer)
                        }
                    }
                    FrameType.FILE_END -> {
                        val cur = current ?: throw ProtocolException("FILE_END outside a file")
                        val m = f.json()
                        if (m.int("index") != cur.index) throw ProtocolException("FILE_END for the wrong file")
                        finishFile(cur, m.str("sha256"), writer)
                    }
                    FrameType.PING -> Unit
                    FrameType.CANCEL -> {
                        current?.let { it.sink.discard(); current = null }
                        drainCommits()
                        ledgers.delete(hello.transferId)
                        return ReceiveOutcome.Cancelled(byPeer = true)
                    }
                    FrameType.ERROR -> return ReceiveOutcome.Failed(Messages.errorMessage(f))
                    FrameType.DONE -> {
                        if (current != null) throw ProtocolException("DONE inside a file")
                        drainCommits()
                        runCatching { writer.writeEmpty(FrameType.DONE) }
                        ledgers.delete(hello.transferId)
                        ledger = null
                        return ReceiveOutcome.Completed
                    }
                    else -> throw ProtocolException("Unexpected ${f.type}")
                }
            }
        }

        private fun openFile(i: Int, entry: FileEntry, offset: Long, writer: FrameWriter) {
            val previous = ledger!!.files[i].destination
            if (offset == 0L && previous != null) runCatching { sinks.discard(previous) } // stale partial
            val sink = sinks.open(request!!.hello.transferId, entry, if (offset > 0) previous else null, offset)
            val digest = Checksums.sha256()
            if (offset > 0) {
                var lastPing = now()
                sink.openExisting().use { existing ->
                    Checksums.update(digest, existing, offset) {
                        token.throwIfCancelled()
                        if (now() - lastPing > SenderSession.PING_INTERVAL_MS) {
                            writer.writeEmpty(FrameType.PING); lastPing = now()
                        }
                    }
                }
            }
            updateLedger(i) { it.copy(destination = sink.destination, confirmed = offset, done = false) }
            saveLedger(force = true)
            current = Current(i, entry, sink, digest, offset, offset, now())
        }

        private fun checkpoint(cur: Current, writer: FrameWriter) {
            cur.sink.sync()
            cur.lastSyncBytes = cur.written
            cur.lastSyncAt = now()
            updateLedger(cur.index) { it.copy(confirmed = cur.written) }
            saveLedger(force = false)
            writer.writeJson(FrameType.PROGRESS, Messages.progress(cur.index, cur.written))
        }

        private fun finishFile(cur: Current, expectedSha: String, writer: FrameWriter) {
            current = null
            if (cur.written != cur.entry.size) {
                cur.sink.discard()
                updateLedger(cur.index) { it.copy(confirmed = 0, destination = null) }
                writer.writeJson(FrameType.FILE_ACK, Messages.fileAck(cur.index, false, cur.written, "Incomplete file"))
                callbacks.onFileFinished(sessionId, cur.index, false, "Incomplete file", null)
                return
            }
            val actual = Checksums.hex(cur.digest.digest())
            if (!Checksums.matches(expectedSha, actual)) {
                CoreLog.log("Receiver: checksum mismatch for file ${cur.index}")
                cur.sink.discard()
                updateLedger(cur.index) { it.copy(confirmed = 0, destination = null, done = false) }
                saveLedger(force = false)
                writer.writeJson(FrameType.FILE_ACK, Messages.fileAck(cur.index, false, cur.written, "Checksum mismatch"))
                callbacks.onFileFinished(sessionId, cur.index, false, "Checksum mismatch (SHA-256)", null)
                return
            }
            // Verified: everything received is on disk, so the ledger can say "complete" right away.
            cur.sink.sync()
            updateLedger(cur.index) { it.copy(confirmed = cur.written) }
            commits.execute { commitFile(cur, writer) }
        }

        /** Runs on the save thread: copy to the final folder, then acknowledge. Never blocks receiving. */
        private fun commitFile(cur: Current, writer: FrameWriter) {
            var lastPing = now()
            val location = try {
                cur.sink.commit {
                    if (now() - lastPing > SenderSession.PING_INTERVAL_MS) {
                        runCatching { writer.writeEmpty(FrameType.PING) }
                        lastPing = now()
                    }
                }
            } catch (e: Exception) {
                CoreLog.log("Receiver: couldn't save file ${cur.index}", e)
                runCatching { cur.sink.discard() }
                updateLedger(cur.index) { it.copy(confirmed = 0, destination = null, done = false) }
                saveLedger(force = false)
                val reason = e.message ?: "Couldn't save the file"
                runCatching { writer.writeJson(FrameType.FILE_ACK, Messages.fileAck(cur.index, false, cur.written, reason)) }
                callbacks.onFileFinished(sessionId, cur.index, false, reason, null)
                return
            }
            runCatching { cur.sink.close() }
            updateLedger(cur.index) { it.copy(confirmed = cur.entry.size, done = true, destination = cur.sink.destination) }
            saveLedger(force = true)
            runCatching { writer.writeJson(FrameType.FILE_ACK, Messages.fileAck(cur.index, true, cur.written, null)) }
            callbacks.onFileFinished(sessionId, cur.index, true, null, location)
        }

        /** Throttled: rewriting the ledger is O(files), so at most every 2 s unless forced. */
        private fun saveLedger(force: Boolean) {
            synchronized(ledgerLock) {
                val l = ledger ?: return
                val t = now()
                if (force || t - lastLedgerSave >= LEDGER_SAVE_EVERY_MS) {
                    runCatching { ledgers.save(l) }
                    lastLedgerSave = t
                }
            }
        }

        private fun finish(outcome: ReceiveOutcome) {
            val cur = current
            current = null
            if (cur != null && outcome !is ReceiveOutcome.Interrupted) runCatching { cur.sink.discard() }
            drainCommits()
            when (outcome) {
                is ReceiveOutcome.Interrupted -> {
                    // Keep what we have for a resume: fsync, record it, close (don't delete).
                    if (cur != null) {
                        val synced = runCatching { cur.sink.sync() }.isSuccess
                        if (synced) updateLedger(cur.index) { it.copy(confirmed = cur.written) }
                        runCatching { cur.sink.close() }
                    }
                    synchronized(ledgerLock) { ledger?.let { runCatching { ledgers.save(it) } } }
                }
                is ReceiveOutcome.Cancelled, is ReceiveOutcome.Failed -> {
                    request?.let { req -> if (ledger != null) runCatching { ledgers.delete(req.hello.transferId) } }
                }
                else -> cur?.let { runCatching { it.sink.close() } }
            }
        }
    }

    companion object {
        // fsync is slow on phone storage; doing it rarely keeps the link busy. Resume granularity
        // stays fine (worst case: re-send the last 64 MiB / 4 s of one file).
        const val SYNC_EVERY_BYTES = 64L shl 20
        const val SYNC_EVERY_MS = 4_000L
        const val LEDGER_SAVE_EVERY_MS = 2_000L
        const val ABANDONED_AFTER_MS = 7L * 24 * 3600 * 1000
    }
}
