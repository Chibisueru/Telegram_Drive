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

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class SortMode { NAME, NEWEST, SIZE }

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
    val gridView: Boolean = false,
    val sortMode: SortMode = SortMode.NAME,
    val indexMessageId: Long = 0,
    val lastSyncAt: Long = 0,
) {
    val isConfigured: Boolean get() = apiId != 0 && apiHash.isNotBlank() && chatId != 0L
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
        val gridView = booleanPreferencesKey("grid_view")
        val sortMode = stringPreferencesKey("sort_mode")
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
        gridView = this[Keys.gridView] ?: false,
        sortMode = this[Keys.sortMode]?.let { runCatching { SortMode.valueOf(it) }.getOrNull() } ?: SortMode.NAME,
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
    suspend fun setGridView(v: Boolean) = context.dataStore.edit { it[Keys.gridView] = v }
    suspend fun setSortMode(v: SortMode) = context.dataStore.edit { it[Keys.sortMode] = v.name }
    suspend fun setIndexSynced(messageId: Long, at: Long) = context.dataStore.edit {
        it[Keys.indexMessageId] = messageId
        it[Keys.lastSyncAt] = at
    }
}
