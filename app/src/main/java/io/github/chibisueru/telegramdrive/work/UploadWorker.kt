package io.github.chibisueru.telegramdrive.work

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.chibisueru.telegramdrive.container
import io.github.chibisueru.telegramdrive.data.db.ChunkEntity
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.telegram.BotApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

/**
 * Uploads one picked file: splits it into [BotApi.CHUNK_SIZE] pieces, posts each as a
 * document to the storage channel, uploads a small thumbnail for photos/videos, then records
 * the file in the index.
 *
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

        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Uploading", name, 0f)) }

        val chatId = c.settings.current().chatId
        val fileId = UUID.randomUUID().toString()
        val uploaded = mutableListOf<ChunkEntity>()
        var thumbMessage: Long? = null

        return try {
            coroutineScope {
                val reporter = launch {
                    while (isActive) {
                        setProgress(workDataOf(TransferKeys.PROGRESS to progress, TransferKeys.NAME to name))
                        runCatching { setForeground(transferForegroundInfo(applicationContext, notificationId, "Uploading", name, progress)) }
                        delay(700)
                    }
                }
                var total = 0L
                withContext(Dispatchers.IO) {
                    applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(BotApi.CHUNK_SIZE)
                        var idx = 0
                        while (true) {
                            val n = readFully(input, buffer)
                            if (n <= 0) break
                            val base = total
                            val sent = c.bot.sendDocument(chatId, "$fileId.part$idx", buffer, n) { done ->
                                if (declaredSize > 0) progress = ((base + done).toFloat() / declaredSize).coerceAtMost(0.99f)
                            }
                            uploaded += ChunkEntity(fileId, idx, sent.fileId, sent.messageId, n.toLong())
                            total += n
                            idx++
                            if (n < buffer.size) break
                        }
                    } ?: error("Can't open $name")
                }
                if (total == 0L) error("$name is empty")

                val thumb = makeThumbnail(uri, mime)
                val thumbSent = thumb?.let {
                    runCatching { c.bot.sendDocument(chatId, "$fileId.thumb.jpg", it, it.size) }.getOrNull()
                }
                thumbMessage = thumbSent?.messageId
                if (thumb != null && thumbSent != null) {
                    withContext(Dispatchers.IO) {
                        c.files.thumbFile(fileId).apply { parentFile?.mkdirs() }.writeBytes(thumb)
                    }
                }

                c.dao.insertFileWithChunks(
                    FileEntity(
                        id = fileId, folderId = folderId, name = name, mimeType = mime, size = total,
                        createdAt = System.currentTimeMillis(), chunkSize = BotApi.CHUNK_SIZE.toLong(),
                        thumbFileId = thumbSent?.fileId, thumbMessageId = thumbSent?.messageId,
                    ),
                    uploaded,
                )
                c.indexSync.requestSync()
                reporter.cancel()
            }
            Result.success(workDataOf(TransferKeys.NAME to name, TransferKeys.PROGRESS to 1f))
        } catch (e: CancellationException) {
            cleanup(chatId, uploaded, thumbMessage)
            throw e
        } catch (e: Exception) {
            cleanup(chatId, uploaded, thumbMessage)
            Result.success(workDataOf(TransferKeys.NAME to name, TransferKeys.ERROR to (e.message ?: e.javaClass.simpleName)))
        } finally {
            runCatching {
                applicationContext.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private suspend fun cleanup(chatId: String, uploaded: List<ChunkEntity>, thumbMessage: Long?) =
        withContext(NonCancellable) {
            val ids = uploaded.map { it.messageId } + listOfNotNull(thumbMessage)
            if (ids.isNotEmpty()) runCatching { applicationContext.container.bot.deleteMessages(chatId, ids) }
        }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n == -1) break
            read += n
        }
        return read
    }

    private suspend fun makeThumbnail(uri: android.net.Uri, mime: String): ByteArray? {
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = applicationContext.contentResolver.loadThumbnail(uri, Size(320, 320), null)
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
            }.getOrNull()
        }
    }
}
