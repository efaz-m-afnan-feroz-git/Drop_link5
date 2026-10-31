package app.droplink.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.droplink.DropLinkApp
import app.droplink.MainActivity
import app.droplink.R
import app.droplink.transfer.PendingRequest
import app.droplink.transfer.Phase
import app.droplink.transfer.ReceiveStatus
import app.droplink.transfer.Role
import app.droplink.transfer.TransferUi
import app.droplink.util.Format

object Notifications {
    private const val CH_ACTIVE = "transfers"
    private const val CH_REQUESTS = "requests"
    private const val CH_FINISHED = "finished"
    const val ID_SERVICE = 1
    private const val ID_INCOMING = 2
    private const val ID_FINISHED = 3

    const val ACTION_CANCEL = "app.droplink.action.CANCEL"
    const val ACTION_STOP_RECEIVING = "app.droplink.action.STOP_RECEIVING"
    const val ACTION_ACCEPT = "app.droplink.action.ACCEPT"
    const val ACTION_DECLINE = "app.droplink.action.DECLINE"
    const val EXTRA_SESSION = "session"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ACTIVE, context.getString(R.string.channel_active), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
                NotificationChannel(CH_REQUESTS, context.getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_FINISHED, context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setAction(MainActivity.ACTION_SHOW_TRANSFER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(context: Context, action: String, requestCode: Int, session: Long = 0): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, NotificationActionReceiver::class.java).setAction(action).putExtra(EXTRA_SESSION, session),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** The ongoing foreground-service notification: progress while transferring, "ready" while listening. */
    fun service(context: Context, receive: ReceiveStatus, ui: TransferUi?): Notification {
        val b = NotificationCompat.Builder(context, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
        if (ui != null && ui.phase.isActive) {
            val title = context.getString(
                if (ui.role == Role.SEND) R.string.notif_sending_to else R.string.notif_receiving_from, ui.peerName,
            )
            val text = when (ui.phase) {
                Phase.TRANSFERRING -> context.getString(
                    R.string.notif_progress_detail,
                    Format.bytes(context, ui.doneBytes), Format.bytes(context, ui.totalBytes),
                    Format.speed(context, ui.bytesPerSecond), Format.eta(context, ui.etaSeconds),
                )
                Phase.RECONNECTING -> context.getString(R.string.phase_reconnecting)
                Phase.WAITING_APPROVAL -> context.getString(R.string.phase_waiting_approval)
                Phase.NEEDS_QR -> context.getString(R.string.phase_needs_qr)
                else -> context.getString(R.string.phase_connecting)
            }
            b.setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(1000, (ui.progress * 1000).toInt(), ui.phase != Phase.TRANSFERRING)
                .addAction(0, context.getString(R.string.action_cancel), action(context, ACTION_CANCEL, 10))
        } else if (receive.active) {
            b.setContentTitle(context.getString(R.string.notif_ready_title))
                .setContentText(context.getString(R.string.notif_ready_text, (context.applicationContext as DropLinkApp).container.settings.current.deviceName))
                .addAction(0, context.getString(R.string.action_stop), action(context, ACTION_STOP_RECEIVING, 11))
        } else {
            b.setContentTitle(context.getString(R.string.app_name)).setContentText(context.getString(R.string.phase_connecting))
        }
        return b.build()
    }

    private fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @Suppress("MissingPermission")
    private fun notify(context: Context, id: Int, n: Notification) {
        if (!canNotify(context)) return
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    fun update(context: Context, n: Notification) = notify(context, ID_SERVICE, n)

    fun showIncoming(context: Context, p: PendingRequest) {
        val text = context.getString(
            R.string.incoming_body, p.senderName,
            context.resources.getQuantityString(R.plurals.file_count, p.fileCount, p.fileCount),
            Format.bytes(context, p.totalBytes), p.pairingCode,
        )
        val n = NotificationCompat.Builder(context, CH_REQUESTS)
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setContentTitle(context.getString(R.string.incoming_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setTimeoutAfter(110_000)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_accept), action(context, ACTION_ACCEPT, 20, p.sessionId))
            .addAction(0, context.getString(R.string.action_decline), action(context, ACTION_DECLINE, 21, p.sessionId))
            .build()
        notify(context, ID_INCOMING, n)
    }

    fun cancelIncoming(context: Context) = NotificationManagerCompat.from(context).cancel(ID_INCOMING)

    fun showFinished(context: Context, ui: TransferUi) {
        val title = when (ui.phase) {
            Phase.COMPLETED -> context.getString(if (ui.role == Role.SEND) R.string.done_sent else R.string.done_received)
            Phase.PARTIAL -> context.getString(R.string.done_partial, ui.failedCount)
            Phase.CANCELLED -> context.getString(R.string.phase_cancelled)
            Phase.DECLINED -> context.getString(R.string.phase_declined)
            else -> context.getString(R.string.phase_failed)
        }
        val text = context.getString(
            R.string.done_body,
            context.resources.getQuantityString(R.plurals.file_count, ui.okCount, ui.okCount),
            Format.bytes(context, ui.doneBytes), ui.peerName,
        )
        val n = NotificationCompat.Builder(context, CH_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_droplink)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_FINISHED, n)
    }
}

/** Handles notification buttons without needing the UI or a service start. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as DropLinkApp).container
        val session = intent.getLongExtra(Notifications.EXTRA_SESSION, 0)
        when (intent.action) {
            Notifications.ACTION_CANCEL -> {
                c.send.cancel()
                c.receive.cancelTransfer()
            }
            Notifications.ACTION_STOP_RECEIVING -> c.receive.stop()
            Notifications.ACTION_ACCEPT -> c.receive.respond(session, accept = true, trust = false)
            Notifications.ACTION_DECLINE -> c.receive.respond(session, accept = false, trust = false)
        }
        if (intent.action == Notifications.ACTION_ACCEPT || intent.action == Notifications.ACTION_DECLINE) {
            Notifications.cancelIncoming(context)
        }
    }
}
