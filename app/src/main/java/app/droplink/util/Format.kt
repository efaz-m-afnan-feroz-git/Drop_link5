package app.droplink.util

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import app.droplink.R

object Format {
    fun bytes(context: Context, bytes: Long): String = Formatter.formatFileSize(context, bytes.coerceAtLeast(0))

    fun speed(context: Context, bytesPerSecond: Double): String =
        context.getString(R.string.format_speed, Formatter.formatFileSize(context, bytesPerSecond.toLong().coerceAtLeast(0)))

    fun eta(context: Context, seconds: Long?): String =
        if (seconds == null) context.getString(R.string.eta_unknown)
        else context.getString(R.string.format_eta, DateUtils.formatElapsedTime(seconds))

    fun time(context: Context, millis: Long): String = DateUtils.formatDateTime(
        context, millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    /** MB/s with one decimal, for the throughput log (decimal megabytes, like most speed tests). */
    fun megabytesPerSecond(context: Context, bytesPerSecond: Long): String =
        context.getString(R.string.format_mbps, bytesPerSecond / 1_000_000.0)
}
