package app.droplink

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.IdentityFactory
import app.droplink.core.transfer.CancelToken
import app.droplink.core.transfer.Decision
import app.droplink.core.transfer.DirectorySinkFactory
import app.droplink.core.transfer.Endpoint
import app.droplink.core.transfer.IncomingRequest
import app.droplink.core.transfer.LocalOutgoingFile
import app.droplink.core.transfer.ReceiveCallbacks
import app.droplink.core.transfer.ReceiverServer
import app.droplink.core.transfer.SendListener
import app.droplink.core.transfer.SendOutcome
import app.droplink.core.transfer.SenderSession
import app.droplink.core.transfer.SinkFactory
import app.droplink.storage.MediaStoreSinkFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sender and receiver on ONE device over 127.0.0.1, using the platform TLS stack (Conscrypt),
 * the real BouncyCastle certificates and real storage. Run with ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class LoopbackInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val locations = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        root = File(context.cacheDir, "itest-" + UUID.randomUUID()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun transfer(sinks: SinkFactory, sizeBytes: Int): Pair<File, SendOutcome> {
        val src = File(root, "src.bin").apply { writeBytes(ByteArray(sizeBytes).also { Random(7).nextBytes(it) }) }
        val receiver = IdentityFactory.generate("receiver")
        val server = ReceiverServer(
            receiver, sinks, LedgerStore(File(root, "ledger")),
            object : ReceiveCallbacks {
                override fun onIncoming(request: IncomingRequest) = Decision.Accept
                override fun onFileFinished(sessionId: Long, index: Int, ok: Boolean, reason: String?, location: String?) {
                    location?.let { locations += it }
                }
            },
            preferredPort = 0,
        )
        val port = server.start()
        try {
            val file = LocalOutgoingFile(src, "droplink-itest-${UUID.randomUUID()}.bin")
            val hello = Hello(Protocol.VERSION, "itest-" + UUID.randomUUID(), "itest", "itest-dev", TransferMode.LOOPBACK, false, listOf(file.entry))
            val started = System.currentTimeMillis()
            val outcome = SenderSession(
                IdentityFactory.generate("sender"),
                Endpoint("127.0.0.1", port, expectedFingerprint = receiver.fingerprint),
                hello, listOf(file), object : SendListener {}, CancelToken(),
            ).run()
            val ms = System.currentTimeMillis() - started
            android.util.Log.i("DropLinkTest", "loopback ${sizeBytes / 1_048_576} MiB in $ms ms = ${sizeBytes / 1000.0 / ms} MB/s")
            return src to outcome
        } finally {
            server.stop(keepResumeState = false)
        }
    }

    @Test
    fun loopbackTransferToPrivateDirectory() {
        val out = File(root, "out").apply { mkdirs() }
        val (src, outcome) = transfer(DirectorySinkFactory(out), 24 shl 20)
        assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
        val received = File(locations.single())
        assertEquals(src.length(), received.length())
        assertEquals(Checksums.sha256Hex(src.inputStream()), Checksums.sha256Hex(received.inputStream()))
    }

    @Test
    fun loopbackTransferIntoMediaStoreDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // MediaStore.Downloads is Android 10+
        val sinks = MediaStoreSinkFactory(context, folder = "DropLink-test")
        val (src, outcome) = transfer(sinks, 4 shl 20)
        assertTrue(outcome is SendOutcome.Completed && outcome.allOk)
        val uri = android.net.Uri.parse(locations.single())
        try {
            val hash = context.contentResolver.openInputStream(uri)!!.use { Checksums.sha256Hex(it) }
            assertEquals(Checksums.sha256Hex(src.inputStream()), hash)
        } finally {
            sinks.discard(uri.toString())
        }
    }
}
