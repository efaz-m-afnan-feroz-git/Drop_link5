package app.droplink.core.transfer

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.IdentityFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The receiver path the app really uses: staging file → verified → copied to the final folder. */
class StagedTransferTest {
    private lateinit var root: File
    private val sender = IdentityFactory.generate("s")
    private val receiver = IdentityFactory.generate("r")
    private val results = CopyOnWriteArrayList<Triple<Int, Boolean, String?>>()

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "staged-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun callbacks() = object : ReceiveCallbacks {
        override fun onIncoming(request: IncomingRequest) = Decision.Accept
        override fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) {
            results += Triple(index, ok, reason ?: location)
        }
    }

    private fun send(port: Int, files: List<OutgoingFile>): SendOutcome = SenderSession(
        sender, Endpoint("127.0.0.1", port, expectedFingerprint = receiver.fingerprint),
        Hello(Protocol.VERSION, "t-" + UUID.randomUUID(), "S", "dev-s", TransferMode.LOOPBACK, false, files.map { it.entry }),
        files, object : SendListener {}, CancelToken(),
    ).run()

    private fun file(name: String, size: Int) =
        File(root, "src/$name").apply { parentFile.mkdirs(); writeBytes(ByteArray(size).also { Random(size.toLong()).nextBytes(it) }) }

    @Test
    fun filesArriveInFinalFolderAndStagingIsEmptied() {
        val out = File(root, "Download/DropLink")
        val staging = File(root, "staging")
        val server = ReceiverServer(receiver, StagedSinkFactory(staging, DirectorySinkFactory(out)), LedgerStore(File(root, "l")), callbacks(), preferredPort = 0)
        val port = server.start()
        try {
            val a = file("a.jpg", 5 * Protocol.BUFFER_SIZE + 3)
            val b = file("b.txt", 0)
            val outcome = send(port, listOf(LocalOutgoingFile(a, "pics/a.jpg"), LocalOutgoingFile(b, "b.txt")))
            assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
            assertEquals(Checksums.sha256Hex(a.readBytes()), Checksums.sha256Hex(File(out, "pics/a.jpg").readBytes()))
            assertTrue(File(out, "b.txt").exists())
            Thread.sleep(200)
            assertTrue("staging emptied", staging.walkTopDown().none { it.isFile })
        } finally {
            server.stop(keepResumeState = false)
        }
    }

    @Test
    fun resumeAfterDropContinuesInTheStagingFile() {
        val out = File(root, "out")
        val staging = File(root, "staging")
        val ledgers = File(root, "l")
        val size = 30 * Protocol.BUFFER_SIZE + 11
        val big = file("big.bin", size)
        val files = listOf(LocalOutgoingFile(big, "big.bin"))
        val id = "t-" + UUID.randomUUID()
        var server: ReceiverServer? = null
        val dropCallbacks = object : ReceiveCallbacks {
            @Volatile var dropped = false
            override fun onIncoming(request: IncomingRequest) = Decision.Accept
            override fun onBytesReceived(sessionId: Long, index: Int, position: Long) {
                if (!dropped && position > size / 2) {
                    dropped = true
                    Thread { server?.stop() }.start()
                }
            }
        }
        server = ReceiverServer(receiver, StagedSinkFactory(staging, DirectorySinkFactory(out)), LedgerStore(ledgers), dropCallbacks, preferredPort = 0)
        var port = server.start()
        fun hello(resume: Boolean) = Hello(Protocol.VERSION, id, "S", "dev-s", TransferMode.LOOPBACK, resume, files.map { it.entry })
        try {
            SenderSession(sender, Endpoint("127.0.0.1", port), hello(false), files, object : SendListener {}, CancelToken()).run()
            throw AssertionError("expected drop")
        } catch (e: IOException) {
            assertFalse(e is FatalTransferException)
        }
        Thread.sleep(300)
        val server2 = ReceiverServer(receiver, StagedSinkFactory(staging, DirectorySinkFactory(out)), LedgerStore(ledgers), callbacks(), preferredPort = 0)
        port = server2.start()
        try {
            var resumedAt = -1L
            val outcome = SenderSession(
                sender, Endpoint("127.0.0.1", port), hello(true), files,
                object : SendListener { override fun onFileStarted(index: Int, offset: Long) { resumedAt = offset } },
                CancelToken(),
            ).run()
            assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
            assertTrue("resumed mid-file at $resumedAt", resumedAt in 1 until size)
            assertEquals(Checksums.sha256Hex(big.readBytes()), Checksums.sha256Hex(File(out, "big.bin").readBytes()))
        } finally {
            server2.stop(keepResumeState = false)
        }
    }

    @Test
    fun saveFailureIsReportedPerFileWithoutKillingTheTransfer() {
        val out = File(root, "out")
        val failing = object : DirectorySinkFactory(out) {
            override fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink {
                if (entry.path == "bad.bin") throw IOException("Disk full")
                return super.open(transferId, entry, existing, offset)
            }
        }
        val server = ReceiverServer(receiver, StagedSinkFactory(File(root, "staging"), failing), LedgerStore(File(root, "l")), callbacks(), preferredPort = 0)
        val port = server.start()
        try {
            val outcome = send(port, listOf(LocalOutgoingFile(file("bad.bin", 1000), "bad.bin"), LocalOutgoingFile(file("ok.bin", 2000), "ok.bin")))
            assertTrue(outcome is SendOutcome.Completed)
            val r = (outcome as SendOutcome.Completed).results
            assertFalse(r[0].ok)
            assertTrue(r[0].reason!!.contains("Disk full"))
            assertTrue(r[1].ok)
            assertTrue(File(out, "ok.bin").exists())
        } finally {
            server.stop(keepResumeState = false)
        }
    }
}
