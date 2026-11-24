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
    }

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
        trigger.tryEmit(Unit)
    }

    suspend fun syncNow() = mutex.withLock {
        val s = settings.current()
        if (!s.isConfigured) return@withLock
        _state.value = SyncState.Syncing
        try {
            val file = withContext(Dispatchers.IO) {
                File(context.cacheDir, "index").apply { mkdirs() }.resolve(INDEX_FILE_NAME).apply {
                    writeText(export().toString())
                }
            }
            val sent = files.sendDocument(
                s.chatId, file.absolutePath,
                caption = "Telegram Drive index. Don't delete this message.",
            )
            // Point the channel description at the new index. Unlike pinning, editing the
            // description doesn't post a service message into the channel.
            val client = telegram.awaitReady()
            client.setChatDescription(s.chatId, DriveProvisioner.describe(indexMessageId = sent.messageId)).get()
            if (s.indexMessageId != 0L) files.deleteMessages(s.chatId, listOf(s.indexMessageId))
            settings.setIndexSynced(sent.messageId, System.currentTimeMillis())
            _state.value = SyncState.Idle
        } catch (e: Exception) {
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
        val message = runCatching { client.getMessage(s.chatId, messageId).get() }.getOrNull() ?: return@withLock false
        val doc = (message.content as? MessageDocument)?.document ?: return@withLock false
        if (doc.fileName != INDEX_FILE_NAME) return@withLock false
        val local = files.download(doc.document.remote.id, priority = 32)
        val json = withContext(Dispatchers.IO) { JSONObject(local.readText()) }
        if (json.optInt("version") != FORMAT_VERSION) return@withLock false
        import(json)
        settings.setIndexSynced(messageId, System.currentTimeMillis())
        true
    }

    private suspend fun export(): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
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
        dao.replaceAll(folders, fileRows)
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
