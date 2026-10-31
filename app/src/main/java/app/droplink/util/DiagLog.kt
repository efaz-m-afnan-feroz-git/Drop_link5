package app.droplink.util

import android.content.Context
import android.os.Build
import android.util.Log
import app.droplink.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device diagnostic log (app-private, never sent anywhere). The user can copy it from
 * Settings or the transfer screen and paste it to whoever is helping them debug.
 */
object DiagLog {
    private const val MAX_BYTES = 400_000L
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun init(context: Context) {
        file = File(context.filesDir, "diag.log")
        log(
            "App start: DropLink ${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
    }

    @Synchronized
    fun log(message: String, error: Throwable? = null) {
        if (error != null) Log.w("DropLink", message, error) else Log.i("DropLink", message)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText("…(older lines removed)\n$tail")
            }
            val line = buildString {
                append(time.format(Date())).append("  ").append(message).append('\n')
                if (error != null) {
                    val sw = StringWriter()
                    error.printStackTrace(PrintWriter(sw))
                    // Stack traces can be long; the first lines say what matters.
                    append(sw.toString().lineSequence().take(25).joinToString("\n")).append('\n')
                }
            }
            f.appendText(line)
        }
    }

    @Synchronized
    fun read(): String = runCatching { file?.readText() }.getOrNull().orEmpty().ifEmpty { "(log is empty)" }

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
