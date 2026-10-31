package app.droplink.core.protocol

/** One file in a transfer. [path] is a relative path using '/' separators (folders are preserved). */
data class FileEntry(val path: String, val size: Long, val mime: String) {
    val name: String get() = path.substringAfterLast('/')

    fun toJson(): Map<String, Any?> = mapOf("path" to path, "size" to size, "mime" to mime)

    companion object {
        fun fromJson(m: Map<String, Any?>): FileEntry {
            val size = m.long("size")
            if (size < 0) throw ProtocolException("Negative file size")
            return FileEntry(
                path = SafePath.sanitize(m.str("path")),
                size = size,
                mime = (m.strOrNull("mime") ?: "application/octet-stream").take(255).ifBlank { "application/octet-stream" },
            )
        }
    }
}

enum class TransferMode { LAN, WIFI_DIRECT, QR_HOTSPOT, LOOPBACK, USB }

/** The first frame on every connection: the transfer header. */
data class Hello(
    val protocolVersion: Int,
    val transferId: String,
    val senderName: String,
    val deviceId: String,
    val mode: TransferMode,
    val resume: Boolean,
    val files: List<FileEntry>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    fun toJson(): Map<String, Any?> = mapOf(
        "v" to protocolVersion,
        "transferId" to transferId,
        "senderName" to senderName,
        "deviceId" to deviceId,
        "mode" to mode.name,
        "resume" to resume,
        "files" to files.map { it.toJson() },
    )

    companion object {
        fun fromJson(m: Map<String, Any?>): Hello {
            val version = m.int("v")
            if (version < Protocol.MIN_COMPATIBLE_VERSION) {
                throw IncompatibleVersionException(version)
            }
            val rawFiles = m.list("files")
            if (rawFiles.size > Protocol.MAX_FILES) throw ProtocolException("Too many files (${rawFiles.size})")
            val files = rawFiles.map {
                @Suppress("UNCHECKED_CAST")
                FileEntry.fromJson(it as? Map<String, Any?> ?: throw ProtocolException("Bad file entry"))
            }
            val dupes = files.groupBy { it.path.lowercase() }.filter { it.value.size > 1 }.keys
            if (dupes.isNotEmpty()) throw ProtocolException("Duplicate paths in header: ${dupes.first()}")
            val transferId = m.str("transferId")
            if (!transferId.matches(Regex("[A-Za-z0-9-]{8,64}"))) throw ProtocolException("Bad transfer id")
            val deviceId = m.str("deviceId")
            if (!deviceId.matches(Regex("[A-Za-z0-9-]{4,64}"))) throw ProtocolException("Bad device id")
            return Hello(
                protocolVersion = version,
                transferId = transferId,
                senderName = m.str("senderName").filter { !it.isISOControl() }.take(64).ifBlank { "Unknown phone" },
                deviceId = deviceId,
                mode = runCatching { TransferMode.valueOf(m.strOrNull("mode") ?: "") }.getOrDefault(TransferMode.LAN),
                resume = m.bool("resume"),
                files = files,
            )
        }

        fun parse(frame: Frame): Hello {
            if (frame.type != FrameType.HELLO) throw ProtocolException("Expected HELLO, got ${frame.type}")
            return fromJson(frame.json())
        }
    }
}

class IncompatibleVersionException(val peerVersion: Int) :
    ProtocolException("The other phone runs an incompatible DropLink version (protocol $peerVersion)")

/** Receiver's answer: where each file should (re)start, and which files are already complete. */
data class AcceptMsg(val offsets: List<Long>, val done: List<Boolean>) {
    fun toJson(): Map<String, Any?> = mapOf("offsets" to offsets, "done" to done)

    companion object {
        fun fromJson(m: Map<String, Any?>, fileCount: Int): AcceptMsg {
            val offsets = m.list("offsets").map { (it as? Long) ?: throw ProtocolException("Bad offset") }
            val done = m.list("done").map { (it as? Boolean) ?: throw ProtocolException("Bad done flag") }
            if (offsets.size != fileCount || done.size != fileCount) throw ProtocolException("ACCEPT does not match file list")
            return AcceptMsg(offsets, done)
        }
    }
}

object Messages {
    fun fileStart(index: Int, offset: Long) = mapOf("index" to index, "offset" to offset)
    fun fileEnd(index: Int, sha256: String) = mapOf("index" to index, "sha256" to sha256)
    fun fileAck(index: Int, ok: Boolean, bytes: Long, reason: String?) =
        mapOf("index" to index, "ok" to ok, "bytes" to bytes, "reason" to reason)
    fun progress(index: Int, bytes: Long) = mapOf("index" to index, "bytes" to bytes)
    fun cancel(reason: String?) = mapOf("reason" to reason)
    fun error(message: String) = mapOf("message" to message)
    fun decline(reason: String) = mapOf("reason" to reason)

    fun errorMessage(f: Frame): String =
        runCatching { f.json().strOrNull("message") }.getOrNull() ?: "The other phone reported an error"

    fun cancelReason(f: Frame): String? = runCatching { f.json().strOrNull("reason") }.getOrNull()
}

/**
 * Validates and normalises a peer-supplied relative path so a malicious sender can never write
 * outside the destination folder: no absolute paths, no '..', no empty or hidden traversal segments,
 * no characters that are illegal on FAT/exFAT SD cards.
 */
object SafePath {
    private const val MAX_SEGMENT = 120
    private const val MAX_DEPTH = 16
    private val illegal = Regex("[\\\\:*?\"<>|\\u0000-\\u001F\\u007F]")

    fun sanitize(raw: String): String {
        val normalized = raw.replace('\\', '/')
        if (normalized.startsWith("/")) throw ProtocolException("Absolute paths are not allowed")
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) throw ProtocolException("Empty path")
        if (segments.size > MAX_DEPTH) throw ProtocolException("Path too deep")
        val clean = segments.map { seg ->
            if (seg == "." || seg == "..") throw ProtocolException("Path traversal is not allowed")
            val s = seg.replace(illegal, "_").trim().trimEnd('.')
            if (s.isEmpty()) "_" else if (s.length > MAX_SEGMENT) shorten(s) else s
        }
        return clean.joinToString("/")
    }

    private fun shorten(s: String): String {
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        return s.take(MAX_SEGMENT - ext.length) + ext
    }
}
