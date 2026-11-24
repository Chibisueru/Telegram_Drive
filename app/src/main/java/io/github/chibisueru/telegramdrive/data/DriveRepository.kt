package io.github.chibisueru.telegramdrive.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.telegram.BotApi
import io.github.chibisueru.telegramdrive.telegram.TelegramFiles
import io.github.chibisueru.telegramdrive.util.guessMimeType
import io.github.chibisueru.telegramdrive.work.DownloadWorker
import io.github.chibisueru.telegramdrive.work.TransferKeys
import io.github.chibisueru.telegramdrive.work.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class DriveRepository(
    private val context: Context,
    val dao: DriveDao,
    private val bot: BotApi,
    private val files: TelegramFiles,
    private val settings: SettingsStore,
    private val indexSync: IndexSync,
) {
    private val workManager get() = WorkManager.getInstance(context)

    suspend fun createFolder(parentId: String?, name: String) {
        dao.insertFolder(FolderEntity(UUID.randomUUID().toString(), parentId, name.trim(), System.currentTimeMillis()))
        indexSync.requestSync()
    }

    suspend fun renameFolder(id: String, name: String) {
        dao.renameFolder(id, name.trim()); indexSync.requestSync()
    }

    suspend fun renameFile(id: String, name: String) {
        dao.renameFile(id, name.trim()); indexSync.requestSync()
    }

    suspend fun moveFile(id: String, target: String?) {
        dao.moveFile(id, target); indexSync.requestSync()
    }

    /** Returns false if [target] is the folder itself or one of its descendants. */
    suspend fun moveFolder(id: String, target: String?): Boolean {
        if (target != null && descendantsOf(id, dao.allFolders()).contains(target)) return false
        dao.moveFolder(id, target); indexSync.requestSync()
        return true
    }

    suspend fun deleteFile(file: FileEntity) = deleteFiles(listOf(file))

    suspend fun deleteFolder(folderId: String) {
        val folderIds = descendantsOf(folderId, dao.allFolders()).toList()
        val contained = folderIds.chunked(500).flatMap { dao.filesInFolders(it) }
        deleteFiles(contained)
        folderIds.chunked(500).forEach { dao.deleteFolders(it) }
        indexSync.requestSync()
    }

    private suspend fun deleteFiles(list: List<FileEntity>) {
        if (list.isEmpty()) return
        val ids = list.map { it.id }
        val messageIds = ids.chunked(500).flatMap { dao.chunksOf(it) }.map { it.messageId } +
            list.mapNotNull { it.thumbMessageId }
        ids.chunked(500).forEach {
            dao.deleteChunks(it)
            dao.deleteFiles(it)
        }
        files.deleteLocalCopies(ids)
        indexSync.requestSync()
        // Remove the bytes from Telegram too (best effort; very old messages may be refused).
        val chatId = settings.current().chatId
        withContext(Dispatchers.IO) { bot.deleteMessages(chatId, messageIds) }
    }

    /** The folder plus every folder nested below it. */
    private fun descendantsOf(rootId: String, all: List<FolderEntity>): Set<String> {
        val children = all.groupBy { it.parentId }
        val result = linkedSetOf(rootId)
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) {
            children[queue.removeFirst()].orEmpty().forEach { if (result.add(it.id)) queue += it.id }
        }
        return result
    }

    fun enqueueUpload(uri: Uri, folderId: String?) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name = "file"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: guessMimeType(name)
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(
                TransferKeys.URI to uri.toString(),
                TransferKeys.FOLDER to (folderId ?: ""),
                TransferKeys.NAME to name,
                TransferKeys.MIME to mime,
                TransferKeys.SIZE to size,
            ))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .addTag(TransferKeys.TAG)
            .addTag(TransferKeys.TAG_UPLOAD)
            .addTag(TransferKeys.NAME_TAG_PREFIX + name)
            .build()
        // One upload at a time keeps us well inside Telegram's flood limits.
        workManager.enqueueUniqueWork("uploads", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun enqueueDownload(file: FileEntity) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(TransferKeys.FILE_ID to file.id, TransferKeys.NAME to file.name))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .addTag(TransferKeys.TAG)
            .addTag(TransferKeys.TAG_DOWNLOAD)
            .addTag(TransferKeys.NAME_TAG_PREFIX + file.name)
            .build()
        workManager.enqueueUniqueWork("downloads", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    fun cancelTransfer(id: UUID) = workManager.cancelWorkById(id)

    fun clearFinishedTransfers() = workManager.pruneWork()

    fun cancelAllTransfers() = workManager.cancelAllWorkByTag(TransferKeys.TAG)
}
