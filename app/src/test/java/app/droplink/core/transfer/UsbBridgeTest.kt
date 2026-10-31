package app.droplink.core.transfer

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.Hello
import app.droplink.core.protocol.LedgerStore
import app.droplink.core.protocol.Protocol
import app.droplink.core.protocol.TransferMode
import app.droplink.core.security.IdentityFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UsbFrameTest {
    @Test
    fun framesAreNeverAMultipleOf64Bytes() {
        for (len in 0..5000) {
            val f = UsbBridge.encode(UsbBridge.DATA, ByteArray(len), 0, len)
            assertNotEquals("len=$len", 0, f.size % UsbBridge.PACKET_GUARD)
        }
    }

    @Test
    fun fixedChunkInputAlwaysReadsFullChunks() {
        val data = ByteArray(100_000) { it.toByte() }
        var pos = 0
        val asked = ArrayList<Int>()
        val input = FixedChunkInputStream({ b ->
            asked += b.size
            if (pos >= data.size) -1 else {
                val n = minOf(b.size, data.size - pos, 7_000)
                System.arraycopy(data, pos, b, 0, n); pos += n; n
            }
        }, 16_384)
        val out = java.io.ByteArrayOutputStream()
        val small = ByteArray(10)
        while (true) {
            val n = input.read(small)
            if (n < 0) break
            out.write(small, 0, n)
        }
        assertTrue(out.toByteArray().contentEquals(data))
        assertTrue(asked.all { it == 16_384 })
    }
}

/** Full encrypted transfers through two bridges joined by a simulated USB cable (a TCP pair). */
class UsbBridgeTest {
    private lateinit var root: File
    private val senderId = IdentityFactory.generate("sender")
    private val receiverId = IdentityFactory.generate("receiver")
    private lateinit var cableA: Socket
    private lateinit var cableB: Socket
    private val bridgeA = UsbBridge({ "Sender phone" }, "dev-sender", { senderId.fingerprint })
    private val bridgeB = UsbBridge({ "Receiver phone" }, "dev-receiver", { receiverId.fingerprint })

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "usb-" + UUID.randomUUID()).apply { mkdirs() }
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        cableA = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
        cableB = ss.accept()
        ss.close()
        fun usbIn(s: Socket) = FixedChunkInputStream({ b -> s.getInputStream().read(b) }, 16_384)
        bridgeA.attach(usbIn(cableA), cableA.getOutputStream()) { cableA.close() }
        bridgeB.attach(usbIn(cableB), cableB.getOutputStream()) { cableB.close() }
    }

    @After
    fun tearDown() {
        bridgeA.shutdown()
        bridgeB.shutdown()
        root.deleteRecursively()
    }

    private fun awaitPeer(bridge: UsbBridge, receiving: Boolean): UsbBridge.Peer {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            bridge.peer?.takeIf { it.receiving == receiving }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("peer not seen")
    }

    @Test
    fun helloAnnouncesTheReceiver() {
        assertFalse(awaitPeer(bridgeA, receiving = false).receiving)
        bridgeB.receiverPort = 12345
        val p = awaitPeer(bridgeA, receiving = true)
        assertEquals("Receiver phone", p.name)
        assertEquals(receiverId.fingerprint, p.fingerprint)
    }

    @Test
    fun transfersOverTheCableWithPinnedKey() {
        val out = File(root, "out")
        val done = CountDownLatch(1)
        val server = ReceiverServer(
            receiverId, StagedSinkFactory(File(root, "staging"), DirectorySinkFactory(out)), LedgerStore(File(root, "l")),
            object : ReceiveCallbacks {
                override fun onIncoming(request: IncomingRequest) = Decision.Accept
                override fun onSessionEnded(sessionId: Long, request: IncomingRequest?, outcome: ReceiveOutcome) = done.countDown()
            },
            preferredPort = 0,
        )
        bridgeB.receiverPort = server.start()
        try {
            val peer = awaitPeer(bridgeA, receiving = true)
            val big = File(root, "video.mp4").apply { writeBytes(ByteArray(12 * Protocol.BUFFER_SIZE + 333).also { Random(1).nextBytes(it) }) }
            val small = File(root, "note.txt").apply { writeBytes(ByteArray(511) { 7 }) }
            val files = listOf(LocalOutgoingFile(big, "video.mp4"), LocalOutgoingFile(small, "note.txt"))
            val outcome = SenderSession(
                senderId, Endpoint("127.0.0.1", bridgeA.localPort(), expectedFingerprint = peer.fingerprint),
                Hello(Protocol.VERSION, "t-" + UUID.randomUUID(), "S", "dev-sender", TransferMode.USB, false, files.map { it.entry }),
                files, object : SendListener {}, CancelToken(),
            ).run()
            assertTrue("$outcome", outcome is SendOutcome.Completed && outcome.allOk)
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(Checksums.sha256Hex(big.readBytes()), Checksums.sha256Hex(File(out, "video.mp4").readBytes()))
            assertEquals(Checksums.sha256Hex(small.readBytes()), Checksums.sha256Hex(File(out, "note.txt").readBytes()))
        } finally {
            server.stop(keepResumeState = false)
        }
    }

    @Test
    fun notReceivingRefusesTheConnection() {
        awaitPeer(bridgeA, receiving = false)
        val files = listOf(LocalOutgoingFile(File(root, "x").apply { writeBytes(ByteArray(10)) }))
        try {
            SenderSession(
                senderId, Endpoint("127.0.0.1", bridgeA.localPort(), connectTimeoutMs = 2_000),
                Hello(Protocol.VERSION, "t-" + UUID.randomUUID(), "S", "dev-sender", TransferMode.USB, false, files.map { it.entry }),
                files, object : SendListener {}, CancelToken(),
            ).run()
            throw AssertionError("expected failure")
        } catch (e: java.io.IOException) {
            // expected: the receiver side closes the tunnel
        }
    }

    @Test
    fun unplugClearsThePeer() {
        awaitPeer(bridgeA, receiving = false)
        cableB.close()
        val deadline = System.currentTimeMillis() + 5_000
        while (bridgeA.peer != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertNull(bridgeA.peer)
        assertFalse(bridgeA.isAttached)
    }
}
