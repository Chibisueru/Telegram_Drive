package io.github.chibisueru.telegramdrive.transfer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.core.net.toUri
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputThumbnail
import io.github.chibisueru.telegramdrive.data.INCOMING_DIR
import io.github.chibisueru.telegramdrive.data.IndexSync
import io.github.chibisueru.telegramdrive.data.SettingsStore
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import io.github.chibisueru.telegramdrive.telegram.TgFiles
import io.github.chibisueru.telegramdrive.util.safeFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Does the actual work of one transfer. Progress is reported as 0..1. */
class TransferRunner(
    private val context: Context,
    private val dao: DriveDao,
    private val files: TgFiles,
    private val settings: SettingsStore,
    private val indexSync: IndexSync,
) {
    suspend fun run(t: TransferEntity, onProgress: (Float) -> Unit) =
        if (t.upload) upload(t, onProgress) else download(t, onProgress)

    /**
     * Uploads one picked file as a single document message (up to 2 GB, 4 GB with Premium).
     * TDLib uploads from a path, so the file is first copied into the app's cache (first 5 %).
     */
    private suspend fun upload(t: TransferEntity, onProgress: (Float) -> Unit) {
        val uri = t.sourceUri!!.toUri()
        val stagingDir = File(context.cacheDir, "upload/${UUID.randomUUID()}")
        val ownCopy = ownCopyOf(t)
        try {
            // Shared files were already copied into the app; picked files are copied now.
            val staged = ownCopy ?: stage(uri, File(stagingDir, safeFileName(t.name)), t.size) { onProgress(0.05f * it) }
            if (!staged.exists()) error("The file is gone. Share or pick it again.")
            if (staged.length() == 0L) error("The file is empty")
            val thumb = makeThumbnail(uri, staged, t.mimeType, File(stagingDir, "thumb.jpg"))
            val stored = files.sendDocument(
                chatId = settings.current().chatId,
                path = staged.absolutePath,
                caption = t.name,
                thumbnail = thumb,
            ) { onProgress(0.05f + 0.95f * it) }
            dao.insertFile(
                FileEntity(
                    id = UUID.randomUUID().toString(), folderId = t.folderId, name = t.name, mimeType = t.mimeType,
                    size = staged.length(), createdAt = System.currentTimeMillis(),
                    messageId = stored.messageId, remoteId = stored.remoteId, thumbRemoteId = stored.thumbRemoteId,
                )
            )
            indexSync.requestSync()
            discardSource(t)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { stagingDir.deleteRecursively() }
        }
    }

    /** Saves a drive file to Downloads/Telegram Drive (no storage permission needed). */
    private suspend fun download(t: TransferEntity, onProgress: (Float) -> Unit) {
        val file = dao.file(t.fileId!!) ?: error("The file no longer exists")
        try {
            val local = files.download(file.remoteId, priority = 8, messageId = file.messageId) { onProgress(it * 0.97f) }
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val target = resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.MIME_TYPE, file.mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Telegram Drive")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    },
                ) ?: error("Can't create the file in Downloads")
                try {
                    resolver.openOutputStream(target)!!.use { out -> local.inputStream().use { it.copyTo(out) } }
                    resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(target, null, null)
                    throw e
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { files.cancelDownload(file.remoteId) }
            throw e
        }
    }

    /** The app's own copy of a shared file, if this upload came from the share menu. */
    private fun ownCopyOf(t: TransferEntity): File? {
        val uri = t.sourceUri?.toUri() ?: return null
        if (uri.scheme != "file") return null
        val file = File(uri.path ?: return null)
        val incoming = File(context.filesDir, INCOMING_DIR)
        return file.takeIf { it.canonicalPath.startsWith(incoming.canonicalPath) }
    }

    /** Called when an upload is finished, cancelled or removed: frees what it was holding on to. */
    fun discardSource(t: TransferEntity) {
        val uri = t.sourceUri?.toUri() ?: return
        ownCopyOf(t)?.let { it.parentFile?.deleteRecursively(); return }
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun stage(uri: Uri, dest: File, declaredSize: Long, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Can't open the file. It may have been moved or deleted.")
            input.use { inp ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        copied += n
                        if (declaredSize > 0) onProgress(copied.toFloat() / declaredSize)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            dest
        }

    private suspend fun makeThumbnail(uri: Uri, local: File, mime: String, dest: File): InputThumbnail? {
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                // Telegram wants JPEG thumbnails of at most 320 px per side.
                val size = Size(320, 320)
                val bitmap = when {
                    uri.scheme == "content" -> context.contentResolver.loadThumbnail(uri, size, null)
                    mime.startsWith("video/") -> ThumbnailUtils.createVideoThumbnail(local, size, null)
                    else -> ThumbnailUtils.createImageThumbnail(local, size, null)
                }
                dest.parentFile?.mkdirs()
                dest.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                InputThumbnail(InputFileLocal(dest.absolutePath), bitmap.width, bitmap.height)
            }.getOrNull()
        }
    }
}
