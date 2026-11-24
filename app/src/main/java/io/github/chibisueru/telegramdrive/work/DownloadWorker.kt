package io.github.chibisueru.telegramdrive.work

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.chibisueru.telegramdrive.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves a drive file to the phone's Downloads/Telegram Drive folder (no storage permission needed). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @Volatile private var progress = 0f

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val fileId = inputData.getString(TransferKeys.FILE_ID)!!
        val file = c.dao.file(fileId)
            ?: return Result.success(workDataOf(TransferKeys.ERROR to "File no longer exists"))
        val notificationId = id.hashCode()
        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Downloading", file.name, 0f)) }

        val resolver = applicationContext.contentResolver
        val target = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, file.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Telegram Drive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: return Result.success(workDataOf(TransferKeys.NAME to file.name, TransferKeys.ERROR to "Can't create file in Downloads"))

        return try {
            coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        setProgress(workDataOf(TransferKeys.PROGRESS to progress, TransferKeys.NAME to file.name))
                        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Downloading", file.name, progress)) }
                        delay(700)
                    }
                }
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(target)!!.buffered().use { out ->
                        val cached = c.files.cachedFile(file)
                        if (cached.exists() && cached.length() == file.size) {
                            cached.inputStream().use { it.copyTo(out) }
                        } else {
                            c.files.downloadTo(file, out) { done, total -> if (total > 0) progress = done.toFloat() / total }
                        }
                    }
                }
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                reporter.cancel()
            }
            Result.success(workDataOf(TransferKeys.NAME to file.name, TransferKeys.PROGRESS to 1f))
        } catch (e: CancellationException) {
            withContext(NonCancellable) { resolver.delete(target, null, null) }
            throw e
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            Result.success(workDataOf(TransferKeys.NAME to file.name, TransferKeys.ERROR to (e.message ?: e.javaClass.simpleName)))
        }
    }
}
