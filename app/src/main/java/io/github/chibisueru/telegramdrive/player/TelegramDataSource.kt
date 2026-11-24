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
import io.github.chibisueru.telegramdrive.data.db.ChunkEntity
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.telegram.TelegramFiles
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream

/**
 * Lets ExoPlayer read a chunked Telegram file as if it were one continuous stream, so audio
 * and video play (and seek) without downloading the whole file first.
 *
 * URIs look like `tgdrive://<fileId>`.
 */
class TelegramDataSource(
    private val dao: DriveDao,
    private val files: TelegramFiles,
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val dao: DriveDao, private val files: TelegramFiles) : DataSource.Factory {
        override fun createDataSource(): DataSource = TelegramDataSource(dao, files)
    }

    companion object {
        const val SCHEME = "tgdrive"
        fun uriFor(fileId: String): Uri = Uri.parse("$SCHEME://$fileId")
    }

    private var uri: Uri? = null
    private var file: FileEntity? = null
    private var chunks: List<ChunkEntity> = emptyList()
    private var stream: InputStream? = null
    private var chunkIndex = 0
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.host ?: throw IOException("Bad URI ${dataSpec.uri}")
        val (f, c) = runBlocking { dao.file(id) to dao.chunks(id) }
        if (f == null || c.isEmpty()) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        file = f
        chunks = c
        if (dataSpec.position > f.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else f.size - position
        openAt(position)
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    private fun openAt(pos: Long) {
        stream?.close()
        stream = null
        val f = file!!
        if (pos >= f.size) return
        val (idx, offset) = files.locate(chunks, f.chunkSize, pos)
        chunkIndex = idx
        val chunk = chunks[idx]
        stream = try {
            files.openChunk(runBlocking { files.urlFor(chunk.tgFileId) }, offset)
        } catch (e: IOException) {
            // A stale file path is the usual culprit; refresh it once.
            files.invalidate(chunk.tgFileId)
            files.openChunk(runBlocking { files.urlFor(chunk.tgFileId) }, offset)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        var n = stream?.read(buffer, offset, toRead) ?: -1
        if (n == -1) {
            // End of the current chunk: continue with the next one.
            if (chunkIndex >= chunks.lastIndex) return C.RESULT_END_OF_INPUT
            openAt(position)
            n = stream?.read(buffer, offset, toRead) ?: -1
            if (n == -1) return C.RESULT_END_OF_INPUT
        }
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close()
        } finally {
            stream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
