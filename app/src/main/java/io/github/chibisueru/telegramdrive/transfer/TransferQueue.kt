package io.github.chibisueru.telegramdrive.transfer

import io.github.chibisueru.telegramdrive.data.SettingsStore
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class QueueSummary(val uploaded: Int = 0, val downloaded: Int = 0, val failed: Int = 0)

/**
 * Works through queued transfers one at a time. Used by [TransferService] (normally) and by the
 * camera-backup job (when Android doesn't let the service start from the background). A lock
 * makes sure only one of them runs at once.
 */
class TransferQueue(
    private val scope: CoroutineScope,
    private val manager: TransferManager,
    private val runner: TransferRunner,
    private val settings: SettingsStore,
    private val network: NetworkMonitor,
    private val notifications: TransferNotifications,
) {
    private val lock = Mutex()
    private val dao get() = manager.dao

    /**
     * Processes the queue until it's empty. Returns null if another caller is already doing it.
     * [waitForWifi] false makes it return instead of waiting when "Wi-Fi only" blocks it.
     */
    suspend fun drain(waitForWifi: Boolean = true): QueueSummary? {
        if (!lock.tryLock()) return null
        try {
            dao.requeueRunning()
            return loop(waitForWifi)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun loop(waitForWifi: Boolean): QueueSummary {
        var uploaded = 0
        var downloaded = 0
        var failed = 0
        val wifiOnly = settings.flow.map { it.wifiOnly }
        val allowed = combine(wifiOnly, network.onWifi) { only, wifi -> !only || wifi }

        while (true) {
            currentCoroutineContext().ensureActive()
            val next = dao.nextQueued() ?: break

            if (!allowed.first()) {
                if (!waitForWifi) break
                notifications.show(notifications.waiting("Waiting for Wi-Fi · ${dao.pendingCount()} in the queue"))
                allowed.first { it }
                continue
            }

            dao.setState(next.id, TransferEntity.STATE_RUNNING)
            val done = uploaded + downloaded + failed
            val total = done + dao.pendingCount()
            val permille = AtomicInteger(0) // current file's progress, shared across coroutines
            val pausedForWifi = AtomicBoolean(false)

            val work = scope.async { runner.run(next) { permille.set((it * 1000).toInt()) } }
            manager.running = next.id to work
            val ticker = scope.launch {
                while (isActive) {
                    val p = permille.get() / 1000f
                    notifications.show(notifications.progress(next.upload, done + 1, total, next.name, p))
                    dao.setProgress(next.id, p)
                    delay(700)
                }
            }
            val wifiWatcher = scope.launch {
                combine(wifiOnly, network.onWifi) { only, wifi -> only && !wifi }.first { it }
                pausedForWifi.set(true)
                work.cancel()
            }
            val result = runCatching { work.await() }
            ticker.cancel()
            wifiWatcher.cancel()
            manager.running = null
            currentCoroutineContext().ensureActive()

            when {
                result.isSuccess -> {
                    dao.setProgress(next.id, 1f)
                    dao.setState(next.id, TransferEntity.STATE_DONE)
                    if (next.upload) uploaded++ else downloaded++
                }
                pausedForWifi.get() -> dao.requeue(next.id)
                dao.get(next.id)?.state == TransferEntity.STATE_CANCELLED -> Unit
                else -> {
                    val e = result.exceptionOrNull()
                    dao.setState(next.id, TransferEntity.STATE_FAILED, e?.message ?: e?.javaClass?.simpleName)
                    failed++
                }
            }
        }
        return QueueSummary(uploaded, downloaded, failed)
    }
}
