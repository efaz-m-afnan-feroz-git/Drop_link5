package app.droplink.core.transfer

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.Fingerprints
import app.droplink.core.security.IdentityFactory
import app.droplink.core.security.IdentityStore
import app.droplink.core.security.PairingCode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SecurityTest {
    @Test
    fun pairingCodeIsSymmetricAndFourDigits() {
        val a = "11".repeat(32)
        val b = "22".repeat(32)
        val code = PairingCode.of(a, b)
        assertEquals(code, PairingCode.of(b, a))
        assertTrue(code.matches(Regex("\\d{4}")))
        assertNotEquals(code, PairingCode.of(a, "33".repeat(32)))
    }

    @Test
    fun identityIsPersistedOncePerInstall() {
        val f = File.createTempFile("identity", ".p12").also { it.delete() }
        val first = IdentityStore(f).get()
        val again = IdentityStore(f).get()
        assertEquals(first.fingerprint, again.fingerprint)
        assertEquals(64, first.fingerprint.length)
        assertEquals(Fingerprints.of(first.certificate), first.fingerprint)
        f.delete()
    }
}

/** Full sender ↔ receiver transfers over TLS on 127.0.0.1 (same code path the phones use). */
class LoopbackTransferTest {
    private lateinit var root: File
    private lateinit var outDir: File
    private val senderId = IdentityFactory.generate("sender")
    private val receiverId = IdentityFactory.generate("receiver")
    private val finished = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    private val requests = CopyOnWriteArrayList<IncomingRequest>()
    private val ended = CountDownLatch(1)
    private var decision: Decision = Decision.Accept
    @Volatile private var onBytes: (Long) -> Unit = {}

