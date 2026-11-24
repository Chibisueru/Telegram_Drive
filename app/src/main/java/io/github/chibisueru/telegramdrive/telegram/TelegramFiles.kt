package io.github.chibisueru.telegramdrive.telegram

import android.content.Context
import io.github.chibisueru.telegramdrive.data.db.ChunkEntity
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** Reads file bytes back out of Telegram: whole-file downloads, byte-range streams, thumbnails. */
class TelegramFiles(
    private val context: Context,
    private val bot: BotApi,
    private val dao: DriveDao,
    private val http: OkHttpClient,
) {
    /** getFile results are valid for at least an hour; we reuse them for 50 minutes. */
    private val pathCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val thumbGate = Semaphore(4)

    private val viewDir get() = File(context.cacheDir, "view")
    private val thumbDir get() = File(context.filesDir, "thumbs")

    suspend fun urlFor(tgFileId: String): String {
        val cached = pathCache[tgFileId]
        val path = if (cached != null && System.currentTimeMillis() - cached.second < 50 * 60_000) {
            cached.first
        } else {
            bot.getFilePath(tgFileId).also { pathCache[tgFileId] = it to System.currentTimeMillis() }
        }
        return bot.fileUrl(path)
    }

    fun invalidate(tgFileId: String) {
        pathCache.remove(tgFileId)
    }

    /**
     * Opens chunk bytes starting at [offset]. Uses an HTTP Range request when the server
     * honours it and falls back to skipping bytes otherwise. Blocking.
     */
    fun openChunk(url: String, offset: Long): InputStream {
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Download failed: HTTP ${response.code}")
        }
        val stream = ResponseStream(response)
        if (offset > 0 && response.code != 206) {
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = stream.skip(toSkip)
                if (skipped <= 0) {
                    if (stream.read() == -1) throw IOException("Unexpected end of chunk")
                    toSkip--
                } else {
                    toSkip -= skipped
                }
            }
        }
        return stream
    }

    /** Writes the whole file to [out], chunk by chunk, resuming a chunk after a dropped connection. */
    suspend fun downloadTo(file: FileEntity, out: OutputStream, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val chunks = dao.chunks(file.id)
            var done = 0L
            val buffer = ByteArray(256 * 1024)
            for (chunk in chunks) {
                var written = 0L
                var attempts = 0
                while (written < chunk.size) {
                    currentCoroutineContext().ensureActive()
                    try {
                        openChunk(urlFor(chunk.tgFileId), written).use { input ->
                            while (written < chunk.size) {
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), chunk.size - written).toInt())
                                if (n == -1) break
                                out.write(buffer, 0, n)
                                written += n
                                onProgress(done + written, file.size)
                                currentCoroutineContext().ensureActive()
                            }
                        }
                        if (written < chunk.size) throw IOException("Chunk ${chunk.idx} ended early")
                    } catch (e: IOException) {
                        if (++attempts > 4) throw e
                        invalidate(chunk.tgFileId)
                        kotlinx.coroutines.delay(1000L * attempts)
                    }
                }
                done += chunk.size
            }
            out.flush()
        }

    fun cachedFile(file: FileEntity): File =
        File(File(viewDir, file.id), safeName(file.name))

    /** Downloads the file into the app cache (once) so it can be previewed or shared. */
    suspend fun cacheForViewing(file: FileEntity, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dest = cachedFile(file)
        if (dest.exists() && dest.length() == file.size) return@withContext dest
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            tmp.outputStream().buffered().use { out ->
                downloadTo(file, out) { done, total -> if (total > 0) onProgress(done.toFloat() / total) }
            }
            if (!tmp.renameTo(dest)) throw IOException("Could not write cache file")
        } finally {
            tmp.delete()
        }
        dest
    }

    fun thumbFile(fileId: String) = File(thumbDir, "$fileId.jpg")

    /** Returns the local thumbnail, downloading it from Telegram if needed. */
    suspend fun thumbnail(file: FileEntity): File? {
        val tgId = file.thumbFileId ?: return null
        val dest = thumbFile(file.id)
        if (dest.exists()) return dest
        return thumbGate.withPermit {
            withContext(Dispatchers.IO) {
                if (dest.exists()) return@withContext dest
                runCatching {
                    thumbDir.mkdirs()
                    val tmp = File(thumbDir, "${file.id}.tmp")
                    openChunk(urlFor(tgId), 0).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(dest)
                    dest
                }.getOrNull()
            }
        }
    }

    fun clearViewCache() {
        viewDir.deleteRecursively()
    }

    fun clearThumbnails() {
        thumbDir.deleteRecursively()
    }

    fun deleteLocalCopies(fileIds: Collection<String>) {
        fileIds.forEach {
            File(viewDir, it).deleteRecursively()
            thumbFile(it).delete()
        }
    }

    fun locate(chunks: List<ChunkEntity>, chunkSize: Long, position: Long): Pair<Int, Long> {
        val idx = (position / chunkSize).toInt().coerceAtMost(chunks.lastIndex)
        return idx to (position - idx * chunkSize)
    }

    private class ResponseStream(private val response: Response) : FilterInputStream(response.body.byteStream()) {
        override fun close() {
            super.close()
            response.close()
        }
    }

    companion object {
        fun safeName(name: String) = name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_").ifBlank { "file" }
    }
}
