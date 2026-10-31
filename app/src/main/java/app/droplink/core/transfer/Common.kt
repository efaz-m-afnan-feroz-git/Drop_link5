package app.droplink.core.transfer

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** An error that retrying won't fix (declined, wrong key, protocol violation, incompatible version…). */
class FatalTransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The source file no longer matches what was announced (edited or deleted while sending). */
class SourceChangedException(message: String) : IOException(message)

/**
 * Cancellation that works with blocking socket IO: hooks run first (e.g. send a CANCEL frame),
 * then registered sockets are closed, which unblocks any thread stuck in read/write.
 */
class CancelToken {
    @Volatile
    var isCancelled = false
        private set

    @Volatile
    var reason: String? = null
        private set

    private val hooks = CopyOnWriteArrayList<() -> Unit>()
    private val closeables = CopyOnWriteArrayList<Closeable>()

    fun onCancel(hook: () -> Unit) {
        hooks += hook
    }

    fun register(c: Closeable) {
        closeables += c
        if (isCancelled) runCatching { c.close() }
    }

    fun unregister(c: Closeable) {
        closeables -= c
    }

    fun cancel(reason: String? = null) {
        synchronized(this) {
            if (isCancelled) return
            isCancelled = true
            this.reason = reason
        }
        // Hooks may block on a busy socket: run them off-thread and close sockets shortly after regardless.
        Thread({
            hooks.forEach { runCatching { it() } }
        }, "droplink-cancel").apply { isDaemon = true }.start()
        Thread({
            runCatching { Thread.sleep(800) }
            closeables.forEach { runCatching { it.close() } }
        }, "droplink-cancel-close").apply { isDaemon = true }.start()
    }

    fun throwIfCancelled() {
        if (isCancelled) throw CancelledException(reason)
    }
}

class CancelledException(val reason: String?) : IOException("Cancelled")

data class FileResult(val index: Int, val ok: Boolean, val reason: String? = null, val skipped: Boolean = false)

internal fun now() = System.currentTimeMillis()

/**
 * Diagnostic log hook for the pure-Kotlin core. The app points [sink] at its on-device log file;
 * tests leave it null. Never logs file contents or keys, only events and errors.
 */
object CoreLog {
    @Volatile
    var sink: ((String, Throwable?) -> Unit)? = null

    fun log(message: String, error: Throwable? = null) {
        runCatching { sink?.invoke(message, error) }
    }
}
