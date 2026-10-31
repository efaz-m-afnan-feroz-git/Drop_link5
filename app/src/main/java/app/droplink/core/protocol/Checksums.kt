package app.droplink.core.protocol

import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

object Checksums {
    fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            chars[i * 2] = HEX[v ushr 4]
            chars[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(chars)
    }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "Odd hex length" }
        return ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
    }

    fun sha256Hex(bytes: ByteArray): String = hex(sha256().digest(bytes))

    /**
     * Feeds exactly [length] bytes of [input] into [digest] (used to rebuild hash state when resuming).
     * Calls [tick] periodically so callers can send keep-alives or check for cancellation.
     */
    fun update(digest: MessageDigest, input: InputStream, length: Long, buffer: ByteArray = ByteArray(Protocol.BUFFER_SIZE), tick: () -> Unit = {}) {
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("File is shorter than expected (changed while sending?)")
            digest.update(buffer, 0, n)
            remaining -= n
            tick()
        }
    }

    /** Hashes a whole stream. */
    fun sha256Hex(input: InputStream): String {
        val d = sha256()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            d.update(buf, 0, n)
        }
        return hex(d.digest())
    }

    /** Constant-time comparison of two hex digests. */
    fun matches(expectedHex: String, actualHex: String): Boolean =
        MessageDigest.isEqual(expectedHex.lowercase().toByteArray(), actualHex.lowercase().toByteArray())

    private val HEX = "0123456789abcdef".toCharArray()
}
