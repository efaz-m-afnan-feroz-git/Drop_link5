package app.droplink.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import app.droplink.core.protocol.FileEntry
import app.droplink.core.transfer.DirectorySinkFactory
import app.droplink.core.transfer.FileSink
import app.droplink.core.transfer.SinkFactory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

const val DROPLINK_FOLDER = "DropLink"

/** Picks where received files go: a user-chosen SAF folder, MediaStore Downloads (10+), or public Downloads (8–9). */
object Sinks {
    fun create(context: Context, saveTreeUri: String?): SinkFactory {
        if (saveTreeUri != null) {
            val tree = Uri.parse(saveTreeUri)
            val stillGranted = context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
            if (stillGranted) return SafTreeSinkFactory(context, tree)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSinkFactory(context)
        } else {
            LegacyDownloadsSinkFactory(context)
        }
    }

    /**
     * What the receiver really uses: files are received into a private staging folder (most reliable
     * on every ROM), verified, then copied to [create]'s destination.
     */
    fun createStaged(context: Context, saveTreeUri: String?): app.droplink.core.transfer.StagedSinkFactory {
        val staging = context.getExternalFilesDir("staging") ?: File(context.filesDir, "staging")
        return app.droplink.core.transfer.StagedSinkFactory(staging, create(context, saveTreeUri))
    }

    /** Guesses the MIME type from the extension (MediaStore/SAF may rename files whose MIME and extension disagree). */
    fun mimeFor(name: String, fallback: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: fallback.ifBlank { "application/octet-stream" }
    }
}

/** Writes to a content Uri through a "rw" file descriptor, starting at [offset] (resume). */
internal class DescriptorSink(
    private val resolver: ContentResolver,
    private val uri: Uri,
    offset: Long,
    private val onCommit: () -> Unit,
    private val onDiscard: () -> Unit,
) : FileSink {
    // A brand-new file is opened write-only ("w"), which every provider supports; only a resume
    // into an existing file needs "rw" (not used by the default staged path).
    private val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, if (offset == 0L) "w" else "rw")
        ?: throw IOException("Can't open the destination file")
    private val out = FileOutputStream(pfd.fileDescriptor)

    init {
        if (offset > 0) {
            try {
                val channel = out.channel
                if (channel.size() != offset) channel.truncate(offset)
                channel.position(offset)
            } catch (e: Exception) {
                close()
                throw IOException("Can't resume this file: ${e.message}", e)
            }
        }
    }

    override val destination: String = uri.toString()
    override fun openExisting(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Can't reopen the partial file")
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
    override fun sync() {
        out.flush()
        // Some providers' descriptors (FUSE, pipes) reject fsync; the data is still written.
        runCatching { pfd.fileDescriptor.sync() }
    }

    override fun commit(tick: () -> Unit): String {
        sync()
        close()
        onCommit()
        return uri.toString()
    }

    override fun discard() {
        close()
        runCatching { onDiscard() }
    }

    override fun close() {
        runCatching { out.close() }
        runCatching { pfd.close() }
    }
}

/** Android 10+: Download/DropLink/<relative path> via MediaStore (no storage permission needed). */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSinkFactory(context: Context, private val folder: String = DROPLINK_FOLDER) : SinkFactory {
    private val resolver = context.applicationContext.contentResolver

    override fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink {
        val uri = if (existing != null && offset > 0) Uri.parse(existing) else create(entry)
        return DescriptorSink(
            resolver, uri, offset,
            onCommit = {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            },
            onDiscard = { resolver.delete(uri, null, null) },
        )
    }

    private fun create(entry: FileEntry): Uri {
        val dir = entry.path.substringBeforeLast('/', "")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name)
            put(MediaStore.MediaColumns.MIME_TYPE, Sinks.mimeFor(entry.name, "application/octet-stream"))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + folder + if (dir.isNotEmpty()) "/$dir" else "",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Can't create ${entry.name} in Downloads")
    }

    override fun lengthOf(destination: String): Long? = runCatching {
        resolver.openFileDescriptor(Uri.parse(destination), "r")?.use { it.statSize }
    }.getOrNull()

    override fun discard(destination: String) {
        runCatching { resolver.delete(Uri.parse(destination), null, null) }
    }
}

/** A folder the user picked with the system file picker (SD card, USB drive, any provider supporting "rw"). */
class SafTreeSinkFactory(context: Context, private val tree: Uri) : SinkFactory {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override fun open(transferId: String, entry: FileEntry, existing: String?, offset: Long): FileSink {
        val uri = if (existing != null && offset > 0) Uri.parse(existing) else create(entry)
        return DescriptorSink(
            resolver, uri, offset,
            onCommit = {},
            onDiscard = { DocumentsContract.deleteDocument(resolver, uri) },
        )
    }

    private fun create(entry: FileEntry): Uri {
        var dir = DocumentFile.fromTreeUri(appContext, tree) ?: throw IOException("The save folder is not available")
        entry.path.split('/').dropLast(1).forEach { name ->
            dir = dir.findFile(name)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(name)
                ?: throw IOException("Can't create folder $name")
        }
        val name = uniqueName(dir, entry.name)
        // octet-stream keeps providers from appending their own extension
        val file = dir.createFile("application/octet-stream", name) ?: throw IOException("Can't create $name")
        return file.uri
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (dir.findFile("$base ($i)$ext") != null) i++
        return "$base ($i)$ext"
    }

    override fun lengthOf(destination: String): Long? = runCatching {
        DocumentFile.fromSingleUri(appContext, Uri.parse(destination))?.takeIf { it.exists() }?.length()
    }.getOrNull()

    override fun discard(destination: String) {
        runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(destination)) }
    }
}

/** Android 8–9: public Download/DropLink (needs WRITE_EXTERNAL_STORAGE, maxSdkVersion 28). */
@Suppress("DEPRECATION")
class LegacyDownloadsSinkFactory(context: Context) : DirectorySinkFactory(
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DROPLINK_FOLDER),
) {
    private val appContext = context.applicationContext

    override fun onCommitted(file: File, entry: FileEntry) {
        MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), arrayOf(Sinks.mimeFor(file.name, entry.mime)), null)
    }
}
