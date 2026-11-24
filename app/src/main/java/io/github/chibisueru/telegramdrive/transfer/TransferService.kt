package io.github.chibisueru.telegramdrive.transfer

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.chibisueru.telegramdrive.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the app alive as a foreground service while [TransferQueue] works through the queue,
 * so transfers continue (and move on to the next file) in the background or with the screen off.
 */
class TransferService : Service() {
    companion object {
        const val ACTION_CANCEL_ALL = "io.github.chibisueru.telegramdrive.CANCEL_ALL"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastStartId = 0
    private lateinit var notifications: TransferNotifications

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = TransferNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Must be called promptly after startForegroundService().
        startForeground(TransferNotifications.PROGRESS_ID, notifications.preparing(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == ACTION_CANCEL_ALL) {
            scope.launch { container.transfers.cancelAll() }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val c = container
        var total = QueueSummary()
        while (true) {
            val summary = c.transferQueue.drain()
            if (summary == null) {
                // The camera-backup job is draining the queue right now; check back shortly.
                delay(2_000)
                continue
            }
            total = QueueSummary(total.uploaded + summary.uploaded, total.downloaded + summary.downloaded, total.failed + summary.failed)
            // Stop only if nothing new was queued meanwhile (onStartCommand runs on Main too).
            val stopped = withContext(Dispatchers.Main) {
                if (c.transfers.dao.pendingCount() > 0) false
                else {
                    notifications.showFinished(total.uploaded, total.downloaded, total.failed)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(lastStartId)
                }
            }
            if (stopped) return
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
