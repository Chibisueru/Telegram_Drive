package io.github.chibisueru.telegramdrive.desktop.data

import io.github.chibisueru.telegramdrive.desktop.AppDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val apiId: Int = 0,
    val apiHash: String = "",
    val chatId: Long = 0,
    val chatTitle: String = "",
    val accountName: String = "",
    /** 0 = follow the system, 1 = light, 2 = dark. */
    val themeMode: Int = 0,
    /** Take the colors from the desktop's accent color. */
    val systemAccent: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val sortMode: SortMode = SortMode.NAME,
    /** Each folder's own sort order (key: folder ID, "root", or "special:…"). */
    val folderSorts: Map<String, SortMode> = emptyMap(),
    val downloadDir: String = "",
    val indexMessageId: Long = 0,
    val lastSyncAt: Long = 0,
    /** When files were last opened on this computer (Recent). */
    val recent: Map<String, Long> = emptyMap(),
) {
    val isConfigured get() = apiId != 0 && apiHash.isNotBlank() && chatId != 0L
    fun sortFor(key: String) = folderSorts[key] ?: sortMode
    val downloadFolder: File get() = downloadDir.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory } ?: AppDirs.downloads
}

/** Settings in a small JSON file in the app's data folder. */
class SettingsStore {
    private val file = File(AppDirs.data, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<AppSettings> = _flow
    val current: AppSettings get() = _flow.value

    private fun load(): AppSettings = runCatching { json.decodeFromString<AppSettings>(file.readText()) }.getOrDefault(AppSettings())

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        _flow.update(change)
        runCatching {
            val tmp = File(file.parentFile, "settings.json.tmp")
            tmp.writeText(json.encodeToString(_flow.value))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    /** Signs out: keeps the API credentials and appearance, forgets the account and drive. */
    fun clearConnection() = update {
        it.copy(chatId = 0, chatTitle = "", accountName = "", indexMessageId = 0, lastSyncAt = 0, recent = emptyMap())
    }
}
