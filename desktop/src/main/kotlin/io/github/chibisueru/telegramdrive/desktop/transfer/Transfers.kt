package io.github.chibisueru.telegramdrive.desktop.transfer

import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputThumbnail
import io.github.chibisueru.telegramdrive.desktop.AppDirs
import io.github.chibisueru.telegramdrive.desktop.data.Drive
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.data.SettingsStore
import io.github.chibisueru.telegramdrive.desktop.data.guessMimeType
import io.github.chibisueru.telegramdrive.desktop.data.uniqueFile
import io.github.chibisueru.telegramdrive.desktop.telegram.TgFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

enum class TransferState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class Transfer(
    val id: Long,
    val upload: Boolean,
    val name: String,
    val size: Long,
    val state: TransferState = TransferState.QUEUED,
    val progress: Float = 0f,
    val error: String? = null,
    /** Upload: the local file. */
    val source: File? = null,
    /** Upload: the drive folder to put it in. */
    val folderId: String? = null,
    /** Download: the drive file. */
    val fileId: String? = null,
    /** Download: where it was saved. */
    val savedTo: File? = null,
) {
    val active get() = state == TransferState.QUEUED || state == TransferState.RUNNING
}

/** Uploads and downloads, one at a time, in the order they were added. */
class Transfers(
    private val scope: CoroutineScope,
    private val drive: Drive,
    private val files: TgFiles,
    private val settings: SettingsStore,
) {
    private val ids = AtomicLong(1)
    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    val list: StateFlow<List<Transfer>> = _list
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var current: Pair<Long, Job>? = null

    init {
        scope.launch {
            while (true) {
                val next = _list.value.firstOrNull { it.state == TransferState.QUEUED }
                if (next == null) { wake.receive(); continue }
                runOne(next)
            }
        }
    }

    /** Queues local files for upload into [folderId]. Folders are uploaded with their structure. */
    fun upload(paths: List<File>, folderId: String?) {
        val items = mutableListOf<Transfer>()
        fun add(file: File, target: String?) {
            when {
                file.isDirectory -> {
                    val sub = drive.createFolder(target, file.name)
                    file.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { add(it, sub) }
                }
                file.isFile -> items += Transfer(ids.getAndIncrement(), upload = true, name = file.name, size = file.length(), source = file, folderId = target)
            }
        }
        paths.forEach { add(it, folderId) }
        enqueue(items)
    }

    fun download(fileIds: List<String>) {
        enqueue(fileIds.mapNotNull { drive.file(it) }.map {
            Transfer(ids.getAndIncrement(), upload = false, name = it.name, size = it.size, fileId = it.id)
        })
    }

    private fun enqueue(items: List<Transfer>) {
        if (items.isEmpty()) return
        _list.update { it + items }
        wake.trySend(Unit)
    }

    fun cancel(id: Long) {
        current?.let { (cid, job) -> if (cid == id) job.cancel() }
        set(id) { if (it.active) it.copy(state = TransferState.CANCELLED) else it }
    }

    fun cancelAll() = _list.value.filter { it.active }.forEach { cancel(it.id) }

    fun retry(id: Long) {
        set(id) { it.copy(state = TransferState.QUEUED, progress = 0f, error = null) }
        wake.trySend(Unit)
    }

    fun clearFinished() = _list.update { list -> list.filter { it.active } }

    val activeCount get() = _list.value.count { it.active }

    private fun set(id: Long, change: (Transfer) -> Transfer) =
        _list.update { list -> list.map { if (it.id == id) change(it) else it } }

    private suspend fun runOne(t: Transfer) {
        set(t.id) { it.copy(state = TransferState.RUNNING, progress = 0f) }
        val job = scope.launch {
            try {
                val savedTo = if (t.upload) doUpload(t) else doDownload(t)
                set(t.id) { it.copy(state = TransferState.DONE, progress = 1f, savedTo = savedTo) }
            } catch (e: CancellationException) {
                set(t.id) { it.copy(state = TransferState.CANCELLED) }
                throw e
            } catch (e: Exception) {
                set(t.id) { it.copy(state = TransferState.FAILED, error = e.message ?: "Failed") }
            }
        }
        current = t.id to job
        job.join()
        current = null
    }

    private suspend fun doUpload(t: Transfer): File? {
        val src = t.source ?: error("No file")
        if (!src.exists()) error("The file is gone")
        if (src.length() == 0L) error("The file is empty")
        val staging = File(AppDirs.cache, "upload/${UUID.randomUUID()}")
        try {
            val mime = guessMimeType(t.name)
            val thumb = if (mime.startsWith("image/")) withContext(Dispatchers.IO) { makeThumbnail(src, File(staging, "thumb.jpg")) } else null
            val stored = files.sendDocument(
                chatId = settings.current.chatId,
                path = src.absolutePath,
                caption = t.name,
                thumbnail = thumb,
            ) { p -> set(t.id) { it.copy(progress = p) } }
            drive.addFile(
                DriveFile(
                    id = UUID.randomUUID().toString(), folderId = t.folderId, name = t.name, mimeType = mime,
                    size = src.length(), createdAt = System.currentTimeMillis(),
                    messageId = stored.messageId, remoteId = stored.remoteId, thumbRemoteId = stored.thumbRemoteId,
                )
            )
            return null
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staging.deleteRecursively() }
        }
    }

    private suspend fun doDownload(t: Transfer): File {
        val f = drive.file(t.fileId!!) ?: error("The file no longer exists")
        try {
            val cached = files.download(f.remoteId, priority = 16) { p -> set(t.id) { it.copy(progress = p * 0.97f) } }
            return withContext(Dispatchers.IO) {
                val dir = settings.current.downloadFolder.apply { mkdirs() }
                val dest = uniqueFile(dir, f.name)
                cached.copyTo(dest)
                dest
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { files.cancelDownload(f.remoteId) }
            throw e
        }
    }

    /** Telegram wants JPEG thumbnails of at most 320 px per side. */
    private fun makeThumbnail(src: File, dest: File): InputThumbnail? = runCatching {
        val img = ImageIO.read(src) ?: return null
        val scale = minOf(1.0, 320.0 / maxOf(img.width, img.height))
        val w = (img.width * scale).toInt().coerceAtLeast(1)
        val h = (img.height * scale).toInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, w, h, java.awt.Color.WHITE, null)
            dispose()
        }
        dest.parentFile.mkdirs()
        ImageIO.write(out, "jpg", dest)
        InputThumbnail(InputFileLocal(dest.absolutePath), w, h)
    }.getOrNull()
}
