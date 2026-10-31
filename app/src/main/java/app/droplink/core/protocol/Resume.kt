package app.droplink.core.protocol

import java.io.File
import java.io.IOException

/** Receiver-side record of how far each file of a transfer has been durably written. */
data class LedgerFile(
    val path: String,
    val size: Long,
    /** Bytes flushed + fsynced to [destination] and reported to the sender as confirmed. */
    val confirmed: Long,
    /** True once the file's SHA-256 matched and it was committed. */
    val done: Boolean,
    /** Opaque destination handle (content Uri or file path); null until the file is first opened. */
    val destination: String?,
)

data class Ledger(
    val transferId: String,
    val senderDeviceId: String,
    val peerFingerprint: String,
    val files: List<LedgerFile>,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun withFile(index: Int, f: LedgerFile) =
        copy(files = files.toMutableList().also { it[index] = f }, updatedAt = System.currentTimeMillis())

    fun toJson(): Map<String, Any?> = mapOf(
        "transferId" to transferId,
        "senderDeviceId" to senderDeviceId,
        "peerFingerprint" to peerFingerprint,
        "updatedAt" to updatedAt,
        "files" to files.map {
            mapOf("path" to it.path, "size" to it.size, "confirmed" to it.confirmed, "done" to it.done, "dest" to it.destination)
        },
    )

    companion object {
        fun fresh(hello: Hello, peerFingerprint: String) = Ledger(
            transferId = hello.transferId,
            senderDeviceId = hello.deviceId,
            peerFingerprint = peerFingerprint,
            files = hello.files.map { LedgerFile(it.path, it.size, 0, false, null) },
        )

        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<String, Any?>) = Ledger(
            transferId = m.str("transferId"),
            senderDeviceId = m.str("senderDeviceId"),
            peerFingerprint = m.str("peerFingerprint"),
            updatedAt = m.long("updatedAt"),
            files = m.list("files").map {
                val f = it as Map<String, Any?>
                LedgerFile(f.str("path"), f.long("size"), f.long("confirmed"), f.bool("done"), f.strOrNull("dest"))
            },
        )
    }
}

/** Result of planning a (possibly resumed) receive. */
data class ResumePlan(val offsets: List<Long>, val done: List<Boolean>, val ledger: Ledger) {
    val isResume: Boolean get() = offsets.any { it > 0 } || done.any { it }
    val remainingBytes: Long get() = ledger.files.indices.sumOf { i -> if (done[i]) 0L else ledger.files[i].size - offsets[i] }
}

object ResumePlanner {

    /** A ledger can only be reused for the same transfer, from the same sender key, with an identical file list. */
    fun matches(ledger: Ledger, hello: Hello, peerFingerprint: String): Boolean =
        ledger.transferId == hello.transferId &&
            ledger.senderDeviceId == hello.deviceId &&
            ledger.peerFingerprint.equals(peerFingerprint, ignoreCase = true) &&
            ledger.files.size == hello.files.size &&
            ledger.files.zip(hello.files).all { (l, h) -> l.path == h.path && l.size == h.size }

    /**
     * Works out where each file restarts. The offset is the smaller of what we confirmed and what is
     * actually on disk ([lengthOf] returns null if the destination vanished), so a crash between
     * write and fsync can never leave a hole.
     */
    fun plan(hello: Hello, existing: Ledger?, peerFingerprint: String, lengthOf: (String) -> Long?): ResumePlan {
        val ledger = if (existing != null && matches(existing, hello, peerFingerprint)) existing else Ledger.fresh(hello, peerFingerprint)
        val offsets = ArrayList<Long>(ledger.files.size)
        val done = ArrayList<Boolean>(ledger.files.size)
        val files = ledger.files.map { f ->
            val dest = f.destination
            val actual = dest?.let(lengthOf)
            when {
                dest == null || actual == null -> {
                    offsets += 0; done += false
                    f.copy(confirmed = 0, done = false, destination = null)
                }
                f.done && actual == f.size -> {
                    offsets += f.size; done += true
                    f
                }
                f.done -> { // committed file was changed or truncated afterwards: start over
                    offsets += 0; done += false
                    f.copy(confirmed = 0, done = false, destination = null)
                }
                else -> {
                    val off = minOf(f.confirmed, actual, f.size).coerceAtLeast(0)
                    offsets += off; done += false
                    f.copy(confirmed = off)
                }
            }
        }
        return ResumePlan(offsets, done, ledger.copy(files = files))
    }
}

/** Stores ledgers as small JSON files, written atomically (temp file + rename). */
class LedgerStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    private fun fileFor(transferId: String): File {
        require(transferId.matches(Regex("[A-Za-z0-9-]{8,64}"))) { "Bad transfer id" }
        return File(dir, "$transferId.json")
    }

    @Synchronized
    fun load(transferId: String): Ledger? {
        val f = fileFor(transferId)
        if (!f.exists()) return null
        return runCatching { Ledger.fromJson(Json.parseObject(f.readText())) }.getOrNull()
    }

    @Synchronized
    fun save(ledger: Ledger) {
        val target = fileFor(ledger.transferId)
        val tmp = File(dir, "${ledger.transferId}.tmp")
        tmp.writeText(Json.stringify(ledger.toJson()))
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not save resume state")
        }
    }

    @Synchronized
    fun delete(transferId: String) {
        fileFor(transferId).delete()
    }

    /** Drops resume state older than [maxAgeMs] (abandoned transfers). Returns the dropped ledgers. */
    @Synchronized
    fun prune(maxAgeMs: Long, now: Long = System.currentTimeMillis()): List<Ledger> {
        val dropped = ArrayList<Ledger>()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            val ledger = runCatching { Ledger.fromJson(Json.parseObject(f.readText())) }.getOrNull()
            if (ledger == null || now - ledger.updatedAt > maxAgeMs) {
                ledger?.let { dropped += it }
                f.delete()
            }
        }
        return dropped
    }
}
