package app.droplink.core.protocol

/**
 * Smoothed throughput / ETA estimator. Feed it cumulative byte counts with timestamps;
 * it keeps an exponential moving average over ~2 s so the numbers don't jump around.
 */
class SpeedMeter(private val smoothingMs: Long = 2_000) {
    private var lastBytes = -1L
    private var lastTime = 0L
    private var firstBytes = -1L
    private var firstTime = 0L
    var bytesPerSecond: Double = 0.0
        private set

    @Synchronized
    fun update(totalBytes: Long, nowMs: Long) {
        if (lastBytes < 0) {
            lastBytes = totalBytes; lastTime = nowMs
            firstBytes = totalBytes; firstTime = nowMs
            return
        }
        val dt = nowMs - lastTime
        if (dt < 200) return
        val instant = (totalBytes - lastBytes).coerceAtLeast(0) * 1000.0 / dt
        val alpha = (dt.toDouble() / smoothingMs).coerceIn(0.05, 1.0)
        bytesPerSecond = if (bytesPerSecond == 0.0) instant else bytesPerSecond + alpha * (instant - bytesPerSecond)
        lastBytes = totalBytes
        lastTime = nowMs
    }

    /** Average over the whole run (what we log as "measured throughput"). */
    @Synchronized
    fun averageBytesPerSecond(totalBytes: Long, nowMs: Long): Double {
        if (firstBytes < 0) return 0.0
        val dt = nowMs - firstTime
        return if (dt <= 0) 0.0 else (totalBytes - firstBytes) * 1000.0 / dt
    }

    /** Seconds left, or null when unknown. */
    fun etaSeconds(remainingBytes: Long): Long? =
        if (bytesPerSecond < 1.0 || remainingBytes <= 0) null else (remainingBytes / bytesPerSecond).toLong()

    @Synchronized
    fun reset() {
        lastBytes = -1; firstBytes = -1; bytesPerSecond = 0.0
    }
}
