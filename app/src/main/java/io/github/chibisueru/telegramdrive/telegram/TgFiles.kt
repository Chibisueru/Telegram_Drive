package io.github.chibisueru.telegramdrive.telegram

import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputDocument
import dev.g000sha256.tdl.dto.InputFileLocal
import dev.g000sha256.tdl.dto.InputMessageDocument
import dev.g000sha256.tdl.dto.InputThumbnail
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.UpdateMessageSendFailed
import dev.g000sha256.tdl.dto.UpdateMessageSendSucceeded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import dev.g000sha256.tdl.dto.File as TdFile

/** What a sent document became on Telegram's side. */
data class StoredDocument(val messageId: Long, val remoteId: String, val thumbRemoteId: String?)

/** File transfers through the user's account: one Telegram message per file, up to 2 GB (4 GB Premium). */
class TgFiles(private val telegram: Telegram) {

    /**
     * Posts a local file as a document and suspends until Telegram has it.
     * [onProgress] receives 0..1. Cancelling the coroutine cancels the upload.
     */
    suspend fun sendDocument(
        chatId: Long,
        path: String,
        caption: String? = null,
        thumbnail: InputThumbnail? = null,
        onProgress: (Float) -> Unit = {},
    ): StoredDocument = coroutineScope {
        val c = telegram.awaitReady()
        // Subscribe before sending: TDLib's update flows don't replay.
        val outcomes = Channel<Any>(Channel.UNLIMITED)
        val okListener = launch(start = CoroutineStart.UNDISPATCHED) { c.messageSendSucceededUpdates.collect { outcomes.send(it) } }
        val failListener = launch(start = CoroutineStart.UNDISPATCHED) { c.messageSendFailedUpdates.collect { outcomes.send(it) } }
        val watchedFileId = AtomicInteger(-1)
        val progressListener = launch(start = CoroutineStart.UNDISPATCHED) {
            c.fileUpdates.collect { u ->
                val f = u.file
                if (f.id == watchedFileId.get()) {
                    val total = if (f.size > 0) f.size else f.expectedSize
                    if (total > 0) onProgress((f.remote.uploadedSize.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }

        var pending: Message? = null
        try {
            val temp = c.sendMessage(
                chatId = chatId,
                inputMessageContent = InputMessageDocument(
                    document = InputDocument(
                        document = InputFileLocal(path = path),
                        thumbnail = thumbnail,
                        disableContentTypeDetection = true,
                    ),
                    caption = caption?.let { FormattedText(text = it, entities = emptyArray()) },
                ),
            ).get()
            pending = temp
            watchedFileId.set((temp.content as? MessageDocument)?.document?.document?.id ?: -1)

            while (true) {
                when (val u = outcomes.receive()) {
                    is UpdateMessageSendSucceeded -> if (u.oldMessageId == temp.id) {
                        pending = null
                        val doc = (u.message.content as? MessageDocument)?.document
                            ?: throw TelegramException("Telegram stored the file in an unexpected form")
                        return@coroutineScope StoredDocument(u.message.id, doc.document.remote.id, doc.thumbnail?.file?.remote?.id)
                    }
                    is UpdateMessageSendFailed -> if (u.oldMessageId == temp.id) {
                        pending = null
                        throw TelegramException(friendlyTdlError(u.error.message), u.error.code)
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        } finally {
            okListener.cancel(); failListener.cancel(); progressListener.cancel()
            // Deleting a message that is still sending cancels its upload.
            pending?.let { p -> withContext(NonCancellable) { runCatching { c.deleteMessages(chatId, longArrayOf(p.id), true) } } }
        }
    }

    suspend fun resolve(remoteId: String): TdFile = telegram.awaitReady().getRemoteFile(remoteId).get()

    /** Downloads the whole file (or reuses TDLib's local copy) and returns it. */
    suspend fun download(remoteId: String, priority: Int = 16, onProgress: (Float) -> Unit = {}): File = coroutineScope {
        val c = telegram.awaitReady()
        val file = c.getRemoteFile(remoteId).get()
        if (file.isComplete()) return@coroutineScope File(file.local.path)

        val done = CompletableDeferred<TdFile>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            var sawActive = false
            c.fileUpdates.collect { u ->
                val f = u.file
                if (f.id != file.id) return@collect
                val total = if (f.size > 0) f.size else f.expectedSize
                if (total > 0) onProgress((f.local.downloadedSize.toFloat() / total).coerceIn(0f, 1f))
                when {
                    f.isComplete() -> done.complete(f)
                    f.local.isDownloadingActive -> sawActive = true
                    sawActive -> done.completeExceptionally(TelegramException("The download stopped"))
                }
            }
        }
        try {
            val started = c.downloadFile(file.id, priority, 0, 0, false).get()
            if (started.isComplete()) done.complete(started)
            File(done.await().local.path)
        } finally {
            watcher.cancel()
        }
    }

    /** Small files (thumbnails): downloads synchronously; null if unavailable. */
    suspend fun downloadSmall(remoteId: String): File? = runCatching {
        val c = telegram.awaitReady()
        val file = c.getRemoteFile(remoteId).get()
        val done = if (file.isComplete()) file else c.downloadFile(file.id, 1, 0, 0, true).get()
        File(done.local.path).takeIf { it.exists() }
    }.getOrNull()

    suspend fun cancelDownload(remoteId: String) {
        runCatching {
            val c = telegram.awaitReady(5_000)
            c.cancelDownloadFile(c.getRemoteFile(remoteId).get().id, false)
        }
    }

    suspend fun deleteMessages(chatId: Long, ids: List<Long>) {
        val c = telegram.awaitReady()
        ids.chunked(100).forEach { batch -> runCatching { c.deleteMessages(chatId, batch.toLongArray(), true) } }
    }

    /** Deletes every file TDLib has cached on this phone (thumbnails, previews, streamed media). */
    suspend fun clearCache() {
        telegram.awaitReady().optimizeStorage(
            size = 0, ttl = 0, count = 0, immunityDelay = 0,
            fileTypes = emptyArray(), chatIds = LongArray(0), excludeChatIds = LongArray(0),
            returnDeletedFileStatistics = false, chatLimit = 0,
        ).get()
    }

    private fun TdFile.isComplete() =
        local.isDownloadingCompleted && local.path.isNotEmpty() && File(local.path).exists()
}
