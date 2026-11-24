package io.github.chibisueru.telegramdrive.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.telegram.TgFiles
import io.github.chibisueru.telegramdrive.util.guessMimeType
import io.github.chibisueru.telegramdrive.util.safeFileName
import java.io.File
import io.github.chibisueru.telegramdrive.data.db.TransferEntity
import io.github.chibisueru.telegramdrive.transfer.TransferManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

const val TRASH_DAYS = 30
/** Private copies of files shared into the app, waiting to be uploaded. */
const val INCOMING_DIR = "incoming"

class DriveRepository(
    private val context: Context,
    val dao: DriveDao,
    private val files: TgFiles,
    private val settings: SettingsStore,
    private val indexSync: IndexSync,
    private val transfers: TransferManager,
) {

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

    // ---- Trash: deleted items stay restorable for 30 days ----

    /** Moves files and folders (with everything inside) to the trash. */
    suspend fun trash(fileIds: List<String>, folderIds: List<String>) {
        val now = System.currentTimeMillis()
        val allFolders = dao.allFolders()
        // Only items not already in the trash; children of a trashed folder share its timestamp.
        val liveFolderIds = folderIds.flatMap { descendantsOf(it, allFolders) }.distinct()
            .filter { id -> allFolders.firstOrNull { it.id == id }?.deletedAt == null }
        val innerFiles = liveFolderIds.chunked(500).flatMap { dao.filesInFolders(it) }.filter { it.deletedAt == null }.map { it.id }
        val allFileIds = (fileIds + innerFiles).distinct()
        liveFolderIds.chunked(400).forEach { dao.trashFolders(it, folderIds, now) }
        allFileIds.chunked(400).forEach { dao.trashFiles(it, fileIds, now) }
        indexSync.requestSync()
    }

    /** Takes trashed items back out. Items whose folder no longer exists go to the root. */
    suspend fun restore(fileIds: List<String>, folderIds: List<String>) {
        val allFolders = dao.allFolders()
        val byId = allFolders.associateBy { it.id }
        val restoredFolders = mutableListOf<String>()
        for (rootId in folderIds) {
            val root = byId[rootId] ?: continue
            val sameBatch = descendantsOf(rootId, allFolders).filter { byId[it]?.deletedAt == root.deletedAt }
            restoredFolders += sameBatch
            val files = sameBatch.chunked(500).flatMap { dao.filesInFolders(it) }.filter { it.deletedAt == root.deletedAt }
            dao.untrashFiles(files.map { it.id })
        }
        restoredFolders.chunked(500).forEach { dao.untrashFolders(it) }
        fileIds.chunked(500).forEach { dao.untrashFiles(it) }
        // Re-home items whose parent is gone or still in the trash.
        val after = dao.allFolders().associateBy { it.id }
        fun parentMissing(parent: String?) = parent != null && (after[parent] == null || after[parent]?.deletedAt != null)
        folderIds.forEach { id -> if (parentMissing(after[id]?.parentId)) dao.moveFolder(id, null) }
        fileIds.forEach { id -> dao.file(id)?.let { if (parentMissing(it.folderId)) dao.moveFile(id, null) } }
        indexSync.requestSync()
    }

    /** Deletes trashed items for good, including their messages in Telegram. */
    suspend fun deleteForever(fileIds: List<String>, folderIds: List<String>) {
        val allFolders = dao.allFolders()
        val folderTree = folderIds.flatMap { descendantsOf(it, allFolders) }.distinct()
        val files = folderTree.chunked(500).flatMap { dao.filesInFolders(it) } +
            fileIds.mapNotNull { dao.file(it) }
        indexSync.markPurged(folderTree + files.map { it.id })
        deleteFiles(files.distinctBy { it.id })
        folderTree.chunked(500).forEach { dao.deleteFolders(it) }
        indexSync.requestSync()
    }

    suspend fun emptyTrash() {
        deleteForever(
            fileIds = dao.trashedFilesBefore(Long.MAX_VALUE).map { it.id },
            folderIds = dao.trashedFoldersBefore(Long.MAX_VALUE).map { it.id },
        )
    }

    /** Called at start-up: removes items that have been in the trash for over 30 days. */
    suspend fun purgeOldTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000
        val files = dao.trashedFilesBefore(cutoff).map { it.id }
        val folders = dao.trashedFoldersBefore(cutoff).map { it.id }
        if (files.isNotEmpty() || folders.isNotEmpty()) deleteForever(files, folders)
    }

    private suspend fun deleteFiles(list: List<FileEntity>) {
        if (list.isEmpty()) return
        list.map { it.id }.chunked(500).forEach { dao.deleteFiles(it) }
        indexSync.requestSync()
        // As the channel's owner the user can delete messages of any age.
        files.deleteMessages(settings.current().chatId, list.map { it.messageId })
    }

    // ---- Favorites, recent, bulk moves ----

    suspend fun setStarred(fileIds: List<String>, folderIds: List<String>, starred: Boolean) {
        fileIds.chunked(500).forEach { dao.starFiles(it, starred) }
        folderIds.chunked(500).forEach { dao.starFolders(it, starred) }
        indexSync.requestSync()
    }

    suspend fun markOpened(fileId: String) = dao.markOpened(fileId, System.currentTimeMillis())

    /** Moves several items; folders that would end up inside themselves are skipped. Returns how many were skipped. */
    suspend fun moveAll(fileIds: List<String>, folderIds: List<String>, target: String?): Int {
        val allFolders = dao.allFolders()
        var skipped = 0
        folderIds.forEach { id ->
            if (target != null && target in descendantsOf(id, allFolders)) skipped++ else dao.moveFolder(id, target)
        }
        fileIds.forEach { dao.moveFile(it, target) }
        indexSync.requestSync()
        return skipped
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

    /**
     * Queues picked files for upload. Each file's own name, size and type are read here and
     * stored with its queue entry, so every file keeps its own name.
     */
    suspend fun enqueueUploads(uris: List<Uri>, folderId: String?) {
        val resolver = context.contentResolver
        val items = withContext(Dispatchers.IO) {
            uris.map { uri ->
                // Lets the transfer service read the file later, even after the app was closed.
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
                TransferEntity(upload = true, name = name, mimeType = mime, size = size, sourceUri = uri.toString(), folderId = folderId)
            }
        }
        transfers.enqueue(items)
    }

    /**
     * Files shared from another app. Android only lends us access to them for a short time, so
     * they're copied into the app first; the copy is deleted once uploaded.
     */
    suspend fun importShared(uris: List<Uri>, folderId: String?): Int {
        val resolver = context.contentResolver
        val items = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                runCatching {
                    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.let { name = it }
                    }
                    val dest = File(File(context.filesDir, INCOMING_DIR), UUID.randomUUID().toString()).resolve(safeFileName(name))
                    dest.parentFile?.mkdirs()
                    resolver.openInputStream(uri)!!.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    val mime = resolver.getType(uri)?.takeIf { it != "application/octet-stream" } ?: guessMimeType(name)
                    TransferEntity(upload = true, name = name, mimeType = mime, size = dest.length(), sourceUri = Uri.fromFile(dest).toString(), folderId = folderId)
                }.getOrNull()
            }
        }
        transfers.enqueue(items)
        return items.size
    }

    suspend fun enqueueDownload(file: FileEntity) {
        transfers.enqueue(listOf(TransferEntity(upload = false, name = file.name, mimeType = file.mimeType, size = file.size, fileId = file.id)))
    }
}
