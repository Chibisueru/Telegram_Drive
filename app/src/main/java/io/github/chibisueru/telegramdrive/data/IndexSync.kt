package io.github.chibisueru.telegramdrive.data

import android.content.Context
import dev.g000sha256.tdl.dto.MessageDocument
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.telegram.DriveProvisioner
import io.github.chibisueru.telegramdrive.telegram.Telegram
import io.github.chibisueru.telegramdrive.telegram.TgFiles
import io.github.chibisueru.telegramdrive.telegram.get
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Telegram has no folders, so the folder tree (and which message holds which file) lives in a
 * local Room database. After every change, the whole index is posted to the drive channel as a
 * JSON document, and the channel description points at it. Signing in on another phone restores
 * it from there.
 *
 * Several devices (phones, the desktop app) can share one drive:
 * - before posting, a newer index from another device is merged in with a three-way merge
 *   against the last index this device posted or adopted ("base"): whatever only one side
 *   changed is taken from that side; only when both changed the same item does this device win;
 * - [refreshIfChanged] adopts another device's newer index when nothing local is waiting.
 * Items deleted for good are also remembered as "purged" IDs so a merge can't bring them back.
 */
@OptIn(FlowPreview::class)
class IndexSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: DriveDao,
    private val telegram: Telegram,
    private val files: TgFiles,
    private val settings: SettingsStore,
) {
    companion object {
        const val INDEX_FILE_NAME = "tgdrive_index.json"
        private const val FORMAT_VERSION = 2
        /** How many purged IDs the index remembers. */
        private const val MAX_PURGED = 20_000
    }

    private val purgedFile = File(context.filesDir, "purged_ids.txt")
    /** The index as of the last post or adoption: the common ancestor for merges. */
    private val baseFile = File(context.filesDir, "index_base.json")
    /** Local changes not yet posted. */
    private val dirty = AtomicBoolean(false)

    private val mutex = Mutex()
    private val trigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    init {
        scope.launch {
            trigger.debounce(2_500).collect { runCatching { syncNow() } }
        }
    }

    /** Schedules an upload of the index; bursts of changes are coalesced into one upload. */
    fun requestSync() {
        dirty.set(true)
        trigger.tryEmit(Unit)
    }

    /** Remembers items deleted for good, so merging another device's index can't revive them. */
    suspend fun markPurged(ids: Collection<String>) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { savePurged(loadPurged() + ids) }
    }

    private fun loadPurged(): LinkedHashSet<String> =
        if (purgedFile.exists()) purgedFile.readLines().filterTo(LinkedHashSet()) { it.isNotBlank() } else LinkedHashSet()

    private fun savePurged(ids: Set<String>) {
        purgedFile.writeText(ids.toList().takeLast(MAX_PURGED).joinToString("\n"))
    }

    /** Drops this device's sync bookkeeping (at sign-out, or before restoring another drive). */
    suspend fun forgetLocal() = withContext(Dispatchers.IO) {
        dirty.set(false)
        purgedFile.delete()
        baseFile.delete()
    }

    /** The index another device posted, if the channel points somewhere newer than our last post. */
    private suspend fun newerRemoteIndex(chatId: Long, known: Long): Pair<Long, JSONObject>? {
        val client = telegram.awaitReady()
        val messageId = DriveProvisioner.indexMessageId(client, chatId) ?: return null
        if (messageId == known) return null
        val json = downloadIndex(chatId, messageId) ?: return null
        return messageId to json
    }

    private suspend fun downloadIndex(chatId: Long, messageId: Long): JSONObject? {
        val client = telegram.awaitReady()
        val message = runCatching { client.getMessage(chatId, messageId).get() }.getOrNull() ?: return null
        val doc = (message.content as? MessageDocument)?.document ?: return null
        if (doc.fileName != INDEX_FILE_NAME) return null
        val local = files.download(doc.document.remote.id, priority = 32)
        val json = withContext(Dispatchers.IO) { JSONObject(local.readText()) }
        return json.takeIf { it.optInt("version") == FORMAT_VERSION }
    }

    /**
     * Adopts another device's newer index, unless this device has changes waiting to be posted
     * (they are merged when posting instead). Returns true when the local index changed.
     */
    suspend fun refreshIfChanged(): Boolean = mutex.withLock {
        val s = settings.current()
        if (!s.isConfigured || dirty.get()) return@withLock false
        val (messageId, json) = runCatching { newerRemoteIndex(s.chatId, s.indexMessageId) }.getOrNull() ?: return@withLock false
        if (dirty.get()) return@withLock false
        import(json)
        settings.setIndexSynced(messageId, System.currentTimeMillis())
        true
    }

    suspend fun syncNow() = mutex.withLock {
        val s = settings.current()
        if (!s.isConfigured) return@withLock
        _state.value = SyncState.Syncing
        dirty.set(false)
        try {
            // Another device posted since our last post: fold its changes in first.
            val remote = newerRemoteIndex(s.chatId, s.indexMessageId)
            remote?.let { (_, json) -> merge(json) }
            val exported = export().toString()
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "index").apply { mkdirs() }.resolve(INDEX_FILE_NAME).apply { writeText(exported) }
            }
            val sent = files.sendDocument(
                s.chatId, file.absolutePath,
                caption = "Telegram Drive index. Don't delete this message.",
            )
            // Point the channel description at the new index. Unlike pinning, editing the
            // description doesn't post a service message into the channel.
            val client = telegram.awaitReady()
            client.setChatDescription(s.chatId, DriveProvisioner.describe(indexMessageId = sent.messageId)).get()
            val superseded = listOfNotNull(s.indexMessageId.takeIf { it != 0L }, remote?.first).distinct()
            if (superseded.isNotEmpty()) files.deleteMessages(s.chatId, superseded)
            withContext(Dispatchers.IO) { baseFile.writeText(exported) }
            settings.setIndexSynced(sent.messageId, System.currentTimeMillis())
            _state.value = SyncState.Idle
        } catch (e: Exception) {
            dirty.set(true)
            _state.value = SyncState.Failed(e.message ?: "Sync failed")
            throw e
        }
    }

    /**
     * Replaces the local index with the latest one saved in the channel.
     * Returns false when the channel has no index yet (a fresh drive).
     */
    suspend fun restoreFromTelegram(): Boolean = mutex.withLock {
        val s = settings.current()
        val client = telegram.awaitReady()
        val messageId = DriveProvisioner.indexMessageId(client, s.chatId) ?: return@withLock false
        val json = downloadIndex(s.chatId, messageId) ?: return@withLock false
        import(json)
        settings.setIndexSynced(messageId, System.currentTimeMillis())
        true
    }

    private suspend fun export(): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("purged", JSONArray(withContext(Dispatchers.IO) { loadPurged() }.toList()))
        .put("exportedAt", System.currentTimeMillis())
        .put("folders", JSONArray().apply {
            dao.allFolders().forEach { f ->
                put(JSONObject().put("id", f.id).put("parentId", f.parentId ?: JSONObject.NULL)
                    .put("name", f.name).put("createdAt", f.createdAt)
                    .put("deletedAt", f.deletedAt ?: JSONObject.NULL).put("trashedRoot", f.trashedRoot)
                    .put("starred", f.starred))
            }
        })
        .put("files", JSONArray().apply {
            dao.allFiles().forEach { f ->
                put(JSONObject()
                    .put("id", f.id).put("folderId", f.folderId ?: JSONObject.NULL)
                    .put("name", f.name).put("mimeType", f.mimeType).put("size", f.size)
                    .put("createdAt", f.createdAt).put("messageId", f.messageId)
                    .put("remoteId", f.remoteId)
                    .put("thumbRemoteId", f.thumbRemoteId ?: JSONObject.NULL)
                    .put("deletedAt", f.deletedAt ?: JSONObject.NULL).put("trashedRoot", f.trashedRoot)
                    .put("starred", f.starred))
            }
        })

    private suspend fun import(json: JSONObject) {
        val (folders, fileRows) = parse(json)
        dao.replaceAll(folders, fileRows)
        withContext(Dispatchers.IO) {
            savePurged(loadPurged() + json.purgedIds())
            baseFile.writeText(json.toString())
        }
    }

    /** Folds another device's newer index into ours (see the class comment). */
    private suspend fun merge(json: JSONObject) {
        val (remoteFolders, remoteFiles) = parse(json)
        val base = withContext(Dispatchers.IO) {
            runCatching { if (baseFile.exists()) JSONObject(baseFile.readText()) else null }.getOrNull()
        }?.let { parse(it) } ?: (emptyList<FolderEntity>() to emptyList())
        val localFiles = dao.allFiles()
        val purged = withContext(Dispatchers.IO) { loadPurged() } + json.purgedIds()
        // lastOpenedAt is this phone's own history, not part of the shared index.
        fun FileEntity.shared() = copy(lastOpenedAt = null)
        val folders = merge3(base.first.associateBy { it.id }, dao.allFolders().associateBy { it.id }, remoteFolders.associateBy { it.id })
        val mergedFiles = merge3(
            base.second.associate { it.id to it.shared() },
            localFiles.associate { it.id to it.shared() },
            remoteFiles.associate { it.id to it.shared() },
        )
        val opened = localFiles.mapNotNull { f -> f.lastOpenedAt?.let { f.id to it } }.toMap()
        dao.replaceAll(
            folders.values.filter { it.id !in purged },
            mergedFiles.values.filter { it.id !in purged }.map { it.copy(lastOpenedAt = opened[it.id]) },
        )
        withContext(Dispatchers.IO) { savePurged(purged) }
    }

    /**
     * Three-way merge by ID: an item one side left as it was in [base] takes the other side's
     * version (including its deletion); if both changed it, [local] wins.
     */
    private fun <T> merge3(base: Map<String, T>, local: Map<String, T>, remote: Map<String, T>): Map<String, T> {
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

    private fun JSONObject.purgedIds(): List<String> {
        val arr = optJSONArray("purged") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private suspend fun parse(json: JSONObject): Pair<List<FolderEntity>, List<FileEntity>> {
        val folders = mutableListOf<FolderEntity>()
        val fileRows = mutableListOf<FileEntity>()
        // "Recently opened" is per phone: keep this phone's history across a restore.
        val opened = dao.allFiles().mapNotNull { f -> f.lastOpenedAt?.let { f.id to it } }.toMap()
        json.optJSONArray("folders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                folders += FolderEntity(
                    id = o.getString("id"),
                    parentId = o.optStringOrNull("parentId"),
                    name = o.getString("name"),
                    createdAt = o.optLong("createdAt"),
                    deletedAt = o.optLongOrNull("deletedAt"),
                    trashedRoot = o.optBoolean("trashedRoot"),
                    starred = o.optBoolean("starred"),
                )
            }
        }
        json.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fileRows += FileEntity(
                    id = o.getString("id"),
                    folderId = o.optStringOrNull("folderId"),
                    name = o.getString("name"),
                    mimeType = o.optString("mimeType", "application/octet-stream"),
                    size = o.getLong("size"),
                    createdAt = o.optLong("createdAt"),
                    messageId = o.getLong("messageId"),
                    remoteId = o.getString("remoteId"),
                    thumbRemoteId = o.optStringOrNull("thumbRemoteId"),
                    deletedAt = o.optLongOrNull("deletedAt"),
                    trashedRoot = o.optBoolean("trashedRoot"),
                    starred = o.optBoolean("starred"),
                    lastOpenedAt = opened[o.getString("id")],
                )
            }
        }
        return folders to fileRows
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
