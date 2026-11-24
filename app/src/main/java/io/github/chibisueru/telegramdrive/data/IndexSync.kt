package io.github.chibisueru.telegramdrive.data

import io.github.chibisueru.telegramdrive.data.db.ChunkEntity
import io.github.chibisueru.telegramdrive.data.db.DriveDao
import io.github.chibisueru.telegramdrive.data.db.FileEntity
import io.github.chibisueru.telegramdrive.data.db.FolderEntity
import io.github.chibisueru.telegramdrive.telegram.BotApi
import io.github.chibisueru.telegramdrive.telegram.TelegramFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * The folder tree and the chunk map live in a local Room database. To make the drive survive
 * reinstalls and work on other devices, the whole index is uploaded as a JSON document to the
 * channel and pinned there. On sign-in, the pinned index is downloaded and restored.
 */
@OptIn(FlowPreview::class)
class IndexSync(
    private val scope: CoroutineScope,
    private val dao: DriveDao,
    private val bot: BotApi,
    private val files: TelegramFiles,
    private val settings: SettingsStore,
) {
    companion object {
        const val INDEX_FILE_NAME = "tgdrive_index.json"
        private const val FORMAT_VERSION = 1
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
            val bytes = export().toString().toByteArray()
            val sent = bot.sendDocument(
                s.chatId, INDEX_FILE_NAME, bytes, bytes.size,
                caption = "Telegram Drive index — keep this message pinned and don't delete it.",
            )
            runCatching { bot.pinChatMessage(s.chatId, sent.messageId) }
            if (s.indexMessageId != 0L) bot.deleteMessages(s.chatId, listOf(s.indexMessageId))
            settings.setIndexSynced(sent.messageId, System.currentTimeMillis())
            _state.value = SyncState.Idle
        } catch (e: Exception) {
            _state.value = SyncState.Failed(e.message ?: "Sync failed")
            throw e
        }
    }

    /**
     * Replaces the local index with the one pinned in the channel.
     * Returns false when the channel has no pinned index (a fresh drive).
     */
    suspend fun restoreFromTelegram(): Boolean = mutex.withLock {
        val s = settings.current()
        val pinned = bot.getChat(s.chatId).optJSONObject("pinned_message") ?: return@withLock false
        val doc = pinned.optJSONObject("document") ?: return@withLock false
        if (doc.optString("file_name") != INDEX_FILE_NAME) return@withLock false
        val url = files.urlFor(doc.getString("file_id"))
        val out = ByteArrayOutputStream()
        files.openChunk(url, 0).use { it.copyTo(out) }
        import(JSONObject(out.toString(Charsets.UTF_8.name())))
        settings.setIndexSynced(pinned.getLong("message_id"), System.currentTimeMillis())
        true
    }

    private suspend fun export(): JSONObject {
        val chunksByFile = dao.allChunks().groupBy { it.fileId }
        return JSONObject()
            .put("version", FORMAT_VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("folders", JSONArray().apply {
                dao.allFolders().forEach { f ->
                    put(JSONObject().put("id", f.id).put("parentId", f.parentId ?: JSONObject.NULL)
                        .put("name", f.name).put("createdAt", f.createdAt))
                }
            })
            .put("files", JSONArray().apply {
                dao.allFiles().forEach { f ->
                    put(JSONObject()
                        .put("id", f.id).put("folderId", f.folderId ?: JSONObject.NULL)
                        .put("name", f.name).put("mimeType", f.mimeType).put("size", f.size)
                        .put("createdAt", f.createdAt).put("chunkSize", f.chunkSize)
                        .put("thumbFileId", f.thumbFileId ?: JSONObject.NULL)
                        .put("thumbMessageId", f.thumbMessageId ?: JSONObject.NULL)
                        .put("chunks", JSONArray().apply {
                            chunksByFile[f.id].orEmpty().forEach { c ->
                                put(JSONObject().put("idx", c.idx).put("fileId", c.tgFileId)
                                    .put("messageId", c.messageId).put("size", c.size))
                            }
                        }))
                }
            })
    }

    private suspend fun import(json: JSONObject) {
        val folders = mutableListOf<FolderEntity>()
        val fileRows = mutableListOf<FileEntity>()
        val chunks = mutableListOf<ChunkEntity>()
        json.optJSONArray("folders")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                folders += FolderEntity(o.getString("id"), o.optStringOrNull("parentId"), o.getString("name"), o.optLong("createdAt"))
            }
        }
        json.optJSONArray("files")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                fileRows += FileEntity(
                    id = id,
                    folderId = o.optStringOrNull("folderId"),
                    name = o.getString("name"),
                    mimeType = o.optString("mimeType", "application/octet-stream"),
                    size = o.getLong("size"),
                    createdAt = o.optLong("createdAt"),
                    chunkSize = o.optLong("chunkSize", BotApi.CHUNK_SIZE.toLong()),
                    thumbFileId = o.optStringOrNull("thumbFileId"),
                    thumbMessageId = if (o.isNull("thumbMessageId")) null else o.optLong("thumbMessageId"),
                )
                o.optJSONArray("chunks")?.let { ca ->
                    for (j in 0 until ca.length()) {
                        val c = ca.getJSONObject(j)
                        chunks += ChunkEntity(id, c.getInt("idx"), c.getString("fileId"), c.getLong("messageId"), c.getLong("size"))
                    }
                }
            }
        }
        dao.replaceAll(folders, fileRows, chunks)
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }
}
