package app.droplink.transfer

import android.content.Context
import android.net.Uri
import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.IdentityFactory
import app.droplink.core.security.IdentityStore
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.Decision
import app.droplink.core.transfer.Endpoint
import app.droplink.core.transfer.IncomingRequest
import app.droplink.core.transfer.LocalOutgoingFile
import app.droplink.core.transfer.ReceiveCallbacks
import app.droplink.core.transfer.ReceiverServer
import app.droplink.core.transfer.SendListener
import app.droplink.core.transfer.SendOutcome
import app.droplink.core.transfer.SenderSession
import app.droplink.storage.Sinks
import app.droplink.util.DiagLog
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Random
import java.util.UUID

/**
 * Self-test (Settings → "Run self-test"): a full encrypted transfer from this phone to itself over
 * 127.0.0.1, through the SAME storage path real transfers use (staging → Download/DropLink or the
 * chosen folder). The test file is deleted afterwards.
 */
object SelfTest {
    data class Result(val ok: Boolean, val bytes: Long, val millis: Long, val detail: String?) {
        val bytesPerSecond: Long get() = if (millis <= 0) 0 else bytes * 1000 / millis
    }

    fun run(context: Context, identity: IdentityStore, saveTreeUri: String?, sizeBytes: Int = 32 shl 20): Result {
        val root = File(context.cacheDir, "selftest-" + UUID.randomUUID()).apply { mkdirs() }
        var step = "preparing"
        try {
            step = "creating test file"
            val src = File(root, "src.bin")
            src.outputStream().buffered(1 shl 20).use { out ->
                val buf = ByteArray(1 shl 20)
                val rnd = Random(42)
                var left = sizeBytes
                while (left > 0) {
                    rnd.nextBytes(buf)
                    val n = minOf(left, buf.size)
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            step = "creating security keys"
            val receiverId = identity.get()
            val senderId = IdentityFactory.generate("self-test-sender")

            step = "starting receiver"
            val sinks = Sinks.createStaged(context, saveTreeUri)
            var savedAt: String? = null
            var fileError: String? = null
            // Explicit IPv4 loopback: on Android, InetAddress.getLoopbackAddress() is ::1.
            val loopback: InetAddress = Inet4Address.getByAddress(byteArrayOf(127, 0, 0, 1))
            val server = ReceiverServer(
                receiverId, sinks, LedgerStore(File(root, "ledger")),
                object : ReceiveCallbacks {
                    override fun onIncoming(request: IncomingRequest) = Decision.Accept
                    override fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) {
                        if (ok) savedAt = location else fileError = reason
                    }
                },
                preferredPort = 0, bindAddress = loopback,
            )
            val port = server.start()
            try {
                step = "sending"
                val file = LocalOutgoingFile(src, "DropLink-selftest-${UUID.randomUUID().toString().take(8)}.bin")
                val hello = Hello(Protocol.VERSION, "selftest-" + UUID.randomUUID(), "self-test", "selftest", TransferMode.LOOPBACK, false, listOf(file.entry))
                val started = System.currentTimeMillis()
                val outcome = SenderSession(
                    senderId, Endpoint("127.0.0.1", port, expectedFingerprint = receiverId.fingerprint),
                    hello, listOf(file), object : SendListener {}, CancelToken(),
                ).run()
                val millis = System.currentTimeMillis() - started
                if (outcome !is SendOutcome.Completed) return Result(false, 0, millis, "Transfer ended: $outcome")
                if (!outcome.allOk) return Result(false, 0, millis, "Saving failed: ${fileError ?: outcome.results.firstOrNull()?.reason}")

                step = "verifying saved file"
                val loc = savedAt ?: return Result(false, 0, millis, "No saved file location")
                val hash = open(context, loc).use { Checksums.sha256Hex(it) }
                val ok = hash == Checksums.sha256Hex(src.inputStream())
                runCatching { sinks.discard(loc) }
                return Result(ok, sizeBytes.toLong(), millis, if (ok) null else "Saved file differs from the original")
            } finally {
                server.stop(keepResumeState = false)
            }
        } catch (e: Throwable) {
            DiagLog.log("Self-test failed while $step", e)
            return Result(false, 0, 0, "While $step: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun open(context: Context, location: String): InputStream =
        if (location.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(location)) ?: error("Can't reopen saved file")
        } else {
            File(location).inputStream()
        }
}