    private val callbacks = object : ReceiveCallbacks {
        override fun onIncoming(request: IncomingRequest): Decision = decision.also { requests += request }
        override fun onBytesReceived(sessionId: Long, index: Int, position: Long) = onBytes(position)
        override fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) {
            finished += index to ok
        }
        override fun onSessionEnded(sessionId: Long, request: IncomingRequest?, outcome: ReceiveOutcome) {
            if (outcome !is ReceiveOutcome.Interrupted) ended.countDown()
        }
    }

    private lateinit var server: ReceiverServer

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "droplink-test-" + UUID.randomUUID()).apply { mkdirs() }
        outDir = File(root, "out").apply { mkdirs() }
        server = newServer()
    }

    private fun newServer() = ReceiverServer(
        receiverId, DirectorySinkFactory(outDir), LedgerStore(File(root, "ledgers")), callbacks, preferredPort = 0,
    )

    @After
    fun tearDown() {
        server.stop()
        root.deleteRecursively()
    }

    private fun makeFile(name: String, size: Int, seed: Long): File =
        File(root, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) }) }

    private fun hello(files: List<OutgoingFile>, id: String, resume: Boolean = false) =
        Hello(Protocol.VERSION, id, "Sender", "dev-sender", TransferMode.LOOPBACK, resume, files.map { it.entry })

    @Test
    fun transfersFilesAndFoldersWithMatchingChecksums() {
        val port = server.start()
        val a = makeFile("src/a.bin", 3 * Protocol.BUFFER_SIZE + 123, 1)
        val b = makeFile("src/photos/b.jpg", 10, 2)
        val empty = makeFile("src/empty.txt", 0, 3)
        val files = listOf(
            LocalOutgoingFile(a, "a.bin"),
            LocalOutgoingFile(b, "photos/b.jpg", "image/jpeg"),
            LocalOutgoingFile(empty, "empty.txt", "text/plain"),
        )
        var code: String? = null
        val outcome = SenderSession(
            senderId, Endpoint("127.0.0.1", port), hello(files, "transfer-" + UUID.randomUUID()), files,
            object : SendListener {
                override fun onConnected(peerFingerprint: String, pairingCode: String) {
                    code = pairingCode
                    assertEquals(receiverId.fingerprint, peerFingerprint)
                }
            },
            CancelToken(),
        ).run()

        assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(code, requests.single().pairingCode) // both screens show the same code
        assertEquals(Checksums.sha256Hex(a.readBytes()), Checksums.sha256Hex(File(outDir, "a.bin").readBytes()))
        assertEquals(Checksums.sha256Hex(b.readBytes()), Checksums.sha256Hex(File(outDir, "photos/b.jpg").readBytes()))
        assertTrue(File(outDir, "empty.txt").exists())
        assertEquals(3, finished.count { it.second })
    }

    @Test
    fun qrModePinsTheReceiverCertificate() {
        val port = server.start()
        val f = makeFile("x.bin", 1000, 4)
        val files = listOf(LocalOutgoingFile(f))
        // Right pin works.
        val ok = SenderSession(
            senderId, Endpoint("127.0.0.1", port, expectedFingerprint = receiverId.fingerprint),
            hello(files, "transfer-" + UUID.randomUUID()), files, object : SendListener {}, CancelToken(),
        ).run()
        assertTrue(ok is SendOutcome.Completed)
        // Wrong pin is refused before any data is sent.
        try {
            SenderSession(
                senderId, Endpoint("127.0.0.1", port, expectedFingerprint = "00".repeat(32)),
                hello(files, "transfer-" + UUID.randomUUID()), files, object : SendListener {}, CancelToken(),
            ).run()
            fail("expected a pin failure")
        } catch (e: FatalTransferException) {
            assertTrue(e.message!!.contains("QR"))
        }
    }

    @Test
    fun declineIsReported() {
        decision = Decision.Decline("Not now")
        val port = server.start()
        val files = listOf(LocalOutgoingFile(makeFile("d.bin", 10, 5)))
        val outcome = SenderSession(
            senderId, Endpoint("127.0.0.1", port), hello(files, "transfer-" + UUID.randomUUID()), files,
            object : SendListener {}, CancelToken(),
        ).run()
        assertEquals(SendOutcome.Declined("Not now"), outcome)
    }

    @Test
    fun checksumMismatchIsDetectedAndMarked() {
        // A hand-rolled sender that lies about the SHA-256, to exercise the receiver's verification.
        val port = server.start()
        val ctx = app.droplink.core.security.Tls.context(senderId, receiverId.fingerprint)
        val socket = ctx.socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket
        socket.use { s ->
            app.droplink.core.security.Tls.configure(s)
            s.startHandshake()
            val w = app.droplink.core.protocol.FrameWriter(s.outputStream)
            val r = app.droplink.core.protocol.FrameReader(s.inputStream)
            val payload = ByteArray(1000) { it.toByte() }
            val entry = FileEntry("bad.bin", payload.size.toLong(), "application/octet-stream")
            val h = Hello(Protocol.VERSION, "transfer-" + UUID.randomUUID(), "Liar", "dev-liar", TransferMode.LOOPBACK, false, listOf(entry))
            w.writeJson(app.droplink.core.protocol.FrameType.HELLO, h.toJson())
            r.expect(app.droplink.core.protocol.FrameType.ACCEPT)
            w.writeJson(app.droplink.core.protocol.FrameType.FILE_START, app.droplink.core.protocol.Messages.fileStart(0, 0))
            w.write(app.droplink.core.protocol.FrameType.DATA, payload)
            w.writeJson(app.droplink.core.protocol.FrameType.FILE_END, app.droplink.core.protocol.Messages.fileEnd(0, "00".repeat(32)))
            var ack = r.next()
            while (ack.type == app.droplink.core.protocol.FrameType.PROGRESS) ack = r.next()
            assertEquals(app.droplink.core.protocol.FrameType.FILE_ACK, ack.type)
            assertEquals(false, ack.json()["ok"])
            w.writeEmpty(app.droplink.core.protocol.FrameType.DONE)
            r.expect(app.droplink.core.protocol.FrameType.DONE)
        }
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(0 to false), finished.toList())
        assertTrue("corrupt file is not kept", outDir.listFiles()!!.none { it.name.startsWith("bad") })
    }

    @Test
    fun resumesFromLastConfirmedOffsetAfterConnectionDrop() {
        var port = server.start()
        val size = 40 * Protocol.BUFFER_SIZE + 7
        val big = makeFile("big.bin", size, 7)
        val files = listOf(LocalOutgoingFile(makeFile("first.txt", 100, 8), "first.txt"), LocalOutgoingFile(big, "big.bin"))
        val transferId = "transfer-" + UUID.randomUUID()

        // Kill the receiver's listener + connection once ~half of big.bin has arrived.
        val dropped = CountDownLatch(1)
        onBytes = { pos ->
            if (pos > size / 2 && dropped.count > 0L) {
                dropped.countDown()
                Thread { server.stop() }.start()
            }
        }
        try {
            SenderSession(senderId, Endpoint("127.0.0.1", port), hello(files, transferId), files, object : SendListener {}, CancelToken()).run()
            fail("expected the connection to drop")
        } catch (e: IOException) {
            assertTrue(e !is FatalTransferException)
        }
        assertTrue(dropped.await(5, TimeUnit.SECONDS))
        onBytes = {}

        // Receiver comes back (same ledger directory), sender reconnects with resume = true.
        Thread.sleep(300)
        server = newServer()
        port = server.start()
        var resumedAt = -1L
        val outcome = SenderSession(
            senderId, Endpoint("127.0.0.1", port), hello(files, transferId, resume = true), files,
            object : SendListener {
                override fun onFileStarted(index: Int, offset: Long) {
                    if (index == 1) resumedAt = offset
                }
            },
            CancelToken(),
        ).run()

        assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
        val results = (outcome as SendOutcome.Completed).results
        assertTrue("first file is not re-sent", results[0].skipped)
        assertTrue("resumed mid-file at $resumedAt", resumedAt > 0 && resumedAt < size)
        assertTrue(requests.last().isResume)
        val received = outDir.listFiles()!!.filter { it.name.startsWith("big") }
        assertEquals(1, received.size) // resumed into the same file, no "big (1).bin"
        assertEquals(Checksums.sha256Hex(big.readBytes()), Checksums.sha256Hex(received.single().readBytes()))
    }

    @Test
    fun receiverCancelStopsSender() {
        val port = server.start()
        val big = makeFile("cancel.bin", 30 * Protocol.BUFFER_SIZE, 9)
        val files = listOf(LocalOutgoingFile(big))
        val serverWithCancel = server
        onBytes = { pos -> if (pos > 2 * Protocol.BUFFER_SIZE) serverWithCancel.cancelAll("Stop") }
        val outcome = try {
            SenderSession(senderId, Endpoint("127.0.0.1", port), hello(files, "transfer-" + UUID.randomUUID()), files, object : SendListener {}, CancelToken()).run()
        } catch (e: IOException) {
            null // a hard close before the CANCEL frame is read is acceptable too
        }
        assertTrue(outcome == null || outcome is SendOutcome.CancelledByPeer)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue("partial file removed", outDir.listFiles()!!.none { it.name.startsWith("cancel") })
    }

    @Test
    fun senderCancelIsReportedLocally() {
        val port = server.start()
        val files = listOf(LocalOutgoingFile(makeFile("s.bin", 30 * Protocol.BUFFER_SIZE, 10)))
        val token = CancelToken()
        val outcome = SenderSession(
            senderId, Endpoint("127.0.0.1", port), hello(files, "transfer-" + UUID.randomUUID()), files,
            object : SendListener {
                override fun onBytesSent(index: Int, position: Long) {
                    if (position > 3 * Protocol.BUFFER_SIZE) token.cancel("user")
                }
            },
            token,
        ).run()
        assertEquals(SendOutcome.CancelledLocally, outcome)
        assertTrue(ended.await(5, TimeUnit.SECONDS))
    }
}
