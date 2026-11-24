package io.github.chibisueru.telegramdrive.transfer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.chibisueru.telegramdrive.data.db.TransferDao
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Front door to the transfer queue; [TransferService] works through it in the background. */
class TransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    val dao: TransferDao,
    private val runner: TransferRunner,
) {
    /** The transfer the service is working on right now, so it can be cancelled. */
    @Volatile var running: Pair<Long, Job>? = null

    suspend fun enqueue(transfers: List<TransferEntity>) {
        if (transfers.isEmpty()) return
        dao.insertAll(transfers)
        start()
    }

    /**
     * Starts (or nudges) the foreground service. Android only allows this while the app is
     * visible; returns false if it was refused (e.g. when called from a background job).
     */
    fun start(): Boolean =
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }.isSuccess

    /** Called when the app comes to the foreground: picks up work left over from earlier. */
    fun resumeIfPending() = scope.launch { if (dao.pendingCount() > 0) start() }

    suspend fun cancel(id: Long) {
        val t = dao.get(id) ?: return
        dao.setState(id, TransferEntity.STATE_CANCELLED)
        running?.takeIf { it.first == id }?.second?.cancel()
        runner.discardSource(t)
    }

    suspend fun cancelAll() {
        dao.cancelAllPending()
        running?.second?.cancel()
    }

    suspend fun retry(id: Long) {
        dao.requeue(id)
        start()
    }

    /** Removes finished entries; failed/cancelled uploads also drop their copies. */
    suspend fun clearFinished() {
        dao.finished().forEach { if (it.upload && it.state != TransferEntity.STATE_DONE) runner.discardSource(it) }
        dao.clearFinished()
    }
}
