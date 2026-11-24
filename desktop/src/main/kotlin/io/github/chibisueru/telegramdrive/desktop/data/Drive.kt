package io.github.chibisueru.telegramdrive.desktop.data

import dev.g000sha256.tdl.dto.MessageDocument
import io.github.chibisueru.telegramdrive.desktop.AppDirs
import io.github.chibisueru.telegramdrive.desktop.telegram.DriveProvisioner
import io.github.chibisueru.telegramdrive.desktop.telegram.Telegram
import io.github.chibisueru.telegramdrive.desktop.telegram.TgFiles
import io.github.chibisueru.telegramdrive.desktop.telegram.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

const val TRASH_DAYS = 30

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * The folder tree and file list. Telegram has no folders, so this index is kept locally and,
 * after every change, posted to the drive channel as a JSON document that the channel
 * description points at, exactly like the Android app does. Changes from other devices are
 * merged in before posting (a three-way merge against the last index this computer posted or
 * adopted: whatever only one side changed comes from that side; if both changed the same item,
 * this computer wins) and picked up by [refreshIfChanged].
 */
@OptIn(FlowPreview::class)
class Drive(
    private val scope: CoroutineScope,
    private val telegram: Telegram,
    private val files: TgFiles,
    private val settings: SettingsStore,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val indexFile = File(AppDirs.data, "index.json")
    /** Exists while local changes haven't been posted yet (survives restarts). */
    private val pendingFile = File(AppDirs.data, "index.pending")
    /** The index as of the last post or adoption: the common ancestor for merges. */
    private val baseFile = File(AppDirs.data, "index.base.json")

    private val _index = MutableStateFlow(load())
    val index: StateFlow<DriveIndex> = _index

    private val syncMutex = Mutex()
    private val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState

    init {
        scope.launch { trigger.debounce(2_500).collect { runCatching { syncNow() } } }
    }

    private fun load(): DriveIndex = runCatching { json.decodeFromString<DriveIndex>(indexFile.readText()) }.getOrDefault(DriveIndex())

    private fun persist(idx: DriveIndex) {
        runCatching {
            val tmp = File(indexFile.parentFile, "index.json.tmp")
            tmp.writeText(json.encodeToString(idx))
            if (!tmp.renameTo(indexFile)) { indexFile.delete(); tmp.renameTo(indexFile) }
        }
    }

    /** Applies a change locally and schedules posting it to Telegram. */
    @Synchronized
    private fun mutate(change: (DriveIndex) -> DriveIndex) {
        val next = change(_index.value)
        _index.value = next
        persist(next)
        pendingFile.createNewFile()
        trigger.tryEmit(Unit)
    }

    @Synchronized
    private fun replace(idx: DriveIndex) {
        _index.value = idx
        persist(idx)
    }

    private val hasPending get() = pendingFile.exists()

    // ---- Lookups ----

    fun liveFolders() = index.value.folders.filter { it.deletedAt == null }
    fun file(id: String) = index.value.files.firstOrNull { it.id == id }

    // ---- Changes ----

    /** Returns the new folder's ID. */
    fun createFolder(parentId: String?, name: String): String {
        val id = UUID.randomUUID().toString()
        mutate { it.copy(folders = it.folders + Folder(id, parentId, name.trim(), System.currentTimeMillis())) }
        return id
    }

    fun rename(id: String, name: String) = mutate { idx ->
        idx.copy(
            folders = idx.folders.map { if (it.id == id) it.copy(name = name.trim()) else it },
            files = idx.files.map { if (it.id == id) it.copy(name = name.trim()) else it },
        )
    }

    /** Moves items; folders that would end up inside themselves are skipped. Returns how many were skipped. */
    fun move(fileIds: Collection<String>, folderIds: Collection<String>, target: String?): Int {
        var skipped = 0
        mutate { idx ->
            val movable = folderIds.filter { id -> target == null || target !in descendantsOf(id, idx.folders) }
            skipped = folderIds.size - movable.size
            idx.copy(
                folders = idx.folders.map { if (it.id in movable) it.copy(parentId = target) else it },
                files = idx.files.map { if (it.id in fileIds) it.copy(folderId = target) else it },
            )
        }
        return skipped
    }

    fun setStarred(ids: Collection<String>, starred: Boolean) = mutate { idx ->
        idx.copy(
            folders = idx.folders.map { if (it.id in ids) it.copy(starred = starred) else it },
            files = idx.files.map { if (it.id in ids) it.copy(starred = starred) else it },
        )
    }

    fun addFile(file: DriveFile) = mutate { it.copy(files = it.files + file) }

    /** Moves files and folders (with everything inside) to the trash for [TRASH_DAYS] days. */
    fun trash(fileIds: Collection<String>, folderIds: Collection<String>) = mutate { idx ->
        val now = System.currentTimeMillis()
        val tree = folderIds.flatMap { descendantsOf(it, idx.folders) }.toSet()
        val folderTree = idx.folders.filter { it.id in tree && it.deletedAt == null }.map { it.id }.toSet()
        idx.copy(
            folders = idx.folders.map { if (it.id in folderTree) it.copy(deletedAt = now, trashedRoot = it.id in folderIds) else it },
            files = idx.files.map {
                if (it.deletedAt == null && (it.id in fileIds || it.folderId in folderTree)) it.copy(deletedAt = now, trashedRoot = it.id in fileIds) else it
            },
        )
    }

    /** Takes trashed items back out. Items whose folder no longer exists go to the top level. */
    fun restore(fileIds: Collection<String>, folderIds: Collection<String>) = mutate { idx ->
        val byId = idx.folders.associateBy { it.id }
        val folders = mutableSetOf<String>()
        val files = fileIds.toMutableSet()
        for (rootId in folderIds) {
            val root = byId[rootId] ?: continue
            val batch = descendantsOf(rootId, idx.folders).filter { byId[it]?.deletedAt == root.deletedAt }
            folders += batch
            files += idx.files.filter { it.folderId in batch && it.deletedAt == root.deletedAt }.map { it.id }
        }
        var next = idx.copy(
            folders = idx.folders.map { if (it.id in folders) it.copy(deletedAt = null, trashedRoot = false) else it },
            files = idx.files.map { if (it.id in files) it.copy(deletedAt = null, trashedRoot = false) else it },
        )
        val after = next.folders.associateBy { it.id }
        fun missing(parent: String?) = parent != null && (after[parent] == null || after[parent]?.deletedAt != null)
        next = next.copy(
            folders = next.folders.map { if (it.id in folderIds && missing(it.parentId)) it.copy(parentId = null) else it },
            files = next.files.map { if (it.id in fileIds && missing(it.folderId)) it.copy(folderId = null) else it },
        )
        next
    }

    /** Deletes items for good, including their messages in Telegram. */
    suspend fun deleteForever(fileIds: Collection<String>, folderIds: Collection<String>) {
        val idx = index.value
        val tree = folderIds.flatMap { descendantsOf(it, idx.folders) }.toSet()
        val doomed = idx.files.filter { it.id in fileIds || it.folderId in tree }
        val doomedIds = doomed.map { it.id }.toSet()
        mutate { cur ->
            cur.copy(
                folders = cur.folders.filter { it.id !in tree },
                files = cur.files.filter { it.id !in doomedIds },
                purged = (cur.purged + tree + doomedIds).distinct().takeLast(MAX_PURGED),
            )
        }
        if (doomed.isNotEmpty()) files.deleteMessages(settings.current.chatId, doomed.map { it.messageId })
    }

    suspend fun emptyTrash() {
        val idx = index.value
        deleteForever(
            idx.files.filter { it.trashedRoot && it.deletedAt != null }.map { it.id },
            idx.folders.filter { it.trashedRoot && it.deletedAt != null }.map { it.id },
        )
    }

    suspend fun purgeOldTrash() {
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000
        val idx = index.value
        val files = idx.files.filter { it.trashedRoot && (it.deletedAt ?: Long.MAX_VALUE) < cutoff }.map { it.id }
        val folders = idx.folders.filter { it.trashedRoot && (it.deletedAt ?: Long.MAX_VALUE) < cutoff }.map { it.id }
        if (files.isNotEmpty() || folders.isNotEmpty()) deleteForever(files, folders)
    }

    fun markOpened(id: String) = settings.update { s ->
        s.copy(recent = (s.recent + (id to System.currentTimeMillis())).entries.sortedByDescending { it.value }.take(60).associate { it.toPair() })
    }

    /** The folder plus every folder nested below it. */
    fun descendantsOf(rootId: String, all: List<Folder>): Set<String> {
        val children = all.groupBy { it.parentId }
        val result = linkedSetOf(rootId)
        val queue = ArrayDeque(listOf(rootId))
        while (queue.isNotEmpty()) children[queue.removeFirst()].orEmpty().forEach { if (result.add(it.id)) queue += it.id }
        return result
    }

    // ---- Sync with Telegram ----

    fun requestSync() { trigger.tryEmit(Unit) }

    /** Posts the index to the channel, first merging in what other devices posted meanwhile. */
    suspend fun syncNow() = syncMutex.withLock {
        val s = settings.current
        if (!s.isConfigured) return@withLock
        _syncState.value = SyncState.Syncing
        pendingFile.delete()
        try {
            val remote = newerRemoteIndex(s.chatId, s.indexMessageId)
            remote?.let { (_, r) -> mergeRemote(r) }
            val exported = json.encodeToString(index.value.copy(exportedAt = System.currentTimeMillis()))
            val out = withContext(Dispatchers.IO) {
                File(AppDirs.cache, "index").apply { mkdirs() }.resolve(DriveIndex.FILE_NAME).apply { writeText(exported) }
            }
            val sent = files.sendDocument(s.chatId, out.absolutePath, caption = "Telegram Drive index. Don't delete this message.")
            telegram.awaitReady().setChatDescription(s.chatId, DriveProvisioner.describe(indexMessageId = sent.messageId)).get()
            val superseded = listOfNotNull(s.indexMessageId.takeIf { it != 0L }, remote?.first).distinct()
            if (superseded.isNotEmpty()) files.deleteMessages(s.chatId, superseded)
            withContext(Dispatchers.IO) { baseFile.writeText(exported) }
            settings.update { it.copy(indexMessageId = sent.messageId, lastSyncAt = System.currentTimeMillis()) }
            _syncState.value = SyncState.Idle
        } catch (e: Exception) {
            pendingFile.createNewFile()
            _syncState.value = SyncState.Failed(e.message ?: "Sync failed")
            throw e
        }
    }

    /** Adopts another device's newer index, unless changes made here are still waiting to be posted. */
    suspend fun refreshIfChanged(): Boolean = syncMutex.withLock {
        val s = settings.current
        if (!s.isConfigured) return@withLock false
        if (hasPending) { requestSync(); return@withLock false }
        val (messageId, remote) = runCatching { newerRemoteIndex(s.chatId, s.indexMessageId) }.getOrNull() ?: return@withLock false
        if (hasPending) return@withLock false
        replace(remote.copy(purged = (index.value.purged + remote.purged).distinct().takeLast(MAX_PURGED)))
        saveBase(remote)
        settings.update { it.copy(indexMessageId = messageId, lastSyncAt = System.currentTimeMillis()) }
        true
    }

    /** Replaces the local index with the one saved in the channel. False if the channel has none yet. */
    suspend fun restoreFromTelegram(): Boolean = syncMutex.withLock {
        val s = settings.current
        val client = telegram.awaitReady()
        val messageId = DriveProvisioner.indexMessageId(client, s.chatId) ?: return@withLock false
        val remote = downloadIndex(s.chatId, messageId) ?: return@withLock false
        pendingFile.delete()
        replace(remote)
        saveBase(remote)
        settings.update { it.copy(indexMessageId = messageId, lastSyncAt = System.currentTimeMillis()) }
        true
    }

    /** Forgets the local index (sign-out, or before restoring). */
    fun clearLocal() {
        pendingFile.delete()
        baseFile.delete()
        replace(DriveIndex())
    }

    private suspend fun saveBase(idx: DriveIndex) = withContext(Dispatchers.IO) {
        runCatching { baseFile.writeText(json.encodeToString(idx)) }
    }

    private fun loadBase(): DriveIndex =
        runCatching { json.decodeFromString<DriveIndex>(baseFile.readText()) }.getOrDefault(DriveIndex())

    private suspend fun newerRemoteIndex(chatId: Long, known: Long): Pair<Long, DriveIndex>? {
        val messageId = DriveProvisioner.indexMessageId(telegram.awaitReady(), chatId) ?: return null
        if (messageId == known) return null
        return downloadIndex(chatId, messageId)?.let { messageId to it }
    }

    private suspend fun downloadIndex(chatId: Long, messageId: Long): DriveIndex? {
        val client = telegram.awaitReady()
        val message = runCatching { client.getMessage(chatId, messageId).get() }.getOrNull() ?: return null
        val doc = (message.content as? MessageDocument)?.document ?: return null
        if (doc.fileName != DriveIndex.FILE_NAME) return null
        val local = files.download(doc.document.remote.id, priority = 32)
        val idx = withContext(Dispatchers.IO) { runCatching { json.decodeFromString<DriveIndex>(local.readText()) }.getOrNull() }
        return idx?.takeIf { it.version == DriveIndex.FORMAT_VERSION }
    }

    /** Folds another device's newer index into ours (see the class comment). */
    @Synchronized
    private fun mergeRemote(remote: DriveIndex) {
        val cur = _index.value
        val base = loadBase()
        val purged = (cur.purged + remote.purged).toSet()
        val folders = merge3(base.folders.associateBy { it.id }, cur.folders.associateBy { it.id }, remote.folders.associateBy { it.id })
        val files = merge3(base.files.associateBy { it.id }, cur.files.associateBy { it.id }, remote.files.associateBy { it.id })
        replace(
            DriveIndex(
                folders = folders.values.filter { it.id !in purged },
                files = files.values.filter { it.id !in purged },
                purged = purged.toList().takeLast(MAX_PURGED),
            )
        )
    }

    companion object {
        private const val MAX_PURGED = 20_000

        /**
         * Three-way merge by ID: an item one side left as it was in [base] takes the other side's
         * version (including its deletion); if both changed it, [local] wins.
         */
        fun <T> merge3(base: Map<String, T>, local: Map<String, T>, remote: Map<String, T>): Map<String, T> {
            val out = LinkedHashMap<String, T>()
            for (id in local.keys + remote.keys + base.keys) {
                val b = base[id]; val l = local[id]; val r = remote[id]
                val pick = when {
                    l == b -> r
                    r == b -> l
                    else -> l
                }
                if (pick != null) out[id] = pick
            }
            return out
        }
    }
}
