package io.github.chibisueru.telegramdrive.backup

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.chibisueru.telegramdrive.AppContainer
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.AppSettings
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Backs up new photos and videos from the camera folder (DCIM/Camera). A WorkManager job runs
 * when MediaStore reports new media (and every few hours as a fallback), within the chosen
 * Wi-Fi / charging conditions, and adds new items to the transfer queue.
 */
object CameraBackup {
    private const val TRIGGER_WORK = "camera_backup_trigger"
    private const val PERIODIC_WORK = "camera_backup_periodic"
    const val FOLDER_NAME = "Camera"
    internal const val KEY_TRIGGER = "trigger"

    fun schedule(context: Context, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        if (!s.cameraBackup || !s.isConfigured) {
            wm.cancelUniqueWork(TRIGGER_WORK)
            wm.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        wm.enqueueUniqueWork(TRIGGER_WORK, ExistingWorkPolicy.REPLACE, triggerRequest(s))
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<CameraBackupWorker>(6, TimeUnit.HOURS).setConstraints(constraints(s)).build(),
        )
    }

    /** Content-triggered work runs only once, so each run queues the next one behind itself. */
    internal fun rearmTrigger(context: Context, s: AppSettings) {
        if (!s.cameraBackup || !s.isConfigured) return
        WorkManager.getInstance(context).enqueueUniqueWork(TRIGGER_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, triggerRequest(s))
    }

    private fun triggerRequest(s: AppSettings): androidx.work.OneTimeWorkRequest {
        val base = constraints(s)
        return OneTimeWorkRequestBuilder<CameraBackupWorker>()
            .setInputData(workDataOf(KEY_TRIGGER to true))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(base.requiredNetworkType)
                    .setRequiresCharging(base.requiresCharging())
                    .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                    .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
                    .build()
            )
            .build()
    }

    private fun constraints(s: AppSettings) = Constraints(
        requiredNetworkType = if (s.cameraWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        requiresCharging = s.cameraChargingOnly,
    )

    /** The newest MediaStore item right now; used so enabling backup only picks up new photos. */
    fun newestMediaId(context: Context): Long {
        context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Files.FileColumns._ID),
            null, null,
            "${MediaStore.Files.FileColumns._ID} DESC",
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return 0
    }

    /** Queues camera items newer than the last one backed up. Returns how many were added. */
    suspend fun queueNewMedia(c: AppContainer): Int = withContext(Dispatchers.IO) {
        val s = c.settings.current()
        if (!s.cameraBackup || !s.isConfigured) return@withContext 0
        val folderId = ensureFolder(c, s)
        val types = buildList {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE)
            if (s.cameraVideos) add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        }
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${types.joinToString()}) AND " +
            "${MediaStore.Files.FileColumns._ID} > ? AND ${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        val items = mutableListOf<TransferEntity>()
        var newest = s.cameraLastMediaId
        c.context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(
                MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
            ),
            selection,
            arrayOf(s.cameraLastMediaId.toString(), "DCIM/Camera/%"),
            "${MediaStore.Files.FileColumns._ID} ASC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                val isVideo = cur.getInt(4) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                items += TransferEntity(
                    upload = true,
                    name = cur.getString(1) ?: "IMG_$id.jpg",
                    mimeType = cur.getString(3) ?: if (isVideo) "video/mp4" else "image/jpeg",
                    size = cur.getLong(2),
                    sourceUri = ContentUris.withAppendedId(base, id).toString(),
                    folderId = folderId,
                )
                newest = maxOf(newest, id)
            }
        }
        // Remember the high-water mark first, so a crash can't queue the same photos twice.
        if (newest != s.cameraLastMediaId) c.settings.setCameraLastMediaId(newest)
        c.transfers.dao.insertAll(items)
        items.size
    }

    /** The "Camera" folder in the drive root, created (or re-created) when needed. */
    private suspend fun ensureFolder(c: AppContainer, s: AppSettings): String {
        val existing = s.cameraFolderId.takeIf { it.isNotEmpty() }?.let { c.dao.folder(it) }
        if (existing != null && existing.deletedAt == null) return existing.id
        val byName = c.dao.rootFolderNamed(FOLDER_NAME)
        val folder = byName ?: FolderEntity(UUID.randomUUID().toString(), null, FOLDER_NAME, System.currentTimeMillis())
            .also { c.dao.insertFolder(it); c.indexSync.requestSync() }
        c.settings.setCameraFolderId(folder.id)
        return folder.id
    }
}

class CameraBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        try {
            // Fails (and is skipped) if photo access was revoked in Android settings.
            val queued = runCatching { CameraBackup.queueNewMedia(c) }.getOrDefault(0)
            if (queued > 0 || c.transfers.dao.pendingCount() > 0) {
                // In the background Android usually refuses to start the foreground service;
                // then this job uploads the queue itself (it may be stopped after ~10 minutes and
                // continues on the next run or when the app is opened).
                if (!c.transfers.start()) c.transferQueue.drain(waitForWifi = false)
            }
        } finally {
            if (inputData.getBoolean(CameraBackup.KEY_TRIGGER, false)) CameraBackup.rearmTrigger(applicationContext, c.settings.current())
        }
        return Result.success()
    }
}
