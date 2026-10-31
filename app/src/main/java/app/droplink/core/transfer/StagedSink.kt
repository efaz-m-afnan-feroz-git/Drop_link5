package app.droplink.core.transfer

import app.droplink.core.protocol.Checksums
import app.droplink.core.protocol.FileEntry
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Receives every file into a private staging file first (plain RandomAccessFile: the most reliable
 * storage path on every Android version and ROM), and only when the SHA-256 has been verified
 * copies it to its final place ([final]: MediaStore Downloads, a SAF folder, or a directory).
 *
 * Resume works on the staging file. A completed file's ledger entry points at its final location.
 */
class StagedSinkFactory(private val stagingRoot: File, private val final: SinkFactory) : SinkFactory {

    init {
        stagingRoot.mkdirs()
    }

    private fun isStaged(destination: String): Boolean = runCatching {
        File(destination).canonicalPath.startsWith(stagingRoot.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun stagingFile(transferId: String, entry: FileEntry): File {
        val key = Checksums.sha256Hex(entry.path.toByteArray(Charsets.UTF_8)).take(24)
        return File(File(stagingRoot, transferId), "$key.part")
    }

    override fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink {
        val file = if (existing != null && offset > 0 && isStaged(existing)) File(existing) else stagingFile(transferId, entry)
        if (offset == 0L) file.delete()
        if (offset > 0 && (!file.isFile || file.length() < offset)) throw IOException("Partial file disappeared")
        file.parentFile?.mkdirs()
        return StagedSink(transferId, entry, file, offset)
    }

    override fun lengthOf(destination: String): Long? =
        if (isStaged(destination)) File(destination).takeIf { it.isFile }?.length() else final.lengthOf(destination)

    override fun discard(destination: String) {
        if (isStaged(destination)) {
            val f = File(destination)
            f.delete()
            f.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        } else {
            final.discard(destination)
        }
    }

    /** Removes staging leftovers that no resume ledger refers to any more. */
    fun cleanup(keepTransferIds: Set<String>) {
        stagingRoot.listFiles()?.forEach { dir ->
            if (dir.name !in keepTransferIds) dir.deleteRecursively()
        }
    }

    private inner class StagedSink(
        private val transferId: String,
        private val entry: FileEntry,
        private val staged: File,
        offset: Long,
    ) : FileSink {
        private val local = LocalFileSink(staged, offset)

        @Volatile
        override var destination: String = staged.absolutePath
            private set

        override fun openExisting(): InputStream = FileInputStream(staged)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = local.write(buffer, offset, length)
        override fun sync() = local.sync()

        override fun commit(tick: () -> Unit): String {
            local.sync()
            local.close()
            val target = try {
                final.open(transferId, entry, null, 0)
            } catch (e: Exception) {
                throw IOException("Couldn't create the file in the save folder: ${e.message}", e)
            }
            try {
                FileInputStream(staged).use { input ->
                    val buf = ByteArray(1 shl 20)
                    var lastTick = System.currentTimeMillis()
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        target.write(buf, 0, n)
                        val t = System.currentTimeMillis()
                        if (t - lastTick > 2_000) {
                            tick(); lastTick = t
                        }
                    }
                }
                val location = target.commit(tick)
                destination = target.destination
                staged.delete()
                staged.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
                return location
            } catch (e: Exception) {
                runCatching { target.discard() }
                throw IOException("Couldn't save to the save folder: ${e.message}", e)
            }
        }

        override fun discard() {
            local.discard()
            staged.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        }

        override fun close() = local.close()
    }
}
