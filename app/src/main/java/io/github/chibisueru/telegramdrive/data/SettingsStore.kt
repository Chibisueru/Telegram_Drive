package io.github.chibisueru.telegramdrive.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class SortMode { NAME, NEWEST, OLDEST, LARGEST, SMALLEST, TYPE }

enum class ViewMode { LIST, COMPACT, GRID, LARGE_GRID, GALLERY }

/** How long the app may stay in the background before the PIN is asked again. */
enum class LockTimeout(val millis: Long) { IMMEDIATELY(0), ONE_MINUTE(60_000), FIVE_MINUTES(300_000) }

data class AppSettings(
    /** From my.telegram.org; TDLib needs them every time it starts. */
    val apiId: Int = 0,
    val apiHash: String = "",
    /** The private channel holding the drive. */
    val chatId: Long = 0,
    val chatTitle: String = "",
    val accountName: String = "",
    val dynamicColor: Boolean = true,
    /** 0 = follow system, 1 = light, 2 = dark */
    val themeMode: Int = 0,
    val viewMode: ViewMode = ViewMode.LIST,
    /** Sort order for folders that haven't been given their own. */
    val sortMode: SortMode = SortMode.NAME,
    /** Per-folder sort orders, keyed by [folderSortKey] (or "special:…" for Favorites etc.). */
    val folderSorts: Map<String, SortMode> = emptyMap(),
    /** Uploads and downloads only run on Wi-Fi (or Ethernet). */
    val wifiOnly: Boolean = false,
    /** PBKDF2 hash of the app PIN; empty when no PIN is set. */
    val pinHash: String = "",
    val pinSalt: String = "",
    val biometricUnlock: Boolean = false,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    /** Automatically upload new photos (and videos) from DCIM/Camera. */
    val cameraBackup: Boolean = false,
    val cameraVideos: Boolean = true,
    val cameraWifiOnly: Boolean = true,
    val cameraChargingOnly: Boolean = false,
    /** MediaStore _ID of the newest item already queued; only newer items are backed up. */
    val cameraLastMediaId: Long = 0,
    /** The drive folder camera backups go into. */
    val cameraFolderId: String = "",
    val indexMessageId: Long = 0,
    val lastSyncAt: Long = 0,
) {
    val isConfigured: Boolean get() = apiId != 0 && apiHash.isNotBlank() && chatId != 0L
    val pinEnabled: Boolean get() = pinHash.isNotEmpty()
    fun sortFor(key: String): SortMode = folderSorts[key] ?: sortMode
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val apiId = intPreferencesKey("api_id")
        val apiHash = stringPreferencesKey("api_hash")
        val chatId = longPreferencesKey("drive_chat_id")
        val accountName = stringPreferencesKey("account_name")
        val chatTitle = stringPreferencesKey("chat_title")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val themeMode = intPreferencesKey("theme_mode")
        val gridView = booleanPreferencesKey("grid_view") // legacy, read once
        val viewMode = stringPreferencesKey("view_mode")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val pinHash = stringPreferencesKey("pin_hash")
        val pinSalt = stringPreferencesKey("pin_salt")
        val biometricUnlock = booleanPreferencesKey("biometric_unlock")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val cameraBackup = booleanPreferencesKey("camera_backup")
        val cameraVideos = booleanPreferencesKey("camera_videos")
        val cameraWifiOnly = booleanPreferencesKey("camera_wifi_only")
        val cameraChargingOnly = booleanPreferencesKey("camera_charging_only")
        val cameraLastMediaId = longPreferencesKey("camera_last_media_id")
        val cameraFolderId = stringPreferencesKey("camera_folder_id")
        val sortMode = stringPreferencesKey("sort_mode")
        val folderSorts = stringPreferencesKey("folder_sorts")
        val indexMessageId = longPreferencesKey("index_message_id")
        val lastSyncAt = longPreferencesKey("last_sync_at")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    private fun Preferences.toSettings() = AppSettings(
        apiId = this[Keys.apiId] ?: 0,
        apiHash = this[Keys.apiHash].orEmpty(),
        chatId = this[Keys.chatId] ?: 0,
        accountName = this[Keys.accountName].orEmpty(),
        chatTitle = this[Keys.chatTitle].orEmpty(),
        dynamicColor = this[Keys.dynamicColor] ?: true,
        themeMode = this[Keys.themeMode] ?: 0,
        viewMode = this[Keys.viewMode]?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() }
            ?: if (this[Keys.gridView] == true) ViewMode.GRID else ViewMode.LIST,
        wifiOnly = this[Keys.wifiOnly] ?: false,
        pinHash = this[Keys.pinHash].orEmpty(),
        pinSalt = this[Keys.pinSalt].orEmpty(),
        biometricUnlock = this[Keys.biometricUnlock] ?: false,
        lockTimeout = this[Keys.lockTimeout]?.let { runCatching { LockTimeout.valueOf(it) }.getOrNull() } ?: LockTimeout.IMMEDIATELY,
        cameraBackup = this[Keys.cameraBackup] ?: false,
        cameraVideos = this[Keys.cameraVideos] ?: true,
        cameraWifiOnly = this[Keys.cameraWifiOnly] ?: true,
        cameraChargingOnly = this[Keys.cameraChargingOnly] ?: false,
        cameraLastMediaId = this[Keys.cameraLastMediaId] ?: 0,
        cameraFolderId = this[Keys.cameraFolderId].orEmpty(),
        sortMode = this[Keys.sortMode]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.NAME,
        folderSorts = parseFolderSorts(this[Keys.folderSorts]),
        indexMessageId = this[Keys.indexMessageId] ?: 0,
        lastSyncAt = this[Keys.lastSyncAt] ?: 0,
    )

    suspend fun saveConnection(apiId: Int, apiHash: String, chatId: Long, chatTitle: String, accountName: String) {
        context.dataStore.edit {
            it[Keys.apiId] = apiId
            it[Keys.apiHash] = apiHash
            it[Keys.chatId] = chatId
            it[Keys.chatTitle] = chatTitle
            it[Keys.accountName] = accountName
            it[Keys.indexMessageId] = 0
            it[Keys.lastSyncAt] = 0
        }
    }

    suspend fun clearConnection() {
        context.dataStore.edit {
            it.remove(Keys.apiId)
            it.remove(Keys.apiHash)
            it.remove(Keys.chatId)
            it.remove(Keys.accountName)
            it.remove(Keys.chatTitle)
            it.remove(Keys.indexMessageId)
            it.remove(Keys.lastSyncAt)
        }
    }

    suspend fun setDynamicColor(v: Boolean) = context.dataStore.edit { it[Keys.dynamicColor] = v }
    suspend fun setThemeMode(v: Int) = context.dataStore.edit { it[Keys.themeMode] = v }
    suspend fun setViewMode(v: ViewMode) = context.dataStore.edit { it[Keys.viewMode] = v.name }
    suspend fun setWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.wifiOnly] = v }
    suspend fun setBiometricUnlock(v: Boolean) = context.dataStore.edit { it[Keys.biometricUnlock] = v }
    suspend fun setLockTimeout(v: LockTimeout) = context.dataStore.edit { it[Keys.lockTimeout] = v.name }
    suspend fun setPin(hash: String, salt: String) = context.dataStore.edit {
        it[Keys.pinHash] = hash
        it[Keys.pinSalt] = salt
    }
    suspend fun setCameraBackup(enabled: Boolean, lastMediaId: Long? = null) = context.dataStore.edit {
        it[Keys.cameraBackup] = enabled
        if (lastMediaId != null) it[Keys.cameraLastMediaId] = lastMediaId
    }
    suspend fun setCameraVideos(v: Boolean) = context.dataStore.edit { it[Keys.cameraVideos] = v }
    suspend fun setCameraWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.cameraWifiOnly] = v }
    suspend fun setCameraChargingOnly(v: Boolean) = context.dataStore.edit { it[Keys.cameraChargingOnly] = v }
    suspend fun setCameraLastMediaId(v: Long) = context.dataStore.edit { it[Keys.cameraLastMediaId] = v }
    suspend fun setCameraFolderId(v: String) = context.dataStore.edit { it[Keys.cameraFolderId] = v }

    suspend fun clearPin() = context.dataStore.edit {
        it.remove(Keys.pinHash)
        it.remove(Keys.pinSalt)
        it[Keys.biometricUnlock] = false
    }
    suspend fun setSortMode(v: SortMode) = context.dataStore.edit { it[Keys.sortMode] = v.name }

    /** Remembers the sort order chosen for one folder (or view). */
    suspend fun setFolderSort(key: String, mode: SortMode) = context.dataStore.edit { prefs ->
        val map = parseFolderSorts(prefs[Keys.folderSorts]).toMutableMap()
        map[key] = mode
        prefs[Keys.folderSorts] = JSONObject().apply { map.forEach { (k, v) -> put(k, v.name) } }.toString()
    }

    private fun parseFolderSorts(raw: String?): Map<String, SortMode> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { k ->
                runCatching { SortMode.valueOf(o.getString(k)) }.getOrNull()?.let { k to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
    suspend fun setIndexSynced(messageId: Long, at: Long) = context.dataStore.edit {
        it[Keys.indexMessageId] = messageId
        it[Keys.lastSyncAt] = at
    }
}
