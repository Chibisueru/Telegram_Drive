package io.github.chibisueru.telegramdrive.desktop.media

import dev.g000sha256.tdl.TdlClient
import io.github.chibisueru.telegramdrive.desktop.data.DriveFile
import io.github.chibisueru.telegramdrive.desktop.telegram.Telegram
import io.github.chibisueru.telegramdrive.desktop.telegram.TgFiles
import io.github.chibisueru.telegramdrive.desktop.telegram.get
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves drive files to the built-in players over HTTP on 127.0.0.1, straight from Telegram:
 * each byte range is answered as soon as TDLib has downloaded it, so playback starts at once
 * and seeking jumps the download to the new position (like the Android app's player).
 * A random token in every URL keeps other programs on the computer out.
 */
class StreamServer(private val telegram: Telegram, private val files: TgFiles) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val known = ConcurrentHashMap<String, DriveFile>()
    private val server by lazy {
        RangeServer { path ->
            val parts = path.removePrefix("/f/").split('/')
            val file = if (parts.size == 2 && parts[0] == token) known[parts[1]] else null
            file?.let { TelegramSource(it) }
        }
    }

    /** The address a player opens for [file]. */
    fun urlFor(file: DriveFile): String {
        known[file.id] = file
        return "http://127.0.0.1:${server.port}/f/$token/${file.id}"
    }

    /** Reads a drive file from TDLib's download, waiting for each part to arrive. */
    private inner class TelegramSource(private val file: DriveFile) : RangeSource {
        override val size get() = file.size
        override val contentType get() = file.mimeType
        private val client: TdlClient = runBlocking { telegram.awaitReady() }
        private val tdFile = runBlocking { files.resolve(file.remoteId, file.messageId) }
        private var raf: RandomAccessFile? = null
        private var rafPath = ""
        private var stalledSince = 0L

        override fun onStart(start: Long) {
            if (!tdFile.local.isDownloadingCompleted) runBlocking { client.downloadFile(tdFile.id, PRIORITY, start, 0, false) }
        }

        override fun read(pos: Long, buf: ByteArray, max: Int): Int = runBlocking {
            val id = tdFile.id
            var result = 0
            while (result == 0) {
                val f = client.getFile(id).get()
                val path = f.local.path
                val available = if (f.local.isDownloadingCompleted) file.size - pos
                else client.getFileDownloadedPrefixSize(id, pos).get().size
                if (available > 0 && path.isNotEmpty()) {
                    if (path != rafPath) { raf?.close(); raf = RandomAccessFile(path, "r"); rafPath = path }
                    val r = raf!!
                    r.seek(pos)
                    val n = r.read(buf, 0, minOf(available, max.toLong()).toInt())
                    if (n > 0) { stalledSince = 0L; result = n; continue }
                } else if (!f.local.isDownloadingActive && !f.local.isDownloadingCompleted) {
                    // Another reader moved the download elsewhere, or it stopped: restart it here.
                    client.downloadFile(id, PRIORITY, pos, 0, false)
                }
                val now = System.currentTimeMillis()
                if (stalledSince == 0L) stalledSince = now
                if (now - stalledSince > STALL_TIMEOUT_MS) throw IOException("Telegram download stalled at $pos")
                Thread.sleep(40)
            }
            result
        }

        override fun close() { runCatching { raf?.close() } }
    }

    private companion object {
        const val PRIORITY = 32
        const val STALL_TIMEOUT_MS = 60_000L
    }
}
