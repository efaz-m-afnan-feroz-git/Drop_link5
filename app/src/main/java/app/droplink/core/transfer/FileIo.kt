package app.droplink.core.transfer

import app.droplink.core.protocol.FileEntry
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** A file the sender will stream. [open] may be called more than once (e.g. after a reconnect). */
interface OutgoingFile {
    val entry: FileEntry
    fun open(): InputStream
}

/** Something that can write received files (MediaStore, a SAF tree, or a plain directory). */
interface SinkFactory {
    /**
     * Opens the destination for [entry]. If [existing] is non-null and [offset] > 0 the same
     * destination is reopened and truncated to [offset]; otherwise a new destination is created.
     */
    fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink

    /** Current length of a destination previously returned by [FileSink.destination], or null if it's gone. */
    fun lengthOf(destination: String): Long?

    /** Removes a destination left behind by an abandoned transfer. */
    fun discard(destination: String)
}

interface FileSink : Closeable {
    /** Opaque handle stored in the resume ledger (content Uri string or absolute path). */
    val destination: String

    /** Reads back the bytes already written (used to rebuild the SHA-256 state on resume). */
    fun openExisting(): InputStream

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Flushes and fsyncs, so the written bytes survive a crash or power loss. */
    fun sync()

    /**
     * Makes the finished file visible (e.g. clears MediaStore IS_PENDING, or copies a staged file to
     * its final place). Returns a shareable location. [tick] is called regularly during long work so
     * the caller can send keep-alives.
     */
    fun commit(tick: () -> Unit = {}): String

    /** Deletes the destination (checksum mismatch or cancel). */
    fun discard()
}

/** [OutgoingFile] backed by a plain [File] (tests, exported APKs). */
class LocalOutgoingFile(private val file: File, relativePath: String = file.name, mime: String = "application/octet-stream") : OutgoingFile {
    override val entry = FileEntry(relativePath, file.length(), mime)
    override fun open(): InputStream = FileInputStream(file)
}

/**
 * Writes into a directory, preserving relative paths. Used on Android 8–9 (public Download/DropLink,
 * scoped-storage-free era) and by tests. New files never overwrite existing ones: "name (1).ext".
 */
open class DirectorySinkFactory(private val root: File) : SinkFactory {

    override fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink {
        val file = if (existing != null && offset > 0) {
            File(existing).takeIf { it.exists() && isInsideRoot(it) } ?: throw IOException("Partial file disappeared")
        } else {
            uniqueFile(entry.path)
        }
        file.parentFile?.mkdirs()
        return LocalFileSink(file, offset) { onCommitted(it, entry) }
    }

    override fun lengthOf(destination: String): Long? = File(destination).takeIf { it.isFile && isInsideRoot(it) }?.length()

    override fun discard(destination: String) {
        File(destination).takeIf { isInsideRoot(it) }?.delete()
    }

    /** Hook for subclasses (e.g. trigger a media scan). */
    protected open fun onCommitted(file: File, entry: FileEntry) {}

    private fun isInsideRoot(f: File): Boolean =
        f.canonicalPath.startsWith(root.canonicalPath + File.separator)

    private fun uniqueFile(relativePath: String): File {
        val candidate = File(root, relativePath)
        if (!candidate.exists()) return candidate
        val parent = candidate.parentFile
        val name = candidate.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val f = File(parent, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}

class LocalFileSink(private val file: File, offset: Long, private val onCommit: (File) -> Unit = {}) : FileSink {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(offset)
        seek(offset)
    }

    override val destination: String = file.absolutePath
    override fun openExisting(): InputStream = FileInputStream(file)
    override fun write(buffer: ByteArray, offset: Int, length: Int) = raf.write(buffer, offset, length)
    override fun sync() = raf.fd.sync()
    override fun commit(tick: () -> Unit): String {
        sync()
        close()
        onCommit(file)
        return file.absolutePath
    }

    override fun discard() {
        close()
        file.delete()
    }

    override fun close() = raf.close()
}
