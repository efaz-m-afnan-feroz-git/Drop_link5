package app.droplink.core.transfer

import app.droplink.core.protocol.Json
import app.droplink.core.protocol.bool
import app.droplink.core.protocol.str
import app.droplink.core.protocol.strOrNull
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * USB cable mode. Turns one USB byte pipe (Android Open Accessory, both directions) into local TCP
 * connections, so the normal encrypted DropLink protocol runs over the cable unchanged:
 *
 *   SenderSession ─TCP→ 127.0.0.1:[localPort] ═ USB ═ bridge → 127.0.0.1:[receiverPort] → ReceiverServer
 *
 * USB framing: `[type | 0x80 if padded][length u32][body][pad byte?]`. A frame is never an exact
 * multiple of 64 bytes, so every frame ends with a short USB packet and the other side's read
 * completes immediately (no stall waiting for a zero-length packet).
 */
class UsbBridge(
    private val selfName: () -> String,
    private val selfDeviceId: String,
    private val selfFingerprint: () -> String,
) {
    data class Peer(val name: String, val deviceId: String, val fingerprint: String, val receiving: Boolean)

    /** Called with the phone on the other end of the cable (null when unplugged). */
    @Volatile
    var onPeer: (Peer?) -> Unit = {}

    @Volatile
    var peer: Peer? = null
        private set

    /** Port of this phone's ReceiverServer while receiving, 0 otherwise. */
    @Volatile
    var receiverPort: Int = 0
        set(value) {
            field = value
            helloSoon()
        }

    /** Hellos go out on their own thread: a USB write can block until the other app opens the pipe. */
    private val helloThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "droplink-usb-hello").apply { isDaemon = true }
    }

    private fun helloSoon() {
        if (link == null) return
        helloThread.execute { runCatching { sendHello() }.onFailure { CoreLog.log("USB: hello failed", it) } }
    }

    private class Link(val input: DataInputStream, val output: OutputStream, val close: () -> Unit)

    @Volatile
    private var link: Link? = null
    private val writeLock = Any()
    private val conns = ConcurrentHashMap<Int, Socket>()
    private val nextId = AtomicInteger(1)
    private var listener: ServerSocket? = null

    val isAttached: Boolean get() = link != null

    /** A USB pipe came up. [input] must already respect the platform's read-size rules. */
    @Synchronized
    fun attach(input: InputStream, output: OutputStream, close: () -> Unit) {
        detach()
        val l = Link(DataInputStream(input), output, close)
        link = l
        Thread({ readLoop(l) }, "droplink-usb-read").apply { isDaemon = true }.start()
        helloSoon()
        CoreLog.log("USB: link attached")
    }

    @Synchronized
    fun detach() {
        val l = link ?: return
        link = null
        conns.values.forEach { runCatching { it.close() } }
        conns.clear()
        runCatching { l.close() }
        setPeer(null)
        CoreLog.log("USB: link detached")
    }

    /** The sender connects its SenderSession here; each TCP connection is carried over the cable. */
    @Synchronized
    fun localPort(): Int {
        listener?.takeIf { !it.isClosed }?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), 4)
        listener = ss
        Thread({ acceptLoop(ss) }, "droplink-usb-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    fun shutdown() {
        runCatching { listener?.close() }
        listener = null
        detach()
    }

    // ------------------------------------------------------------------------------------ frames

    private fun setPeer(p: Peer?) {
        if (p == peer) return
        peer = p
        runCatching { onPeer(p) }
    }

    private fun sendHello() {
        val body = Json.stringify(
            mapOf("v" to 1L, "n" to selfName(), "id" to selfDeviceId, "fp" to selfFingerprint(), "r" to (receiverPort > 0)),
        ).toByteArray(Charsets.UTF_8)
        writeFrame(HELLO, body, 0, body.size)
    }

    private fun writeConnFrame(type: Int, id: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0) {
        val body = ByteArray(4 + len)
        body[0] = (id ushr 24).toByte(); body[1] = (id ushr 16).toByte(); body[2] = (id ushr 8).toByte(); body[3] = id.toByte()
        if (data != null && len > 0) System.arraycopy(data, off, body, 4, len)
        writeFrame(type, body, 0, body.size)
    }

    private fun writeFrame(type: Int, body: ByteArray, off: Int, len: Int) {
        val l = link ?: throw IOException("USB cable not connected")
        val frame = encode(type, body, off, len)
        synchronized(writeLock) {
            try {
                l.output.write(frame) // one write per frame: it ends with a short USB packet
                l.output.flush()
            } catch (e: IOException) {
                if (link === l) detach()
                throw e
            }
        }
    }

    private fun readLoop(l: Link) {
        try {
            while (link === l) {
                val head = l.input.readUnsignedByte()
                val padded = head and 0x80 != 0
                val type = head and 0x7F
                val len = l.input.readInt()
                if (len < 0 || len > MAX_BODY) throw IOException("Bad USB frame length $len")
                val body = ByteArray(len)
                l.input.readFully(body)
                if (padded) l.input.readUnsignedByte()
                handle(type, body)
            }
        } catch (e: Exception) {
            if (link === l) CoreLog.log("USB: link lost", e)
        } finally {
            if (link === l) detach()
        }
    }

    private fun handle(type: Int, body: ByteArray) {
        when (type) {
            HELLO -> {
                val m = Json.parseObject(String(body, Charsets.UTF_8))
                setPeer(Peer(m.strOrNull("n") ?: "Phone", m.str("id"), m.str("fp"), m.bool("r")))
            }
            OPEN -> onOpen(idOf(body))
            DATA -> {
                val id = idOf(body)
                val s = conns[id] ?: return
                try {
                    s.getOutputStream().write(body, 4, body.size - 4)
                } catch (e: IOException) {
                    closeConn(id, notify = true)
                }
            }
            CLOSE -> closeConn(idOf(body), notify = false)
            else -> Unit // unknown: newer version, ignore
        }
    }

    /** Receiving side: the other phone opened a connection → connect it to our ReceiverServer. */
    private fun onOpen(id: Int) {
        val port = receiverPort
        if (port <= 0) {
            runCatching { writeConnFrame(CLOSE, id) }
            return
        }
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 5_000)
            conns[id] = s
            pump(id, s)
        } catch (e: IOException) {
            CoreLog.log("USB: couldn't reach the local receiver", e)
            runCatching { writeConnFrame(CLOSE, id) }
        }
    }

    /** Sending side: a local TCP connection → a new connection over the cable. */
    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                return
            }
            if (link == null) {
                runCatching { s.close() }
                continue
            }
            s.tcpNoDelay = true
            val id = nextId.getAndIncrement()
            conns[id] = s
            try {
                writeConnFrame(OPEN, id)
                pump(id, s)
            } catch (e: IOException) {
                closeConn(id, notify = false)
            }
        }
    }

    /** Copies one local TCP connection's incoming bytes onto the cable. */
    private fun pump(id: Int, s: Socket) {
        Thread({
            val buf = ByteArray(CHUNK)
            try {
                val input = s.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) writeConnFrame(DATA, id, buf, 0, n)
                }
            } catch (_: IOException) {
            } finally {
                closeConn(id, notify = true)
            }
        }, "droplink-usb-pump-$id").apply { isDaemon = true }.start()
    }

    private fun closeConn(id: Int, notify: Boolean) {
        val s = conns.remove(id) ?: return
        runCatching { s.close() }
        if (notify) runCatching { writeConnFrame(CLOSE, id) }
    }

    companion object {
        const val HELLO = 1
        const val OPEN = 2
        const val DATA = 3
        const val CLOSE = 4
        const val CHUNK = 64 * 1024
        const val MAX_BODY = CHUNK + 1024
        /** Frames must not be a multiple of this (the smallest USB bulk packet size that divides 512). */
        const val PACKET_GUARD = 64

        private fun idOf(body: ByteArray): Int {
            if (body.size < 4) throw IOException("Short USB frame")
            return ((body[0].toInt() and 0xFF) shl 24) or ((body[1].toInt() and 0xFF) shl 16) or
                ((body[2].toInt() and 0xFF) shl 8) or (body[3].toInt() and 0xFF)
        }

        /** Encodes one frame, padding it by a byte if its size would be a multiple of [PACKET_GUARD]. */
        fun encode(type: Int, body: ByteArray, off: Int, len: Int): ByteArray {
            val pad = (5 + len) % PACKET_GUARD == 0
            val out = ByteArray(5 + len + if (pad) 1 else 0)
            out[0] = (type or if (pad) 0x80 else 0).toByte()
            out[1] = (len ushr 24).toByte(); out[2] = (len ushr 16).toByte(); out[3] = (len ushr 8).toByte(); out[4] = len.toByte()
            System.arraycopy(body, off, out, 5, len)
            return out
        }
    }
}

/**
 * Wraps a USB input so every underlying read asks for exactly [chunk] bytes. The Android
 * accessory driver silently drops bytes when a read asks for less than one USB request, and
 * host-side bulk reads must be a multiple of the packet size.
 */
class FixedChunkInputStream(private val source: (ByteArray) -> Int, chunk: Int) : InputStream() {
    private val buf = ByteArray(chunk)
    private var pos = 0
    private var lim = 0

    private fun fill(): Boolean {
        while (pos >= lim) {
            val n = source(buf)
            if (n < 0) return false
            pos = 0
            lim = n
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else buf[pos++].toInt() and 0xFF

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, lim - pos)
        System.arraycopy(buf, pos, b, off, n)
        pos += n
        return n
    }

    override fun available(): Int = lim - pos
}
