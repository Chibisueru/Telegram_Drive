package io.github.chibisueru.telegramdrive.work

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputThumbnail
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.util.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Uploads one picked file as a single document message in the drive channel (up to 2 GB,
 * 4 GB with Premium), with a thumbnail for photos and videos, then records it in the index.
 *
 * TDLib uploads from a file path, so the picked file is first copied into the app's cache.
 * Failures are reported through output data with Result.success, so a failed file does not
 * cancel the uploads queued after it.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @Volatile private var progress = 0f

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val uri = inputData.getString(TransferKeys.URI)!!.toUri()
        val folderId = inputData.getString(TransferKeys.FOLDER)?.ifEmpty { null }
        val name = inputData.getString(TransferKeys.NAME) ?: "file"
        val mime = inputData.getString(TransferKeys.MIME) ?: "application/octet-stream"
        val declaredSize = inputData.getLong(TransferKeys.SIZE, -1)
        val notificationId = id.hashCode()
        val stagingDir = File(applicationContext.cacheDir, "upload/${UUID.randomUUID()}")

        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Uploading", name, null)) }

        return try {
            coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        setProgress(workDataOf(TransferKeys.PROGRESS to progress, TransferKeys.NAME to name))
                        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Uploading", name, progress)) }
                        delay(700)
                    }
                }
                val chatId = c.settings.current().chatId
                val staged = stage(uri, File(stagingDir, safeFileName(name)), declaredSize)
                if (staged.length() == 0L) error("$name is empty")
                val thumb = makeThumbnail(uri, mime, File(stagingDir, "thumb.jpg"))

                val stored = c.files.sendDocument(
                    chatId = chatId,
                    path = staged.absolutePath,
                    caption = name,
                    thumbnail = thumb,
                ) { p -> progress = 0.05f + 0.95f * p }

                c.dao.insertFile(
                    FileEntity(
                        id = UUID.randomUUID().toString(), folderId = folderId, name = name, mimeType = mime,
                        size = staged.length(), createdAt = System.currentTimeMillis(),
                        messageId = stored.messageId, remoteId = stored.remoteId, thumbRemoteId = stored.thumbRemoteId,
                    )
                )
                c.indexSync.requestSync()
                reporter.cancel()
            }
            Result.success(workDataOf(TransferKeys.NAME to name, TransferKeys.PROGRESS to 1f))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.success(workDataOf(TransferKeys.NAME to name, TransferKeys.ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            stagingDir.deleteRecursively()
            runCatching {
                applicationContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Copies the picked file into the cache; this is the first 5 % of the progress bar. */
    private suspend fun stage(uri: Uri, dest: File, declaredSize: Long): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val input = applicationContext.contentResolver.openInputStream(uri) ?: error("Can't open the file")
        input.use { inp ->
            dest.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    val n = inp.read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (declaredSize > 0) progress = 0.05f * copied / declaredSize
                    currentCoroutineContext().ensureActive()
                }
            }
        }
        dest
    }

    private suspend fun makeThumbnail(uri: Uri, mime: String, dest: File): InputThumbnail? {
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // Telegram wants JPEG thumbnails of at most 320 px per side.
                val bitmap = applicationContext.contentResolver.loadThumbnail(uri, Size(320, 320), null)
                dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                InputThumbnail(InputFileLocal(dest.absolutePath), bitmap.width, bitmap.height)
            }.getOrNull()
        }
    }
}
