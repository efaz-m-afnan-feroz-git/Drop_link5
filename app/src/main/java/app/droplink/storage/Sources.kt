package app.droplink.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.droplink.core.protocol.FileEntry
import app.droplink.core.protocol.SafePath
import app.droplink.core.transfer.OutgoingFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** A file picked for sending. */
data class OutgoingItem(
    val key: String,
    val file: OutgoingFile,
    /** Which picker it came from, for grouping in the UI. */
    val origin: Origin,
) {
    enum class Origin { MEDIA, DOCUMENT, FOLDER, APP, SHARED }
    val entry: FileEntry get() = file.entry
}

class UriOutgoingFile(private val resolver: ContentResolver, val uri: Uri, override val entry: FileEntry) : OutgoingFile {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Can't open ${entry.name}")
}

class ApkOutgoingFile(private val apk: File, override val entry: FileEntry) : OutgoingFile {
    override fun open(): InputStream = FileInputStream(apk)
}

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val apkPath: String,
    val sizeBytes: Long,
    val icon: Drawable?,
)

/** Turns picker results (Uris, folder trees, installed apps) into [OutgoingItem]s. Blocking: call off the main thread. */
class SourceResolver(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun fromUris(uris: List<Uri>, origin: OutgoingItem.Origin): List<OutgoingItem> = uris.distinct().mapNotNull { uri ->
        runCatching {
            val (name, size) = queryNameAndSize(uri)
            val mime = resolver.getType(uri) ?: Sinks.mimeFor(name, "application/octet-stream")
            val knownSize = size ?: probeSize(uri)
            if (knownSize != null) {
                OutgoingItem(uri.toString(), UriOutgoingFile(resolver, uri, FileEntry(clean(name), knownSize, mime)), origin)
            } else {
                // Some share-sheet providers don't report a size; spool to cache so the header can announce it.
                val spooled = spool(uri, name)
                OutgoingItem(uri.toString(), ApkOutgoingFile(spooled, FileEntry(clean(name), spooled.length(), mime)), origin)
            }
        }.getOrNull()
    }

    /** Walks a folder picked with ACTION_OPEN_DOCUMENT_TREE, keeping paths relative to (and including) the folder. */
    fun fromTree(treeUri: Uri): List<OutgoingItem> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDocName(DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "Folder"
        val out = ArrayList<OutgoingItem>()
        fun walk(docId: String, prefix: String, depth: Int) {
            if (depth > 15) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        walk(id, "$prefix$name/", depth + 1)
                    } else {
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val path = runCatching { SafePath.sanitize("$prefix$name") }.getOrNull() ?: continue
                        out += OutgoingItem(uri.toString(), UriOutgoingFile(resolver, uri, FileEntry(path, size, mime)), OutgoingItem.Origin.FOLDER)
                    }
                }
            }
        }
        walk(rootId, "${clean(rootName)}/", 0)
        return out
    }

    @Suppress("DEPRECATION")
    fun installedApps(): List<InstalledApp> {
        val pm = appContext.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
        return packages.mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0) return@runCatching null
                if (pkg == appContext.packageName) return@runCatching null
                val apk = File(info.sourceDir)
                InstalledApp(
                    packageName = pkg,
                    label = pm.getApplicationLabel(info).toString(),
                    versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull(),
                    apkPath = apk.absolutePath,
                    sizeBytes = apk.length(),
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                )
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    /** Exports an app's base APK (split APKs of App Bundle installs are not included). */
    fun fromApp(app: InstalledApp): OutgoingItem {
        val base = clean("${app.label}${app.versionName?.let { "-$it" } ?: ""}") + ".apk"
        val apk = File(app.apkPath)
        if (!apk.canRead()) throw IOException("Can't read ${app.label}")
        return OutgoingItem(
            "app:${app.packageName}",
            ApkOutgoingFile(apk, FileEntry(base, apk.length(), "application/vnd.android.package-archive")),
            OutgoingItem.Origin.APP,
        )
    }

    /** True when the app is installed as split APKs, so the exported base APK alone won't install. */
    fun hasSplits(app: InstalledApp): Boolean = runCatching {
        !appContext.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).splitSourceDirs.isNullOrEmpty()
    }.getOrDefault(false)

    private fun queryNameAndSize(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1).takeIf { it >= 0 }
                }
            }
        }
        return (name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file") to size
    }

    private fun queryDocName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun probeSize(uri: Uri): Long? = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.takeIf { l -> l >= 0 } }
    }.getOrNull()

    private fun spool(uri: Uri, name: String): File {
        val dir = File(appContext.cacheDir, "outgoing").apply { mkdirs() }
        val f = File(dir, System.nanoTime().toString() + "-" + clean(name))
        resolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IOException("Can't read $name")
        return f
    }

    private fun clean(name: String): String = runCatching { SafePath.sanitize(name.replace('/', '_')) }.getOrDefault("file")

    companion object {
        /** Makes relative paths unique (two picked files called IMG_1.jpg → "IMG_1.jpg", "IMG_1 (2).jpg"). */
        fun uniquify(items: List<OutgoingItem>): List<OutgoingItem> {
            val used = HashSet<String>()
            return items.map { item ->
                var path = item.entry.path
                var i = 2
                while (!used.add(path.lowercase())) {
                    val dir = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
                    val name = item.entry.path.substringAfterLast('/')
                    val dot = name.lastIndexOf('.')
                    val renamed = if (dot > 0) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
                    path = dir + renamed
                    i++
                }
                if (path == item.entry.path) item else item.copy(file = Renamed(item.file, item.entry.copy(path = path)))
            }
        }
    }

    private class Renamed(private val inner: OutgoingFile, override val entry: FileEntry) : OutgoingFile {
        override fun open(): InputStream = inner.open()
    }
}
