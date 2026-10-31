package app.droplink.core.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Wire-protocol constants. Bump [VERSION] on any incompatible change. */
object Protocol {
    const val VERSION = 1
    /** Oldest peer version we still talk to. */
    const val MIN_COMPATIBLE_VERSION = 1
    const val DEFAULT_PORT = 47820
    /** Service type for NsdManager (no trailing dot; NsdManager adds the domain). */
    const val NSD_SERVICE_TYPE = "_droplink._tcp"
    const val BUFFER_SIZE = 1 shl 20                  // 1 MiB streaming buffer
    const val MAX_DATA_PAYLOAD = BUFFER_SIZE
    const val MAX_CONTROL_PAYLOAD = 8 shl 20          // JSON frames (a header with many files)
    const val MAX_FILES = 20_000
    const val HEADER_SIZE = 5                         // 1 byte type + 4 bytes length
}

open class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

enum class FrameType(val code: Int) {
    HELLO(1),       // sender → receiver: JSON header (protocol version, names, ids, file list)
    ACCEPT(2),      // receiver → sender: JSON {offsets, done}
    DECLINE(3),     // receiver → sender: JSON {reason}
    FILE_START(4),  // sender → receiver: JSON {index, offset}
    DATA(5),        // sender → receiver: raw bytes (≤ 1 MiB)
    FILE_END(6),    // sender → receiver: JSON {index, sha256}
    FILE_ACK(7),    // receiver → sender: JSON {index, ok, bytes, reason}
    PROGRESS(8),    // receiver → sender: JSON {index, bytes} = last durably written offset
    CANCEL(9),      // either way: JSON {reason}
    DONE(10),       // either way: transfer finished
    ERROR(11),      // either way: JSON {message}
    PING(12);       // either way: keep-alive while one side is busy hashing

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: Int): FrameType = byCode[code] ?: throw ProtocolException("Unknown frame type $code")
    }
}

/**
 * A decoded frame. For [FrameType.DATA] frames read by [FrameReader] the [payload] array is a reused
 * buffer: only the first [length] bytes are valid and only until the next call to [FrameReader.next].
 */
class Frame(val type: FrameType, val payload: ByteArray, val length: Int = payload.size) {
    fun text(): String = String(payload, 0, length, Charsets.UTF_8)
    fun json(): Map<String, Any?> = Json.parseObject(text())
    override fun toString() = "Frame($type, $length bytes)"
}

/** Encodes frames as `[type:u8][length:u32 big-endian][payload]`. Thread-safe. */
class FrameWriter(output: OutputStream) {
    private val out = BufferedOutputStream(output, 64 * 1024)
    private val header = ByteArray(Protocol.HEADER_SIZE)

    @Synchronized
    fun write(type: FrameType, payload: ByteArray, offset: Int = 0, length: Int = payload.size, flush: Boolean = true) {
        val max = if (type == FrameType.DATA) Protocol.MAX_DATA_PAYLOAD else Protocol.MAX_CONTROL_PAYLOAD
        require(length in 0..max) { "Frame payload too large: $length" }
        header[0] = type.code.toByte()
        header[1] = (length ushr 24).toByte()
        header[2] = (length ushr 16).toByte()
        header[3] = (length ushr 8).toByte()
        header[4] = length.toByte()
        out.write(header)
        out.write(payload, offset, length)
        if (flush) out.flush()
    }

    fun writeJson(type: FrameType, body: Map<String, Any?>, flush: Boolean = true) =
        write(type, Json.stringify(body).toByteArray(Charsets.UTF_8), flush = flush)

    fun writeEmpty(type: FrameType) = write(type, EMPTY)

    @Synchronized
    fun flush() = out.flush()

    companion object {
        private val EMPTY = ByteArray(0)

        /** Encodes a single frame to bytes (used by tests and tools). */
        fun encode(type: FrameType, payload: ByteArray): ByteArray {
            val bos = java.io.ByteArrayOutputStream()
            FrameWriter(bos).write(type, payload)
            return bos.toByteArray()
        }
    }
}

/** Decodes frames. Not thread-safe: use from a single reader thread. */
class FrameReader(input: InputStream) {
    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val dataBuffer = ByteArray(Protocol.MAX_DATA_PAYLOAD)

    /** Reads the next frame, or throws [EOFException] if the stream ended cleanly before a header. */
    fun next(): Frame {
        val typeByte = din.read()
        if (typeByte < 0) throw EOFException("Connection closed")
        val type = FrameType.of(typeByte)
        val length = din.readInt()
        val max = if (type == FrameType.DATA) Protocol.MAX_DATA_PAYLOAD else Protocol.MAX_CONTROL_PAYLOAD
        if (length < 0 || length > max) throw ProtocolException("Invalid $type frame length $length")
        return if (type == FrameType.DATA) {
            din.readFully(dataBuffer, 0, length)
            Frame(type, dataBuffer, length)
        } else {
            val buf = ByteArray(length)
            din.readFully(buf)
            Frame(type, buf, length)
        }
    }

    /** Reads the next frame and requires it to be of [expected] type (handling ERROR/CANCEL clearly). */
    fun expect(vararg expected: FrameType): Frame {
        val f = next()
        if (f.type in expected) return f
        when (f.type) {
            FrameType.ERROR -> throw RemoteErrorException(Messages.errorMessage(f))
            FrameType.CANCEL -> throw RemoteCancelException(Messages.cancelReason(f))
            else -> throw ProtocolException("Expected ${expected.joinToString()} but got ${f.type}")
        }
    }
}

class RemoteErrorException(message: String) : IOException(message)
class RemoteCancelException(val reason: String?) : IOException("Cancelled by the other phone")
