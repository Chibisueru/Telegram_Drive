package io.github.chibisueru.telegramdrive.transfer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import io.github.chibisueru.telegramdrive.MainActivity
import io.github.chibisueru.telegramdrive.R
import io.github.chibisueru.telegramdrive.TgDriveApp

/** Builds the transfer notifications: a two-bar progress one while working, a summary when done. */
class TransferNotifications(private val context: Context) {
    companion object {
        const val PROGRESS_ID = 1001
        const val DONE_ID = 1002
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_TRANSFERS = "transfers"
    }

    private val manager = context.getSystemService(NotificationManager::class.java)

    private val openApp = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TAB, TAB_TRANSFERS)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val cancelAll = PendingIntent.getService(
        context, 1,
        Intent(context, TransferService::class.java).setAction(TransferService.ACTION_CANCEL_ALL),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * @param index 1-based number of the current file in this batch
     * @param total files in this batch (finished + current + waiting)
     */
    fun progress(upload: Boolean, index: Int, total: Int, name: String, fileProgress: Float): Notification {
        val verb = if (upload) "Uploading" else "Downloading"
        val overall = ((index - 1 + fileProgress) / total.coerceAtLeast(1)).coerceIn(0f, 1f)
        val percent = (fileProgress * 100).toInt()
        val small = RemoteViews(context.packageName, R.layout.notification_transfer_small).apply {
            setTextViewText(R.id.summary, "$verb $index of $total · $percent%")
            setProgressBar(R.id.overall_bar, 1000, (overall * 1000).toInt(), false)
            setProgressBar(R.id.file_bar, 1000, (fileProgress * 1000).toInt(), false)
        }
        val big = RemoteViews(context.packageName, R.layout.notification_transfer_big).apply {
            setTextViewText(R.id.overall_label, "$verb file $index of $total")
            setProgressBar(R.id.overall_bar, 1000, (overall * 1000).toInt(), false)
            setTextViewText(R.id.file_label, "$name · $percent%")
            setProgressBar(R.id.file_bar, 1000, (fileProgress * 1000).toInt(), false)
        }
        return base()
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .build()
    }

    fun waiting(text: String): Notification = base()
        .setContentTitle("Transfers paused")
        .setContentText(text)
        .build()

    fun preparing(): Notification = base()
        .setContentTitle("Preparing transfers…")
        .setProgress(0, 0, true)
        .build()

    fun show(notification: Notification) = manager.notify(PROGRESS_ID, notification)

    fun showFinished(uploaded: Int, downloaded: Int, failed: Int) {
        if (uploaded + downloaded + failed == 0) return
        val parts = buildList {
            if (uploaded > 0) add("$uploaded uploaded")
            if (downloaded > 0) add("$downloaded saved to Downloads")
            if (failed > 0) add("$failed failed")
        }
        manager.notify(
            DONE_ID,
            NotificationCompat.Builder(context, TgDriveApp.TRANSFER_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_cloud)
                .setContentTitle(if (failed > 0) "Transfers finished with errors" else "Transfers finished")
                .setContentText(parts.joinToString(" · "))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun base() = NotificationCompat.Builder(context, TgDriveApp.TRANSFER_CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_cloud)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openApp)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .addAction(0, "Cancel all", cancelAll)
}
