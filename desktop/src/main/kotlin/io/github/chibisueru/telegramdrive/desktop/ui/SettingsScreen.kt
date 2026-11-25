package io.github.chibisueru.telegramdrive.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.chibisueru.telegramdrive.desktop.AppContainer
import io.github.chibisueru.telegramdrive.desktop.AppDirs
import io.github.chibisueru.telegramdrive.desktop.data.SyncState
import io.github.chibisueru.telegramdrive.desktop.data.formatSize
import io.github.chibisueru.telegramdrive.desktop.data.openUrl
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(c: AppContainer, onSignedOut: () -> Unit, onMessage: (String) -> Unit) {
    val s by c.settings.flow.collectAsState()
    val idx by c.drive.index.collectAsState()
    val sync by c.drive.syncState.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }

    fun run(done: String, block: suspend () -> Unit) = scope.launch {
        busy = true
        try { block(); onMessage(done) } catch (e: Exception) { onMessage(e.message ?: "Something went wrong") }
        busy = false
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))

            Section("Account")
            Item(Icons.Filled.AccountCircle, s.accountName.ifBlank { "Telegram account" }, "Files are stored in the private channel “${s.chatTitle}”")
            val live = idx.files.filter { it.deletedAt == null }
            Item(Icons.Filled.Storage, "${live.size} files · ${formatSize(live.sumOf { it.size })}",
                "Telegram stores them for free. Up to 2 GB per file (4 GB with Premium).")

            Section("Appearance")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Palette, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Theme", Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                val options = listOf("System", "Light", "Dark")
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = s.themeMode == i,
                            onClick = { c.settings.update { it.copy(themeMode = i) } },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }

            val look by SystemLookWatcher.look.collectAsState()
            Row(
                Modifier.fillMaxWidth().clickable { c.settings.update { it.copy(systemAccent = !it.systemAccent) } }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.ColorLens, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text("Use system accent color", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (look.accent != null) "Colors follow your desktop's accent color" else "Your desktop doesn't set an accent color, so the app's blue is used",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(s.systemAccent, { on -> c.settings.update { it.copy(systemAccent = on) } })
            }

            Section("Downloads")
            Item(Icons.Filled.Folder, "Save downloads to", s.downloadFolder.absolutePath) {
                Pickers.pickFolder("Save downloads to", s.downloadFolder)?.let { dir -> c.settings.update { it.copy(downloadDir = dir.absolutePath) } }
            }
            Item(Icons.Filled.CleaningServices, "Clear cache", "Deletes thumbnails and files opened on this computer. Nothing is deleted from Telegram.") {
                run("Cache cleared") { c.files.clearCache(); java.io.File(AppDirs.cache, "open").deleteRecursively() }
            }

            Section("Sync")
            val syncText = when (val st = sync) {
                SyncState.Syncing -> "Saving…"
                is SyncState.Failed -> "Last attempt failed: ${st.message}"
                SyncState.Idle -> if (s.lastSyncAt > 0) "Last saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.lastSyncAt)) else "Not saved yet"
            }
            Item(Icons.Filled.CloudSync, "Save folder structure to Telegram now", "$syncText. This happens automatically after every change.", enabled = !busy) {
                run("Index saved to Telegram") { c.drive.syncNow() }
            }
            Item(Icons.Filled.CloudDownload, "Reload from Telegram", "Replaces the folder structure on this computer with the latest one saved in the channel.", enabled = !busy) {
                confirm = "restore"
            }

            Section("About")
            Item(Icons.Filled.Code, "Telegram Drive ${AppDirs.version}", "Source code and updates on GitHub") {
                openUrl("https://github.com/Chibisueru/Telegram_Drive")
            }
            Item(Icons.AutoMirrored.Filled.Logout, "Sign out", "Ends this session. Your files stay in Telegram.", danger = true, enabled = !busy) {
                confirm = "signout"
            }
        }
    }

    when (confirm) {
        "restore" -> ConfirmDialog("Reload from Telegram?", "Changes made on this computer that haven't been saved yet will be lost.", "Reload", { confirm = null }) {
            run("Drive reloaded from Telegram") { if (!c.drive.restoreFromTelegram()) error("No saved index found in the channel") }
        }
        "signout" -> ConfirmDialog("Sign out?", "You'll need to log in again to use the drive on this computer. Your files stay in Telegram.", "Sign out", { confirm = null }) {
            scope.launch {
                busy = true
                c.transfers.cancelAll()
                runCatching { c.telegram.logOut() }
                c.settings.clearConnection()
                c.drive.clearLocal()
                busy = false
                onSignedOut()
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
