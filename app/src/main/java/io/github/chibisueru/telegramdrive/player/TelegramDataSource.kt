@file:OptIn(UnstableApi::class)

package io.github.chibisueru.telegramdrive.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.telegram.Telegram
import io.github.chibisueru.telegramdrive.telegram.get
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Lets ExoPlayer play a Telegram file while it downloads. Opening at a position asks TDLib to
 * download from that byte onwards; reads wait until TDLib has the bytes on disk.
 *
 * URIs look like `tgdrive://<fileId>`.
 */
class TelegramDataSource(
    private val dao: DriveDao,
    private val telegram: Telegram,
    private val files: io.github.chibisueru.telegramdrive.telegram.TgFiles,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val dao: DriveDao,
        private val telegram: Telegram,
        private val files: io.github.chibisueru.telegramdrive.telegram.TgFiles,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(dao, telegram, files)
    }

    companion object {
        const val SCHEME = "tgdrive"
        fun uriFor(fileId: String): Uri = Uri.parse("$SCHEME://$fileId")
        private const val PRIORITY = 32
        private const val STALL_TIMEOUT_MS = 60_000L
    }

    private var uri: Uri? = null
    private var tdFileId = 0
    private var size = 0L
    private var position = 0L
    private var bytesRemaining = 0L
    /** Bytes known to be on disk start at [position] and end before this offset. */
    private var availableUntil = 0L
    private var raf: RandomAccessFile? = null
    private var rafPath = ""
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw IOException("Bad URI ${dataSpec.uri}")
        val entity = runBlocking { dao.file(id) }
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        size = entity.size
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        availableUntil = position
        runBlocking {
            val c = telegram.awaitReady()
            tdFileId = files.resolve(entity.remoteId, entity.messageId).id
            c.downloadFile(tdFileId, PRIORITY, position, 0, false).get()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (position >= availableUntil) waitForData()
        val toRead = minOf(length.toLong(), bytesRemaining, availableUntil - position).toInt()
        val file = raf ?: throw IOException("File not open")
        file.seek(position)
        val n = file.read(buffer, offset, toRead)
        if (n == -1) return C.RESULT_END_OF_INPUT
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    /** Blocks until TDLib has at least one byte at [position] on disk. */
    private fun waitForData() {
        val deadline = System.currentTimeMillis() + STALL_TIMEOUT_MS
        runBlocking {
            val c = telegram.awaitReady()
            while (true) {
                val f = c.getFile(tdFileId).get()
                val path = f.local.path
                val available = if (f.local.isDownloadingCompleted) size - position
                else c.getFileDownloadedPrefixSize(tdFileId, position).get().size
                if (available > 0 && path.isNotEmpty()) {
                    if (path != rafPath) {
                        raf?.close()
                        raf = RandomAccessFile(path, "r")
                        rafPath = path
                    }
                    availableUntil = position + available
                    return@runBlocking
                }
                if (!f.local.isDownloadingActive && !f.local.isDownloadingCompleted) {
                    // Another reader moved the download elsewhere, or it stopped: restart it here.
                    c.downloadFile(tdFileId, PRIORITY, position, 0, false)
                }
                if (System.currentTimeMillis() > deadline) throw IOException("Telegram download stalled")
                Thread.sleep(50)
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            raf?.close()
        } finally {
            raf = null
            rafPath = ""
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
